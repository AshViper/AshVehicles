/*
 * 学習用の試合1つ。チームデスマッチの規則（チケット・流血・制圧・再出撃）と、AI の1両ごとの tick を回す。
 *
 * 1両の1 tick はゲームの GroundPilot.think と同じ順:
 *
 *   observe     見る    名簿から敵と味方の観測（視線は高さ地図、組ごとのキャッシュ）
 *   decide      決める  周期か出来事で BattleState → 行動1つ（brain.decide）
 *   tactics     戦術    行動 → MovementIntent（tactics.Tactics）
 *   combat      撃つ    目標（brain.selectTarget）・兵装・砲塔・引き金
 *   navigate    走る    道（tactics.planRoute）と追従
 *
 * <b>ここにある物理と射撃は、ゲームの写しではなく見積もり。</b> 判断の層が読む量（見えるか・届くか・効くか・
 * 撃たれたか・どれだけ減ったか）が同じ向きに動くように作ってあるだけで、弾道・跳弾・車体の揺れは無い。
 * 学習器で強い版がゲームで強いとは限らない——採用はゲームの自己対戦の門（/tdm ai selfplay → promote）を通す。
 */
function AVSim_match(AV) {
  "use strict";

  const clamp = AV.clamp;
  const brain = AV.brain;
  const tactics = AV.tactics;

  AV.TIMING = { perception: 5, decision: 20, target: 10, objective: 40, threatMap: 20, tactics: 60, replan: 120 };
  AV.SIGHT = 192;
  AV.CAPTURE_TICKS = 400;

  const MEMORY_TICKS = 200;
  const ATTACKED_WINDOW = 100;
  const LOS_CACHE_TICKS = 10;
  const THREAT_RADIUS = 128;
  const THREAT_SOURCES = 16;
  const DECAY = 0.85;
  const UNKNOWN_EXPOSURE = 0.5;
  const CAPTURE_INTERVAL = 10;
  const CAPTURE_DECAY = 2;
  const REDECIDE = 5;
  const SUPPORT_REACH = 150;
  const NEAR = 60;
  const FRONTAL = Math.cos(45 * Math.PI / 180);
  const AIM_CONE = Math.cos(12 * Math.PI / 180);
  const SCUTTLE_AFTER = 200;
  const PLAN_BUDGET = 2500;
  const COAX_RANGE = 60;
  /** 空を撃てる車両が空の相手を見る距離（視界の倍率、Perception.AIR_SIGHT）。 */
  const AIR_SIGHT = 8;
  /** 地上の砲が空を撃つ距離（FireControl.AIR_GUN_REACH）と、空の相手へ許す角のずれ。 */
  const AIR_GUN_REACH = 800;
  const AIR_ALIGNED = 1.5 * Math.PI / 180;
  const HIT_AIRCRAFT = 0.06;
  const AIR_MISSILE_HIT = 0.6;
  const MISSILE_SPEED = 12;
  /** 陣営の AI のうち航空機に回す割合（Bots.AIR_SHARE）と、損失を覚える長さ（Reinforcements.MEMORY）。 */
  const AIR_SHARE = 0.3;
  const LOSS_MEMORY = 2400;
  const LAUNCH_BEHIND = 600;
  /** 戦車と歩兵戦闘車を組と数える距離（Reinforcements.PAIR_REACH）と、組を数え直す間隔。 */
  const PAIR_REACH = 60;
  const PAIR_EVERY = 20;

  /** 砲の種類ごとの、止まって撃ったときの当たりやすさ（見積もり）。 */
  const ACCURACY = { tank_gun: 0.85, autocannon: 0.55, machine_gun: 0.4, howitzer: 0.45, naval_gun: 0.6 };

  // ------------------------------------------------------------------
  // 撃ったのに効かない相手（DamageLedger）
  // ------------------------------------------------------------------

  class Ledger {
    constructor() {
      this.rows = new Map();
    }

    shot(id, now, amount, maxHealth) {
      let row = this.rows.get(id);

      if (!row || now - row.since > 200) {
        row = { since: now, shots: 0, damage: 0, maxHealth, until: -1 };
        this.rows.set(id, row);
      }

      row.shots++;
      row.damage += amount;

      if (row.shots >= 4 && row.damage < row.maxHealth * 0.05) {
        row.until = now + 200;
      }
    }

    ineffective(id, now) {
      const row = this.rows.get(id);

      return !!row && row.until >= now;
    }
  }

  // ------------------------------------------------------------------
  // 1両
  // ------------------------------------------------------------------

  class Unit {
    constructor(match, id, team, slot, def, x, z) {
      const side = match.teams[team];

      this.match = match;
      this.id = id;
      this.team = team;
      this.slot = slot;
      this.def = def;
      this.role = def.role;
      this.params = side.params;
      this.profile = AV.profile(def.role, side.params);
      this.weights = side.weights;
      this.reach = brain.reach(def);
      this.x = x;
      this.z = z;
      this.heading = Math.atan2(match.terrain.size / 2 - z, match.terrain.size / 2 - x);
      this.turret = this.heading;
      this.speed = 0;
      this.health = def.health;
      this.alive = true;
      this.bornTick = match.tick;
      this.ammo = {
        main: def.main ? def.main.capacity : 0,
        coax: def.coax ? def.coax.capacity : 0,
        missile: def.missile ? def.missile.capacity : 0,
      };
      this.cooldown = 0;
      this.spent = 0;
      this.observations = new Map();
      this.allies = [];
      this.threat = 0;
      this.engaging = 0;
      this.target = null;
      this.weapon = null;
      this.lostTarget = false;
      this.ledger = new Ledger();
      this.attackers = new Map();
      this.lastHurt = -1e9;
      this.lastAttacker = -1;
      this.lastVictim = null;
      this.action = "advance";
      this.decided = false;
      this.actionSince = match.tick;
      this.decidedAt = -1e9;
      this.boardRevision = -1;
      this.wasUnderFire = false;
      this.state = null;
      this.objective = null;
      this.tactics = new tactics.Tactics(this);
      this.intent = null;
      this.route = null;
      this.replan = true;
      this.stall = 0;
      this.stallCheckX = x;
      this.stallCheckZ = z;
      this.reversing = 0;
    }

    underFire(now) {
      return now - this.lastHurt <= ATTACKED_WINDOW;
    }

    attackerId(now) {
      return now - this.lastHurt <= ATTACKED_WINDOW ? this.lastAttacker : -1;
    }

    think(now) {
      const phase = (k, every) => (now + this.id * k) % every === 0;

      if (phase(3, AV.TIMING.perception)) this.observe(now);

      const board = this.match.teams[this.team].board;
      const underFire = this.underFire(now);
      const event = board.revision !== this.boardRevision || (underFire && !this.wasUnderFire) || this.lostTarget;
      let changed = false;

      this.wasUnderFire = underFire;
      this.lostTarget = false;

      if (phase(5, AV.TIMING.decision) || !this.decided || (event && now - this.decidedAt >= REDECIDE)) {
        this.boardRevision = board.revision;
        this.objective = board.assignmentOrBest(this);

        const s = this.buildState(now);
        const next = brain.decide(this.weights.policy, s, this.match.scratchUtilities);

        changed = next !== this.action || !this.decided;
        this.decidedAt = now;
        this.decided = true;
        this.state = s;
        this.callForSupport(s, now);
        this.match.countDecision(this.team, next, changed);

        if (changed) {
          this.action = next;
          this.actionSince = now;
          this.replan = true;
        }
      }

      if (this.state) {
        this.state.now = now;
        this.intent = this.tactics.resolve(this.action, this.state, changed, now);
      }

      this.combat(now);
      this.navigate(now);
      this.resupply();
    }

    // ---------------------------------------------------------------- 見る

    observe(now) {
      const match = this.match;
      const intel = match.intel[this.team];
      const board = match.teams[this.team].board;
      const terrain = match.terrain;
      let threat = 0;
      let engaging = 0;
      const seen = match.scratchSeen;

      seen.clear();

      for (const enemy of match.units) {
        if (!enemy.alive || enemy.team === this.team) continue;

        const distance = AV.dist2(this.x, this.z, enemy.x, enemy.z);
        const spotted = intel.spotted.get(enemy.id);
        const teamCurrent = !!spotted && now - spotted.tick <= LOS_CACHE_TICKS;
        const attackingMe = (this.attackers.get(enemy.id) || -1e9) >= now - ATTACKED_WINDOW;
        const flying = !!enemy.flying;
        // 遠い相手は陣営の誰かが見ているか撃ってきたときだけ。空を撃てる車両は遠くの空の相手も持つ——撃つために。
        const airSight = this.def.defendsAir ? AV.SIGHT * AIR_SIGHT : AV.SIGHT;
        const beyond = distance > AV.SIGHT * 1.5 && !attackingMe && !(teamCurrent && distance <= AV.SIGHT * 2);

        if (beyond && !(flying && distance <= airSight * 1.5)) continue;

        let obs = this.observations.get(enemy.id);

        if (!obs) {
          obs = {
            id: enemy.id, unit: enemy, alive: true, distance, visible: false, seenBy: false, inWeaponRange: false,
            inTheirRange: false, attackingMe: false, attackingAlly: false, attackingObjective: false, aimingAtMe: false,
            armoured: true, healthFraction: 1, threatLevel: 0, capability: 0, effectiveness: 1, targetPriority: 0,
            lastSeen: -1e9, lastX: enemy.x, lastZ: enemy.z,
          };
          this.observations.set(enemy.id, obs);
        }

        seen.add(enemy.id);

        const lineKnown = distance <= AV.SIGHT || flying;
        // 見上げる機体には線を引かずに見えると数える（高さ地図は空を遮らない）。
        const visible = flying ? distance <= airSight : lineKnown && match.los(this, enemy, now);
        const theirRange = enemy.reach.maxRange;
        const toMe = Math.atan2(this.z - enemy.z, this.x - enemy.x);
        const aiming = enemy.target !== null && enemy.target.id === this.id
          && Math.cos(AV.wrap(enemy.turret - toMe)) >= AIM_CONE;
        const cap = brain.capability(enemy.reach, this.health, this.def.armour);

        obs.alive = true;
        obs.flying = flying;
        obs.armoured = !flying;
        obs.distance = distance;
        obs.visible = visible;
        obs.seenBy = visible && distance <= AV.SIGHT * (flying ? 2 : 1);
        obs.inWeaponRange = flying ? distance <= AV.air.airReach(this.def) : distance <= this.reach.maxRange;
        obs.inTheirRange = distance <= theirRange;
        obs.attackingMe = attackingMe;
        obs.attackingAlly = !!enemy.lastVictim && enemy.lastVictim.team === this.team
          && enemy.lastVictim.id !== this.id && now - enemy.lastVictim.tick <= ATTACKED_WINDOW;
        obs.attackingObjective = attacksObjective(enemy, board.states, this.team);
        obs.aimingAtMe = aiming;
        obs.healthFraction = enemy.health / enemy.def.health;
        obs.capability = cap;
        // 地上の視界の外の空の相手は、撃つ相手であって身を守る相手ではない。
        obs.threatLevel = beyond ? 0 : brain.threat(cap, distance, theirRange, obs.seenBy, lineKnown, aiming, attackingMe);
        obs.effectiveness = flying ? 1 : brain.effectiveness(this.reach, enemy.def.armour);

        if (visible) {
          obs.lastSeen = now;
          obs.lastX = enemy.x;
          obs.lastZ = enemy.z;

          if (!flying || distance <= AV.SIGHT) intel.report(enemy, now);
        }

        threat = AV.combine(threat, obs.threatLevel);

        if (obs.seenBy && obs.inTheirRange) engaging++;
      }

      for (const id of this.observations.keys()) {
        if (!seen.has(id)) {
          if (this.target && this.target.id === id) {
            this.target = null;
            this.lostTarget = true;
          }

          this.observations.delete(id);
        }
      }

      this.threat = threat;
      this.engaging = engaging;
      this.allies.length = 0;

      for (const ally of match.units) {
        if (!ally.alive || ally.team !== this.team || ally === this || ally.flying) continue;

        this.allies.push({
          unit: ally, distance: AV.dist2(this.x, this.z, ally.x, ally.z), underFire: ally.underFire(now),
          attackerId: ally.attackerId(now), healthFraction: ally.health / ally.def.health,
        });
      }
    }

    alliesWithin(radius) {
      let counted = 0;

      for (const ally of this.allies) {
        if (ally.distance <= radius) counted++;
      }

      return counted;
    }

    // ---------------------------------------------------------------- 決める

    buildState(now) {
      const match = this.match;
      const intel = match.intel[this.team];
      const board = match.teams[this.team].board;
      const aim = this.target;
      const terrain = match.terrain;
      let frontal = false;

      if (aim && !aim.flying) {
        const other = aim.unit;
        const tx = this.x - other.x;
        const tz = this.z - other.z;
        const length = Math.sqrt(tx * tx + tz * tz);

        frontal = aim.aimingAtMe || (length > 1e-3
          && (Math.cos(other.heading) * tx + Math.sin(other.heading) * tz) / length > FRONTAL);
      }

      let trouble = null;
      let need = 0;

      for (const ally of this.allies) {
        if (ally.distance > SUPPORT_REACH || !ally.underFire) continue;

        const urgency = ((1 - ally.healthFraction) * 0.6 + (ally.attackerId >= 0 ? 0.4 : 0.2))
          * (1 - 0.5 * ally.distance / SUPPORT_REACH);

        if (urgency > need) {
          need = urgency;
          trouble = ally;
        }
      }

      const call = match.teams[this.team].support.answerFor(this.id, now);
      let called = false;

      if (call) {
        const caller = match.byId(call.caller);

        if (caller && caller.alive) {
          const seenAlly = this.allies.find((a) => a.unit === caller);

          trouble = seenAlly && seenAlly.attackerId >= 0 ? seenAlly : {
            unit: caller, distance: AV.dist2(this.x, this.z, caller.x, caller.z), underFire: true,
            attackerId: call.attackers.length ? call.attackers[0] : -1, healthFraction: caller.health / caller.def.health,
          };
          need = Math.max(need, call.urgency);
          called = true;
        }
      }

      const duty = this.objective;
      let best = 0;

      for (const o of board.states) best = Math.max(best, o.score);

      const risk = this.route ? this.route.risk
        : duty ? intel.threats.riskAlong(this.x, this.z, duty.x, duty.z, 6) : 0;
      const threats = this.tactics.threats(null);
      let inCover;

      if (threats.length > 0) {
        const t = threats[0];
        inCover = terrain.trace(t.x, t.y, t.z, this.x, tactics.hullOf(terrain, this.x, this.z, this.def.height), this.z) === 1;
      } else {
        inCover = tactics.coverValue(terrain, this.x, this.z) >= 0.5;
      }

      return {
        now, profile: this.profile, health: this.health / this.def.health, ammo: this.ammoFraction(),
        threat: this.threat, exposure: intel.threats.exposure(this.x, this.z), underFire: this.underFire(now),
        enemiesEngaging: this.engaging, alliesNear: this.alliesWithin(NEAR), knownEnemies: intel.known(now),
        target: aim, targetFrontal: frontal, alliesOnTarget: aim ? intel.engagedBy(aim.id, this.id) : 0,
        targetIneffective: aim ? this.ledger.ineffective(aim.id, now) : false, allyInTrouble: trouble,
        allyNeed: need, supportCall: called, objectives: board.states, objective: duty,
        atObjective: !!duty && AV.dist2(this.x, this.z, duty.x, duty.z) <= duty.radius,
        objectiveWeight: !duty || best <= 0 ? 0 : Math.max(0, duty.score) / best, routeRisk: risk,
        inCover, coverNearby: !!this.tactics.position && this.tactics.position.protection >= 0.5,
        currentAction: this.action, ticksInAction: now - this.actionSince, arena: match.arena,
      };
    }

    callForSupport(s, now) {
      const support = this.match.teams[this.team].support;
      const deficit = s.enemiesEngaging - s.alliesNear - 1;
      const helpless = s.underFire && s.targetIneffective;

      if (deficit < 1 && !helpless) {
        support.withdraw(this.id);
        return;
      }

      const attackers = [];

      for (const enemy of this.observations.values()) {
        if (enemy.alive && (enemy.attackingMe || (enemy.seenBy && enemy.inTheirRange))) attackers.push(enemy.id);
      }

      const lacking = Math.max(deficit, 1);
      const urgency = Math.max(0.2, Math.min(1, lacking / 3 + (1 - s.health) * 0.5 + (helpless ? 0.3 : 0)));

      support.raise({ caller: this.id, x: this.x, z: this.z, lacking, urgency, attackers, tick: now });
    }

    canAnswer(now) {
      const s = this.state;

      if (!["tank", "ifv", "apc", "scout", "support"].includes(this.role) || !s || this.action === "retreat"
        || this.engaging > 0 || this.underFire(now)) {
        return false;
      }

      const duty = this.objective;
      const at = !!duty && AV.dist2(this.x, this.z, duty.x, duty.z) <= duty.radius;

      return !duty || !at || (duty.state === "friendly" && !duty.beingTaken);
    }

    ammoFraction() {
      const d = this.def;
      let best = 0;

      if (d.main && d.main.capacity > 0) best = Math.max(best, this.ammo.main / d.main.capacity);
      if (d.coax && d.coax.capacity > 0) best = Math.max(best, this.ammo.coax / d.coax.capacity);
      if (d.missile && d.missile.capacity > 0) best = Math.max(best, this.ammo.missile / d.missile.capacity);

      return Math.min(best, 1);
    }

    // ---------------------------------------------------------------- 撃つ

    combat(now) {
      const match = this.match;

      if (this.target && (!this.target.alive || !this.target.unit.alive)) {
        this.target = null;
        this.lostTarget = true;
      }

      if ((now + this.id * 7) % AV.TIMING.target === 0) {
        const range = Math.min(this.reach.maxRange, AV.SIGHT);
        const preferred = this.intent ? this.intent.targetId : null;
        const next = brain.selectTarget(this, this.weights.target, now, range, preferred);

        if (this.target && !next) this.lostTarget = true;

        this.target = next;
      }

      if (this.cooldown > 0) this.cooldown--;

      const aim = this.target;

      if (!aim) {
        this.weapon = null;
        this.slew(this.heading);
        return;
      }

      const enemy = aim.unit;
      const distance = AV.dist2(this.x, this.z, enemy.x, enemy.z);
      const d = this.def;
      let weapon = null;
      let mount = null;

      if (enemy.flying) {
        // WeaponSelector: 空へは空を追う誘導弾、無いか尽きていれば主砲の機関砲。対戦車ミサイルとロケットは向けない。
        if (d.airMissile && this.ammo.missile > 0 && distance <= d.missile.range) {
          weapon = d.missile; mount = "missile";
        } else if (d.main && d.main.gunClass === "autocannon" && this.ammo.main > 0) {
          weapon = d.main; mount = "main";
        }
      } else if (d.missile && this.ammo.missile > 0 && !d.airMissile
        && (enemy.def.armour >= 3 || !d.main || this.ammo.main <= 0) && distance > 30 && distance <= d.missile.range) {
        weapon = d.missile; mount = "missile";
      } else if (d.coax && this.ammo.coax > 0 && ((enemy.def.armour < 1.5 && distance <= COAX_RANGE * 2)
        || !d.main || this.ammo.main <= 0)) {
        weapon = d.coax; mount = "coax";
      } else if (d.main && this.ammo.main > 0) {
        weapon = d.main; mount = "main";
      } else if (d.missile && this.ammo.missile > 0) {
        weapon = d.missile; mount = "missile";
      }

      this.weapon = mount;

      const bearing = Math.atan2(enemy.z - this.z, enemy.x - this.x);
      const error = this.slew(bearing);

      if (!weapon || this.cooldown > 0 || !aim.visible || distance > weapon.range) return;

      if (enemy.flying) {
        this.shootAir(weapon, mount, enemy, distance, error, now);
        return;
      }

      if (Math.abs(error) > 2 * Math.PI / 180) return;
      if (weapon.explosion >= 2 && distance < 12) return;
      if (!match.los(this, enemy, now)) return;

      const automatic = weapon.rps >= 2;
      const moving = Math.abs(this.speed) > 0.15 ? 0.7 : 1.0;
      const evasive = Math.abs(enemy.speed) > 0.5 ? 0.85 : 1.0;
      const base = weapon.type === "missile" ? (weapon.guided ? 0.85 : 0.3) : weapon.type === "rocket" ? 0.3
        : ACCURACY[weapon.gunClass] || 0.5;
      const falloff = Math.max(0.3, 1 - distance / Math.max(weapon.range * 0.5, 60));
      const p = clamp(base * moving * evasive * falloff, 0.02, 0.97);
      const eff = trueEffect(weapon, this, enemy);
      let rounds;
      let damage;

      if (automatic) {
        rounds = Math.min(this.ammo[mount], Math.max(1, Math.round(weapon.rps / 4)));
        damage = rounds * (weapon.salvo || 1) * p * weapon.damage * eff * (0.7 + 0.6 * match.random());
        this.cooldown = 5;
      } else {
        rounds = 1;
        damage = match.random() < p ? weapon.damage * eff : 0;
        this.cooldown = Math.max(1, Math.round(20 / weapon.rps));
      }

      this.ammo[mount] -= rounds;
      match.shots.push({ x0: this.x, z0: this.z, x1: enemy.x, z1: enemy.z, team: this.team, hit: damage > 0, tick: now });
      this.ledger.shot(enemy.id, now, damage, enemy.def.health);
      this.lastVictim = { id: enemy.id, team: enemy.team, tick: now };
      match.stats[this.team].shots += rounds;

      if (damage > 0) {
        match.hurt(enemy, damage, this, now);

        if (weapon.explosion >= 2) {
          const radius = weapon.explosion * 1.2;

          for (const other of match.units) {
            if (other.alive && !other.flying && other !== enemy && other.team !== this.team
              && AV.dist2(other.x, other.z, enemy.x, enemy.z) <= radius) {
              match.hurt(other, weapon.damage * 0.3 * trueEffect(weapon, this, other), this, now);
            }
          }
        }
      }
    }

    /** 空の相手を撃つ。据わるのを待たず（動く機体を追う砲塔は回り続ける）、角は 1.5 度まで許す。 */
    shootAir(weapon, mount, enemy, distance, error, now) {
      const match = this.match;

      if (Math.abs(error) > AIR_ALIGNED) return;

      if (mount === "missile") {
        this.ammo.missile--;
        this.cooldown = Math.max(1, Math.round(20 / weapon.rps));
        match.stats[this.team].shots++;
        this.lastVictim = { id: enemy.id, team: enemy.team, tick: now };
        match.launch(this, enemy, weapon.damage, AIR_MISSILE_HIT, now + Math.ceil(distance / MISSILE_SPEED), now);
        return;
      }

      if (distance > AIR_GUN_REACH) return;

      const rounds = Math.min(this.ammo[mount], Math.max(1, Math.round(weapon.rps / 4)));
      const falloff = Math.max(0.2, 1 - distance / AIR_GUN_REACH);
      const damage = rounds * (weapon.salvo || 1) * HIT_AIRCRAFT * falloff * weapon.damage * (0.5 + match.random());

      this.ammo[mount] -= rounds;
      this.cooldown = 5;
      match.stats[this.team].shots += rounds;
      this.lastVictim = { id: enemy.id, team: enemy.team, tick: now };
      match.shots.push({ x0: this.x, z0: this.z, x1: enemy.x, z1: enemy.z, team: this.team, hit: true, tick: now, air: true });
      match.hurt(enemy, damage, this, now);
    }

    /** 砲塔を向ける。残りの角度を返す。 */
    slew(bearing) {
      const rate = this.def.traverse * Math.PI / 180;
      const error = AV.wrap(bearing - this.turret);

      this.turret = AV.wrap(this.turret + clamp(error, -rate, rate));

      return AV.wrap(bearing - this.turret);
    }

    wantsToHold(intent) {
      const aim = this.target;

      if (!aim || !aim.visible || aim.flying || !intent || intent.stop <= 0) return false;
      if (intent.action === "seek_cover" || intent.action === "retreat") return false;

      const stop = this.weapon === "coax" ? Math.min(intent.stop, COAX_RANGE) : intent.stop;

      return aim.distance <= stop;
    }

    resupply() {
      if (this.ammo.main <= 0 && this.ammo.coax <= 0 && this.ammo.missile <= 0) {
        if (++this.spent >= SCUTTLE_AFTER) {
          this.match.destroy(this, null, this.match.tick, "scuttled");
        }
      } else {
        this.spent = 0;
      }
    }

    // ---------------------------------------------------------------- 走る

    navigate(now) {
      const match = this.match;
      const intent = this.intent;
      const terrain = match.terrain;
      let wantSpeed = 0;
      let wantHeading = this.heading;

      if (this.reversing > 0) {
        this.reversing--;
        this.drive(this.heading + 0.03, -this.def.reverse);
        return;
      }

      if (intent && intent.mode === "move" && !this.wantsToHold(intent)) {
        const distance = AV.dist2(this.x, this.z, intent.x, intent.z);

        if (distance > intent.arrive) {
          const route = this.route;
          const due = this.replan || !route || now - route.plannedAt >= AV.TIMING.replan
            || AV.dist2(route.goalX, route.goalZ, intent.x, intent.z) > 16;

          if (due && match.budget.plan > 0) {
            match.budget.plan--;
            this.replan = false;
            const planned = tactics.planRoute(match, this, intent.x, intent.z, intent.risk, PLAN_BUDGET);

            this.route = {
              points: planned.points, index: 0, risk: planned.risk, wet: planned.wet, goalX: intent.x, goalZ: intent.z,
              plannedAt: now, riskWeight: intent.risk,
            };
          }

          const way = this.waypoint();

          if (way) {
            wantHeading = Math.atan2(way.z - this.z, way.x - this.x);
            const room = Math.max(distance, 4);
            wantSpeed = Math.min(this.def.speed * Math.min(intent.throttle, 1), Math.sqrt(2 * this.def.braking * room) + 0.1);
          }
        }
      } else if (intent) {
        let fx = intent.faceX;
        let fz = intent.faceZ;

        if (this.target && this.target.visible) {
          fx = this.target.unit.x;
          fz = this.target.unit.z;
        }

        if (fx !== null && fx !== undefined && this.def.steer > 0) {
          const error = AV.wrap(Math.atan2(fz - this.z, fx - this.x) - this.heading);

          // 砲塔が回れば車体は向き直らない。回りの遅い車両だけが車体ごと向ける。
          if (Math.abs(error) > Math.PI / 3 && this.def.traverse < 1.2) wantHeading = this.heading + error;
        }
      }

      this.drive(wantHeading, wantSpeed);

      // 詰まり: 走りたいのに40 tick で1ブロックも進んでいない。
      if ((now + this.id) % 40 === 0) {
        const moved = AV.dist2(this.x, this.z, this.stallCheckX, this.stallCheckZ);

        if (wantSpeed > 0.1 && moved < 1.0) {
          this.stall++;
          this.replan = true;
          this.reversing = 15;
          match.stats[this.team].stalls++;
        } else {
          this.stall = 0;
        }

        this.stallCheckX = this.x;
        this.stallCheckZ = this.z;
      }
    }

    /** 道の上の、真っ直ぐ行ける一番先の通過点。 */
    waypoint() {
      const route = this.route;

      if (!route || route.points.length === 0) {
        return this.intent ? { x: this.intent.x, z: this.intent.z } : null;
      }

      while (route.index < route.points.length - 1
        && AV.dist2(this.x, this.z, route.points[route.index].x, route.points[route.index].z) < 3) {
        route.index++;
      }

      let chosen = route.index;

      for (let ahead = route.index + 1; ahead < Math.min(route.points.length, route.index + 6); ahead++) {
        if (this.straight(route.points[ahead], route.wet)) chosen = ahead; else break;
      }

      route.index = chosen;

      return route.points[chosen];
    }

    /** そこまで真っ直ぐ行けるか。車体の幅（左右 1.5 ブロック）で1ブロックおきに掃く（RoutePlanner.smooth の掃引）。 */
    straight(to, wet) {
      const terrain = this.match.terrain;
      const length = AV.dist2(this.x, this.z, to.x, to.z);

      if (length < 1e-3) return true;

      const steps = Math.ceil(length);
      const sideX = -(to.z - this.z) / length * 1.5;
      const sideZ = (to.x - this.x) / length * 1.5;

      for (const offset of [0, 1, -1]) {
        let last = terrain.index(this.x + sideX * offset, this.z + sideZ * offset);

        for (let s = 1; s <= steps; s++) {
          const t = s / steps;
          const i = terrain.index(this.x + (to.x - this.x) * t + sideX * offset, this.z + (to.z - this.z) * t + sideZ * offset);

          if (i < 0 || terrain.kind[i] === AV.KIND.BUILDING || (!wet && terrain.kind[i] === AV.KIND.WATER)) return false;
          if (last >= 0 && i !== last && !terrain.passable(last, i)) return false;

          last = i;
        }
      }

      return true;
    }

    drive(wantHeading, wantSpeed) {
      const terrain = this.match.terrain;
      const d = this.def;
      const turn = Math.min(d.steer * 2.2, 5) * Math.PI / 180;
      const error = AV.wrap(wantHeading - this.heading);

      this.heading = AV.wrap(this.heading + clamp(error, -turn, turn));

      if (wantSpeed > 0 && Math.abs(error) > Math.PI / 4) {
        wantSpeed *= Math.abs(error) > Math.PI / 2 ? 0.15 : 0.5;
      }

      const here = terrain.index(this.x, this.z);
      const kind = here < 0 ? AV.KIND.OPEN : terrain.kind[here];

      if (kind === AV.KIND.WATER) this.match.stats[this.team].wetTicks++;
      const slow = kind === AV.KIND.WATER ? 0.35 : kind === AV.KIND.WOOD ? 0.6 : 1.0;

      wantSpeed *= slow;

      const delta = wantSpeed - this.speed;

      this.speed += clamp(delta, -d.braking, d.acceleration * 2.5);

      if (Math.abs(this.speed) < 1e-3) return;

      const dx = Math.cos(this.heading) * this.speed;
      const dz = Math.sin(this.heading) * this.speed;
      const wet = !!(this.route && this.route.wet);
      // 壁に当たったら、当たった軸だけ止めて沿って滑る（ゲームの車両も衝突で片方の軸だけ止まる）。
      const moves = [[dx, dz, 1], [dx, 0, 0.7], [0, dz, 0.7]];

      for (const [mx, mz, keep] of moves) {
        if (Math.abs(mx) + Math.abs(mz) < 1e-4) continue;

        const next = terrain.index(this.x + mx, this.z + mz);

        if (next < 0 || terrain.kind[next] === AV.KIND.BUILDING || (here >= 0 && next !== here && !terrain.passable(here, next))) {
          continue;
        }

        // 水は危ない（2026-09-17 の指示）。触角は、既に水の中か、今の道が水を渡ると決めたときでなければ深い水を壁と呼ぶ
        // （Navigator と Obstacles のゲームの規則）。
        const entering = next !== here && terrain.kind[next] === AV.KIND.WATER && kind !== AV.KIND.WATER;

        if (entering && !wet) continue;
        if (entering) this.match.stats[this.team].waterEntries++;

        this.x += mx;
        this.z += mz;
        this.speed *= keep;
        return;
      }

      this.speed = 0;
    }
  }

  function attacksObjective(enemy, states, team) {
    for (const o of states) {
      const ours = o.state === "friendly" || (o.state === "contested" && o.owner === team);

      if (ours && AV.dist2(enemy.x, enemy.z, o.x, o.z) <= o.radius + 24) return true;
    }

    return false;
  }

  /** 本当の効き（見積もりの WeaponReach.effectiveness より厳しい）。当たった面で装甲が変わる。 */
  function trueEffect(weapon, shooter, victim) {
    const armour = victim.def.armour;
    const tx = shooter.x - victim.x;
    const tz = shooter.z - victim.z;
    const length = Math.sqrt(tx * tx + tz * tz) || 1;
    const facing = (Math.cos(victim.heading) * tx + Math.sin(victim.heading) * tz) / length;
    const face = facing > 0.7 ? 1.0 : facing < -0.5 ? 0.35 : 0.6;

    if (weapon.type === "missile" || weapon.type === "rocket") {
      return armour >= 5 ? 0.8 * (face < 1 ? 1.15 : 1) : 1;
    }

    if (weapon.explosion > 0) {
      return 0.85 * (armour * face >= 3 ? 0.6 : 1);
    }

    return clamp((weapon.ricochet - armour * face * 6) / 45, 0.08, 1);
  }

  // ------------------------------------------------------------------
  // 陣営の頭
  // ------------------------------------------------------------------

  class Intel {
    constructor(match, team) {
      this.match = match;
      this.team = team;
      this.spotted = new Map();
      this.threats = new brain.ThreatMap(match.terrain);
    }

    report(enemy, now) {
      let row = this.spotted.get(enemy.id);

      if (!row) {
        row = { id: enemy.id, unit: enemy, x: 0, z: 0, tick: now, alive: true };
        this.spotted.set(enemy.id, row);
      }

      row.x = enemy.x;
      row.z = enemy.z;
      row.tick = now;
    }

    forget(id) {
      this.spotted.delete(id);
    }

    known(now) {
      let count = 0;

      for (const row of this.spotted.values()) {
        if (row.alive && now - row.tick <= MEMORY_TICKS) count++;
      }

      return count;
    }

    engagedBy(targetId, exceptId) {
      let count = 0;

      for (const unit of this.match.units) {
        if (unit.alive && unit.team === this.team && unit.id !== exceptId && unit.target && unit.target.id === targetId) {
          count++;
        }
      }

      return count;
    }

    updateThreats(now) {
      const terrain = this.match.terrain;
      const rows = [];

      for (const row of this.spotted.values()) {
        if (row.alive && row.unit.alive && now - row.tick <= MEMORY_TICKS) rows.push(row);
      }

      rows.sort((a, b) => b.tick - a.tick);

      const sources = rows.slice(0, THREAT_SOURCES).map((row) => ({
        x: row.x, z: row.z, y: row.unit.flying ? terrain.groundAt(row.x, row.z) + AV.air.BOMB_HEIGHT
          : tactics.eyeOf(terrain, row.x, row.z, row.unit.def.height),
        capability: brain.capability(row.unit.reach, 350, 2.5), reach: Math.min(row.unit.reach.maxRange, THREAT_RADIUS),
        current: now - row.tick <= 20, seenTick: row.tick,
      }));

      this.threats.update(sources, now, MEMORY_TICKS, DECAY, UNKNOWN_EXPOSURE);
    }
  }

  /** SupportCalls の写し（要請と、手の空いた近い味方の割り当て）。 */
  class Support {
    constructor() {
      this.calls = new Map();
      this.answers = new Map();
    }

    raise(call) {
      this.calls.set(call.caller, call);
    }

    withdraw(id) {
      this.calls.delete(id);
    }

    answerFor(id, now) {
      const callerId = this.answers.get(id);

      if (callerId === undefined) return null;

      const call = this.calls.get(callerId);

      return call && now - call.tick <= 100 ? call : null;
    }

    /** SupportCalls.airSupport: 要請の主を撃っている敵に付ける急ぎ（一番急ぐ要請の物）。 */
    airSupport(enemyId, now) {
      let most = 0;

      for (const call of this.calls.values()) {
        if (now - call.tick > 100 || call.urgency <= most) continue;
        if (call.attackers.includes(enemyId)) most = call.urgency;
      }

      return most;
    }

    match(match, team, now) {
      const previous = this.answers;
      const next = new Map();
      const calls = [...this.calls.values()].filter((c) => now - c.tick <= 100 && match.byId(c.caller)
        && match.byId(c.caller).alive).sort((a, b) => b.urgency - a.urgency);

      for (const [id, call] of this.calls) {
        if (now - call.tick > 100) this.calls.delete(id);
      }

      const callers = new Set(calls.map((c) => c.caller));

      for (const call of calls) {
        const wanted = Math.min(3, call.lacking + 1);
        const candidates = match.units.filter((u) => u.alive && !u.flying && u.team === team && !callers.has(u.id)
          && !next.has(u.id) && u.canAnswer(now) && AV.dist2(u.x, u.z, call.x, call.z) <= 300);

        candidates.sort((a, b) => {
          const ka = previous.get(a.id) === call.caller ? 0 : 1;
          const kb = previous.get(b.id) === call.caller ? 0 : 1;

          return ka - kb || AV.dist2(a.x, a.z, call.x, call.z) - AV.dist2(b.x, b.z, call.x, call.z);
        });

        for (const unit of candidates.slice(0, wanted)) next.set(unit.id, call.caller);
      }

      this.answers = next;
    }
  }

  // ------------------------------------------------------------------
  // 試合
  // ------------------------------------------------------------------

  /**
   * options: {seed, map:{size, points, hills, woods, towns, water}, teamSize, versions:[values, values],
   *           tickets, bleed, respawn, minutes, roster:[vehicle id…]|null}
   */
  class Match {
    constructor(options) {
      this.options = options;
      this.seed = options.seed >>> 0;
      this.random = AV.rng(this.seed ^ 0x9e3779b9);
      this.terrain = new AV.Terrain(Object.assign({ seed: this.seed }, options.map || {}));
      // 試合の中の乱数は種で決まる。ワールドの会場は種に関わらず同じ。
      this.tick = 0;
      this.limit = Math.round((options.minutes || 15) * 60 * 20);
      this.bleedInterval = options.bleed || 100;
      this.respawnTicks = options.respawn || 200;
      this.teamSize = options.teamSize || 8;
      this.startTickets = options.tickets || 100;
      this.units = [];
      this.byIdMap = new Map();
      this.nextId = 1;
      this.queue = [];
      this.shots = [];
      this.blasts = [];
      this.missiles = [];
      this.air = options.air !== false && !!AV.DATA.aircraft && AV.DATA.aircraft.length > 0;
      this.budget = { search: 3, plan: 2 };
      this.scratchUtilities = {};
      this.scratchSeen = new Set();
      this.losCache = new Map();
      this.routeScratch = new tactics.RouteScratch(this.terrain);
      this.finished = false;
      this.winner = null;
      this.spawns = this.terrain.spawns;
      this.points = this.terrain.points.map((p) => ({
        id: p.id, x: p.x, z: p.z, radius: p.radius, owner: p.home === undefined ? null : p.home, taking: null, progress: 0,
        contested: false, home: p.home === undefined ? null : p.home,
      }));

      const centre = this.terrain.size / 2;
      let farthest = 0;

      for (const p of this.points.concat(this.spawns)) {
        farthest = Math.max(farthest, AV.dist2(p.x, p.z, centre, centre));
      }

      this.centre = { x: centre, z: centre };
      this.arena = Math.max(farthest + 96, 250);

      if (this.terrain.world) {
        this.centre = { x: this.terrain.world.centreX, z: this.terrain.world.centreZ };
        this.arena = this.terrain.world.arena;
      }

      const roster = AV.DATA.vehicles.filter((v) => !options.roster || options.roster.includes(v.id));

      this.kinds = new Map();

      for (const v of roster) {
        const kind = brain.kindOf(v.role);

        if (!this.kinds.has(kind)) this.kinds.set(kind, []);
        this.kinds.get(kind).push(v);
      }

      // 防空が一覧に無ければ、空を撃てる車両（主砲が機関砲）で埋める（Bots.pick）。
      if (!this.kinds.has("air_defence")) {
        const guns = roster.filter((v) => v.defendsAir);

        if (guns.length) this.kinds.set("air_defence", guns);
      }

      this.airKinds = new Map();

      if (this.air) {
        for (const plane of AV.DATA.aircraft) {
          if (options.roster && !options.roster.includes(plane.id)) continue;
          if (!this.airKinds.has(plane.kind)) this.airKinds.set(plane.kind, []);
          this.airKinds.get(plane.kind).push(plane);
        }

        this.air = this.airKinds.size > 0;
      }

      this.teams = [0, 1].map((team) => {
        const params = new AV.Params(options.versions[team] || {});

        return {
          params,
          weights: {
            policy: brain.policyWeights(params), target: brain.targetWeights(params), tactics: tactics.weights(params),
            spawn: brain.spawnWeights(params),
            air: AV.air.weights(params),
          },
          tickets: this.startTickets,
          board: new brain.Board(team),
          support: new Support(),
          losses: [],
        };
      });
      this.intel = [new Intel(this, 0), new Intel(this, 1)];
      this.stats = [0, 1].map(() => ({
        kills: 0, losses: 0, scuttled: 0, captures: 0, damage: 0, shots: 0, stalls: 0, decisions: 0, switches: 0,
        airKills: 0, airLosses: 0, airDamage: 0, sorties: 0, retired: 0,
        airDefenceIdle: 0, airDefenceWanted: 0, pairSamples: 0, pairShare: 0, waterEntries: 0, wetTicks: 0,
        actions: Object.fromEntries(AV.ACTIONS.map((a) => [a, 0])), lifetime: 0, spawned: {},
      }));

      for (let slot = 0; slot < this.teamSize; slot++) {
        for (let team = 0; team < 2; team++) this.spawn(team, slot);
      }

      for (let team = 0; team < 2; team++) this.teams[team].board.refresh(this, this.teams[team].params, 0);
    }

    byId(id) {
      return this.byIdMap.get(id) || null;
    }

    /** 拠点の枠に入る AI（地上の車両だけ。航空機は拠点を取らない）。 */
    pilotsOf(team) {
      return this.units.filter((u) => u.alive && u.team === team && !u.flying);
    }

    countDecision(team, action, changed) {
      const s = this.stats[team];

      s.decisions++;
      s.actions[action]++;
      if (changed) s.switches++;
    }

    los(a, b, now) {
      if (a.flying || b.flying) return true;

      const key = a.id < b.id ? a.id * 65536 + b.id : b.id * 65536 + a.id;
      const cached = this.losCache.get(key);

      if (cached && now - cached.tick <= LOS_CACHE_TICKS) return cached.clear;

      const t = this.terrain;
      const clear = t.trace(a.x, tactics.eyeOf(t, a.x, a.z, a.def.height), a.z,
        b.x, tactics.eyeOf(t, b.x, b.z, b.def.height), b.z) !== 1;

      this.losCache.set(key, { tick: now, clear });

      return clear;
    }

    /** Bots.situation の写し: 名簿を種類で数え、拠点と最近の損失とチケットの余裕を足す。 */
    situation(team, now) {
      const t = {
        own: 0, enemyAir: 0, enemyArmour: 0, enemyLight: 0, enemyAirDefence: 0, ownTanks: 0, ownLight: 0,
        ownAirDefence: 0, ownArtillery: 0, ownFighters: 0, ownStrikers: 0, capturable: 0, threatened: 0,
      };

      for (const u of this.units) {
        if (!u.alive) continue;

        const ours = u.team === team;

        if (ours) t.own++;

        if (u.flying) {
          if (!ours) t.enemyAir++;
          else if (u.kind === "fighter") t.ownFighters++;
          else t.ownStrikers++;
          continue;
        }

        const air = u.role === "aa" ? 1 : u.def.defendsAir ? 0.5 : 0;

        if (ours) {
          t.ownAirDefence += air;
          if (u.role === "tank") t.ownTanks++;
          else if (u.role === "artillery") t.ownArtillery++;
          else if (u.role !== "aa") t.ownLight++;
        } else {
          t.enemyAirDefence += air;
          if (u.role === "tank") t.enemyArmour++;
          else if (u.role !== "aa" && u.role !== "artillery") t.enemyLight++;
        }
      }

      const side = this.teams[team];

      for (const o of side.board.states) {
        if (o.state !== "friendly" || o.recaptureRequired) t.capturable++;
        if (o.beingTaken) t.threatened++;
      }

      const lost = (cause) => side.losses.filter((l) => now - l.tick <= LOSS_MEMORY && l.cause === cause).length;

      t.lostToAir = lost("air");
      t.lostToArmour = lost("armour");
      t.lostToAirDefence = lost("air_defence");
      t.spare = brain.spare(side.tickets, this.startTickets);
      t.loneTanks = this.lonely(team, "tank", "ifv");
      t.loneIfvs = this.lonely(team, "ifv", "tank");

      return t;
    }

    /** 相方の役割の味方が PAIR_REACH の内に1両もいない、その役割の味方の数。 */
    lonely(team, role, partner) {
      let count = 0;

      for (const u of this.units) {
        if (!u.alive || u.flying || u.team !== team || u.role !== role) continue;

        const paired = this.units.some((v) => v.alive && !v.flying && v.team === team && v.role === partner
          && AV.dist2(u.x, u.z, v.x, v.z) <= PAIR_REACH);

        if (!paired) count++;
      }

      return count;
    }

    /** 学習器の加点のため: 戦車と歩兵戦闘車のうち、相方が近くにいる割合を数える。 */
    samplePairs() {
      for (let team = 0; team < 2; team++) {
        let members = 0;
        let lonely = 0;

        for (const u of this.units) {
          if (u.alive && !u.flying && u.team === team && (u.role === "tank" || u.role === "ifv")) members++;
        }

        if (members === 0) continue;

        lonely = this.lonely(team, "tank", "ifv") + this.lonely(team, "ifv", "tank");

        const s = this.stats[team];

        s.pairShare = (s.pairShare * s.pairSamples + (members - lonely) / members) / (s.pairSamples + 1);
        s.pairSamples++;
      }
    }

    spawn(team, slot) {
      const side = this.teams[team];
      const now = this.tick;
      const available = new Set(this.kinds.keys());
      const airborne = this.units.filter((u) => u.alive && u.flying && u.team === team).length;
      // Bots.airSlots: 2両以下の陣営は地上だけ、それ以外は3割（最低1）。
      const airSlots = this.teamSize < 3 ? 0 : Math.max(1, Math.floor(this.teamSize * AIR_SHARE));

      if (this.air && airborne < airSlots) {
        for (const kind of this.airKinds.keys()) available.add(kind);
      }

      const situation = this.situation(team, now);
      const kind = brain.choose(brain.needs(situation, side.weights.spawn), available, this.random());
      let unit;

      // 学習器の罰と加点のため: 敵機のいないときに出した防空と、いるときに出した防空を数える。
      if (kind === "air_defence") {
        if (situation.enemyAir > 0) this.stats[team].airDefenceWanted++;
        else this.stats[team].airDefenceIdle++;
      }

      if (kind === "fighter" || kind === "striker") {
        const choices = this.airKinds.get(kind);
        const def = choices[Math.floor(this.random() * choices.length)];
        const base = this.spawns[team];
        const ax = base.x - this.centre.x;
        const az = base.z - this.centre.z;
        const length = Math.sqrt(ax * ax + az * az) || 1;
        const hostile = this.units.filter((u) => u.alive && u.flying && u.team !== team).length;

        unit = new AV.air.Plane(this, this.nextId++, team, slot, def, base.x + ax / length * LAUNCH_BEHIND,
          base.z + az / length * LAUNCH_BEHIND, Math.atan2(-az, -ax), hostile);
        this.stats[team].sorties++;
      } else {
        let choices = this.kinds.get(kind) || [...this.kinds.values()][0];

        // 相方のいない戦車がいれば、軽装甲は歩兵戦闘車から（Bots.pick）。
        if (kind === "light" && situation.loneTanks > 0) {
          const ifvs = choices.filter((v) => v.role === "ifv");

          if (ifvs.length) choices = ifvs;
        }

        const def = choices[Math.floor(this.random() * choices.length)];
        const base = this.spawns[team];
        let x = base.x;
        let z = base.z;

        for (let tries = 0; tries < 24; tries++) {
          const cx = base.x + (this.random() - 0.5) * 60;
          const cz = base.z + (this.random() - 0.5) * 60;
          const i = this.terrain.index(cx, cz);

          if (i >= 0 && this.terrain.kind[i] !== AV.KIND.BUILDING && this.terrain.kind[i] !== AV.KIND.WATER) {
            x = cx;
            z = cz;
            break;
          }
        }

        unit = new Unit(this, this.nextId++, team, slot, def, x, z);
      }

      this.units.push(unit);
      this.byIdMap.set(unit.id, unit);
      this.stats[team].spawned[unit.def.id] = (this.stats[team].spawned[unit.def.id] || 0) + 1;

      return unit;
    }

    /** 誘導弾を1発。着く tick に当たりを振る（追われた機体は避ける）。 */
    launch(shooter, target, damage, hit, arrive, now) {
      this.missiles.push({ shooter, target, damage, hit, arrive, launched: now, x: shooter.x, z: shooter.z, team: shooter.team });

      if (target.flying && target.inbound) target.inbound(now);
    }

    /** 撃つ物が尽きた航空機を下げる（Bots.scuttle）。チケットは動かさない。 */
    retire(plane, now) {
      if (!plane.alive) return;

      plane.alive = false;
      this.stats[plane.team].retired++;
      this.stats[plane.team].lifetime += now - plane.bornTick;
      this.intel[1 - plane.team].forget(plane.id);
      this.queue.push({ team: plane.team, slot: plane.slot, at: now + this.respawnTicks });
    }

    hurt(victim, amount, attacker, now) {
      if (!victim.alive || amount <= 0) return;

      const dealt = Math.min(amount, victim.health);

      victim.health -= amount;
      victim.lastHurt = now;

      if (attacker) {
        victim.lastAttacker = attacker.id;
        victim.attackers.set(attacker.id, now);
        this.stats[attacker.team].damage += dealt;

        if (attacker.flying) this.stats[attacker.team].airDamage += dealt;
      }

      if (victim.health <= 0) this.destroy(victim, attacker, now, "destroyed");
    }

    destroy(unit, attacker, now, cause) {
      if (!unit.alive) return;

      unit.alive = false;

      const side = this.teams[unit.team];

      side.tickets -= 1;
      side.losses.push({ tick: now, cause: lossCause(unit, attacker) });
      this.stats[unit.team].losses++;

      if (unit.flying) this.stats[unit.team].airLosses++;
      if (attacker && attacker.flying) this.stats[attacker.team].airKills++;
      this.stats[unit.team].lifetime += now - unit.bornTick;

      if (cause === "scuttled") this.stats[unit.team].scuttled++;
      if (attacker) this.stats[attacker.team].kills++;

      this.intel[1 - unit.team].forget(unit.id);
      this.teams[unit.team].support.withdraw(unit.id);
      this.queue.push({ team: unit.team, slot: unit.slot, at: now + this.respawnTicks });
    }

    /** 1 tick 進める。終わったら true。 */
    step() {
      if (this.finished) return true;

      const now = ++this.tick;

      this.budget.search = 3;
      this.budget.plan = 2;

      for (let team = 0; team < 2; team++) {
        if ((now + team * 10) % AV.TIMING.threatMap === 0) this.intel[team].updateThreats(now);
        if ((now + team * 20) % AV.TIMING.objective === 0) this.teams[team].board.refresh(this, this.teams[team].params, now);
        if ((now + team * 10) % 20 === 5) this.teams[team].support.match(this, team, now);
      }

      for (const unit of this.units) {
        if (unit.alive) unit.think(now);
      }

      this.separate();
      this.flyMissiles(now);

      if (now % CAPTURE_INTERVAL === 0) this.capture();
      if (now % PAIR_EVERY === 0) this.samplePairs();
      if (now % this.bleedInterval === 0) this.bleed();

      if (now % 100 === 0) {
        this.units = this.units.filter((u) => u.alive);

        for (const [id, unit] of this.byIdMap) {
          if (!unit.alive) this.byIdMap.delete(id);
        }

        for (const [key, row] of this.losCache) {
          if (now - row.tick > LOS_CACHE_TICKS) this.losCache.delete(key);
        }
      }

      for (let at = this.queue.length - 1; at >= 0; at--) {
        const wait = this.queue[at];

        if (now >= wait.at && this.teams[wait.team].tickets > 0) {
          this.queue.splice(at, 1);
          this.spawn(wait.team, wait.slot);
        }
      }

      if (this.shots.length > 400) this.shots.splice(0, this.shots.length - 200);
      if (this.blasts.length > 100) this.blasts.splice(0, this.blasts.length - 50);

      const [a, b] = this.teams;

      if (a.tickets <= 0 || b.tickets <= 0 || now >= this.limit) {
        this.finished = true;
        this.winner = a.tickets === b.tickets ? null : a.tickets > b.tickets ? 0 : 1;

        for (const unit of this.units) {
          if (unit.alive) this.stats[unit.team].lifetime += now - unit.bornTick;
        }
      }

      return this.finished;
    }

    flyMissiles(now) {
      for (let at = this.missiles.length - 1; at >= 0; at--) {
        const m = this.missiles[at];
        const t = m.target;
        const left = Math.max(m.arrive - now, 1);

        m.x += (t.x - m.x) / left;
        m.z += (t.z - m.z) / left;

        if (now < m.arrive) continue;

        this.missiles.splice(at, 1);

        if (!t.alive) continue;

        const p = m.hit * (t.flying && t.evading && t.evading(now) ? 0.5 : 1);

        if (this.random() < p) {
          this.blasts.push({ x: t.x, z: t.z, radius: 8, team: m.team, tick: now });
          this.hurt(t, m.damage * (0.8 + 0.4 * this.random()), m.shooter, now);
        }
      }
    }

    separate() {
      const units = this.units;

      for (let i = 0; i < units.length; i++) {
        const a = units[i];

        if (!a.alive || a.flying) continue;

        for (let j = i + 1; j < units.length; j++) {
          const b = units[j];

          if (!b.alive || b.flying) continue;

          const dx = b.x - a.x;
          const dz = b.z - a.z;
          const least = (a.def.width + b.def.width) * 0.5;

          if (dx * dx + dz * dz >= least * least) continue;

          const length = Math.sqrt(dx * dx + dz * dz) || 0.01;
          const push = (least - length) * 0.5;
          const ux = dx / length;
          const uz = dz / length;

          moveIfOpen(this.terrain, a, -ux * push, -uz * push);
          moveIfOpen(this.terrain, b, ux * push, uz * push);
        }
      }
    }

    capture() {
      for (const point of this.points) {
        let a = 0, b = 0;

        for (const unit of this.units) {
          if (unit.alive && !unit.flying && AV.dist2(unit.x, unit.z, point.x, point.z) <= point.radius) {
            if (unit.team === 0) a++; else b++;
          }
        }

        point.contested = a > 0 && b > 0;

        if (a > 0 && b > 0) continue;

        if (a === 0 && b === 0) {
          point.progress = Math.max(point.progress - CAPTURE_INTERVAL * CAPTURE_DECAY, 0);
          if (point.progress === 0) point.taking = null;
          continue;
        }

        const team = a > 0 ? 0 : 1;

        if (team === point.owner) {
          point.progress = Math.max(point.progress - CAPTURE_INTERVAL * CAPTURE_DECAY, 0);
          if (point.progress === 0) point.taking = null;
          continue;
        }

        if (team !== point.taking) {
          point.progress -= CAPTURE_INTERVAL;

          if (point.progress > 0) continue;

          point.taking = team;
          point.progress = -point.progress;
        } else {
          point.progress += CAPTURE_INTERVAL;
        }

        if (point.progress >= AV.CAPTURE_TICKS) {
          point.owner = team;
          point.taking = null;
          point.progress = 0;
          this.stats[team].captures++;
        }
      }
    }

    bleed() {
      const held = [0, 0];

      for (const point of this.points) {
        if (point.owner !== null) held[point.owner]++;
      }

      const most = Math.max(held[0], held[1]);

      if (most <= 0) return;

      for (let team = 0; team < 2; team++) {
        const behind = most - held[team];

        if (behind > 0) this.teams[team].tickets -= behind;
      }
    }

    run() {
      while (!this.step()) {
        // 回す
      }

      return this.result();
    }

    result() {
      return {
        seed: this.seed,
        winner: this.winner,
        tickets: this.teams.map((t) => Math.max(t.tickets, 0)),
        ticks: this.tick,
        points: this.points.map((p) => p.owner),
        stats: this.stats,
      };
    }

    /** 観戦の絵のための写し。 */
    snapshot() {
      return {
        tick: this.tick,
        tickets: this.teams.map((t) => t.tickets),
        points: this.points.map((p) => ({ id: p.id, x: p.x, z: p.z, radius: p.radius, owner: p.owner, taking: p.taking,
          progress: p.progress / AV.CAPTURE_TICKS, contested: p.contested })),
        units: this.units.filter((u) => u.alive).map((u) => ({
          id: u.id, team: u.team, vehicle: u.def.id, role: u.role, flying: !!u.flying, x: u.x, z: u.z, heading: u.heading, turret: u.turret,
          health: u.health / u.def.health, action: u.action, target: u.target ? u.target.id : null,
          objective: u.objective ? u.objective.id : null, route: u.route ? u.route.points.slice(u.route.index) : null,
          intentX: u.intent ? u.intent.x : null, intentZ: u.intent ? u.intent.z : null,
        })),
        shots: this.shots.filter((s) => this.tick - s.tick <= 4),
        finished: this.finished,
        winner: this.winner,
      };
    }
  }

  /** Bots.noteLoss: 何に倒されたか。 */
  function lossCause(victim, attacker) {
    if (!attacker) return "other";
    if (attacker.flying) return "air";
    if (victim.flying && attacker.def.defendsAir) return "air_defence";
    if (attacker.role === "tank") return "armour";
    if (attacker.role === "artillery") return "artillery";

    return "other";
  }

  function moveIfOpen(terrain, unit, dx, dz) {
    const i = terrain.index(unit.x + dx, unit.z + dz);

    // 押し合いで水へ押し出さない。
    if (i >= 0 && terrain.kind[i] !== AV.KIND.BUILDING
      && (terrain.kind[i] !== AV.KIND.WATER || terrain.kind[terrain.index(unit.x, unit.z)] === AV.KIND.WATER)) {
      unit.x += dx;
      unit.z += dz;
    }
  }

  AV.Match = Match;
}
