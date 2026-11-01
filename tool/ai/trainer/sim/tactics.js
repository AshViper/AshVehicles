/*
 * 戦術と道の層の写し。
 *
 *   Tactics         resolve（行動 → MovementIntent）
 *   CoverFinder     findCover
 *   FlankPlanner    findFlank
 *   RetreatPlanner  findRetreat
 *   RoutePlanner    planRoute（4ブロックの格子の A*。危険と露出と地形の代償、危ないマスでだけ遮蔽と高所を割り引く）
 *
 * ゲームと違うのは地形の読み方だけ——ボクセルの触角と車幅の掃引の代わりに、マスが建物か・段差が崖か・水か。
 * パラメータの名前と式は同じ（tactics.cover.* / tactics.flank.* / tactics.retreat.* / route.*）。
 */
function AVSim_tactics(AV) {
  "use strict";

  const clamp = AV.clamp;
  const CELL = AV.CELL;

  const SPREAD_LEAST = 4, SPREAD_STEP = 3, RETARGET_MOVE = 24, AWAY = 32, LEAST_RANGE = 40, MOST_RANGE = 140;
  const CAPTURE_FIGHT = 40, ANCHOR_IN = 4, ANCHOR_IN_SHARE = 0.35, ANCHOR_OUT = 1, COVER_REACH = 48;
  const THREAT_FLOOR = 0.05, MOST_THREATS = 4, ARRIVED = 6, CLOSE_FIGHT = 70;

  function weights(params) {
    const g = (k) => params.get(k);

    return {
      cover: {
        protection: g("tactics.cover.protection"), fire: g("tactics.cover.fire"), danger: g("tactics.cover.danger"),
        exposure: g("tactics.cover.exposure"), distance: g("tactics.cover.distance"),
        approach: g("tactics.cover.approach"), height: g("tactics.cover.height"),
      },
      flank: {
        weakness: g("tactics.flank.weakness"), fire: g("tactics.flank.fire"), protection: g("tactics.flank.protection"),
        approach: g("tactics.flank.approach"), travel: g("tactics.flank.travel"), danger: g("tactics.flank.danger"),
      },
      retreat: {
        safety: g("tactics.retreat.safety"), support: g("tactics.retreat.support"), away: g("tactics.retreat.away"),
        distance: g("tactics.retreat.distance"), point: g("tactics.retreat.point"), base: g("tactics.retreat.base"),
        ally: g("tactics.retreat.ally"), cover: g("tactics.retreat.cover"), high: g("tactics.retreat.high"),
      },
      route: {
        threat: g("route.threat"), exposure: g("route.exposure"), terrain: g("route.terrain"), cover: g("route.cover"),
        height: g("route.height"), strategic: g("route.strategic"), water: g("route.water"),
      },
    };
  }

  const move = (action, x, z, risk, stop) => ({
    action, mode: "move", x, z, arrive: ARRIVED, throttle: 1, risk, stop, faceX: null, faceZ: null, targetId: null,
    position: null,
  });
  const hold = (action, face, stop) => ({
    action, mode: "hold", x: null, z: null, arrive: ARRIVED, throttle: 0, risk: 1, stop,
    faceX: face ? face.x : null, faceZ: face ? face.z : null, targetId: null, position: null,
  });
  const anchor = (action, face, stop) => Object.assign(hold(action, face, stop), { mode: "anchor" });

  // ------------------------------------------------------------------
  // 高さ地図の視線
  // ------------------------------------------------------------------

  const eyeOf = (terrain, x, z, height) => terrain.groundAt(x, z) + height * 0.85;
  const hullOf = (terrain, x, z, height) => terrain.groundAt(x, z) + height * 0.45;

  /** 周りのマスのうち、視線を遮る物（建物・林・高い土手）の割合。TacticalCell.coverValue の見積もり。 */
  function coverValue(terrain, x, z) {
    const n = terrain.n;
    const cx = Math.floor(x / CELL);
    const cz = Math.floor(z / CELL);
    const here = terrain.groundAt(x, z);
    let high = 0;

    for (let dz = -1; dz <= 1; dz++) {
      for (let dx = -1; dx <= 1; dx++) {
        if (dx === 0 && dz === 0) continue;

        const ix = cx + dx;
        const iz = cz + dz;

        if (ix < 0 || iz < 0 || ix >= n || iz >= n) continue;

        if (terrain.top[iz * n + ix] > here + 2.0) high++;
      }
    }

    return high / 8;
  }

  // ------------------------------------------------------------------
  // CoverFinder
  // ------------------------------------------------------------------

  const RINGS = [8, 16, 24, 32, 40, 48, 56, 64];

  /**
   * req: {fromX, fromZ, threats:[{x,y,z,weight}], target:{x,y,z}|null, anchor:{x,z}|null, anchorRadius,
   *       searchRadius, height, wantFire}
   */
  function findCover(match, team, req, w) {
    if (req.threats.length === 0) return null;

    const terrain = match.terrain;
    const threats = match.intel[team].threats;
    const found = [];
    const consider = (x, z) => {
      if (!terrain.inside(x, z)) return;

      const i = terrain.index(x, z);

      if (terrain.kind[i] === AV.KIND.BUILDING || terrain.kind[i] === AV.KIND.WATER) return;

      const ground = terrain.ground[i];

      for (let corner = 0; corner < 4; corner++) {
        const around = terrain.groundAt(x + ((corner & 1) === 0 ? -2 : 2), z + ((corner & 2) === 0 ? -2 : 2));

        if (Math.abs(around - ground) > 1.75) return;
      }

      const hull = ground + req.height * 0.45;
      const eye = ground + req.height * 0.85;
      let protection = 0;
      let total = 0;

      for (const threat of req.threats) {
        total += threat.weight;
        const v = terrain.trace(threat.x, threat.y, threat.z, x, hull, z);
        protection += v === 1 ? threat.weight : v === 2 ? threat.weight * 0.3 : 0;
      }

      protection = total > 0 ? protection / total : 0;

      if (protection < 0.34) return;

      let fire = 0.5;

      if (req.target) {
        const v = terrain.trace(x, eye, z, req.target.x, req.target.y, req.target.z);
        fire = v === 0 ? 1 : v === 2 ? 0.3 : 0;
      }

      const distance = AV.dist2(x, z, req.fromX, req.fromZ) / Math.max(req.searchRadius, 1);
      const score = w.protection * protection
        + (req.wantFire ? w.fire : w.fire * 0.25) * fire
        - w.danger * threats.danger(x, z)
        - w.exposure * threats.exposure(x, z)
        - w.distance * distance
        - w.approach * threats.riskAlong(req.fromX, req.fromZ, x, z, 4)
        + w.height * terrain.heightValue[i];

      found.push({ x, z, score, protection, fire });
    };
    const ring = (cx, cz, radius) => {
      const count = Math.max(6, Math.min(32, Math.round(Math.PI * 2 * radius / 8)));

      for (let at = 0; at < count; at++) {
        const angle = Math.PI * 2 * at / count;
        consider(cx + Math.cos(angle) * radius, cz + Math.sin(angle) * radius);
      }
    };

    if (req.anchor) {
      consider(req.anchor.x, req.anchor.z);

      for (let fraction = 0.25; fraction <= 1; fraction += 0.25) {
        ring(req.anchor.x, req.anchor.z, req.anchorRadius * fraction);
      }
    } else {
      for (const radius of RINGS) {
        if (radius > req.searchRadius) break;
        ring(req.fromX, req.fromZ, radius);
      }
    }

    if (found.length === 0) return null;

    found.sort((a, b) => b.score - a.score);

    const best = found[0];

    return {
      x: best.x, z: best.z, kind: best.fire >= 0.6 && req.wantFire ? "firing" : "cover", score: best.score,
      protection: best.protection, fire: best.fire,
    };
  }

  // ------------------------------------------------------------------
  // FlankPlanner
  // ------------------------------------------------------------------

  const ANGLES = [70, -70, 100, -100, 130, -130, 160, -160, 180];
  const DISTANCES = [0.8, 1.0, 1.25];
  const FRONT_ARC = Math.cos(40 * Math.PI / 180);

  function findFlank(match, team, req, w) {
    const terrain = match.terrain;
    const threats = match.intel[team].threats;
    const target = req.target;
    const facingX = Math.cos(target.heading);
    const facingZ = Math.sin(target.heading);
    const targetEye = eyeOf(terrain, target.x, target.z, target.def.height);
    let best = null;
    let bestScore = -Infinity;

    for (const angle of ANGLES) {
      const r = angle * Math.PI / 180;
      const dirX = facingX * Math.cos(r) - facingZ * Math.sin(r);
      const dirZ = facingX * Math.sin(r) + facingZ * Math.cos(r);
      const weakness = clamp((Math.abs(angle) - 45) / 135, 0, 1);

      for (const d of DISTANCES) {
        const x = target.x + dirX * req.range * d;
        const z = target.z + dirZ * req.range * d;

        if (!terrain.inside(x, z)) continue;

        const i = terrain.index(x, z);

        if (terrain.kind[i] === AV.KIND.BUILDING || terrain.kind[i] === AV.KIND.WATER) continue;

        const ground = terrain.ground[i];
        const v = terrain.trace(x, ground + req.height * 0.85, z, target.x, targetEye, target.z);
        const fire = v === 0 ? 1 : v === 2 ? 0.3 : 0;

        if (fire <= 0) continue;

        let protection = 0;

        if (req.others.length > 0) {
          let blocked = 0;
          let total = 0;

          for (const threat of req.others) {
            total += threat.weight;

            if (terrain.trace(threat.x, threat.y, threat.z, x, ground + req.height * 0.45, z) === 1) {
              blocked += threat.weight;
            }
          }

          protection = total > 0 ? blocked / total : 0;
        }

        let approach = 0;

        for (let at = 1; at <= 6; at++) {
          const t = at / 6;
          const sx = req.fromX + (x - req.fromX) * t;
          const sz = req.fromZ + (z - req.fromZ) * t;
          const dx = sx - target.x;
          const dz = sz - target.z;
          const length = Math.sqrt(dx * dx + dz * dz);
          let risk = threats.danger(sx, sz);

          if (length > 1e-3 && length < req.range * 1.5 && (dx * facingX + dz * facingZ) / length > FRONT_ARC) {
            risk += 0.5;
          }

          approach += Math.min(risk, 1);
        }

        approach /= 6;

        const travel = AV.dist2(x, z, req.fromX, req.fromZ) / Math.max(req.range * 3, 1);
        const score = w.weakness * weakness + w.fire * fire + w.protection * protection - w.approach * approach
          - w.travel * travel - w.danger * threats.danger(x, z);

        if (score > bestScore) {
          bestScore = score;
          best = { x, z, kind: "flank", score, protection, fire };
        }
      }
    }

    return best;
  }

  // ------------------------------------------------------------------
  // RetreatPlanner
  // ------------------------------------------------------------------

  function findRetreat(match, team, req, w, coverWeights) {
    const terrain = match.terrain;
    const threats = match.intel[team].threats;
    let away = null;

    if (req.centroid) {
      const ax = req.fromX - req.centroid.x;
      const az = req.fromZ - req.centroid.z;
      const length = Math.sqrt(ax * ax + az * az);

      away = length < 1e-2 ? null : { x: ax / length, z: az / length };
    }

    let best = null;
    let bestScore = -Infinity;
    const offer = (x, z, bonus, protection) => {
      const cell = terrain.index(x, z);

      // 水の中へは下がらない。
      if (cell < 0 || terrain.kind[cell] === AV.KIND.WATER || terrain.kind[cell] === AV.KIND.BUILDING) return;

      const sx = x - req.fromX;
      const sz = z - req.fromZ;
      const distance = Math.sqrt(sx * sx + sz * sz);
      let awayness = 0.5;

      if (req.centroid && distance > 1e-3) {
        const fx = req.fromX - req.centroid.x;
        const fz = req.fromZ - req.centroid.z;
        const length = Math.sqrt(fx * fx + fz * fz);

        awayness = length < 1e-3 ? 0.5 : (sx * fx + sz * fz) / (distance * length);
      }

      if (awayness < -0.2 && distance > 24) return;

      const safety = 1 - clamp(threats.danger(x, z) + 0.5 * threats.exposure(x, z), 0, 1);
      let near = 0;

      for (const ally of req.allies) {
        if ((ally.x - x) ** 2 + (ally.z - z) ** 2 <= 48 * 48) near++;
      }

      const score = w.safety * safety + w.support * Math.min(near / 3, 1) + w.away * awayness
        - w.distance * distance / 300 + bonus;

      if (score > bestScore) {
        bestScore = score;
        best = { x, z, kind: "retreat", score, protection, fire: 0 };
      }
    };

    for (const p of req.points) offer(p.x, p.z, w.point, 0);
    for (const b of req.bases) offer(b.x, b.z, w.base, 0);

    if (req.allies.length > 0) {
      let x = 0, z = 0;

      for (const ally of req.allies) { x += ally.x; z += ally.z; }

      offer(x / req.allies.length, z / req.allies.length, w.ally, 0);
    }

    if (req.threats.length > 0) {
      const sx = away ? req.fromX + away.x * 32 : req.fromX;
      const sz = away ? req.fromZ + away.z * 32 : req.fromZ;
      const cover = findCover(match, team, {
        fromX: sx, fromZ: sz, threats: req.threats, target: null, anchor: null, anchorRadius: 0, searchRadius: 40,
        height: req.height, wantFire: false,
      }, coverWeights);

      if (cover) offer(cover.x, cover.z, w.cover * cover.protection, cover.protection);
    }

    if (away) {
      for (let at = -3; at <= 3; at++) {
        const angle = Math.atan2(away.z, away.x) + at * Math.PI / 8;
        const x = req.fromX + Math.cos(angle) * 40;
        const z = req.fromZ + Math.sin(angle) * 40;

        if (!terrain.inside(x, z)) continue;

        const i = terrain.index(x, z);

        if (terrain.heightValue[i] >= 0.4) offer(x, z, w.high, 0);
      }
    }

    return best;
  }

  // ------------------------------------------------------------------
  // Tactics
  // ------------------------------------------------------------------

  class Tactics {
    constructor(unit) {
      this.unit = unit;
      const angle = unit.id * 2.39996;
      const radius = SPREAD_LEAST + (unit.id % 3) * SPREAD_STEP;
      this.spreadX = Math.cos(angle) * radius;
      this.spreadZ = Math.sin(angle) * radius;
      this.position = null;
      this.positionFor = null;
      this.targetAtX = 0;
      this.targetAtZ = 0;
      this.anchoredAt = null;
      this.nextDue = 0;
      this.w = unit.weights.tactics;
    }

    resolve(action, s, changed, now) {
      const intent = this.choose(action, s, changed, now);

      if (intent.mode === "move" && intent.x !== null) {
        const spot = dry(this.unit.match.terrain, this.unit.x, this.unit.z, intent.x, intent.z);

        intent.x = spot.x;
        intent.z = spot.z;
      }

      return intent;
    }

    choose(action, s, changed, now) {
      let due = now >= this.nextDue;

      if (due) this.nextDue = now + AV.TIMING.tactics;

      if (changed || this.positionFor !== action) {
        this.position = null;
        this.positionFor = action;
        due = true;
      }

      switch (action) {
        case "capture_objective": case "recapture_objective": return this.capture(action, s);
        case "defend_objective": return this.defend(s, due);
        case "attack": return this.attack(s);
        case "flank": return this.flank(s, due);
        case "seek_cover": return this.cover(s, due);
        case "retreat": return this.retreat(s, due);
        case "search_enemy": return this.search(s);
        case "support_ally": return this.support(s);
        default: return this.advance(action, s, true);
      }
    }

    searchAllowed() {
      const match = this.unit.match;

      if (match.budget.search > 0) {
        match.budget.search--;
        return true;
      }

      this.nextDue = 0;
      return false;
    }

    capture(action, s) {
      const duty = s.objective;

      if (!duty) return this.advance(action, s, false);

      if (this.anchorsIn(duty)) {
        const aim = ground(s.target);
        return anchor(action, aim ? aim.unit : null, CAPTURE_FIGHT);
      }

      const spot = this.spotIn(duty);

      return move(action, spot.x, spot.z, 1.0, CAPTURE_FIGHT);
    }

    defend(s, due) {
      const duty = s.objective;
      const unit = this.unit;

      if (!duty) return this.advance("defend_objective", s, true);

      const threats = this.threats(s);
      const aim = ground(s.target);

      if (due && threats.length > 0 && this.searchAllowed()) {
        this.position = findCover(unit.match, unit.team, {
          fromX: unit.x, fromZ: unit.z, threats, target: aim ? this.sightOf(aim.unit) : null,
          anchor: { x: duty.x, z: duty.z }, anchorRadius: duty.radius * 0.8, searchRadius: COVER_REACH,
          height: unit.def.height, wantFire: true,
        }, this.w.cover);
      }

      const face = aim ? aim.unit : centroid(threats);

      if (!this.position && this.anchorsIn(duty)) {
        return anchor("defend_objective", face, 120);
      }

      const dest = this.position || this.spotIn(duty);
      const intent = move("defend_objective", dest.x, dest.z, 1.0, 120);

      intent.position = this.position;
      if (face) { intent.faceX = face.x; intent.faceZ = face.z; }

      return intent;
    }

    attack(s) {
      const aim = ground(s.target);

      if (!aim) return this.fallback(s);

      const target = aim.unit;
      const desired = this.engagementRange();
      const distance = AV.dist2(target.x, target.z, this.unit.x, this.unit.z);

      if (distance <= desired * 1.15 && aim.visible) {
        const intent = hold("attack", target, desired * 1.3);
        intent.targetId = target.id;
        return intent;
      }

      const bx = this.unit.x - target.x;
      const bz = this.unit.z - target.z;
      const length = Math.sqrt(bx * bx + bz * bz);
      const dx = length < 1e-2 ? target.x : target.x + bx / length * desired;
      const dz = length < 1e-2 ? target.z : target.z + bz / length * desired;
      const intent = move("attack", dx, dz, 1.1, desired);

      intent.targetId = target.id;
      intent.faceX = target.x;
      intent.faceZ = target.z;

      return intent;
    }

    flank(s, due) {
      const aim = ground(s.target);
      const unit = this.unit;

      if (!aim) return this.attack(s);

      const target = aim.unit;

      if ((due || AV.dist2(target.x, target.z, this.targetAtX, this.targetAtZ) > RETARGET_MOVE || !this.position)
        && this.searchAllowed()) {
        const others = this.threats(s).filter((t) => (t.x - target.x) ** 2 + (t.z - target.z) ** 2 > 4);

        this.position = findFlank(unit.match, unit.team, {
          fromX: unit.x, fromZ: unit.z, target, range: this.engagementRange(), others, height: unit.def.height,
        }, this.w.flank);
        this.targetAtX = target.x;
        this.targetAtZ = target.z;
      }

      if (!this.position) return this.attack(s);

      const intent = move("flank", this.position.x, this.position.z, 1.6, 0);

      intent.throttle = 1.1;
      intent.faceX = target.x;
      intent.faceZ = target.z;
      intent.targetId = target.id;
      intent.position = this.position;

      return intent;
    }

    cover(s, due) {
      const unit = this.unit;
      const threats = this.threats(s);
      const aim = ground(s.target);

      if (due && threats.length > 0 && this.searchAllowed()) {
        this.position = findCover(unit.match, unit.team, {
          fromX: unit.x, fromZ: unit.z, threats, target: aim ? this.sightOf(aim.unit) : null, anchor: null,
          anchorRadius: 0, searchRadius: COVER_REACH, height: unit.def.height, wantFire: unit.profile.aggression >= 0.4,
        }, this.w.cover);
      }

      const face = aim ? aim.unit : centroid(threats);
      const dest = this.position || this.awayPoint(threats);
      const intent = move("seek_cover", dest.x, dest.z, 1.5, 0);

      intent.position = this.position;
      if (face) { intent.faceX = face.x; intent.faceZ = face.z; }

      return intent;
    }

    retreat(s, due) {
      const unit = this.unit;
      const threats = this.threats(s);

      if (due && this.searchAllowed()) {
        const points = s.objectives.filter((o) => o.state === "friendly" && !o.beingTaken);
        const allies = [];

        for (const ally of unit.allies) {
          if (ally.distance <= 200 && !ally.underFire) allies.push(ally.unit);
        }

        this.position = findRetreat(unit.match, unit.team, {
          fromX: unit.x, fromZ: unit.z, centroid: centroid(threats), points, bases: [unit.match.spawns[unit.team]],
          allies, threats, height: unit.def.height,
        }, this.w.retreat, this.w.cover);
      }

      const dest = this.position || this.awayPoint(threats);
      const intent = move("retreat", dest.x, dest.z, 2.0, 0);
      const face = centroid(threats);

      intent.arrive = 8;
      intent.throttle = 1.1;
      intent.position = this.position;
      if (face) { intent.faceX = face.x; intent.faceZ = face.z; }

      return intent;
    }

    advance(action, s, frontierCheck) {
      const unit = this.unit;
      let goal = this.front(s);

      if (frontierCheck) {
        goal = this.frontier(goal, 0.25 + 0.5 * unit.profile.riskTolerance);
      }

      return move(action, goal.x, goal.z, 1.2, CLOSE_FIGHT);
    }

    search(s) {
      const unit = this.unit;
      const intel = unit.match.intel[unit.team];
      let best = null;

      for (const spotted of intel.spotted.values()) {
        if (!spotted.alive || s.now - spotted.tick <= 20) continue;

        if (!best || spotted.tick > best.tick
          || (spotted.tick === best.tick && AV.dist2(spotted.x, spotted.z, unit.x, unit.z) < AV.dist2(best.x, best.z, unit.x, unit.z))) {
          best = spotted;
        }
      }

      if (best && AV.dist2(best.x, best.z, unit.x, unit.z) > ARRIVED) {
        return move("search_enemy", best.x, best.z, 1.0, CLOSE_FIGHT);
      }

      return this.advance("search_enemy", s, true);
    }

    support(s) {
      const ally = s.allyInTrouble;
      const unit = this.unit;

      if (!ally) return this.fallback(s);

      const at = ally.unit;
      const attacker = ally.attackerId >= 0 ? unit.match.byId(ally.attackerId) : null;
      let dx = at.x;
      let dz = at.z;

      if (attacker && attacker.alive) {
        const tx = attacker.x - at.x;
        const tz = attacker.z - at.z;
        const length = Math.sqrt(tx * tx + tz * tz);

        if (length > 1e-2) {
          const ux = tx / length;
          const uz = tz / length;
          const side = unit.id % 2 === 0 ? 8 : -8;

          dx = at.x + ux * 12 - uz * side;
          dz = at.z + uz * 12 + ux * side;
        }
      }

      const intent = move("support_ally", dx, dz, 1.1, CLOSE_FIGHT);

      intent.targetId = attacker && attacker.alive ? attacker.id : null;

      return intent;
    }

    fallback(s) {
      return s.objective ? this.capture("capture_objective", s) : this.advance("advance", s, false);
    }

    spotIn(duty) {
      const limit = duty.radius * 0.6;
      const length = Math.sqrt(this.spreadX * this.spreadX + this.spreadZ * this.spreadZ);
      const scale = length > limit && length > 1e-6 ? limit / length : 1;

      return { x: duty.x + this.spreadX * scale, z: duty.z + this.spreadZ * scale };
    }

    anchorsIn(duty) {
      const distance = AV.dist2(this.unit.x, this.unit.z, duty.x, duty.z);
      const anchored = duty.id === this.anchoredAt;
      const margin = anchored ? ANCHOR_OUT : Math.min(ANCHOR_IN, duty.radius * ANCHOR_IN_SHARE);
      const inside = distance <= duty.radius - margin;

      this.anchoredAt = inside ? duty.id : null;

      return inside;
    }

    front(s) {
      if (s.objective) return this.spotIn(s.objective);

      const unit = this.unit;
      let nearest = null;

      for (const o of s.objectives) {
        if (o.state !== "friendly" && (!nearest
          || AV.dist2(o.x, o.z, unit.x, unit.z) < AV.dist2(nearest.x, nearest.z, unit.x, unit.z))) {
          nearest = o;
        }
      }

      if (nearest) return this.spotIn(nearest);

      return { x: unit.x + Math.cos(unit.heading) * 48, z: unit.z + Math.sin(unit.heading) * 48 };
    }

    frontier(goal, limit) {
      const unit = this.unit;
      const threats = unit.match.intel[unit.team].threats;
      const sx = goal.x - unit.x;
      const sz = goal.z - unit.z;
      const length = Math.sqrt(sx * sx + sz * sz);

      if (length < 24) return goal;

      const ux = sx / length;
      const uz = sz / length;
      let lastX = unit.x;
      let lastZ = unit.z;

      for (let along = 16; along < length; along += 16) {
        const x = unit.x + ux * along;
        const z = unit.z + uz * along;

        if (threats.danger(x, z) > limit) {
          return along <= 24 ? { x: unit.x + ux * 24, z: unit.z + uz * 24 } : { x: lastX, z: lastZ };
        }

        lastX = x;
        lastZ = z;
      }

      return goal;
    }

    engagementRange() {
      const effective = Math.min(this.unit.reach.maxRange, AV.SIGHT);

      return clamp(effective * this.unit.profile.preferredRange, LEAST_RANGE, MOST_RANGE);
    }

    awayPoint(threats) {
      const unit = this.unit;
      const from = centroid(threats);
      let ax, az;

      if (!from) {
        ax = -Math.cos(unit.heading);
        az = -Math.sin(unit.heading);
      } else {
        ax = unit.x - from.x;
        az = unit.z - from.z;
      }

      const length = Math.sqrt(ax * ax + az * az);

      return length < 1e-2 ? { x: unit.x, z: unit.z } : { x: unit.x + ax / length * AWAY, z: unit.z + az / length * AWAY };
    }

    sightOf(other) {
      return { x: other.x, y: eyeOf(this.unit.match.terrain, other.x, other.z, other.def.height), z: other.z };
    }

    threats(s) {
      const ranked = [];

      for (const enemy of this.unit.observations.values()) {
        if (enemy.alive && enemy.threatLevel > THREAT_FLOOR) ranked.push(enemy);
      }

      ranked.sort((a, b) => b.threatLevel - a.threatLevel);

      const terrain = this.unit.match.terrain;

      return ranked.slice(0, MOST_THREATS).map((enemy) => {
        const x = enemy.visible ? enemy.unit.x : enemy.lastX;
        const z = enemy.visible ? enemy.unit.z : enemy.lastZ;

        const y = enemy.flying ? terrain.groundAt(x, z) + AV.air.BOMB_HEIGHT : eyeOf(terrain, x, z, enemy.unit.def.height);

        return { x, y, z, weight: enemy.threatLevel };
      });
    }
  }

  /** Tactics.ground: 空の相手へは寄らない・回らない・隠れない・向かない。砲塔が空を追う間も車体は持ち場の仕事を続ける。 */
  function ground(aim) {
    return aim && !aim.flying ? aim : null;
  }

  /** 行き先が水の中なら、自分からそこへの線の上の一番遠い乾いた点へ引き戻す。 */
  function dry(terrain, fromX, fromZ, x, z) {
    const i = terrain.index(x, z);

    if (i < 0 || terrain.kind[i] !== AV.KIND.WATER) return { x, z };

    const length = AV.dist2(fromX, fromZ, x, z);

    for (let along = length - 4; along > 0; along -= 4) {
      const px = fromX + (x - fromX) * along / length;
      const pz = fromZ + (z - fromZ) * along / length;
      const j = terrain.index(px, pz);

      if (j >= 0 && terrain.kind[j] !== AV.KIND.WATER) return { x: px, z: pz };
    }

    return { x: fromX, z: fromZ };
  }

  function centroid(threats) {
    if (threats.length === 0) return null;

    let x = 0, z = 0, weight = 0;

    for (const t of threats) {
      x += t.x * t.weight;
      z += t.z * t.weight;
      weight += t.weight;
    }

    return weight <= 0 ? null : { x: x / weight, z: z / weight };
  }

  // ------------------------------------------------------------------
  // RoutePlanner
  // ------------------------------------------------------------------

  const RANGE = 96;
  const LEAST = 0.6;
  const BONUS_CAP = 0.4;
  const STEP_X = [1, -1, 0, 0, 1, 1, -1, -1];
  const STEP_Z = [0, 0, 1, -1, 1, -1, 1, -1];

  /** 1つの試合で使い回す A* の作業場所。 */
  class RouteScratch {
    constructor(terrain) {
      const cells = terrain.n * terrain.n;
      this.g = new Float64Array(cells);
      this.parent = new Int32Array(cells);
      this.seen = new Uint32Array(cells);
      this.closed = new Uint32Array(cells);
      this.stamp = 1;
      this.heap = new AV.Heap(4096);
      this.cover = terrain.world && terrain.world.cover ? terrain.world.cover : new Float32Array(cells);

      if (!(terrain.world && terrain.world.cover)) {
        for (let cz = 0; cz < terrain.n; cz++) {
          for (let cx = 0; cx < terrain.n; cx++) {
            this.cover[cz * terrain.n + cx] = coverValue(terrain, (cx + 0.5) * CELL, (cz + 0.5) * CELL);
          }
        }

        if (terrain.world) terrain.world.cover = this.cover;
      }
    }
  }

  /**
   * 行き先までの道。返すのは通過点（ブロック座標）の配列と、道の危険（ThreatMap.riskAlong と同じ尺度）。
   * 届かなければ、一番近付けたマスまで。
   */
  function planRoute(match, unit, goalX, goalZ, riskWeight, budget) {
    const terrain = match.terrain;
    const scratch = match.routeScratch;
    const threats = match.intel[unit.team].threats;
    const w = unit.weights.tactics.route;
    const n = terrain.n;
    let fromX = clamp(Math.floor(unit.x / CELL), 0, n - 1);
    let fromZ = clamp(Math.floor(unit.z / CELL), 0, n - 1);
    const toX = clamp(Math.floor(goalX / CELL), 0, n - 1);
    const toZ = clamp(Math.floor(goalZ / CELL), 0, n - 1);
    const start = fromZ * n + fromX;
    const goal = toZ * n + toX;

    if (start === goal) {
      return { points: [{ x: goalX, z: goalZ }], risk: threats.riskAlong(unit.x, unit.z, goalX, goalZ, 4), wet: false };
    }

    const stamp = ++scratch.stamp;
    const heap = scratch.heap;
    const kinds = terrain.kind;
    const grounds = terrain.ground;
    const heights = terrain.heightValue;
    const dangers = threats.dangerCells;
    const exposures = threats.exposureCells;
    const tn = threats.n;
    const BUILDING = AV.KIND.BUILDING;
    const WATER = AV.KIND.WATER;
    const WOOD = AV.KIND.WOOD;
    // 少しだけ貪欲に（1.15 倍）。予算の内で遠くまで届かせるため。最短からのずれは代償の数パーセント。
    const heuristic = (i) => {
      const dx = Math.abs((i % n) - toX);
      const dz = Math.abs(((i / n) | 0) - toZ);

      return (dx > dz ? dx + 0.41 * dz : dz + 0.41 * dx) * CELL * 1.15;
    };

    // 水は危ない（2026-09-17 の指示）。乾いた所から探すときは水のマスへ入らない。水の中から探すときだけ、岸へ上がるために通す。
    const startWet = kinds[start] === WATER;

    heap.clear();
    scratch.g[start] = 0;
    scratch.parent[start] = -1;
    scratch.seen[start] = stamp;
    heap.push(start, heuristic(start));

    let closest = start;
    let closestH = heuristic(start);
    let expansions = 0;

    while (heap.size > 0 && expansions < budget) {
      const current = heap.pop();

      if (scratch.closed[current] === stamp) continue;

      scratch.closed[current] = stamp;
      expansions++;

      const h = heuristic(current);

      if (h < closestH && kinds[current] !== WATER) {
        closestH = h;
        closest = current;
      }

      if (current === goal) {
        closest = goal;
        break;
      }

      const cx = current % n;
      const cz = (current - cx) / n;
      const here = grounds[current];

      for (let k = 0; k < 8; k++) {
        const nx = cx + STEP_X[k];
        const nz = cz + STEP_Z[k];

        if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
        if (nx - fromX > RANGE || fromX - nx > RANGE || nz - fromZ > RANGE || fromZ - nz > RANGE) continue;

        const next = nz * n + nx;

        if (scratch.closed[next] === stamp || kinds[next] === BUILDING) continue;
        if (kinds[next] === WATER && !(startWet && kinds[current] === WATER)) continue;

        const rise = grounds[next] - here;

        if (rise > 3.2 || rise < -3.2) continue;
        if (k >= 4 && (kinds[cz * n + nx] === BUILDING || kinds[nz * n + cx] === BUILDING)) continue;

        const length = k >= 4 ? CELL * 1.41 : CELL;
        const threatCell = (nz >> 1) * tn + (nx >> 1);
        const danger = dangers[threatCell];
        const exposure = exposures[threatCell];
        const kind = kinds[next];
        const slope = (rise < 0 ? -rise : rise) / 3.2;
        const terrainCost = (kind === WOOD ? 1.0 : 0) + slope * 0.5;
        const risky = danger + exposure;
        const bonus = risky > 0
          ? Math.min(BONUS_CAP, (w.cover * scratch.cover[next] + w.height * heights[next]) * (risky > 1 ? 1 : risky)) : 0;
        let cost = 1 + riskWeight * (w.threat * danger + w.exposure * exposure) + w.terrain * terrainCost - bonus;
        let step = length * (cost > LEAST ? cost : LEAST);

        if (kind === WATER) step += length * w.water;

        const g = scratch.g[current] + step;

        if (scratch.seen[next] !== stamp || g < scratch.g[next]) {
          scratch.seen[next] = stamp;
          scratch.g[next] = g;
          scratch.parent[next] = current;
          heap.push(next, g + heuristic(next));
        }
      }
    }

    const cells = [];

    for (let at = closest; at !== -1 && at !== start; at = scratch.parent[at]) {
      cells.push(at);
    }

    cells.reverse();

    let wet = false;
    let risk = 0;
    const points = cells.map((i) => {
      const x = (i % n + 0.5) * CELL;
      const z = (Math.floor(i / n) + 0.5) * CELL;

      if (terrain.kind[i] === AV.KIND.WATER) wet = true;
      risk += Math.min(1, threats.danger(x, z) + 0.5 * threats.exposure(x, z));

      return { x, z };
    });

    if (closest === goal && points.length > 0) {
      points[points.length - 1] = { x: goalX, z: goalZ };
    }

    return { points, risk: points.length ? risk / points.length : 0, wet };
  }

  AV.tactics = { weights, Tactics, planRoute, RouteScratch, coverValue, eyeOf, hullOf, CLOSE_FIGHT, ARRIVED };
}
