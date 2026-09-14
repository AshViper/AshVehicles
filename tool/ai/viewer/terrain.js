/*
 * ワールドの地形を、上から見た絵にする。リージョンファイル1つ（512×512 ブロック）が絵1枚。
 *
 * 1ブロック1画素。色は一番上のブロック（colors.js）で、北西から光を当てて段差に陰を付ける——建物の壁と道と
 * 水路が、色より先に形で見えるように。上が水の列は水底の色に水の青を重ね、深いほど暗くする。
 *
 * 読むのは頼まれたリージョンだけ。1つ 4〜5MB を展開して 1024 チャンクの NBT を歩くので、1つ数秒かかる。
 * 展開は 16 チャンクずつまとめて待ち、その間に画面を返す。
 */
(function (AV) {
  "use strict";

  const SIZE = 512;
  const BATCH = 16;
  const NONE = -32768;

  const names = ["minecraft:air"];
  const ids = new Map([["minecraft:air", 0]]);

  function idOf(name) {
    let id = ids.get(name);

    if (id === undefined) {
      id = names.length;
      names.push(name);
      ids.set(name, id);
    }

    return id;
  }

  function clampByte(value) {
    return value < 0 ? 0 : value > 255 ? 255 : value | 0;
  }

  class Tile {
    constructor(rx, rz) {
      this.rx = rx;
      this.rz = rz;
      this.state = "loading";
      this.done = 0;
      this.heights = new Int16Array(SIZE * SIZE).fill(NONE);
      this.blocks = new Uint16Array(SIZE * SIZE);
      this.depth = new Uint8Array(SIZE * SIZE);
      this.floors = new Uint16Array(SIZE * SIZE);
      this.canvas = null;
      this.error = null;
    }
  }

  class Terrain {
    constructor() {
      this.key = null;
      this.source = null;
      this.tiles = new Map();
    }

    /** 読むリージョンファイルの一覧（"rx,rz" → File）。変われば読んだ絵を捨てる。 */
    setSource(key, files) {
      if (this.key === key) {
        return;
      }

      this.key = key;
      this.source = files || null;
      this.tiles.clear();
      AV.emit("terrain", null);
    }

    count() {
      return this.source ? this.source.size : 0;
    }

    tile(rx, rz) {
      return this.tiles.get(rx + "," + rz);
    }

    /** 範囲（ブロック）にかかるリージョンのうち、まだ読んでいない物を中心に近い順に limit 個まで読む。 */
    async request(minX, minZ, maxX, maxZ, limit) {
      if (!this.source) {
        return { loaded: 0, waiting: 0 };
      }

      const wanted = [];
      const centreX = (minX + maxX) / 2 / SIZE;
      const centreZ = (minZ + maxZ) / 2 / SIZE;

      for (let rx = Math.floor(minX / SIZE); rx <= Math.floor(maxX / SIZE); rx++) {
        for (let rz = Math.floor(minZ / SIZE); rz <= Math.floor(maxZ / SIZE); rz++) {
          const key = rx + "," + rz;

          if (this.source.has(key) && !this.tiles.has(key)) {
            wanted.push({ rx, rz, far: (rx + 0.5 - centreX) ** 2 + (rz + 0.5 - centreZ) ** 2 });
          }
        }
      }

      wanted.sort((a, b) => a.far - b.far);

      const take = wanted.slice(0, limit);

      for (const { rx, rz } of take) {
        await this.load(rx, rz);
      }

      return { loaded: take.length, waiting: wanted.length - take.length };
    }

    async load(rx, rz) {
      const key = rx + "," + rz;
      const tile = new Tile(rx, rz);
      const source = this.source;

      this.tiles.set(key, tile);
      AV.emit("terrain-progress", tile);

      try {
        const file = await AV.files.open(source.get(key));
        const chunks = AV.nbt.regionChunks(await file.arrayBuffer());

        for (let i = 0; i < chunks.length; i += BATCH) {
          const part = chunks.slice(i, i + BATCH);
          const inflated = await Promise.all(part.map((chunk) => AV.nbt.inflate(chunk.bytes, chunk.kind).catch(() => null)));

          // 読んでいる間に別のワールドへ切り替えられた。
          if (this.source !== source) {
            return tile;
          }

          inflated.forEach((bytes, k) => {
            if (!bytes) {
              return;
            }

            try {
              this.absorb(tile, part[k].index, AV.nbt.chunkColumns(AV.nbt.readRoot(bytes)));
            } catch (error) {
              // 壊れたチャンクは飛ばす。絵に1つ穴が開くだけ。
            }
          });

          tile.done = Math.min(1, (i + BATCH) / chunks.length);
          AV.emit("terrain-progress", tile);
        }

        this.render(tile);
        tile.state = "ready";
      } catch (error) {
        tile.state = "error";
        tile.error = String(error && error.message ? error.message : error);
      }

      AV.emit("terrain", tile);

      return tile;
    }

    absorb(tile, index, columns) {
      if (!columns) {
        return;
      }

      const chunkX = typeof columns.cx === "number" ? columns.cx - tile.rx * 32 : index % 32;
      const chunkZ = typeof columns.cz === "number" ? columns.cz - tile.rz * 32 : index >> 5;

      if (chunkX < 0 || chunkX >= 32 || chunkZ < 0 || chunkZ >= 32) {
        return;
      }

      const baseX = chunkX * 16;
      const baseZ = chunkZ * 16;

      for (let column = 0; column < 256; column++) {
        const top = columns.tops[column];

        if (top === NONE) {
          continue;
        }

        const at = (baseZ + (column >> 4)) * SIZE + baseX + (column & 15);

        tile.heights[at] = top;
        tile.blocks[at] = idOf(columns.names[column]);
        tile.depth[at] = columns.depth[column];

        if (columns.depth[column]) {
          tile.floors[at] = idOf(columns.floors[column] || "minecraft:air");
        }
      }
    }

    render(tile) {
      const canvas = document.createElement("canvas");

      canvas.width = SIZE;
      canvas.height = SIZE;

      const context = canvas.getContext("2d");
      const image = context.createImageData(SIZE, SIZE);
      const pixels = image.data;
      const heights = tile.heights;

      for (let z = 0; z < SIZE; z++) {
        for (let x = 0; x < SIZE; x++) {
          const at = z * SIZE + x;
          const out = at * 4;
          const height = heights[at];

          if (height === NONE) {
            continue;
          }

          let red;
          let green;
          let blue;
          const depth = tile.depth[at];

          if (depth) {
            const floor = AV.colors.of(names[tile.floors[at]]);
            const deep = Math.min(1, (depth - 1) / 8);
            const tint = AV.colors.mix(AV.colors.mix(floor, [46, 90, 196], 0.62), [16, 36, 104], deep * 0.85);

            [red, green, blue] = tint;
          } else {
            const base = AV.colors.of(names[tile.blocks[at]]);
            const north = z > 0 && heights[at - SIZE] !== NONE ? heights[at - SIZE] : height;
            const west = x > 0 && heights[at - 1] !== NONE ? heights[at - 1] : height;
            const shade = Math.max(0.5, Math.min(1.4, 1 + ((height - north) + (height - west)) * 0.08));

            red = base[0] * shade;
            green = base[1] * shade;
            blue = base[2] * shade;
          }

          pixels[out] = clampByte(red);
          pixels[out + 1] = clampByte(green);
          pixels[out + 2] = clampByte(blue);
          pixels[out + 3] = 255;
        }
      }

      context.putImageData(image, 0, 0);
      tile.canvas = canvas;
    }

    /** その位置の地形。読んでいなければ null。 */
    sample(x, z) {
      const rx = Math.floor(x / SIZE);
      const rz = Math.floor(z / SIZE);
      const tile = this.tile(rx, rz);

      if (!tile || tile.state !== "ready") {
        return null;
      }

      const at = (Math.floor(z) - rz * SIZE) * SIZE + (Math.floor(x) - rx * SIZE);
      const height = tile.heights[at];

      if (height === NONE) {
        return null;
      }

      return {
        y: height, block: names[tile.blocks[at]], depth: tile.depth[at],
        floor: tile.depth[at] ? names[tile.floors[at]] : null,
      };
    }
  }

  AV.terrain = new Terrain();
  AV.terrain.SIZE = SIZE;
})(window.AV = window.AV || {});
