/*
 * 学習の段取り: Worker の群れ、学習するパラメータの一覧、進化戦略と採用の門。
 *
 * <b>何を学習するか。</b> ゲームの AI の版（learning/AiVersion）そのもの——rule_based の方針に渡すパラメータの集まり。
 * 学習した物は versions/<名前>.json として書き出し、ゲームの /tdm ai versions reload で読める。物理・当たり判定・
 * 方針の骨組みは触らない（decision/ParameterSet の約束）。
 *
 * <b>どう学習するか。</b> 1世代ごとに:
 *
 *   1. 平均の版の周りに λ 個の候補を撒く（パラメータごとに対数の空間で ±σ、対の片方は符号を反転）
 *   2. 各候補を採用版（時々は過去の採用版）と K 試合戦わせる。同じ世代の候補は同じ会場の種で戦う（運を揃える）。
 *      陣営は試合ごとに入れ替える
 *   3. 勝ち点（勝ち1・分け0.5・チケット差を少し、に罰と加点——敵機のいないときの防空・敵機のいるときの防空・戦車と
 *      歩兵戦闘車の組・水に入った回数・障害物に詰まった回数）の上位半分の加重平均へ、平均を寄せる
 *   4. 門: 平均の版を採用版と G 試合（種ごとに陣営を入れ替えた対）戦わせ、勝率が基準以上なら採用する
 *
 * 試合は全部 Worker の群れへ投げるので、同時に走る数は Worker の数。
 */
(function (global) {
  "use strict";

  const AV = {};

  AVTrainerData(AV);
  AVSim_core(AV);

  // ------------------------------------------------------------------
  // Worker の群れ
  // ------------------------------------------------------------------

  const MODULES = ["AVTrainerData", "AVSim_core", "AVSim_terrain", "AVSim_brain", "AVSim_tactics", "AVSim_air",
    "AVSim_match", "AVSim_worker"];

  /** file:// で開いても作れるように、読み込み済みの関数の文字列から Blob の Worker を作る。 */
  function workerUrl() {
    const source = "var AV = {};\n" + MODULES.map((name) => "(" + global[name].toString() + ")(AV);").join("\n");

    return URL.createObjectURL(new Blob([source], { type: "text/javascript" }));
  }

  class Pool {
    constructor(size) {
      this.url = workerUrl();
      this.workers = [];
      this.queue = [];
      this.pending = new Map();
      this.nextJob = 1;
      this.done = 0;
      this.ticks = 0;
      this.ms = 0;
      this.startedAt = performance.now();
      this.world = null;
      this.resize(size);
    }

    /** 読み込んだワールドの会場を全部の Worker へ1度だけ送る。後から足した Worker にも送る。 */
    setWorld(key, data) {
      this.world = key ? { key, data } : null;

      if (this.world) {
        for (const slot of this.workers) slot.worker.postMessage({ world: this.world });
      }
    }

    resize(size) {
      while (this.workers.length < size) {
        const worker = new Worker(this.url);
        const slot = { worker, job: null };

        worker.onmessage = (event) => this.finished(slot, event.data);
        worker.onerror = (event) => this.finished(slot, { jobId: slot.job ? slot.job.jobId : -1, error: event.message });

        if (this.world) worker.postMessage({ world: this.world });

        this.workers.push(slot);
      }

      while (this.workers.length > size) {
        const idle = this.workers.findIndex((slot) => !slot.job);
        const index = idle >= 0 ? idle : this.workers.length - 1;
        const [slot] = this.workers.splice(index, 1);

        if (slot.job) this.queue.unshift(slot.job);

        slot.worker.terminate();
      }

      this.pump();
    }

    get size() {
      return this.workers.length;
    }

    get busy() {
      return this.workers.filter((slot) => slot.job).length;
    }

    /** 試合1つ。結果の Promise。 */
    run(options) {
      return new Promise((resolve, reject) => {
        const job = { jobId: this.nextJob++, options };

        this.pending.set(job.jobId, { resolve, reject });
        this.queue.push(job);
        this.pump();
      });
    }

    pump() {
      for (const slot of this.workers) {
        if (slot.job || this.queue.length === 0) continue;

        slot.job = this.queue.shift();
        slot.worker.postMessage(slot.job);
      }
    }

    finished(slot, message) {
      const waiting = this.pending.get(message.jobId);

      slot.job = null;
      this.pending.delete(message.jobId);

      if (waiting) {
        if (message.error) {
          waiting.reject(new Error(message.error));
        } else {
          this.done++;
          this.ticks += message.result.ticks;
          this.ms += message.ms;
          waiting.resolve(message.result);
        }
      }

      this.pump();
    }

    /** 待っている試合を捨てる（止めたとき）。走っている試合は走り切る。 */
    cancelQueued() {
      for (const job of this.queue) {
        const waiting = this.pending.get(job.jobId);

        this.pending.delete(job.jobId);
        if (waiting) waiting.reject(new Error("cancelled"));
      }

      this.queue.length = 0;
    }

    terminate() {
      this.cancelQueued();

      for (const slot of this.workers) slot.worker.terminate();

      this.workers.length = 0;
      URL.revokeObjectURL(this.url);
    }
  }

  // ------------------------------------------------------------------
  // 学習するパラメータ
  // ------------------------------------------------------------------

  /** 群ごとの説明。学習するかをページで選ぶ。 */
  const GROUPS = [
    { key: "policy", label: "行動の重み（policy.*）", on: true },
    { key: "profile", label: "役割の目盛り（profile.*）", on: true },
    { key: "objective", label: "拠点の点数（objective.*）", on: true },
    { key: "target", label: "目標の選び方（target.*）", on: true },
    { key: "tactics", label: "遮蔽・側面・後退の位置（tactics.*）", on: false },
    { key: "route", label: "道の代償（route.*）", on: false },
    { key: "spawn", label: "補充の種類（spawn.*）", on: false },
    { key: "air", label: "航空機の狙う相手（air.target.*）", on: false },
  ];

  /**
   * 学習器の試合が読まない物。学習させても結果に効かず、ただ揺れた値が書き出される。
   * 失速（舵が無いので起きない）、覚えた地図（map.*）、掘る・未ロードの土地、拠点の無い試合の敵陣。
   */
  const IGNORED = [
    /^air\.target\.stall$/, /^map\./, /^route\.(unknown|dig|strategic)$/, /^objective\.capture\.base$/,
  ];

  const LIMIT = 2.0;

  class Catalog {
    constructor(groups) {
      const defaults = AV.DATA.parameters;

      this.keys = Object.keys(defaults).filter((key) => groups.includes(key.split(".")[0])
        && !IGNORED.some((pattern) => pattern.test(key)));
      this.defaults = this.keys.map((key) => defaults[key]);
    }

    get size() {
      return this.keys.length;
    }

    /**
     * 符号化した値 θ（0 = 基の値）から、実際の値。正の値は対数（値 × e^θ）、0 と負は線形で、
     * profile.* は 0〜1 に収める。
     */
    decode(base, theta) {
      // 学習しない名前は基の値のまま持ち越す（読み込んだ版の値を捨てない）。
      const out = Object.assign({}, base);

      for (let i = 0; i < this.keys.length; i++) {
        const key = this.keys[i];
        const start = base[key] !== undefined ? base[key] : this.defaults[i];
        const t = Math.max(-LIMIT, Math.min(LIMIT, theta[i]));
        let value = start > 0 ? start * Math.exp(t) : start + t * Math.max(Math.abs(start), 0.3);

        if (key.startsWith("profile.")) value = Math.max(0, Math.min(1, value));
        if (key.endsWith("_ticks") || key.endsWith("min_dwell")) value = Math.max(1, Math.round(value));

        out[key] = Math.round(value * 1e6) / 1e6;
      }

      return out;
    }
  }

  /** 全部の名前に値を補う（書き出す形）。 */
  function complete(values) {
    return Object.assign({}, AV.DATA.parameters, values);
  }

  function gaussian(random) {
    let u = 0;

    while (u === 0) u = random();

    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random());
  }

  // ------------------------------------------------------------------
  // 学習
  // ------------------------------------------------------------------

  const DEFAULT_SETTINGS = {
    workers: Math.max(1, (navigator.hardwareConcurrency || 4) - 1),
    teamSize: 8,
    minutes: 12,
    points: 3,
    mapSize: 1024,
    varyTerrain: true,
    population: 12,
    matchesPerCandidate: 4,
    sigma: 0.2,
    learningRate: 0.6,
    sigmaDecay: 0.995,
    sigmaFloor: 0.05,
    hallShare: 0.3,
    gateMatches: 24,
    gateWinRate: 0.55,
    groups: GROUPS.filter((g) => g.on).map((g) => g.key),
    prefix: "web",
    air: true,
    mapSource: "generated",
    // 勝ち点に足す罰と加点（1試合あたり）。
    shapeAirIdle: 0.05,
    shapeAirWanted: 0.03,
    shapePair: 0.2,
    shapeWater: 0.02,
    shapeStall: 0.005,
  };

  /** 世代の記録の上限。 */
  const HISTORY = 400;

  class Trainer {
    constructor(listener) {
      this.listener = listener || (() => {});
      this.settings = Object.assign({}, DEFAULT_SETTINGS);
      this.pool = null;
      this.world = null;
      this.running = false;
      this.stopping = false;
      this.reset({ id: "rule_v1", values: {} });
    }

    /** 学習をやり直す。基にする版を渡す。 */
    reset(base) {
      this.base = { id: base.id, values: Object.assign({}, base.values) };
      this.catalog = new Catalog(this.settings.groups);
      this.mean = new Float64Array(this.catalog.size);
      this.sigma = this.settings.sigma;
      this.generation = 0;
      this.champion = { id: base.id, values: complete(base.values), generation: 0, theta: Array.from(this.mean) };
      this.hall = [];
      this.promotions = 0;
      this.history = [];
      this.seedBase = (Math.random() * 1e9) >>> 0;
      this.log = [];
      this.note("学習を始める版: " + base.id + "（学習するパラメータ " + this.catalog.size + " 個）");
      this.emit();
    }

    note(text) {
      this.log.push({ at: new Date().toLocaleTimeString(), text });

      if (this.log.length > 200) this.log.shift();
    }

    emit() {
      this.listener(this);
    }

    ensurePool() {
      if (!this.pool) {
        this.pool = new Pool(this.settings.workers);
      } else if (this.pool.size !== this.settings.workers) {
        this.pool.resize(this.settings.workers);
      }

      if (this.world && (!this.pool.world || this.pool.world.key !== this.world.key)) {
        this.pool.setWorld(this.world.key, this.world.data);
      }

      return this.pool;
    }

    /** 会場にするワールド（world.js の build の結果）。null で自動生成に戻す。 */
    setWorld(data) {
      this.world = data ? { key: data.name + "@" + data.originX + "," + data.originZ + "/" + data.size, data } : null;

      if (this.pool && this.world) this.pool.setWorld(this.world.key, this.world.data);

      this.emit();
    }

    usesWorld() {
      return this.settings.mapSource === "world" && !!this.world;
    }

    matchOptions(seed, first, second) {
      const s = this.settings;
      const random = AV.rng(seed ^ 0x5bd1e995);
      const vary = (value) => (s.varyTerrain ? value * (0.5 + random()) : value);

      return {
        seed,
        teamSize: s.teamSize,
        minutes: s.minutes,
        air: s.air,
        versions: [first, second],
        map: this.usesWorld() ? { worldKey: this.world.key }
          : { size: s.mapSize, points: s.points, hills: vary(1), woods: vary(1), towns: vary(1), water: vary(1) },
      };
    }

    /** 候補の値 values が相手 opponent と戦った1試合。候補から見た点。 */
    async play(seed, values, opponent, candidateSide) {
      const versions = candidateSide === 0 ? [values, opponent] : [opponent, values];
      const result = await this.pool.run(this.matchOptions(seed, versions[0], versions[1]));
      const mine = candidateSide;
      const win = result.winner === null ? 0.5 : result.winner === mine ? 1 : 0;
      const margin = (result.tickets[mine] - result.tickets[1 - mine]) / 100;

      return { win, score: win + 0.25 * margin + this.shaping(result.stats[mine]), result, mine };
    }

    /**
     * 勝敗の外の罰と加点（2026-09-17 の指示）: 敵機のいないときに出した防空は罰、いるときに出した防空は加点、戦車と歩兵戦闘車が
     * 近くで組んでいた割合は加点、水に入った回数と障害物に詰まった回数は罰。
     */
    shaping(stats) {
      const s = this.settings;

      return s.shapeAirWanted * stats.airDefenceWanted - s.shapeAirIdle * stats.airDefenceIdle
        + s.shapePair * stats.pairShare - s.shapeWater * stats.waterEntries - s.shapeStall * stats.stalls;
    }

    async start() {
      if (this.running) return;

      this.running = true;
      this.stopping = false;
      this.ensurePool();
      this.note("開始: Worker " + this.pool.size + " 個");
      this.emit();

      try {
        while (!this.stopping) {
          await this.step();
        }
      } catch (error) {
        if (error.message !== "cancelled") {
          this.note("止まった: " + error.message);
          console.error(error);
        }
      } finally {
        this.running = false;
        this.stopping = false;
        this.note("停止");
        this.emit();
      }
    }

    stop() {
      if (!this.running) return;

      this.stopping = true;
      this.pool.cancelQueued();
      this.emit();
    }

    /** 1世代。 */
    async step() {
      const s = this.settings;
      const catalog = this.catalog;
      const dims = catalog.size;
      const generation = ++this.generation;
      const random = AV.rng(this.seedBase + generation * 7919);
      const half = Math.max(1, Math.round(s.population / 2));
      const candidates = [];

      for (let pair = 0; pair < half; pair++) {
        const noise = new Float64Array(dims);

        for (let i = 0; i < dims; i++) noise[i] = gaussian(random);

        for (const sign of [1, -1]) {
          const theta = new Float64Array(dims);

          for (let i = 0; i < dims; i++) theta[i] = this.mean[i] + sign * this.sigma * noise[i];

          candidates.push({ theta, values: catalog.decode(this.base.values, theta), score: 0, wins: 0, games: 0 });
        }
      }

      // 相手: 採用版か、時々は過去の採用版。どの候補も同じ相手・同じ会場で戦う。
      const fixtures = [];

      for (let k = 0; k < s.matchesPerCandidate; k++) {
        const useHall = this.hall.length > 0 && random() < s.hallShare;
        const opponent = useHall ? this.hall[Math.floor(random() * this.hall.length)] : this.champion;

        fixtures.push({ seed: (this.seedBase + generation * 1000 + k) >>> 0, opponent, side: k % 2 });
      }

      this.phase = { generation, kind: "candidates", total: candidates.length * fixtures.length, done: 0 };
      this.emit();

      await Promise.all(candidates.flatMap((candidate) => fixtures.map(async (fixture) => {
        const outcome = await this.play(fixture.seed, candidate.values, fixture.opponent.values, fixture.side);

        candidate.score += outcome.score;
        candidate.wins += outcome.win;
        candidate.games++;
        this.phase.done++;
        this.emit();
      })));

      if (this.stopping) return;

      for (const c of candidates) {
        c.score /= Math.max(c.games, 1);
        c.winRate = c.wins / Math.max(c.games, 1);
      }

      candidates.sort((a, b) => b.score - a.score);

      // 上位半分の対数順位の重みで平均を寄せる。
      const mu = Math.max(1, Math.floor(candidates.length / 2));
      const raw = [];

      for (let i = 0; i < mu; i++) raw.push(Math.log(mu + 0.5) - Math.log(i + 1));

      const total = raw.reduce((a, b) => a + b, 0);
      const target = new Float64Array(dims);

      for (let i = 0; i < mu; i++) {
        const weight = raw[i] / total;

        for (let d = 0; d < dims; d++) target[d] += weight * candidates[i].theta[d];
      }

      for (let d = 0; d < dims; d++) {
        this.mean[d] += s.learningRate * (target[d] - this.mean[d]);
      }

      this.sigma = Math.max(s.sigmaFloor, this.sigma * s.sigmaDecay);

      const meanScore = candidates.reduce((a, c) => a + c.score, 0) / candidates.length;
      const record = {
        generation, best: candidates[0].score, bestWin: candidates[0].winRate, mean: meanScore,
        meanWin: candidates.reduce((a, c) => a + c.winRate, 0) / candidates.length, sigma: this.sigma,
        gate: null, promoted: false,
      };

      this.history.push(record);
      if (this.history.length > HISTORY) this.history.shift();

      // 門
      const values = catalog.decode(this.base.values, this.mean);
      const gate = await this.gate(values, generation);

      if (this.stopping || !gate) return;

      record.gate = gate.winRate;
      record.gateGames = gate.games;

      if (gate.winRate >= s.gateWinRate) {
        this.promote(values, gate, generation);
        record.promoted = true;
      }

      this.emit();
    }

    /** 平均の版を採用版と戦わせる。種ごとに陣営を入れ替えた対。 */
    async gate(values, generation) {
      const s = this.settings;
      const pairs = Math.max(1, Math.round(s.gateMatches / 2));
      const games = [];

      this.phase = { generation, kind: "gate", total: pairs * 2, done: 0 };
      this.emit();

      const actions = { challenger: {}, champion: {} };
      const tally = {
        wins: 0, draws: 0, losses: 0, games: 0, kills: 0, losses_: 0, captures: 0, airKills: 0, airDefenceIdle: 0,
        airDefenceWanted: 0, pair: 0, water: 0, championWater: 0, championPair: 0,
      };

      await Promise.all([...Array(pairs).keys()].flatMap((pair) => [0, 1].map(async (side) => {
        const seed = (this.seedBase ^ 0xa5a5a5a5) + generation * 104729 + pair;
        const outcome = await this.play(seed >>> 0, values, this.champion.values, side);
        const mine = outcome.mine;
        const stats = outcome.result.stats;

        tally.games++;
        if (outcome.win === 1) tally.wins++; else if (outcome.win === 0.5) tally.draws++; else tally.losses++;
        tally.kills += stats[mine].kills;
        tally.losses_ += stats[mine].losses;
        tally.captures += stats[mine].captures;
        tally.airKills += stats[mine].airKills;
        tally.airDefenceIdle += stats[mine].airDefenceIdle;
        tally.airDefenceWanted += stats[mine].airDefenceWanted;
        tally.pair += stats[mine].pairShare;
        tally.water += stats[mine].waterEntries;
        tally.stalls = (tally.stalls || 0) + stats[mine].stalls;
        tally.championStalls = (tally.championStalls || 0) + stats[1 - mine].stalls;
        tally.championPair += stats[1 - mine].pairShare;
        tally.championWater += stats[1 - mine].waterEntries;
        merge(actions.challenger, stats[mine].actions);
        merge(actions.champion, stats[1 - mine].actions);
        games.push(outcome.result);
        this.phase.done++;
        this.emit();
      })));

      if (this.stopping) return null;

      const winRate = (tally.wins + 0.5 * tally.draws) / Math.max(tally.games, 1);

      this.lastGate = { generation, winRate, tally, actions };

      return { winRate, games: tally.games, tally, actions };
    }

    promote(values, gate, generation) {
      this.hall.push(this.champion);

      if (this.hall.length > 6) this.hall.shift();

      this.promotions++;

      const id = this.versionId();

      this.champion = { id, values: complete(values), generation, theta: Array.from(this.mean), gate };
      this.note("採用: " + id + "（" + (gate.winRate * 100).toFixed(1) + "% / " + gate.games + " 試合、第 " + generation
        + " 世代）");
    }

    versionId() {
      const prefix = (this.settings.prefix || "web").toLowerCase().replace(/[^a-z0-9_.-]/g, "_");

      return (prefix + "_v" + (this.promotions + 1)).slice(0, 48);
    }

    /** versions/<id>.json の中身（AiVersion.toJson と同じ形）。 */
    versionJson(entry) {
      const champion = entry || this.champion;
      const parent = this.hall.length ? this.hall[this.hall.length - 1].id : this.base.id;
      const parameters = {};

      for (const key of Object.keys(champion.values).sort()) parameters[key] = champion.values[key];

      return {
        id: champion.id,
        policy: "rule_based",
        description: "Trained in the browser trainer (tool/ai/trainer) from " + this.base.id + ", generation "
          + champion.generation + (champion.gate ? ", gate " + (champion.gate.winRate * 100).toFixed(1) + "% over "
            + champion.gate.games + " matches" : "") + ". Check it in-game with /tdm ai selfplay before promoting.",
        parent: champion === this.champion ? parent : this.base.id,
        parameters,
      };
    }

    /** 状態を保存できる形に。 */
    save() {
      return {
        version: 1,
        settings: this.settings,
        base: this.base,
        keys: this.catalog.keys,
        mean: Array.from(this.mean),
        sigma: this.sigma,
        generation: this.generation,
        champion: this.champion,
        hall: this.hall,
        promotions: this.promotions,
        history: this.history,
        seedBase: this.seedBase,
        log: this.log,
        lastGate: this.lastGate || null,
      };
    }

    load(saved) {
      if (!saved || saved.version !== 1) return false;

      this.settings = Object.assign({}, DEFAULT_SETTINGS, saved.settings);
      this.base = saved.base;
      this.catalog = new Catalog(this.settings.groups);
      this.mean = new Float64Array(this.catalog.size);

      // 学習する群を変えて保存し直した場合でも、名前で合わせて戻す。
      const byKey = new Map(saved.keys.map((key, i) => [key, saved.mean[i]]));

      this.catalog.keys.forEach((key, i) => { this.mean[i] = byKey.get(key) || 0; });
      this.sigma = saved.sigma;
      this.generation = saved.generation;
      this.champion = saved.champion;
      this.hall = saved.hall || [];
      this.promotions = saved.promotions || 0;
      this.history = saved.history || [];
      this.seedBase = saved.seedBase >>> 0;
      this.log = saved.log || [];
      this.lastGate = saved.lastGate;
      this.emit();

      return true;
    }

    /** 学習する群を変える。平均は名前で引き継ぐ。 */
    setGroups(groups) {
      const previous = new Map(this.catalog.keys.map((key, i) => [key, this.mean[i]]));

      this.settings.groups = groups;
      this.catalog = new Catalog(groups);
      this.mean = new Float64Array(this.catalog.size);
      this.catalog.keys.forEach((key, i) => { this.mean[i] = previous.get(key) || 0; });
      this.emit();
    }
  }

  function merge(into, from) {
    for (const [key, value] of Object.entries(from)) into[key] = (into[key] || 0) + value;
  }

  global.AVTrainer = { AV, Pool, Catalog, Trainer, GROUPS, DEFAULT_SETTINGS, complete };
})(window);
