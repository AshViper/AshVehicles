/*
 * 航空機の写し。
 *
 *   KillScore   score / killsPerMinute / reachTicks / cycleTicks / turnRadius / damagePerPass / passesLeft /
 *               penetration / blast / airspace（式もパラメータ air.target.* もゲームと同じ）
 *   AirPilot    Plane（狙う相手を10 tick ごとに選び、TRANSIT → RUN → EXTEND の航過で撃ち、撃つ物が尽きたら出直す）
 *
 * 飛び方は見積もり: 高さを持たない平面の上で、巡航の速さ（失速の2.8倍、3.5〜6）と、バンク60度の旋回半径で回るだけ。
 * 舵（FlightControl）・失速・地面への引き起こしは無い。AI の航空機に渡すのは固定翼機だけなので回転翼は写さない
 * （同梱のヘリはどれも機首砲が砲手席で、爆弾も吊らない——AirLoadout.flyable）。
 */
function AVSim_air(AV) {
  "use strict";

  const clamp = AV.clamp;

  // ------------------------------------------------------------------
  // KillScore
  // ------------------------------------------------------------------

  const BOMBS_PER_PASS = 2;
  const AAM_PER_ENGAGEMENT = 2;
  const MINUTE = 1200;
  const LEAST_TICKS = 40;
  const MOST_PASSES = 100;
  const PARTIAL = 0.5;
  const GRAVITY = 0.0245;
  const TURN_BANK = 60;
  const LEAST_RADIUS = 120;
  const MERGE_TICKS = 60;
  const HOVER_TICKS = 60;
  const AIRSPACE_SCALE = 600;
  const GROUND_BURST = 3;
  const AIR_BURST = 2;
  const HIT_VEHICLE = 0.3;
  const HIT_AIRCRAFT = 0.06;
  const GROUND_RANGE = 600;
  const AIR_RANGE = 500;
  const FACE_SHARE = 0.5;
  const FACE_INCIDENCE = 15;
  const INCIDENCE_SPREAD = 30;
  const BOMB_MISS = 4;
  const DRIFT_MISS = 0.3;
  const FALL_TICKS = 130;
  const DIRECT_SHARE = 0.2;
  const CLUSTER_VEHICLE = 120;
  const AAM_HIT = 0.55;

  function weights(params) {
    const g = (k) => params.get("air.target." + k);

    return {
      air: g("air"), gunAir: g("gun_air"), kill: g("kill"), attackingMe: g("attacking_me"), threat: g("threat"),
      objective: g("objective"), sticky: g("sticky"), committed: g("committed"), crowd: g("crowd"),
      defence: g("defence"), outside: g("outside"), inside: g("inside"), support: g("support"), stall: g("stall"),
    };
  }

  function score(p, flight, w, current, committed) {
    const rate = killsPerMinute(p, flight) * Math.exp(-w.defence * p.defence) / (1 + w.crowd * p.allies);
    const value = !p.airborne ? 1 : flight.missiles ? w.air : w.gunAir;
    let s = w.kill * value * rate + w.support * p.support;

    if (p.onObjective) s += w.objective;
    if (current) s += committed ? w.committed : w.sticky;

    s *= airspace(p, w);
    s *= p.stalls <= 0 ? 1 : Math.exp(-w.stall * p.stalls);

    if (p.attackingMe) s += w.attackingMe;
    if (p.threatensMe) s += w.threat;

    return s;
  }

  function airspace(p, w) {
    if (p.airspace <= 0) return 1;

    if (p.outside > 0) {
      const beyond = p.outside / AIRSPACE_SCALE;
      return Math.exp(-w.outside * beyond * beyond);
    }

    return 1 + w.inside * clamp(-p.outside / p.airspace, 0, 1);
  }

  function killsPerMinute(p, flight) {
    if (p.damagePerPass <= 0 || p.passes <= 0 || p.health <= 0) return 0;

    const needed = Math.min(Math.ceil(p.health / p.damagePerPass), MOST_PASSES);
    const flown = Math.min(needed, p.passes);
    const chance = flown >= needed ? 1 : PARTIAL * Math.pow(flown / needed, 2);
    const ticks = reachTicks(p, flight) + (flown - 1) * cycleTicks(p, flight);

    return chance * MINUTE / Math.max(ticks, LEAST_TICKS);
  }

  function reachTicks(p, flight) {
    const speed = Math.max(flight.speed, 0.5);
    let ticks = (p.distance + flight.turnRadius * p.offTrack) / speed;

    if (!p.airborne && p.distance < flight.setup) ticks += 2 * (flight.setup - p.distance) / speed;

    return ticks;
  }

  function cycleTicks(p, flight) {
    const speed = Math.max(flight.speed, 0.5);
    const turn = Math.PI * flight.turnRadius / speed;

    return p.airborne ? MERGE_TICKS + turn : 2 * flight.extend / speed + turn;
  }

  function turnRadius(speed) {
    return Math.max(LEAST_RADIUS, speed * speed / (GRAVITY * Math.tan(TURN_BANK * Math.PI / 180)));
  }

  function penetration(round, mark) {
    if (!mark.armoured || round.ricochet <= 0) return 1;

    const margin = Math.max(round.ricochet - mark.armour, 0);

    return FACE_SHARE * clamp((margin - FACE_INCIDENCE) / INCIDENCE_SPREAD, 0, 1);
  }

  function blast(power, distance) {
    if (power <= 0) return 0;

    const reach = power * 2;
    const impact = 1 - distance / reach;

    return impact <= 0 ? 0 : (impact * impact + impact) / 2 * 7 * reach + 1;
  }

  /** WeaponDefinition.Projectile.blastAt。 */
  function blastAt(blastDamage, power, distance) {
    if (blastDamage <= 0 || power <= 0) return 0;

    const reach = power * 2;

    if (distance >= reach) return 0;

    return distance <= power ? blastDamage : blastDamage * (reach - distance) / (reach - power);
  }

  function burst(round, mark) {
    return round.rps * (mark.airborne ? AIR_BURST : GROUND_BURST);
  }

  function damagePerPass(store, round, mark, ammo) {
    if (ammo <= 0 || !round) return 0;

    switch (store) {
      case "gun": {
        const rounds = Math.min(ammo, burst(round, mark));
        const hit = mark.airborne ? HIT_AIRCRAFT : HIT_VEHICLE;
        const energy = round.explosion > 0 ? 1 : Math.exp(-2 * round.drag * (mark.airborne ? AIR_RANGE : GROUND_RANGE));

        return rounds * hit * penetration(round, mark) * round.damage * energy * mark.taken;
      }
      case "bomb": {
        if (mark.airborne) return 0;

        const bombs = Math.min(ammo, BOMBS_PER_PASS);

        if (round.cluster) return bombs * CLUSTER_VEHICLE * mark.taken;

        const miss = BOMB_MISS + mark.speed * FALL_TICKS * DRIFT_MISS;

        if (round.blast > 0) {
          const near = blastAt(round.blast, round.explosion, miss);
          return bombs * (near + DIRECT_SHARE * Math.max(0, round.damage - near)) * mark.taken;
        }

        return bombs * (blast(round.explosion, miss) + DIRECT_SHARE * round.damage) * mark.taken;
      }
      case "aam":
        return mark.airborne ? Math.min(ammo, AAM_PER_ENGAGEMENT) * AAM_HIT * round.damage * mark.taken : 0;
      default:
        return 0;
    }
  }

  function passesLeft(store, round, mark, ammo) {
    if (ammo <= 0) return 0;

    switch (store) {
      case "gun": return Math.ceil(ammo / Math.max(burst(round, mark), 1));
      case "bomb": return Math.ceil(ammo / BOMBS_PER_PASS);
      case "aam": return Math.ceil(ammo / AAM_PER_ENGAGEMENT);
      default: return 0;
    }
  }

  // ------------------------------------------------------------------
  // AirPilot
  // ------------------------------------------------------------------

  const RETARGET = 10;
  const SEARCH = 5000;
  const AIR_SEARCH = 6000;
  const AIR_THREAT = 3000;
  const LEASH = 1500;
  const OBJECTIVE_MARGIN = 48;
  const LOITER = 700;
  const EXTEND = 1800;
  const EXTEND_TICKS = 160;
  const EXTEND_SPEED = 1.15;
  const SETUP = 900;
  const GUN_REACH = 1100;
  const GUN_CLOSE = 320;
  const GUN_CONE = 3.0;
  const AIR_GUN_REACH = 900;
  const AIR_GUN_CONE = 4.0;
  const AAM_CLOSE = 250;
  const AAM_GAP = 60;
  const AAM_LOCK_RANGE = 4500;
  const AAM_CONE = 20;
  const MERGE = 150;
  const BOMB_HEIGHT = 150;
  const SCUTTLE_AFTER = 200;
  const EVADE_TICKS = 60;
  const CRUISE_STALL = 2.8;
  const CRUISE_LEAST = 3.5;
  const CRUISE_MOST = 6.0;
  const CRUISE_SHARE = 0.7;
  const DEG = Math.PI / 180;

  /** 見積もりの当たりやすさ（ゲームの KillScore の数を実際の結果にも使い、振れを足す）。 */
  const MISSILE_SPEED = 12;

  class Plane {
    constructor(match, id, team, slot, def, x, z, heading, hostile) {
      const side = match.teams[team];

      this.match = match;
      this.id = id;
      this.team = team;
      this.slot = slot;
      this.def = def;
      this.flying = true;
      this.role = "support";
      this.kind = def.kind;
      this.weights = side.weights;
      this.airWeights = side.weights.air;
      this.reach = AIR_REACH;
      this.x = x;
      this.z = z;
      this.heading = heading;
      this.turret = heading;
      this.cruise = clamp(def.stallSpeed * CRUISE_STALL, CRUISE_LEAST,
        Math.max(CRUISE_LEAST, Math.min(CRUISE_MOST, def.maxSpeed * CRUISE_SHARE)));
      this.speed = this.cruise;
      this.turnRadius = turnRadius(this.cruise);
      this.health = def.health;
      this.alive = true;
      this.bornTick = match.tick;
      this.target = null;
      this.targetScore = 0;
      this.targetStore = null;
      this.state = "transit";
      this.stateSince = match.tick;
      this.extendFrom = null;
      this.lastAam = -1e9;
      this.gunHeat = 0;
      this.evadeUntil = -1;
      this.spentAt = -1;
      this.attackers = new Map();
      this.lastHurt = -1e9;
      this.lastAttacker = -1;
      this.lastVictim = null;
      this.observations = new Map();
      this.fired = { gun: 0, bomb: 0, aam: 0 };

      // AirLoadout.equip の見積もり: 翼端は空対空ミサイル、敵に航空機がいれば戦闘機の翼下にも1対（3機以上で2対）、残りは爆弾。
      const airPairs = hostile <= 0 ? 0 : def.kind !== "fighter" ? 0 : hostile >= 3 ? 2 : 1;
      const aamCount = def.aam ? def.aamTips + Math.min(airPairs * 2, def.aamPylons) : 0;
      const pylonsLeft = Math.max(0, def.aamPylons - airPairs * 2);
      let bombs = 0;

      if (def.bomb) {
        const bySlots = Math.round(def.bombSlots * pylonsLeft / Math.max(def.aamPylons, 1));
        const byWeight = Math.floor((def.bombPayload - aamCount * (def.aam ? def.aam.mass : 0)) / Math.max(def.bomb.mass, 1));

        bombs = Math.max(0, Math.min(bySlots, byWeight));
      }

      this.ammo = { gun: def.gun ? def.gun.capacity : 0, bomb: bombs, aam: aamCount };
    }

    underFire(now) {
      return now - this.lastHurt <= 100;
    }

    attackerId(now) {
      return now - this.lastHurt <= 100 ? this.lastAttacker : -1;
    }

    get missiles() {
      return this.ammo.aam > 0;
    }

    evading(now) {
      return now <= this.evadeUntil;
    }

    /** 追ってくるミサイルを知らされた（BotPilot.onMissileInbound）。 */
    inbound(now) {
      this.evadeUntil = now + EVADE_TICKS;
    }

    think(now) {
      const match = this.match;

      if (this.ammo.gun <= 0 && this.ammo.bomb <= 0 && this.ammo.aam <= 0) {
        if (this.spentAt < 0) this.spentAt = now;

        this.fly(match.spawns[this.team].x, match.spawns[this.team].z, 1.0);

        if (now - this.spentAt >= SCUTTLE_AFTER) match.retire(this, now);

        return;
      }

      if (this.target && !this.target.alive) {
        this.target = null;
        this.state = "transit";
      }

      if ((now + this.id) % RETARGET === 0 || !this.target) {
        this.pick(now);
      }

      if (this.evading(now)) {
        // 追ってくる物の視線に直角へ全速。視線の代わりに、今の向きから右へ90度。
        this.fly(this.x + Math.cos(this.heading + Math.PI / 2) * 500, this.z + Math.sin(this.heading + Math.PI / 2) * 500, 1.15);
        return;
      }

      const target = this.target;

      if (this.state === "extend") {
        const away = this.extendFrom ? AV.dist2(this.x, this.z, this.extendFrom.x, this.extendFrom.z) : EXTEND;

        if (now - this.stateSince >= EXTEND_TICKS || away >= EXTEND) {
          this.state = "transit";
          this.stateSince = now;
        } else {
          this.fly(this.x + Math.cos(this.heading) * 500, this.z + Math.sin(this.heading) * 500, EXTEND_SPEED);
          return;
        }
      }

      if (!target) {
        const focus = this.focus();
        const angle = Math.atan2(this.z - focus.z, this.x - focus.x) + 0.35;

        this.fly(focus.x + Math.cos(angle) * LOITER, focus.z + Math.sin(angle) * LOITER, 1.0);
        return;
      }

      const distance = AV.dist2(this.x, this.z, target.x, target.z);
      const bearing = Math.atan2(target.z - this.z, target.x - this.x);
      const error = Math.abs(AV.wrap(bearing - this.heading)) / DEG;
      const store = this.targetStore;

      if (target.flying) {
        this.state = "run";
        this.fly(target.x + Math.cos(target.heading) * target.speed * 20, target.z + Math.sin(target.heading) * target.speed * 20, 1.1);

        if (store === "aam" && this.ammo.aam > 0 && distance >= AAM_CLOSE && distance <= AAM_LOCK_RANGE
          && error <= AAM_CONE && now - this.lastAam >= AAM_GAP) {
          this.launchAam(target, now, distance);
        } else if (this.ammo.gun > 0 && distance <= AIR_GUN_REACH && error <= AIR_GUN_CONE) {
          this.shootGun(target, now, true);
        }

        if (distance < MERGE) this.extend(target, now);

        return;
      }

      // 地上の相手: 近すぎれば一度離れて向き直る（SETUP）。
      if (this.state === "transit" && distance < SETUP && error > 45) {
        this.fly(this.x + (this.x - target.x), this.z + (this.z - target.z), 1.0);
        return;
      }

      this.state = "run";
      this.fly(target.x, target.z, 1.0);

      if (store === "bomb" && this.ammo.bomb > 0) {
        const fall = Math.sqrt(2 * BOMB_HEIGHT / 0.0245);
        const release = this.speed * fall;

        if (distance <= release && error <= 10) {
          this.dropBombs(target, now, distance - release);
          this.extend(target, now);
        }
      } else if (this.ammo.gun > 0) {
        if (distance <= GUN_REACH && distance >= GUN_CLOSE && error <= GUN_CONE) {
          this.shootGun(target, now, false);
        } else if (distance < GUN_CLOSE) {
          this.extend(target, now);
        }
      } else if (distance < GUN_CLOSE) {
        this.extend(target, now);
      }
    }

    extend(target, now) {
      this.state = "extend";
      this.stateSince = now;
      this.extendFrom = { x: target.x, z: target.z };
    }

    fly(toX, toZ, throttle) {
      const match = this.match;
      const centre = match.centre;
      let wantX = toX;
      let wantZ = toZ;

      // 戦域から遠く離れたら戻る。
      if (AV.dist2(this.x, this.z, centre.x, centre.z) > match.arena + LEASH) {
        wantX = centre.x;
        wantZ = centre.z;
      }

      const want = Math.atan2(wantZ - this.z, wantX - this.x);
      const rate = this.speed / this.turnRadius * clamp(this.def.rollRate / 1.2, 0.5, 1.5);
      const error = AV.wrap(want - this.heading);

      this.heading = AV.wrap(this.heading + clamp(error, -rate, rate));
      this.speed += clamp(this.cruise * throttle - this.speed, -0.05, 0.05);
      this.x += Math.cos(this.heading) * this.speed;
      this.z += Math.sin(this.heading) * this.speed;
      this.turret = this.heading;
    }

    focus() {
      const board = this.match.teams[this.team].board;
      let best = null;
      let bestRank = -1;

      for (const o of board.states) {
        const rank = o.beingTaken ? 3 : o.state === "contested" ? 2 : o.recaptureRequired ? 1 : 0;

        if (rank > bestRank) {
          bestRank = rank;
          best = o;
        }
      }

      return best || this.match.centre;
    }

    // ---------------------------------------------------------------- 狙う

    pick(now) {
      const match = this.match;
      const centre = match.centre;
      const arena = match.arena;
      const w = this.airWeights;
      const flight = {
        speed: Math.max(this.speed, this.cruise), turnRadius: turnRadius(Math.max(this.speed, this.cruise)),
        extend: EXTEND, setup: SETUP, missiles: this.missiles,
      };
      const committed = this.state === "run";
      const board = match.teams[this.team].board;
      const support = match.teams[this.team].support;
      const defences = match.units.filter((u) => u.alive && u.team !== this.team && !u.flying && u.def.defendsAir);
      const wingmen = new Map();

      for (const u of match.units) {
        if (u.alive && u.flying && u.team === this.team && u !== this && u.target) {
          wingmen.set(u.target.id, (wingmen.get(u.target.id) || 0) + 1);
        }
      }

      let best = null;
      let bestScore = -Infinity;
      let bestStore = null;

      for (const enemy of match.units) {
        if (!enemy.alive || enemy.team === this.team) continue;

        const airborne = !!enemy.flying;
        const distance = AV.dist2(this.x, this.z, enemy.x, enemy.z);

        if (distance > (airborne ? AIR_SEARCH : SEARCH)
          || AV.dist2(centre.x, centre.z, enemy.x, enemy.z) > arena + (airborne ? LEASH : OBJECTIVE_MARGIN)) {
          continue;
        }

        const mark = {
          airborne, armoured: !airborne, armour: airborne ? 0 : enemy.def.armour, taken: 1,
          speed: Math.abs(enemy.speed || 0),
        };
        let store = null;
        let dpp = 0;

        for (const kind of ["gun", "bomb", "aam"]) {
          const round = kind === "gun" ? this.def.gun : kind === "bomb" ? this.def.bomb : this.def.aam;
          const value = damagePerPass(kind, round, mark, this.ammo[kind]);

          if (value > dpp) {
            dpp = value;
            store = kind;
          }
        }

        if (!store) continue;

        const round = store === "gun" ? this.def.gun : store === "bomb" ? this.def.bomb : this.def.aam;
        const offTrack = Math.abs(AV.wrap(Math.atan2(enemy.z - this.z, enemy.x - this.x) - this.heading));
        let threatensMe = airborne && flight.missiles && distance <= AIR_THREAT;
        let defence = 0;

        for (const guard of defences) {
          const reach = airReach(guard.def);

          if (guard === enemy) {
            threatensMe = AV.dist2(guard.x, guard.z, this.x, this.z) <= reach;
          } else if (AV.dist2(guard.x, guard.z, enemy.x, enemy.z) <= reach) {
            defence += guard.role === "aa" ? 1 : 0.5;
          }
        }

        const chasedBy = airborne && enemy.target && enemy.target.id === this.id;
        const onObjective = board.states.some((o) => AV.dist2(enemy.x, enemy.z, o.x, o.z) <= o.radius + OBJECTIVE_MARGIN);
        const prospect = {
          airborne, distance, offTrack, health: enemy.health, damagePerPass: dpp,
          passes: passesLeft(store, round, mark, this.ammo[store]),
          attackingMe: (this.attackers.get(enemy.id) || -1e9) >= now - 100 || chasedBy, threatensMe, onObjective,
          allies: wingmen.get(enemy.id) || 0, defence, outside: AV.dist2(centre.x, centre.z, enemy.x, enemy.z) - arena,
          airspace: arena, support: support.airSupport(enemy.id, now), stalls: 0,
        };
        const current = this.target === enemy;
        const s = score(prospect, flight, w, current, current && committed);

        if (s > bestScore) {
          bestScore = s;
          best = enemy;
          bestStore = store;
        }
      }

      if (best !== this.target) {
        this.state = this.state === "extend" ? "extend" : "transit";
      }

      this.target = best;
      this.targetStore = bestStore;
      this.targetScore = best ? bestScore : 0;
    }

    // ---------------------------------------------------------------- 撃つ

    shootGun(target, now, air) {
      const gun = this.def.gun;
      const match = this.match;
      const rounds = Math.min(this.ammo.gun, Math.max(1, Math.round(gun.rps / 4)));

      if ((now + this.id) % 5 !== 0) return;

      this.ammo.gun -= rounds;
      this.fired.gun += rounds;

      const mark = { airborne: air, armoured: !air, armour: air ? 0 : target.def.armour, taken: 1, speed: 0 };
      const hit = air ? HIT_AIRCRAFT * (target.evading(now) ? 0.5 : 1) : HIT_VEHICLE;
      const energy = gun.explosion > 0 ? 1 : Math.exp(-2 * gun.drag * (air ? AIR_RANGE : GROUND_RANGE));
      const damage = rounds * hit * penetration(gun, mark) * gun.damage * energy * (0.6 + 0.8 * match.random());

      match.shots.push({ x0: this.x, z0: this.z, x1: target.x, z1: target.z, team: this.team, hit: damage > 0, tick: now, air: true });
      this.lastVictim = { id: target.id, team: target.team, tick: now };
      match.stats[this.team].shots += rounds;

      if (damage > 0) match.hurt(target, damage, this, now);
    }

    dropBombs(target, now, overshoot) {
      const bomb = this.def.bomb;
      const match = this.match;
      const count = Math.min(this.ammo.bomb, BOMBS_PER_PASS);

      this.ammo.bomb -= count;
      this.fired.bomb += count;
      match.stats[this.team].shots += count;
      this.lastVictim = { id: target.id, team: target.team, tick: now };

      for (let i = 0; i < count; i++) {
        const spread = BOMB_MISS + Math.abs(target.speed || 0) * FALL_TICKS * DRIFT_MISS;
        const angle = match.random() * Math.PI * 2;
        const miss = Math.abs(gaussian(match.random)) * spread + Math.abs(overshoot) * 0.2;
        const bx = target.x + Math.cos(angle) * miss;
        const bz = target.z + Math.sin(angle) * miss;

        match.blasts.push({ x: bx, z: bz, radius: Math.max(bomb.explosion * 2, 6), team: this.team, tick: now });

        for (const victim of match.units) {
          if (!victim.alive || victim.flying || victim.team === this.team) continue;

          const d = Math.max(0, AV.dist2(victim.x, victim.z, bx, bz) - 1.5);
          let damage;

          if (bomb.cluster) {
            damage = d < 12 ? CLUSTER_VEHICLE * 0.5 * (1 - d / 12) : 0;
          } else if (bomb.blast > 0) {
            damage = blastAt(bomb.blast, bomb.explosion, d);
            if (d < 1) damage = Math.max(damage, bomb.damage);
          } else {
            damage = blast(bomb.explosion, d) + (d < 1 ? bomb.damage : 0);
          }

          if (damage > 0) match.hurt(victim, damage, this, now);
        }
      }
    }

    launchAam(target, now, distance) {
      const aam = this.def.aam;

      this.ammo.aam--;
      this.fired.aam++;
      this.lastAam = now;
      this.lastVictim = { id: target.id, team: target.team, tick: now };
      this.match.stats[this.team].shots++;
      this.match.launch(this, target, aam.damage, AAM_HIT, now + Math.ceil(distance / MISSILE_SPEED), now);
    }
  }

  function gaussian(random) {
    let u = 0;

    while (u === 0) u = random();

    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random());
  }

  /** WeaponReach.AIRCRAFT: 地上から見た航空機の射程と威力（積んでいる物を1つずつ見ない）。 */
  const AIR_REACH = {
    mainRange: 1200, coaxRange: 0, missileRange: 2500, mainDps: 40, coaxDps: 0, missileDps: 60, mainRicochet: 30,
    mainExplosive: true, maxRange: 2500,
  };

  /** 地上の車両が空へ届く距離（WeaponReach.rangeAgainst(true) と FireControl.AIR_GUN_REACH）。 */
  function airReach(def) {
    if (!def.defendsAir) return 0;

    const gun = def.main && def.main.gunClass === "autocannon" ? Math.min(def.main.range, 800) : 0;
    const missile = def.airMissile && def.missile ? def.missile.range : 0;

    return Math.max(gun, missile);
  }

  AV.air = {
    weights, score, airspace, killsPerMinute, reachTicks, cycleTicks, turnRadius, penetration, blast, blastAt,
    damagePerPass, passesLeft, Plane, airReach, AIR_REACH, BOMB_HEIGHT,
  };
}
