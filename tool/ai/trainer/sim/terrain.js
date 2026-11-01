/*
 * 学習用の会場。4ブロックのマスの高さ地図に、林・建物・水を置いた物。
 *
 * ゲームの AI が地形から読むのは、高さ地図（視線・遮蔽・脅威の掃引、battlefield/HeightField）と、通れるか・代償
 * （navigation/Obstacles・RoutePlanner）だけ。ここはその2つを同じ粒度で持つ。ブロックの形・車体の揺れ・登坂の
 * 物理は持たない——学習させるのは判断で、物理ではない（[[battle-ai-is-layered-with-a-discrete-action-policy]]）。
 *
 * <b>会場は点対称。</b> 試合ごとに陣営を入れ替えるのと合わせて、片側の有利を勝率から消す（learning/SelfPlay）。
 */
function AVSim_terrain(AV) {
  "use strict";

  const CELL = 4;
  const OPEN = 0;
  const WOOD = 1;
  const BUILDING = 2;
  const WATER = 3;

  /** 建物と林が視線を遮る高さ（地面から）。 */
  const BUILDING_HEIGHT = 9.0;
  const WOOD_HEIGHT = 6.5;
  /** 隣のマスとの段差がこれを超えたら崖（地上車両は登れない）。 */
  const CLIFF = 3.2;

  class Terrain {
    /**
     * @param options {seed, size, points, hills, woods, towns, water}
     */
    constructor(options) {
      if (options.world) {
        this.adopt(options.world);
        return;
      }

      const size = options.size || 768;
      const n = Math.floor(size / CELL);

      this.world = null;
      this.seed = options.seed >>> 0;
      this.size = n * CELL;
      this.n = n;
      this.ground = new Float32Array(n * n);
      this.top = new Float32Array(n * n);
      this.kind = new Uint8Array(n * n);
      this.heightValue = new Float32Array(n * n);
      this.points = [];
      this.spawns = [];

      this.generate(options);
    }

    /**
     * Minecraft のワールドから作った会場（world.js）をそのまま使う。配列は写さない——Worker の中で同じ会場を
     * 何試合も使い回すので、高さの目盛り（heightValue）もワールドの物に覚えさせる。
     */
    adopt(world) {
      this.world = world;
      this.seed = 0;
      this.size = world.size;
      this.n = world.n;
      this.ground = world.ground;
      this.top = world.top;
      this.kind = world.kind;
      this.spawns = world.spawns.map((s) => ({ x: s.x, z: s.z }));
      this.points = world.points.map((p) => ({ id: p.id, x: p.x, z: p.z, radius: p.radius, home: p.home }));

      if (!world.heightValue) {
        this.heightValue = new Float32Array(this.n * this.n);
        this.measureHeightValue();
        world.heightValue = this.heightValue;
      }

      this.heightValue = world.heightValue;
    }

    // ------------------------------------------------------------------
    // 作る
    // ------------------------------------------------------------------

    generate(options) {
      const random = AV.rng(this.seed * 2654435761 + 97);
      const n = this.n;
      const size = this.size;
      const hills = options.hills === undefined ? 1.0 : options.hills;
      const woods = options.woods === undefined ? 1.0 : options.woods;
      const towns = options.towns === undefined ? 1.0 : options.towns;
      const wet = options.water === undefined ? 1.0 : options.water;
      const pointCount = options.points || 3;

      // 陣地と拠点。中心について点対称に置く。
      const offset = (random() - 0.5) * size * 0.3;
      const margin = 72;

      this.spawns = [
        { x: margin, z: size / 2 + offset },
        { x: size - margin, z: size / 2 - offset },
      ];

      const points = [{ x: size / 2, z: size / 2 }];

      // 拠点の数は奇数（中心＋対の数）。偶数を頼まれたら1つ減らす——対称を崩さないため。
      while (points.length + 2 <= pointCount) {
        const along = 0.2 + random() * 0.22;
        const x = size * along + size * 0.08;
        const z = size * (0.2 + random() * 0.6);

        points.push({ x, z });
        points.push({ x: size - x, z: size - z });
      }

      this.points = points.map((p, index) => ({ id: "P" + (index + 1), x: p.x, z: p.z, radius: 16 }));

      // 起伏: 粗い格子の値ノイズを3段重ねる。
      const layers = [
        { cells: 6, amplitude: 14 * hills },
        { cells: 14, amplitude: 5 * hills },
        { cells: 32, amplitude: 1.6 * hills },
      ];
      const noise = (layer, x, z) => {
        const gx = (x / size) * layer.cells;
        const gz = (z / size) * layer.cells;
        const ix = Math.floor(gx);
        const iz = Math.floor(gz);
        const fx = gx - ix;
        const fz = gz - iz;
        const sx = fx * fx * (3 - 2 * fx);
        const sz = fz * fz * (3 - 2 * fz);
        const v = (i, j) => layer.lattice[(j * (layer.cells + 2) + i) % layer.lattice.length];
        const a = AV.lerp(sx, v(ix, iz), v(ix + 1, iz));
        const b = AV.lerp(sx, v(ix, iz + 1), v(ix + 1, iz + 1));

        return AV.lerp(sz, a, b);
      };

      for (const layer of layers) {
        layer.lattice = new Float32Array((layer.cells + 2) * (layer.cells + 2));

        for (let i = 0; i < layer.lattice.length; i++) {
          layer.lattice[i] = random();
        }
      }

      const woodLattice = { cells: 18, lattice: new Float32Array(20 * 20) };

      for (let i = 0; i < woodLattice.lattice.length; i++) {
        woodLattice.lattice[i] = random();
      }

      for (let cz = 0; cz < n; cz++) {
        for (let cx = 0; cx < n; cx++) {
          const x = (cx + 0.5) * CELL;
          const z = (cz + 0.5) * CELL;
          // 点対称: その点と、中心について反対の点の平均。写すと継ぎ目に崖ができる。
          const mx = size - x;
          const mz = size - z;
          let h = 0;

          for (const layer of layers) {
            h += (noise(layer, x, z) + noise(layer, mx, mz)) * 0.5 * layer.amplitude;
          }

          const i = cz * n + cx;
          const wood = Math.max(noise(woodLattice, x, z), noise(woodLattice, mx, mz));

          this.ground[i] = h;
          this.kind[i] = wood > 1.0 - 0.2 * woods ? WOOD : OPEN;
        }
      }

      // 陣地と拠点の周りは均す——出撃した瞬間に崖の下、拠点が池の底、にしない。
      const flats = this.spawns.map((s) => ({ x: s.x, z: s.z, r: 40 })).concat(
        this.points.map((p) => ({ x: p.x, z: p.z, r: p.radius + 10 })));

      for (const flat of flats) {
        this.flatten(flat.x, flat.z, flat.r);
      }

      // 均す順で左右がずれるので、もう一度平均して点対称に戻す。
      for (let i = 0, j = n * n - 1; i < j; i++, j--) {
        const mean = (this.ground[i] + this.ground[j]) * 0.5;

        this.ground[i] = mean;
        this.ground[j] = mean;
      }

      // 水: 低い所に溜める。
      let lowest = Infinity;
      let highest = -Infinity;

      for (let i = 0; i < n * n; i++) {
        lowest = Math.min(lowest, this.ground[i]);
        highest = Math.max(highest, this.ground[i]);
      }

      const waterLevel = lowest + (highest - lowest) * 0.16 * wet;

      for (let i = 0; i < n * n; i++) {
        if (wet > 0 && this.ground[i] < waterLevel) {
          this.kind[i] = WATER;
        }
      }

      // 町: 拠点のいくつかに建物の区画を置く。通りは残す。
      // 対の片方（添字が奇数）と中心だけを見る。もう片方には回した区画が入る。
      for (let at = 0; at < this.points.length; at += at === 0 ? 1 : 2) {
        if (random() > 0.55 * towns) {
          continue;
        }

        const p = this.points[at];
        const blocks = 4 + Math.floor(random() * 6);

        for (let b = 0; b < blocks; b++) {
          const angle = random() * Math.PI * 2;
          const reach = p.radius + 10 + random() * 36;
          const bx = Math.floor((p.x + Math.cos(angle) * reach) / CELL);
          const bz = Math.floor((p.z + Math.sin(angle) * reach) / CELL);
          const w = 2 + Math.floor(random() * 3);
          const d = 2 + Math.floor(random() * 3);

          for (let dz = 0; dz < d; dz++) {
            for (let dx = 0; dx < w; dx++) {
              this.setKind(bx + dx, bz + dz, BUILDING);
              this.setKind(n - 1 - (bx + dx), n - 1 - (bz + dz), BUILDING);
            }
          }
        }
      }

      // 均した所に建物や水が残っていれば消す（写した側にも）。
      for (const flat of flats) {
        this.clear(flat.x, flat.z, flat.r);
        this.clear(size - flat.x, size - flat.z, flat.r);
      }

      for (let i = 0; i < n * n; i++) {
        const k = this.kind[i];
        this.top[i] = this.ground[i] + (k === BUILDING ? BUILDING_HEIGHT : k === WOOD ? WOOD_HEIGHT : 0);
      }

      this.measureHeightValue();
    }

    flatten(x, z, radius) {
      const n = this.n;
      const cx0 = Math.max(0, Math.floor((x - radius * 1.8) / CELL));
      const cx1 = Math.min(n - 1, Math.floor((x + radius * 1.8) / CELL));
      const cz0 = Math.max(0, Math.floor((z - radius * 1.8) / CELL));
      const cz1 = Math.min(n - 1, Math.floor((z + radius * 1.8) / CELL));
      const centre = this.ground[Math.floor(z / CELL) * n + Math.floor(x / CELL)];

      for (let cz = cz0; cz <= cz1; cz++) {
        for (let cx = cx0; cx <= cx1; cx++) {
          const d = AV.dist2((cx + 0.5) * CELL, (cz + 0.5) * CELL, x, z);
          const t = AV.clamp((d - radius) / (radius * 0.8), 0, 1);
          const i = cz * n + cx;

          this.ground[i] = AV.lerp(t, centre, this.ground[i]);
        }
      }
    }

    clear(x, z, radius) {
      const n = this.n;

      for (let cz = Math.max(0, Math.floor((z - radius) / CELL)); cz <= Math.min(n - 1, Math.floor((z + radius) / CELL)); cz++) {
        for (let cx = Math.max(0, Math.floor((x - radius) / CELL)); cx <= Math.min(n - 1, Math.floor((x + radius) / CELL)); cx++) {
          if (AV.dist2((cx + 0.5) * CELL, (cz + 0.5) * CELL, x, z) <= radius) {
            const i = cz * n + cx;
            this.kind[i] = OPEN;
          }
        }
      }
    }

    setKind(cx, cz, kind) {
      if (cx >= 0 && cz >= 0 && cx < this.n && cz < this.n) {
        this.kind[cz * this.n + cx] = kind;
      }
    }

    /** TacticalCell.heightValue: 周り（半径 5 マス）の平均より高いほど 1。 */
    measureHeightValue() {
      const n = this.n;
      const r = 5;
      const sums = new Float64Array((n + 1) * (n + 1));

      for (let cz = 0; cz < n; cz++) {
        for (let cx = 0; cx < n; cx++) {
          sums[(cz + 1) * (n + 1) + cx + 1] = this.ground[cz * n + cx] + sums[cz * (n + 1) + cx + 1]
            + sums[(cz + 1) * (n + 1) + cx] - sums[cz * (n + 1) + cx];
        }
      }

      for (let cz = 0; cz < n; cz++) {
        for (let cx = 0; cx < n; cx++) {
          const x0 = Math.max(0, cx - r);
          const x1 = Math.min(n, cx + r + 1);
          const z0 = Math.max(0, cz - r);
          const z1 = Math.min(n, cz + r + 1);
          const total = sums[z1 * (n + 1) + x1] - sums[z0 * (n + 1) + x1] - sums[z1 * (n + 1) + x0]
            + sums[z0 * (n + 1) + x0];
          const mean = total / ((x1 - x0) * (z1 - z0));
          const i = cz * n + cx;

          this.heightValue[i] = AV.clamp((this.ground[i] - mean) / 6.0 + 0.3, 0, 1);
        }
      }
    }

    // ------------------------------------------------------------------
    // 訊く
    // ------------------------------------------------------------------

    index(x, z) {
      if (!(x >= 0 && z >= 0 && x < this.size && z < this.size)) {
        return -1;
      }

      return ((z / CELL) | 0) * this.n + ((x / CELL) | 0);
    }

    groundAt(x, z) {
      const i = this.index(x, z);

      return i < 0 ? 0 : this.ground[i];
    }

    /** 視線を遮る面の高さ（HeightField.sightLine）。会場の外は NaN（分からない）。 */
    sightLine(x, z) {
      const i = this.index(x, z);

      return i < 0 ? NaN : this.top[i];
    }

    kindAt(x, z) {
      const i = this.index(x, z);

      return i < 0 ? BUILDING : this.kind[i];
    }

    inside(x, z) {
      return x >= 0 && z >= 0 && x < this.size && z < this.size;
    }

    /**
     * 2点の間の視線（HeightfieldSight.trace）。0 = 通る、1 = 遮られる、2 = 分からない。
     * 両端のマス自身は数えない（自分の林の中からは外が見える）。
     */
    trace(ax, ay, az, bx, by, bz) {
      const dx = bx - ax;
      const dz = bz - az;
      const length = Math.sqrt(dx * dx + dz * dz);
      const steps = Math.ceil(length / 3.0);
      let unknown = false;

      const size = this.size;
      const n = this.n;
      const tops = this.top;
      const first = Math.ceil(3.0 / Math.max(length, 1e-6) * steps);
      const last = steps - first;

      for (let s = 1; s < steps; s++) {
        const t = s / steps;
        const x = ax + dx * t;
        const z = az + dz * t;

        if (!(x >= 0 && z >= 0 && x < size && z < size)) {
          unknown = true;
          continue;
        }

        if (s < first || s > last) {
          continue;
        }

        if (tops[((z / CELL) | 0) * n + ((x / CELL) | 0)] > ay + (by - ay) * t) {
          return 1;
        }
      }

      return unknown ? 2 : 0;
    }

    /** そのマスへ地上車両が入れるか（建物と会場の外は入れない）。 */
    walkable(cx, cz) {
      return cx >= 0 && cz >= 0 && cx < this.n && cz < this.n && this.kind[cz * this.n + cx] !== BUILDING;
    }

    /** 隣のマスへ段差で入れるか。 */
    passable(fromIndex, toIndex) {
      return Math.abs(this.ground[fromIndex] - this.ground[toIndex]) <= CLIFF;
    }
  }

  AV.Terrain = Terrain;
  AV.CELL = CELL;
  AV.KIND = { OPEN, WOOD, BUILDING, WATER };
}
