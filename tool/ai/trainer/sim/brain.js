/*
 * 判断の層の写し。ゲームのクラスを1つずつ、同じ式・同じパラメータ名で JS に移した物。
 *
 *   RuleBasedPolicy     decide
 *   ObjectiveScoring    teamScore / botScore
 *   ObjectiveBoard      Board（点数・枠・割り当て）
 *   TargetSelector      selectTarget
 *   ThreatModel         capability / threat
 *   ThreatMap           ThreatMap（8ブロックのマスの危険と露出、放射状の掃引）
 *   Reinforcements      needs / choose
 *   SupportCalls        Support（要請と割り当て）
 *
 * <b>ゲームの式を変えたらここも直す。</b> 学習した版がゲームで効くのは、ここの算術がゲームと同じときだけ。
 * 式を離したら、この学習器で強くなった版はゲームでは別物になる。
 */
function AVSim_brain(AV) {
  "use strict";

  const clamp = AV.clamp;

  // ------------------------------------------------------------------
  // RuleBasedPolicy
  // ------------------------------------------------------------------

  const CLEARLY_BETTER = 0.25;

  function policyWeights(params) {
    const g = (k) => params.get("policy." + k);

    return {
      survival: g("survival"), recapture: g("recapture"), defend: g("defend"), capture: g("capture"),
      attack: g("attack"), flank: g("flank"), cover: g("cover"), support: g("support"), call: g("call"),
      advance: g("advance"), search: g("search"), hysteresis: g("hysteresis"), minDwell: g("min_dwell"),
      emergency: g("emergency"),
    };
  }

  /** 行動の点数と選んだ行動。s は BattleState の写し。 */
  function decide(w, s, raw) {
    const profile = s.profile;
    const duty = s.objective;
    const aim = s.target;

    for (const action of AV.ACTIONS) {
      raw[action] = 0;
    }

    const danger = clamp(s.threat * 0.7 + s.exposure * 0.3, 0, 1);
    const risk = danger * (1.2 - profile.riskTolerance);
    const outnumbered = clamp((s.enemiesEngaging - s.alliesNear - 1) / 3.0, 0, 1);
    const retreatLine = profile.retreatHealth + 0.15;
    const wounded = clamp((retreatLine - s.health) / retreatLine, 0, 1);
    const contact = s.enemiesEngaging > 0 || s.underFire;

    let retreat = w.survival * Math.min(1.5, wounded * (0.6 + danger)
      + outnumbered * (0.3 + 0.5 * danger)
      + (s.ammo <= 0 ? (contact ? 0.6 : 0.2) : 0));

    if (duty && duty.beingTaken && s.atObjective && s.health > 0.3) {
      retreat -= 0.2 * profile.holdBias;
    }

    raw.retreat = retreat;

    let cover = w.cover * clamp(risk * (s.underFire ? 1.0 : 0.6) * (s.inCover ? 0.3 : 1.0)
      * (0.5 + profile.coverBias) * (1.0 - 0.5 * wounded), 0, 1);

    if (!contact && s.exposure < 0.2) {
      cover *= 0.3;
    }

    raw.seek_cover = cover;

    let attack = 0;
    let flank = 0;

    // 空の相手は撃つだけで、追って走らない（攻撃と側面に数えない）。
    if (aim && !aim.flying) {
      const priority = clamp(aim.targetPriority / 1.5, 0, 1.2);
      const closeness = Math.max(0, 1 - aim.distance / Math.max(s.arena, 1));

      attack = w.attack * priority * (aim.visible ? 1.0 : 0.4) * (aim.inWeaponRange ? 1.0 : 0.7)
        * (1.0 + 0.3 * closeness)
        * (1.0 - risk * (1.0 - profile.aggression))
        * (0.5 + 0.5 * aim.effectiveness)
        * (s.targetIneffective ? 0.4 : 1.0)
        * (1.0 - 0.5 * wounded);

      const flankable = aim.armoured && s.targetFrontal && aim.distance >= 30 && aim.distance <= 220
        && (s.alliesOnTarget >= 1 || profile.flankBias >= 0.6);

      if (flankable) {
        flank = w.flank * profile.flankBias * Math.max(priority, 0.4) * (1.0 - danger * 0.5)
          * (aim.effectiveness < 0.6 || s.targetIneffective ? 1.5 : 1.0);
      }
    }

    raw.attack = attack;
    raw.flank = flank;

    let support = !s.allyInTrouble ? 0 : w.support * profile.supportBias * s.allyNeed * (1.0 - danger * 0.5);

    if (s.supportCall && s.allyInTrouble) {
      support = Math.max(support, w.call * (0.6 + 0.4 * s.allyNeed) * (1.0 - danger * 0.5));
    }

    raw.support_ally = support;

    if (duty) {
      const weight = clamp(s.objectiveWeight, 0, 1) * (0.5 + 0.5 * profile.objectiveBias);

      if (duty.recaptureRequired) {
        raw.recapture_objective = w.recapture * weight;
      } else if (duty.state === "friendly") {
        if (duty.beingTaken || duty.enemiesNear > 0 || profile.holdBias >= 0.6) {
          raw.defend_objective = w.defend * weight * (duty.beingTaken ? 1.0 : 0.6);
        }
      } else {
        raw.capture_objective = w.capture * weight;
      }
    }

    raw.advance = w.advance * (duty ? 0.2 : 1.0) * (s.knownEnemies > 0 ? 1.0 : 0.6);
    raw.search_enemy = w.search * (s.knownEnemies === 0 ? (duty ? 1.0 : 0.5) : 0.2) * (0.5 + profile.scoutBias);

    // choose
    const current = s.currentAction;
    const currentRaw = raw[current] || 0;
    let best = current;
    let bestScore = -Infinity;
    let currentShown = currentRaw;

    for (const action of AV.ACTIONS) {
      let score = raw[action];

      if (action === current && currentRaw > 0) {
        score += w.hysteresis;
        currentShown = score;
      }

      if (score > bestScore) {
        bestScore = score;
        best = action;
      }
    }

    if (best !== current && currentRaw > 0 && s.ticksInAction < w.minDwell) {
      const emergency = best === "retreat" && bestScore >= w.emergency;

      if (!emergency && bestScore < currentShown + CLEARLY_BETTER) {
        best = current;
      }
    }

    return bestScore <= 0 ? "advance" : best;
  }

  // ------------------------------------------------------------------
  // ObjectiveScoring
  // ------------------------------------------------------------------

  function objectiveWeights(params) {
    const g = (k) => params.get("objective." + k);

    return {
      enemyPoint: g("capture.enemy"), neutralPoint: g("capture.neutral"), contestedPoint: g("capture.contested"),
      friendlyPoint: g("capture.friendly"), enemyBase: g("capture.base"), strategic: g("strategic"),
      recapture: g("recapture"), recaptureRecencyTicks: g("recapture_recency_ticks"),
      defendUrgency: g("defend_urgency"), allySupport: g("ally_support"), enemyThreat: g("enemy_threat"),
      distance: g("distance"), routeRisk: g("route_risk"), sticky: g("sticky"), scoutFar: g("role.scout_far"),
      artillery: g("role.artillery"), antiAirHold: g("role.anti_air_hold"), tankFight: g("role.tank_fight"),
      lightCapture: g("role.light_capture"), tankCapture: g("role.tank_capture"),
    };
  }

  function captureValue(o, w) {
    switch (o.state) {
      case "enemy": return o.base ? w.enemyBase : w.enemyPoint;
      case "neutral": return w.neutralPoint;
      case "contested": return w.contestedPoint;
      default: return w.friendlyPoint;
    }
  }

  function teamScore(o, w, now) {
    let urgency = 0;

    if (o.recaptureRequired) {
      const recency = o.lostTick === null ? 0
        : Math.max(0, 1 - (now - o.lostTick) / Math.max(w.recaptureRecencyTicks, 1));

      urgency += w.recapture * (1 + 0.5 * recency);
    }

    if (o.beingTaken) {
      urgency += w.defendUrgency * (0.5 + o.progress);
    }

    const strategic = w.strategic * o.strategic;
    const support = w.allySupport * Math.min(o.alliesNear / 3, 1);
    const threat = w.enemyThreat * clamp(o.threat + o.enemiesNear * 0.15, 0, 1);

    return captureValue(o, w) + strategic + urgency + support - threat;
  }

  function botScore(o, x, z, routeRisk, arena, w, role, profile) {
    const distance = AV.dist2(o.x, o.z, x, z);
    const far = clamp(distance / Math.max(arena, 100), 0, 1.5);
    const distancePenalty = w.distance * far * (role === "scout" ? 0.5 : 1.0);
    const wants = o.state !== "friendly";
    let bias = 0;

    switch (role) {
      case "scout": bias = wants ? w.scoutFar * Math.min(far, 1) + w.lightCapture : 0; break;
      case "ifv": case "apc": bias = wants ? w.lightCapture : 0; break;
      case "tank": bias = wants ? w.tankCapture + w.tankFight * clamp(o.enemiesNear / 3 + o.threat, 0, 1) : 0; break;
      case "artillery": bias = w.artillery; break;
      case "aa": bias = o.state === "friendly" ? w.antiAirHold : 0; break;
      default: bias = 0;
    }

    return o.score * (0.5 + 0.5 * profile.objectiveBias) - distancePenalty - w.routeRisk * routeRisk + bias;
  }

  // ------------------------------------------------------------------
  // ObjectiveBoard
  // ------------------------------------------------------------------

  const NEAR_MARGIN = 24;

  class Board {
    constructor(team) {
      this.team = team;
      this.history = new Map();
      this.assignments = new Map();
      this.states = [];
      this.revision = 0;
      this.weights = null;
    }

    refresh(match, params, now) {
      const team = this.team;
      const intel = match.intel[team];
      const w = this.weights = objectiveWeights(params);
      const arena = match.arena;
      const middle = match.terrain.size / 2;
      const built = [];

      for (const point of match.points) {
        let past = this.history.get(point.id);

        if (!past) {
          past = { held: false, lostTick: null, beingTaken: false, lastOwner: null };
          this.history.set(point.id, past);
        }

        const owner = point.owner;

        if (owner === team || point.home === team) {
          past.held = true;
        }

        if (past.lastOwner === team && owner !== team) {
          past.lostTick = now;
          this.revision++;
        }

        const beingTaken = owner === team && point.taking !== null && point.taking !== team && point.progress > 0;

        if (beingTaken && !past.beingTaken) {
          this.revision++;
        }

        past.beingTaken = beingTaken;
        past.lastOwner = owner;

        let alliesNear = 0;
        let enemiesNear = 0;
        const reach = point.radius + NEAR_MARGIN;

        for (const unit of match.units) {
          // 上空を回る航空機は拠点を取らないので、守りの薄さにも脅威にも数えない。
          if (unit.alive && !unit.flying && AV.dist2(unit.x, unit.z, point.x, point.z) <= reach) {
            if (unit.team === team) alliesNear++; else enemiesNear++;
          }
        }

        const state = point.contested ? "contested" : owner === team ? "friendly" : owner === null ? "neutral" : "enemy";
        const threats = intel.threats;
        const threat = (threats.danger(point.x, point.z) + threats.danger(point.x + point.radius, point.z)
          + threats.danger(point.x - point.radius, point.z) + threats.danger(point.x, point.z + point.radius)
          + threats.danger(point.x, point.z - point.radius)) / 5;
        const o = {
          id: point.id, x: point.x, z: point.z, radius: point.radius, state, owner, taking: point.taking,
          progress: Math.min(point.progress / AV.CAPTURE_TICKS, 1), base: false, beingTaken,
          recaptureRequired: past.held && owner !== null && owner !== team, lostTick: past.lostTick,
          alliesNear, enemiesNear, threat,
          strategic: 1 - Math.min(AV.dist2(point.x, point.z, middle, middle) / Math.max(arena, 1), 1),
          score: 0,
        };

        o.score = teamScore(o, w, now);
        built.push(o);
      }

      this.states = built;
      this.assign(match, match.pilotsOf(team));
    }

    botScore(o, unit) {
      const risk = unit.match.intel[this.team].threats.riskAlong(unit.x, unit.z, o.x, o.z, 6);

      return botScore(o, unit.x, unit.z, risk, unit.match.arena, this.weights, unit.role, unit.profile);
    }

    assignmentOrBest(unit) {
      const id = this.assignments.get(unit.id);

      if (id !== undefined) {
        for (const o of this.states) {
          if (o.id === id) return o;
        }
      }

      let best = null;
      let bestScore = -Infinity;

      for (const o of this.states) {
        const score = this.botScore(o, unit);

        if (score > bestScore) {
          bestScore = score;
          best = o;
        }
      }

      return best;
    }

    assign(match, pilots) {
      const size = this.states.length;

      if (size === 0) {
        this.assignments.clear();
        return;
      }

      const scores = this.states.map((o) => o.score);
      const quiet = this.states.map((o) => o.state === "friendly" && !o.beingTaken && o.enemiesNear === 0);
      const slots = Board.slots(scores, quiet, pilots.length);
      const used = new Array(size).fill(0);
      const ordered = pilots.slice().sort((a, b) => a.id - b.id);
      const next = new Map();
      const table = new Map();

      for (const unit of ordered) {
        table.set(unit.id, this.states.map((o) => this.botScore(o, unit)));
      }

      for (const unit of ordered) {
        const current = this.assignments.get(unit.id);
        const index = this.states.findIndex((o) => o.id === current);

        if (index < 0 || used[index] >= slots[index]) continue;

        const row = table.get(unit.id);

        if (row[index] >= Math.max(...row) - this.weights.sticky) {
          next.set(unit.id, current);
          used[index]++;
        }
      }

      for (const unit of ordered) {
        if (next.has(unit.id)) continue;

        const row = table.get(unit.id);
        let chosen = -1, fallback = -1, anywhere = -1;
        let chosenScore = -Infinity, fallbackScore = -Infinity, anywhereScore = -Infinity;

        for (let at = 0; at < size; at++) {
          const score = row[at];

          if (score > anywhereScore) { anywhereScore = score; anywhere = at; }
          if (!quiet[at] && score > fallbackScore) { fallbackScore = score; fallback = at; }
          if (used[at] < slots[at] && score > chosenScore) { chosenScore = score; chosen = at; }
        }

        const index = chosen >= 0 ? chosen : fallback >= 0 ? fallback : anywhere;

        if (index < 0) continue;

        next.set(unit.id, this.states[index].id);
        used[index]++;
      }

      this.assignments = next;
    }

    static slots(scores, quiet, pilots) {
      const size = scores.length;
      const slots = new Array(size).fill(0);
      let active = 0, guards = 0, total = 0;

      for (let at = 0; at < size; at++) {
        if (quiet[at]) guards++;
        else { active++; total += Math.max(scores[at], 0.05); }
      }

      if (active === 0) {
        return slots.fill(1);
      }

      const guarded = Math.max(0, Math.min(guards, pilots - 2 * active));

      for (let left = guarded; left > 0; left--) {
        let best = -1;

        for (let at = 0; at < size; at++) {
          if (quiet[at] && slots[at] === 0 && (best < 0 || scores[at] > scores[best])) best = at;
        }

        slots[best] = 1;
      }

      const share = pilots - guarded;
      const remainders = new Array(size).fill(0);
      let given = 0;

      for (let at = 0; at < size; at++) {
        if (quiet[at]) continue;

        const exact = share * Math.max(scores[at], 0.05) / total;

        slots[at] = Math.max(1, Math.floor(exact));
        remainders[at] = exact - Math.floor(exact);
        given += slots[at];
      }

      while (given < share) {
        let best = -1;

        for (let at = 0; at < size; at++) {
          if (!quiet[at] && (best < 0 || remainders[at] > remainders[best])) best = at;
        }

        slots[best]++;
        remainders[best] = -1;
        given++;
      }

      return slots;
    }
  }

  // ------------------------------------------------------------------
  // TargetSelector
  // ------------------------------------------------------------------

  const KEEP_UNSEEN = 40;

  function targetWeights(params) {
    const g = (k) => params.get("target." + k);

    return {
      threat: g("threat"), attackingMe: g("attacking_me"), objective: g("objective"), ally: g("ally"),
      proximity: g("proximity"), weak: g("weak"), effect: g("effect"), inRange: g("in_range"), sticky: g("sticky"),
      focus: g("focus"), overkill: g("overkill"), ineffective: g("ineffective"), preferred: g("preferred"),
      antiAir: g("anti_air"), airDuty: g("air_duty"),
    };
  }

  function selectTarget(unit, w, now, range, preferredId) {
    let best = null;
    let bestScore = 0;
    const current = unit.target;
    const intel = unit.match.intel[unit.team];
    // 空の相手は空を撃てる車両だけが狙い、そのときは先に狙う（届く距離は誘導弾ならシーカー、砲なら 800 まで）。
    const airDefence = unit.def.defendsAir;
    const airRange = Math.max(range, unit.def.airMissile && unit.ammo.missile > 0 ? AV.air.airReach(unit.def)
      : Math.min(AV.air.airReach(unit.def), 800));

    for (const enemy of unit.observations.values()) {
      enemy.targetPriority = 0;

      if (!enemy.alive || (enemy.flying && !airDefence)) continue;

      const kept = enemy === current && now - enemy.lastSeen <= KEEP_UNSEEN;

      if (!enemy.visible && !kept) continue;

      const distance = enemy.distance;
      const reachHere = enemy.flying ? airRange : range;

      if (distance > reachHere && !kept && !enemy.attackingMe) continue;

      let score = w.threat * enemy.threatLevel
        + (enemy.attackingMe ? w.attackingMe : 0)
        + (enemy.attackingObjective ? w.objective : 0)
        + (enemy.attackingAlly ? w.ally : 0)
        + w.proximity * Math.max(0, 1 - distance / Math.max(reachHere, 1))
        + w.weak * (1 - enemy.healthFraction)
        + w.effect * enemy.effectiveness
        + (enemy.inWeaponRange ? w.inRange : 0);

      if (unit.ledger.ineffective(enemy.id, now)) score -= w.ineffective;
      if (enemy === current) score += w.sticky;

      const others = intel.engagedBy(enemy.id, unit.id);

      if (others > 0) score += w.focus;
      if (others > 2) score -= w.overkill * (others - 2);
      if (enemy.flying) score += w.antiAir * unit.profile.antiAirBias + w.airDuty;
      if (preferredId !== null && enemy.id === preferredId) score += w.preferred;

      switch (unit.role) {
        case "artillery": score += 0.2 * Math.min(distance / Math.max(range, 1), 1); break;
        case "tank": score += enemy.armoured ? 0.1 : 0; break;
        case "ifv": case "apc": case "scout": score += enemy.armoured ? 0 : 0.1; break;
      }

      enemy.targetPriority = Math.max(score, 0);

      if (score > bestScore) {
        bestScore = score;
        best = enemy;
      }
    }

    return best;
  }

  // ------------------------------------------------------------------
  // WeaponReach と ThreatModel
  // ------------------------------------------------------------------

  const KILL_SECONDS = 20;

  /** WeaponReach.of: 車両1種の射程と毎秒の威力。 */
  function reach(def) {
    const dps = (weapon) => (weapon ? weapon.damage * weapon.rps * (weapon.salvo || 1) : 0);

    return {
      mainRange: def.main ? def.main.range : 0,
      coaxRange: def.coax ? def.coax.range : 0,
      missileRange: def.missile ? def.missile.range : 0,
      mainDps: dps(def.main),
      coaxDps: dps(def.coax),
      missileDps: dps(def.missile),
      mainRicochet: def.main ? def.main.ricochet : 0,
      mainExplosive: def.main ? def.main.explosion > 0 : false,
      maxRange: Math.max(def.main ? def.main.range : 0, def.coax ? def.coax.range : 0, def.missile ? def.missile.range : 0),
    };
  }

  function effectiveness(r, armour) {
    if (r.mainExplosive) return 0.85;

    return Math.max(0.15, Math.min(1, (r.mainRicochet - armour) / 45));
  }

  function dpsAgainst(r, armour) {
    return r.mainDps * effectiveness(r, armour) + r.missileDps + r.coaxDps * 0.05;
  }

  function capability(r, victimHealth, victimArmour) {
    const dps = dpsAgainst(r, victimArmour);

    if (dps <= 1e-6) return 0;

    return KILL_SECONDS / (KILL_SECONDS + Math.max(victimHealth, 1) / dps);
  }

  function threat(cap, distance, theirRange, seenBy, lineKnown, aimingAtMe, attackingMe) {
    if (cap <= 0 || theirRange <= 0) return 0;

    const range = distance <= theirRange ? 1 : Math.max(0, 1 - (distance - theirRange) / Math.max(theirRange, 32));
    const sight = seenBy ? 1 : lineKnown ? 0.25 : 0.5;

    return clamp(cap * range * sight * (aimingAtMe ? 1.3 : 1) * (attackingMe ? 1.6 : 1), 0, 1);
  }

  // ------------------------------------------------------------------
  // ThreatMap
  // ------------------------------------------------------------------

  const TM_CELL = 8;
  const TARGET_HEIGHT = 2.2;
  const HIDDEN = 0.15;
  const FORGET = 0.02;
  const CLUSTER = 16;

  class ThreatMap {
    constructor(terrain) {
      this.terrain = terrain;
      this.n = Math.ceil(terrain.size / TM_CELL);
      const cells = this.n * this.n;
      this.dangerCells = new Float32Array(cells);
      this.exposureCells = new Float32Array(cells);
      this.freshDanger = new Float32Array(cells);
      this.freshExposure = new Float32Array(cells);
      this.oneDanger = new Float32Array(cells);
      this.oneExposure = new Float32Array(cells);
      this.touched = new Int32Array(cells);
      this.touchedCount = 0;
      this.mark = new Uint32Array(cells);
      this.stamp = 1;
      this.revision = 0;
    }

    cell(x, z) {
      if (!(x >= 0 && z >= 0)) return -1;

      const cx = (x / TM_CELL) | 0;
      const cz = (z / TM_CELL) | 0;

      return cx >= this.n || cz >= this.n ? -1 : cz * this.n + cx;
    }

    danger(x, z) {
      const i = this.cell(x, z);
      return i < 0 ? 0 : this.dangerCells[i];
    }

    exposure(x, z) {
      const i = this.cell(x, z);
      return i < 0 ? 0 : this.exposureCells[i];
    }

    riskAlong(ax, az, bx, bz, samples) {
      let total = 0;

      for (let at = 1; at <= samples; at++) {
        const t = at / samples;
        const x = ax + (bx - ax) * t;
        const z = az + (bz - az) * t;

        total += Math.min(1, this.danger(x, z) + 0.5 * this.exposure(x, z));
      }

      return samples <= 0 ? 0 : total / samples;
    }

    /** sources: [{x, y, z, capability, reach, current, seenTick}] */
    update(sources, now, memoryTicks, decay, unknownExposure) {
      const cells = this.dangerCells.length;

      for (let i = 0; i < cells; i++) {
        const left = this.dangerCells[i] * decay;
        this.dangerCells[i] = left < FORGET ? 0 : left;
      }

      this.freshDanger.fill(0);
      this.freshExposure.fill(0);

      for (let at = 0; at < sources.length; at++) {
        const source = sources[at];
        const memory = source.current ? 1 : Math.max(0, 1 - (now - source.seenTick) / Math.max(memoryTicks, 1));

        if (memory <= 0 || source.capability <= 0 || this.coveredBy(sources, at)) continue;

        this.stamp++;
        this.touchedCount = 0;
        this.sweep(source, memory, unknownExposure);

        for (let k = 0; k < this.touchedCount; k++) {
          const i = this.touched[k];
          this.freshDanger[i] = 1 - (1 - this.freshDanger[i]) * (1 - this.oneDanger[i]);
          this.freshExposure[i] = 1 - (1 - this.freshExposure[i]) * (1 - this.oneExposure[i]);
        }
      }

      for (let i = 0; i < cells; i++) {
        if (this.freshDanger[i] > this.dangerCells[i]) this.dangerCells[i] = this.freshDanger[i];
        this.exposureCells[i] = this.freshExposure[i];
      }

      this.revision++;
    }

    coveredBy(sources, index) {
      const source = sources[index];

      for (let before = 0; before < index; before++) {
        const e = sources[before];

        if (e.capability >= source.capability && e.current === source.current
          && (e.x - source.x) ** 2 + (e.y - source.y) ** 2 + (e.z - source.z) ** 2 < CLUSTER * CLUSTER
          && e.reach >= source.reach) {
          return true;
        }
      }

      return false;
    }

    sweep(source, memory, unknownExposure) {
      const reachBlocks = source.reach;

      if (reachBlocks < TM_CELL) return;

      const cap = source.capability;
      const size = this.terrain.size;
      const tops = this.terrain.top;
      const tn = this.terrain.n;
      const azimuths = clamp(Math.ceil(Math.PI * 2 * reachBlocks / TM_CELL), 16, 128);
      const samples = Math.ceil(reachBlocks / TM_CELL);

      this.deposit(source.x, source.z, cap * memory, source.current ? cap : 0);

      for (let azimuth = 0; azimuth < azimuths; azimuth++) {
        const angle = Math.PI * 2 * azimuth / azimuths;
        const dx = Math.cos(angle);
        const dz = Math.sin(angle);
        let steepest = -Infinity;

        for (let sample = 1; sample <= samples; sample++) {
          const run = sample * TM_CELL;
          const x = source.x + dx * run;
          const z = source.z + dz * run;
          const top = x >= 0 && z >= 0 && x < size && z < size ? tops[((z / 4) | 0) * tn + ((x / 4) | 0)] : NaN;
          let seen, exposed;

          if (top !== top) {
            seen = Math.max(unknownExposure, HIDDEN);
            exposed = unknownExposure;
          } else {
            const visible = (top + TARGET_HEIGHT - source.y) / run >= steepest;
            seen = visible ? 1 : HIDDEN;
            exposed = visible ? 1 : 0;
            steepest = Math.max(steepest, (top - source.y) / run);
          }

          const near = run / reachBlocks;

          this.deposit(x, z, cap * memory * (1 - near * near) * seen,
            source.current ? cap * (1 - 0.5 * near) * exposed : 0);
        }
      }
    }

    deposit(x, z, danger, exposure) {
      const i = this.cell(x, z);

      if (i < 0) return;

      if (this.mark[i] !== this.stamp) {
        this.mark[i] = this.stamp;
        this.oneDanger[i] = 0;
        this.oneExposure[i] = 0;
        this.touched[this.touchedCount++] = i;
      }

      if (danger > this.oneDanger[i]) this.oneDanger[i] = danger;
      if (exposure > this.oneExposure[i]) this.oneExposure[i] = exposure;
    }
  }

  // ------------------------------------------------------------------
  // Reinforcements（地上の4種だけ。空の2種は学習器の試合に出ない）
  // ------------------------------------------------------------------

  const SQUAD = 5;

  function spawnWeights(params) {
    const g = (k) => params.get("spawn." + k);

    return {
      base: g("base"), armour: g("armour"), capture: g("capture"), hold: g("hold"), soft: g("soft"),
      airDefence: g("air_defence"), intercept: g("intercept"), flak: g("flak"), lost: g("lost"), have: g("have"),
      support: g("support"), strike: g("strike"), spare: g("spare"), airIdle: g("air_idle"),
      airWanted: g("air_wanted"), pair: g("pair"),
    };
  }

  function needs(s, w) {
    const per = SQUAD / Math.max(s.own, SQUAD);

    return {
      tank: w.base + w.armour * Math.max(0, s.enemyArmour - s.ownTanks) * per + w.hold * s.threatened
        + w.lost * s.lostToArmour - w.have * s.ownTanks * per + w.pair * s.loneIfvs * per,
      light: w.base + w.capture * s.capturable + w.soft * Math.max(0, s.enemyLight - s.ownLight) * per
        - w.have * s.ownLight * per + w.pair * s.loneTanks * per,
      // 2026-09-17: 敵機がいなければ防空は罰、いれば加点。
      air_defence: w.airDefence * Math.max(0, s.enemyAir - s.ownAirDefence - 0.5 * s.ownFighters) * per
        + w.lost * s.lostToAir + (s.enemyAir > 0 ? w.airWanted : -w.airIdle),
      artillery: w.support - 2 * w.have * s.ownArtillery * per,
      fighter: w.intercept * Math.max(0, s.enemyAir - s.ownFighters - 0.5 * s.ownAirDefence) * per
        + 0.5 * w.lost * s.lostToAir
        - w.flak * s.enemyAirDefence * per + w.spare * s.spare,
      striker: w.strike + w.spare * s.spare + 0.5 * w.armour * s.enemyArmour * per - w.have * s.ownStrikers * per
        - w.flak * s.enemyAirDefence * per - w.lost * s.lostToAirDefence,
    };
  }

  /** Reinforcements.spare: チケットの余裕（3割で0、7割で1）。 */
  function spare(tickets, start) {
    return clamp((tickets / Math.max(start, 1) - 0.3) / (0.7 - 0.3), 0, 1);
  }

  const KINDS = ["tank", "light", "air_defence", "artillery", "fighter", "striker"];

  function choose(need, available, roll) {
    const kinds = KINDS.filter((k) => available.has(k));

    if (kinds.length === 0) return null;

    const weight = (k) => (need[k] > 0 ? need[k] * need[k] : 0);
    const total = kinds.reduce((sum, k) => sum + weight(k), 0);

    if (total <= 0) return kinds[Math.min(Math.floor(roll * kinds.length), kinds.length - 1)];

    let mark = roll * total;
    let last = null;

    for (const k of kinds) {
      const value = weight(k);

      if (value <= 0) continue;

      last = k;

      if (mark < value) return k;

      mark -= value;
    }

    return last;
  }

  function kindOf(role) {
    return role === "tank" ? "tank" : role === "aa" ? "air_defence" : role === "artillery" ? "artillery" : "light";
  }

  AV.brain = {
    policyWeights, decide, objectiveWeights, teamScore, botScore, Board, targetWeights, selectTarget, reach,
    effectiveness, capability, threat, ThreatMap, spawnWeights, needs, spare, choose, kindOf, KINDS,
  };
}
