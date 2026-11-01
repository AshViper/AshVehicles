/*
 * 学習器のシミュレーションが共有する小物: 乱数・数の道具・版のパラメータ。
 *
 * sim/ のファイルはどれも「AV を受け取って中身を足す関数」1つだけを持つ。ページでは <script> で読み、Worker へは
 * 関数の文字列を束ねて Blob にして渡す（file:// で開いても Worker が作れるように。trainer.js の makeWorker）。
 * だから関数の外の変数を掴んではならない。
 */
function AVSim_core(AV) {
  "use strict";

  /** 種から決まる乱数（mulberry32）。同じ種なら同じ試合になる。 */
  AV.rng = function (seed) {
    let a = seed >>> 0;

    return function () {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);

      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  };

  AV.clamp = (value, low, high) => (value < low ? low : value > high ? high : value);
  AV.lerp = (t, a, b) => a + (b - a) * t;
  AV.dist2 = (ax, az, bx, bz) => Math.sqrt((ax - bx) * (ax - bx) + (az - bz) * (az - bz));
  AV.wrap = (angle) => {
    while (angle > Math.PI) angle -= Math.PI * 2;
    while (angle < -Math.PI) angle += Math.PI * 2;
    return angle;
  };

  /** ThreatModel.combine。 */
  AV.combine = (first, second) => 1.0 - (1.0 - first) * (1.0 - second);

  /**
   * 版のパラメータ。ゲームの ParameterSet と同じく、書いてあればその値、無ければコードの既定値（data.js）。
   * 平らな名前 → 数の物を受け取る。
   */
  AV.Params = class {
    constructor(values) {
      this.values = values || {};
    }

    get(key) {
      const value = this.values[key];

      if (typeof value === "number" && isFinite(value)) {
        return value;
      }

      const fallback = AV.DATA.parameters[key];

      if (fallback === undefined) {
        throw new Error("unknown parameter " + key);
      }

      return fallback;
    }
  };

  AV.ROLES = ["scout", "tank", "ifv", "apc", "aa", "artillery", "support"];
  AV.ACTIONS = ["capture_objective", "recapture_objective", "defend_objective", "advance", "attack", "flank",
    "seek_cover", "retreat", "search_enemy", "support_ally"];

  /** TacticalProfile.tuned。 */
  AV.profile = function (role, params) {
    const p = (name) => AV.clamp(params.get("profile." + role + "." + name), 0, 1);

    return {
      aggression: p("aggression"), riskTolerance: p("risk_tolerance"), preferredRange: p("preferred_range"),
      flankBias: p("flank_bias"), coverBias: p("cover_bias"), holdBias: p("hold_bias"),
      supportBias: p("support_bias"), scoutBias: p("scout_bias"), antiAirBias: p("anti_air_bias"),
      retreatHealth: p("retreat_health"), objectiveBias: p("objective_bias"),
    };
  };

  /** 最小ヒープ（A* の開いた集合）。値と優先度を型付き配列で持つ。 */
  AV.Heap = class {
    constructor(capacity) {
      this.items = new Int32Array(capacity);
      this.keys = new Float64Array(capacity);
      this.size = 0;
    }

    clear() {
      this.size = 0;
    }

    push(item, key) {
      if (this.size >= this.items.length) {
        const items = new Int32Array(this.items.length * 2);
        const keys = new Float64Array(this.keys.length * 2);
        items.set(this.items);
        keys.set(this.keys);
        this.items = items;
        this.keys = keys;
      }

      let at = this.size++;

      while (at > 0) {
        const parent = (at - 1) >> 1;

        if (this.keys[parent] <= key) {
          break;
        }

        this.items[at] = this.items[parent];
        this.keys[at] = this.keys[parent];
        at = parent;
      }

      this.items[at] = item;
      this.keys[at] = key;
    }

    pop() {
      const top = this.items[0];
      const lastItem = this.items[--this.size];
      const lastKey = this.keys[this.size];
      let at = 0;

      while (true) {
        let child = at * 2 + 1;

        if (child >= this.size) {
          break;
        }

        if (child + 1 < this.size && this.keys[child + 1] < this.keys[child]) {
          child++;
        }

        if (this.keys[child] >= lastKey) {
          break;
        }

        this.items[at] = this.items[child];
        this.keys[at] = this.keys[child];
        at = child;
      }

      this.items[at] = lastItem;
      this.keys[at] = lastKey;

      return top;
    }
  };
}
