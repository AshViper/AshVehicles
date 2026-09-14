/*
 * NBT と Anvil のリージョンファイルの読み手。ワールドの地形を上から見た絵にするためだけに要る分。
 *
 * リージョンファイル r.X.Z.mca は 32×32 チャンク。先頭 4KB がチャンクごとの場所（3バイトのセクタ番号と
 * 1バイトのセクタ数）、各チャンクは「長さ4バイト・圧縮の種類1バイト・本体」。1.21 の既定は zlib（種類2）。
 * 本体を展開すると NBT で、使うのは Heightmaps.MOTION_BLOCKING（列ごとの一番上の、動きを遮るか水を持つ
 * ブロックの上の高さ。9ビットずつ long に詰めてある）と sections（16ブロックごとの palette と data）。
 *
 * 配列はコピーしない。値としては「展開したバイト列の中の場所」だけを持ち、読むときにそこから取る。
 */
(function (AV) {
  "use strict";

  const decoder = new TextDecoder("utf-8");

  /** 水として扱うブロック。水の中に生える物は、上から見ればただの水。 */
  const WATERY = new Set([
    "minecraft:water", "minecraft:bubble_column", "minecraft:kelp", "minecraft:kelp_plant",
    "minecraft:seagrass", "minecraft:tall_seagrass",
  ]);

  class Reader {
    constructor(bytes) {
      this.bytes = bytes;
      this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      this.pos = 0;
    }

    u8() {
      return this.bytes[this.pos++];
    }

    u16() {
      const value = this.view.getUint16(this.pos);
      this.pos += 2;
      return value;
    }

    i32() {
      const value = this.view.getInt32(this.pos);
      this.pos += 4;
      return value;
    }

    text() {
      const length = this.u16();
      const value = decoder.decode(this.bytes.subarray(this.pos, this.pos + length));
      this.pos += length;
      return value;
    }
  }

  function payload(reader, tag) {
    const view = reader.view;

    switch (tag) {
      case 1: {
        const value = view.getInt8(reader.pos);
        reader.pos += 1;
        return value;
      }
      case 2: {
        const value = view.getInt16(reader.pos);
        reader.pos += 2;
        return value;
      }
      case 3:
        return reader.i32();
      case 4: {
        // long は精度を捨てて数にする。使う long の値（時刻）は無いので困らない。
        const value = view.getInt32(reader.pos) * 4294967296 + view.getUint32(reader.pos + 4);
        reader.pos += 8;
        return value;
      }
      case 5: {
        const value = view.getFloat32(reader.pos);
        reader.pos += 4;
        return value;
      }
      case 6: {
        const value = view.getFloat64(reader.pos);
        reader.pos += 8;
        return value;
      }
      case 7: {
        const length = reader.i32();
        const at = reader.pos;
        reader.pos += Math.max(0, length);
        return { bytes: at, length };
      }
      case 8:
        return reader.text();
      case 9: {
        const inner = reader.u8();
        const length = reader.i32();
        const list = new Array(Math.max(0, length));

        for (let i = 0; i < length; i++) {
          list[i] = payload(reader, inner);
        }

        return list;
      }
      case 10: {
        const compound = {};

        for (;;) {
          const inner = reader.u8();

          if (inner === 0) {
            return compound;
          }

          const name = reader.text();

          compound[name] = payload(reader, inner);
        }
      }
      case 11: {
        const length = reader.i32();
        const at = reader.pos;
        reader.pos += 4 * Math.max(0, length);
        return { ints: at, length };
      }
      case 12: {
        const length = reader.i32();
        const at = reader.pos;
        reader.pos += 8 * Math.max(0, length);
        return { longs: at, length, view };
      }
      default:
        throw new Error("NBT: unknown tag " + tag + " at " + reader.pos);
    }
  }

  /** 展開済みの NBT の根（名前付きの compound）。 */
  function readRoot(bytes) {
    const reader = new Reader(bytes);
    const tag = reader.u8();

    if (tag !== 10) {
      throw new Error("NBT: the root is not a compound");
    }

    reader.text();

    return payload(reader, tag);
  }

  /** long に bits ビットずつ詰めた値の index 番目。1つの long に floor(64 / bits) 個入る（1.16 以降の詰め方）。 */
  function packed(array, index, bits) {
    const perLong = Math.floor(64 / bits);
    const at = array.longs + 8 * Math.floor(index / perLong);
    const shift = (index % perLong) * bits;

    if (at + 8 > array.view.byteLength) {
      return 0;
    }

    const high = array.view.getUint32(at);
    const low = array.view.getUint32(at + 4);
    let value;

    if (shift >= 32) {
      value = high >>> (shift - 32);
    } else if (shift + bits <= 32) {
      value = low >>> shift;
    } else {
      value = (low >>> shift) | (high << (32 - shift));
    }

    return value & ((1 << bits) - 1);
  }

  /** チャンク本体を展開する。1 = gzip、2 = zlib、3 = 無圧縮。 */
  async function inflate(bytes, kind) {
    if (kind === 3) {
      return bytes;
    }

    const format = kind === 1 ? "gzip" : kind === 2 ? "deflate" : null;

    if (!format) {
      throw new Error("圧縮の種類 " + kind + " は読めない（LZ4 など）");
    }

    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(format));

    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  /** リージョンファイル1つの中のチャンク。展開はまだしない。 */
  function regionChunks(buffer) {
    const bytes = new Uint8Array(buffer);
    const view = new DataView(buffer);
    const chunks = [];

    if (bytes.length < 8192) {
      return chunks;
    }

    for (let index = 0; index < 1024; index++) {
      const entry = view.getUint32(index * 4);
      const sector = entry >>> 8;

      if (sector === 0 || (entry & 0xff) === 0) {
        continue;
      }

      const base = sector * 4096;

      if (base + 5 > bytes.length) {
        continue;
      }

      const length = view.getUint32(base);
      const kind = bytes[base + 4];

      // 1MB を超えるチャンクは外のファイル（.mcc）にある。地形の絵には1つ欠けても困らない。
      if (length < 1 || base + 4 + length > bytes.length || (kind & 0x80) !== 0) {
        continue;
      }

      chunks.push({ index, kind, bytes: bytes.subarray(base + 5, base + 4 + length) });
    }

    return chunks;
  }

  /**
   * チャンク1つの 16×16 列。一番上のブロックとその高さ、上が水なら水の深さと水底のブロック。
   *
   * @returns null（生成の途中か、高さの地図が無い）か
   *          { cx, cz, tops: Int16Array(256), names: string[], depth: Uint8Array(256), floors: string[] }
   *          列の番号は x + z × 16。一番上が無い列の高さは -32768
   */
  function chunkColumns(root) {
    if (root.Status !== undefined && root.Status !== "minecraft:full" && root.Status !== "full") {
      return null;
    }

    const maps = root.Heightmaps;
    const heights = maps && (maps.MOTION_BLOCKING || maps.WORLD_SURFACE);

    if (!heights || !heights.length) {
      return null;
    }

    const minY = (typeof root.yPos === "number" ? root.yPos : -4) * 16;
    const heightBits = Math.floor(64 / Math.ceil(256 / heights.length));
    const sections = new Map();

    for (const section of root.sections || []) {
      const states = section.block_states;

      if (states && states.palette && states.palette.length) {
        sections.set(section.Y, states);
      }
    }

    const blockAt = (x, y, z) => {
      const states = sections.get(Math.floor(y / 16));

      if (!states) {
        return "minecraft:air";
      }

      const palette = states.palette;

      if (palette.length === 1 || !states.data) {
        return palette[0].Name;
      }

      const bits = Math.max(4, Math.ceil(Math.log2(palette.length)));
      const block = palette[packed(states.data, ((y & 15) * 16 + z) * 16 + x, bits)];

      return block ? block.Name : "minecraft:air";
    };

    const tops = new Int16Array(256).fill(-32768);
    const names = new Array(256);
    const depth = new Uint8Array(256);
    const floors = new Array(256);

    for (let column = 0; column < 256; column++) {
      const value = packed(heights, column, heightBits);

      if (value === 0) {
        continue;
      }

      const x = column & 15;
      const z = column >> 4;
      const top = value + minY - 1;
      const name = blockAt(x, top, z);

      tops[column] = top;
      names[column] = name;

      if (WATERY.has(name)) {
        let under = 1;

        while (under < 64 && WATERY.has(blockAt(x, top - under, z))) {
          under++;
        }

        depth[column] = under;
        floors[column] = blockAt(x, top - under, z);
      }
    }

    return { cx: root.xPos, cz: root.zPos, tops, names, depth, floors };
  }

  AV.nbt = { readRoot, packed, inflate, regionChunks, chunkColumns, WATERY };
})(window.AV = window.AV || {});
