/*
 * 読み込んだファイルを、ビューアが描く形にする。覚えた地図・戦闘1回ぶんの走り方と飛び方と出来事・戦闘を並べた成長の数字。
 *
 * 覚えた地図の読み（trap / water / death / flow）と道の代償は、ゲームの ai/learning/MapKnowledge と同じ式。
 * 式を変えたら両方を直すこと——ビューアが違う数字を出すと、見て決めたことがゲームの中で外れる。
 */
(function (AV) {
  "use strict";

  const PRIOR = 2;
  const open = (ref) => AV.files.open(ref);

  // ------------------------------------------------------------------
  // 覚えた地図
  // ------------------------------------------------------------------

  async function readMemory(ref) {
    const file = await open(ref);
    const json = JSON.parse(await file.text());
    const names = json.columns || ["x", "z", "visits", "passes", "stalls", "wet", "deaths"];
    const at = (name) => names.indexOf(name);
    const rows = Array.isArray(json.cells) ? json.cells : [];
    const size = rows.length;
    const memory = {
      battles: json.battles || 0, cell: json.cell || 4, size, modified: file.lastModified || 0,
      x: new Int32Array(size), z: new Int32Array(size),
      visits: new Float32Array(size), passes: new Float32Array(size), stalls: new Float32Array(size),
      wet: new Float32Array(size), deaths: new Float32Array(size),
      index: new Map(), bounds: null,
    };
    const columns = { visits: at("visits"), passes: at("passes"), stalls: at("stalls"), wet: at("wet"), deaths: at("deaths") };
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;

    rows.forEach((row, i) => {
      const x = row[0] | 0;
      const z = row[1] | 0;

      memory.x[i] = x;
      memory.z[i] = z;

      for (const kind of Object.keys(columns)) {
        const column = columns[kind];

        memory[kind][i] = column >= 0 && column < row.length ? Math.max(0, Number(row[column]) || 0) : 0;
      }

      memory.index.set(x + "," + z, i);
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z);
      maxZ = Math.max(maxZ, z);
    });

    if (size) {
      memory.bounds = { minX, maxX, minZ, maxZ };
    }

    return memory;
  }

  /** そのマスの数と読み。 */
  function reading(memory, i) {
    const visits = memory.visits[i];
    const passes = memory.passes[i];
    const stalls = memory.stalls[i];
    const wet = memory.wet[i];
    const deaths = memory.deaths[i];
    const seen = visits + PRIOR;

    return {
      visits, passes, stalls, wet, deaths,
      trap: Math.min(1, stalls / (stalls + passes + PRIOR)),
      water: Math.min(1, wet / seen),
      death: Math.min(1, deaths / seen),
      flow: Math.min(1, passes / seen),
    };
  }

  /** 道の1歩に足す代償（1歩の距離に対する倍率）。weights は {trap, water, death, flow, risk}。 */
  function cost(read, weights) {
    return weights.trap * read.trap + weights.water * read.water + weights.death * weights.risk * read.death
        - weights.flow * read.flow;
  }

  function cellAt(memory, blockX, blockZ) {
    const i = memory.index.get(Math.floor(blockX / memory.cell) + "," + Math.floor(blockZ / memory.cell));

    return i === undefined ? -1 : i;
  }

  function summarize(memory) {
    const out = { cells: memory.size, traps: 0, wet: 0, deadly: 0, lanes: 0, visits: 0 };

    for (let i = 0; i < memory.size; i++) {
      const read = reading(memory, i);

      out.traps += read.trap >= 0.5 ? 1 : 0;
      out.wet += read.water >= 0.3 ? 1 : 0;
      out.deadly += read.death >= 0.3 ? 1 : 0;
      out.lanes += read.flow >= 0.6 ? 1 : 0;
      out.visits += read.visits;
    }

    return out;
  }

  /** key（trap / water / death / flow）の上位。経験の多いマスを上に。 */
  function ranked(memory, key, count) {
    const amount = { trap: "stalls", water: "wet", death: "deaths", flow: "passes" }[key];
    const list = [];

    for (let i = 0; i < memory.size; i++) {
      const read = reading(memory, i);

      if (read[key] > 0) {
        list.push({ i, score: read[key] * Math.log2(2 + read[amount]), read });
      }
    }

    return list.sort((a, b) => b.score - a.score).slice(0, count);
  }

  // ------------------------------------------------------------------
  // 戦闘
  // ------------------------------------------------------------------

  async function readText(ref) {
    if (!ref) {
      return "";
    }

    return (await open(ref)).text();
  }

  async function readJson(ref) {
    const text = await readText(ref);

    return text ? JSON.parse(text) : null;
  }

  function lines(text, each) {
    let start = 0;

    while (start < text.length) {
      let end = text.indexOf("\n", start);

      if (end < 0) {
        end = text.length;
      }

      const line = text.slice(start, end).trim();

      start = end + 1;

      if (!line) {
        continue;
      }

      try {
        each(JSON.parse(line));
      } catch (error) {
        // 書きかけの最後の行。飛ばす。
      }
    }
  }

  /** 航空機か。記録の車両名に名前空間が無いのは航空機（エンティティの型の短い名前、ゲームの LifeRecord.vehicleName）。 */
  function isAir(vehicle) {
    return typeof vehicle === "string" && vehicle.length > 0 && !vehicle.includes(":");
  }

  /**
   * 撃った物の種類。AI の航空機の兵装は機関砲・自由落下爆弾・空対空ミサイルの3つだけ（ゲームの AirLoadout）なので、
   * 兵装の ID の名前から当てる。知らない名前は、1まとまりの弾の数で機関砲か爆弾かに分ける（描き分けるためだけ）。
   */
  function releaseKind(weapon, rounds) {
    const name = String(weapon || "").replace(/^[^:]*:/, "").toLowerCase();

    if (/^(aim|r\d|r_\d|pl_|mica|meteor|iris|python|derby)/.test(name)) {
      return "missile";
    }

    if (/(bomb|^fab|^ofab|^kab|^gbu|^cbu|^rbk|^mk_?8)/.test(name)) {
      return "bomb";
    }

    if (/^(gsh|gau|bk_|m61|shvak|ubs|m230|m134|m2|kord|2a|bofors|flak|mg|sppu|m242)/.test(name)) {
      return "gun";
    }

    return (rounds || 0) > 4 ? "gun" : "bomb";
  }

  /** 戦闘1回の見出し。meta と summary だけ（小さい）。 */
  async function readHeader(entry) {
    const [meta, summary] = await Promise.all([readJson(entry.meta), readJson(entry.summary)]);
    const lives = summary && Array.isArray(summary.lives) ? summary.lives : [];

    return {
      id: entry.id, entry, meta, summary, lives,
      started: meta && meta.started ? meta.started : entry.id,
      startTick: meta ? meta.start_tick : null,
      duration: summary ? summary.duration_ticks : null,
      winner: summary ? summary.winner : null,
      aborted: summary ? summary.aborted : null,
      finished: !!summary,
      hasFlights: !!entry.flights,
      teams: meta && Array.isArray(meta.teams) ? meta.teams : [],
      points: meta && Array.isArray(meta.points) ? meta.points : [],
      arena: meta ? { x: meta.arena_x, z: meta.arena_z, radius: meta.arena_radius } : null,
      world: meta ? meta.world : null,
    };
  }

  const MARKED = new Set(["VehicleDestroyed", "WaterEntered", "ObjectiveCaptured", "ObjectiveRecaptured",
    "UnnecessaryDeath", "WeaponReleased", "EnemyDestroyed"]);

  /** 段階の記録が無い行（describeFlight を足す前の AirPilot）は、行動から段階を推す。移動と離脱は見分けられない。 */
  const PHASE_OF_ACTION = { ATTACK: "RUN", RETREAT: "SPENT", SEARCH_ENEMY: "TRANSIT", ADVANCE: "TRANSIT" };

  function nearest(samples, t) {
    let low = 0;
    let high = samples.length - 1;

    if (high < 0) {
      return null;
    }

    while (low < high) {
      const middle = (low + high) >> 1;

      if (samples[middle].t < t) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }

    const after = samples[low];
    const before = samples[Math.max(0, low - 1)];

    return Math.abs(before.t - t) <= Math.abs(after.t - t) ? before : after;
  }

  /** 戦闘1回の中身。AI ごとの走った跡と飛んだ跡、地図に置く出来事。 */
  async function readDetail(header) {
    const entry = header.entry;
    const bots = new Map();
    const markers = [];
    const counts = {};
    let first = Infinity;
    let last = -Infinity;
    const [decisions, flights, events] = await Promise.all([
      readText(entry.decisions), readText(entry.flights), readText(entry.events)]);

    lines(decisions, (row) => {
      if (typeof row.x !== "number" || typeof row.z !== "number") {
        return;
      }

      let bot = bots.get(row.bot);

      if (!bot) {
        bot = { id: row.bot, team: row.team, vehicle: row.vehicle, role: row.role, air: false, samples: [] };
        bots.set(row.bot, bot);
      }

      const nav = row.navigation || {};

      bot.samples.push({
        t: row.t, x: row.x, y: row.y, z: row.z, action: row.action, objective: row.objective, health: row.health,
        water: nav.water || 0, jams: nav.jams || 0, escaping: !!nav.escaping, lost: !!nav.lost,
        backing: !!nav.backing, following: !!nav.following, leashed: !!nav.leashed,
      });
      first = Math.min(first, row.t);
      last = Math.max(last, row.t);
    });

    lines(flights, (row) => {
      if (typeof row.x !== "number" || typeof row.z !== "number") {
        return;
      }

      let bot = bots.get(row.bot);

      if (!bot) {
        bot = { id: row.bot, team: row.team, vehicle: row.vehicle, role: "air", air: true, rotorcraft: false, samples: [] };
        bots.set(row.bot, bot);
      }

      bot.air = true;
      bot.rotorcraft = bot.rotorcraft || row.rotorcraft === true;
      bot.samples.push({
        t: row.t, x: row.x, y: row.y, z: row.z, action: row.action, objective: row.focus || null, health: row.health,
        phase: row.phase || PHASE_OF_ACTION[row.action] || "TRANSIT", exact: typeof row.phase === "string",
        evading: row.evading === true, speed: row.speed || 0, heading: row.heading || 0, climb: row.climb || 0,
        weapon: row.weapon || null, target: row.target || null, tx: row.target_x, ty: row.target_y, tz: row.target_z,
        toX: row.to_x, toZ: row.to_z, water: 0, jams: 0, escaping: false, lost: false,
        // 使った物の履歴（2026-09-13 夜の AirPilot から）。無い行は null のまま——0 と書くと「撃っていない」に見える。
        score: typeof row.score === "number" ? row.score : null,
        missiles: typeof row.missiles === "boolean" ? row.missiles : null,
        firedGun: typeof row.fired_gun === "number" ? row.fired_gun : null,
        firedBomb: typeof row.fired_bomb === "number" ? row.fired_bomb : null,
        firedAam: typeof row.fired_aam === "number" ? row.fired_aam : null,
      });
      first = Math.min(first, row.t);
      last = Math.max(last, row.t);
    });

    for (const bot of bots.values()) {
      bot.samples.sort((a, b) => a.t - b.t);
    }

    lines(events, (event) => {
      counts[event.type] = (counts[event.type] || 0) + 1;

      if (event.type === "EnemyDestroyed" && isAir(event.vehicle)) {
        counts.airKills = (counts.airKills || 0) + 1;
      }

      if (event.type === "VehicleDestroyed" && isAir(event.vehicle)) {
        counts.airLosses = (counts.airLosses || 0) + 1;
      }

      first = Math.min(first, event.t);
      last = Math.max(last, event.t);

      if (!MARKED.has(event.type)) {
        return;
      }

      let x = event.x;
      let y = event.y;
      let z = event.z;
      let approximate = false;
      const bot = event.bot ? bots.get(event.bot) : null;

      // 出来事に位置が無い記録（2026-09-13 の昼までの物）は、その AI の一番近い時刻の行の位置で置く。
      if (typeof x !== "number" && bot) {
        const near = nearest(bot.samples, event.t);

        if (near) {
          x = near.x;
          y = near.y;
          z = near.z;
          approximate = Math.abs(near.t - event.t) > 20;
        }
      }

      if (typeof x === "number") {
        markers.push({ type: event.type, t: event.t, x, y, z, team: event.team, bot: event.bot, vehicle: event.vehicle,
          air: isAir(event.vehicle), detail: event.detail || {}, reward: event.reward || 0, approximate });
      }
    });

    // 詰まり: 抜け出し始めた判断の行（続いている間は1つにまとめる）。地上の車両だけ。
    for (const bot of bots.values()) {
      if (bot.air) {
        continue;
      }

      let escaping = false;

      for (const sample of bot.samples) {
        if (sample.escaping && !escaping) {
          markers.push({ type: "Escape", t: sample.t, x: sample.x, y: sample.y, z: sample.z, team: bot.team, bot: bot.id,
            vehicle: bot.vehicle, air: false, detail: { jams: sample.jams }, approximate: false });
        }

        escaping = sample.escaping;
      }
    }

    markers.sort((a, b) => a.t - b.t);

    if (header.startTick !== null && header.startTick !== undefined) {
      first = Math.min(first, header.startTick);
    }

    if (first === Infinity) {
      first = 0;
      last = 0;
    }

    return { header, bots: Array.from(bots.values()), markers, counts, t0: first, t1: Math.max(last, first) };
  }

  /**
   * その時刻の位置。前後の行の間を真っ直ぐ結ぶ（地上の判断は最長5秒おき、航空機は1秒おき）。次の行まで長く空いて
   * いれば、もう居ない（倒されて次の車両になった）。
   */
  function positionAt(bot, t) {
    const samples = bot.samples;

    if (!samples.length || t < samples[0].t) {
      return null;
    }

    let low = 0;
    let high = samples.length - 1;

    while (low < high) {
      const middle = (low + high + 1) >> 1;

      if (samples[middle].t <= t) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }

    const a = samples[low];
    const b = samples[low + 1];
    const linger = bot.air ? 60 : 200;
    const gap = bot.air ? 200 : 600;

    if (!b || b.t - a.t > gap) {
      return t - a.t <= linger ? { x: a.x, y: a.y, z: a.z, sample: a } : null;
    }

    const f = (t - a.t) / Math.max(1, b.t - a.t);

    return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f, z: a.z + (b.z - a.z) * f, sample: a };
  }

  /** 成長の表のための、中身を JSON にしない速い数え方。 */
  async function quickMetrics(entry) {
    const count = (text, pattern) => (text.match(pattern) || []).length;
    const out = {};

    if (entry.events) {
      const text = await readText(entry.events);

      for (const type of ["WaterEntered", "Underwater", "VehicleDestroyed", "EnemyDestroyed", "ObjectiveCaptured",
        "ObjectiveRecaptured", "ObjectiveLost", "UnnecessaryDeath", "LongExposure", "RetreatStarted", "WeaponReleased"]) {
        out[type] = count(text, new RegExp('"type":"' + type + '"', "g"));
      }

      out.airKills = count(text, /"type":"EnemyDestroyed"[^\n]*?"vehicle":"[^":]+"/g);
    }

    if (entry.decisions) {
      const text = await readText(entry.decisions);

      out.rows = count(text, /\n/g);
      // 抜け出しと水を記録する走り方（2026-09-13 以降の jar）で戦った戦闘か。
      out.learning = text.includes('"escaping":');
      out.escaping = count(text, /"escaping":true/g);
      out.lost = count(text, /"lost":true/g);
      out.underwater = count(text, /"water":([2-9]|\d\d)/g);
      out.jammed = count(text, /"jams":([3-9]|\d\d)/g);
    }

    if (entry.flights) {
      const text = await readText(entry.flights);

      out.flights = count(text, /\n/g);
      out.evading = count(text, /"evading":true/g);
    }

    return out;
  }

  // ------------------------------------------------------------------
  // 版
  // ------------------------------------------------------------------

  function flatten(prefix, value, into) {
    for (const [key, inner] of Object.entries(value || {})) {
      const name = prefix ? prefix + "." + key : key;

      if (inner && typeof inner === "object" && !Array.isArray(inner)) {
        flatten(name, inner, into);
      } else if (typeof inner === "number") {
        into[name] = inner;
      }
    }

    return into;
  }

  /** 版の書き出し（exports/<版>.json）から map.* の重み。無ければ null。 */
  async function readWeights(ref) {
    const json = await readJson(ref);
    const flat = flatten("", json && json.parameters ? json.parameters : json, {});
    const pick = (key) => (typeof flat[key] === "number" ? flat[key] : null);

    if (pick("map.trap") === null && pick("map.water") === null) {
      return null;
    }

    return { trap: pick("map.trap"), water: pick("map.water"), death: pick("map.death"), flow: pick("map.flow") };
  }

  function clock(ticks) {
    const seconds = Math.max(0, Math.round(ticks / 20));

    return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
  }

  AV.data = { readMemory, reading, cost, cellAt, summarize, ranked, readJson, readHeader, readDetail, positionAt,
    quickMetrics, readWeights, clock, isAir, releaseKind };
})(window.AV = window.AV || {});
