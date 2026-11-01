/*
 * Minecraft のワールドを学習器の会場にする。
 *
 *   <ワールド>/data/ashvehicles_deathmatch.dat   チームデスマッチの陣地・拠点・戦域（MatchState.save）
 *   <ワールド>/region/r.X.Z.mca                  地形（ディメンションは MatchState の World から）
 *
 * 陣地と拠点を囲む範囲のチャンクを読み、列ごとに「地面の高さ・視線を遮る面の高さ・その上にある物（林・建物・水）」を
 * 決めて、4ブロックのマス（sim/terrain.js の粒度）に畳む。
 *
 * <b>地面は上から掘って探す。</b> 高さ地図（MOTION_BLOCKING）は葉と屋根の上を答えるので、そこから下へ、自然の地面の
 * ブロックに当たるまで降りる。途中にあった物で林（原木・葉）か建物（それ以外の固い物）かを決める。ゲームの AI が
 * 読む高さ地図（battlefield/HeightField）と同じく張り出しは読み違える——橋の下は建物の中と数える。
 *
 * ページで動く（DecompressionStream と File System Access）。関数は ArrayBuffer を受け取る形にしてあるので Node でも試せる。
 */
(function (global) {
  "use strict";

  const CELL = 4;
  const NONE = -32768;
  const MOST_SIDE = 2048;
  const MARGIN = 160;

  // ------------------------------------------------------------------
  // NBT（long は BigInt で持つ。BlockPos.asLong は 64 ビットを全部使う）
  // ------------------------------------------------------------------

  const decoder = new TextDecoder("utf-8");

  function readNbt(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let pos = 0;
    const text = () => {
      const length = view.getUint16(pos);
      pos += 2;
      const value = decoder.decode(bytes.subarray(pos, pos + length));
      pos += length;
      return value;
    };
    const payload = (tag) => {
      switch (tag) {
        case 1: return view.getInt8(pos++);
        case 2: { const v = view.getInt16(pos); pos += 2; return v; }
        case 3: { const v = view.getInt32(pos); pos += 4; return v; }
        case 4: { const v = view.getBigInt64(pos); pos += 8; return v; }
        case 5: { const v = view.getFloat32(pos); pos += 4; return v; }
        case 6: { const v = view.getFloat64(pos); pos += 8; return v; }
        case 7: { const n = view.getInt32(pos); pos += 4; const at = pos; pos += Math.max(0, n); return { bytes: at, length: n }; }
        case 8: return text();
        case 9: {
          const inner = view.getUint8(pos++);
          const n = view.getInt32(pos);
          pos += 4;
          const list = [];
          for (let i = 0; i < n; i++) list.push(payload(inner));
          return list;
        }
        case 10: {
          const out = {};
          for (;;) {
            const inner = view.getUint8(pos++);
            if (inner === 0) return out;
            const name = text();
            out[name] = payload(inner);
          }
        }
        case 11: { const n = view.getInt32(pos); pos += 4; const at = pos; pos += 4 * Math.max(0, n); return { ints: at, length: n, view }; }
        case 12: { const n = view.getInt32(pos); pos += 4; const at = pos; pos += 8 * Math.max(0, n); return { longs: at, length: n, view }; }
        default: throw new Error("NBT: unknown tag " + tag);
      }
    };

    if (view.getUint8(pos++) !== 10) throw new Error("NBT: root is not a compound");

    text();

    return payload(10);
  }

  async function inflate(bytes, format) {
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream(format));

    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  /** BlockPos.of(long): x 26 ビット・z 26 ビット・y 12 ビット。 */
  function blockPos(value) {
    const v = BigInt.asIntN(64, BigInt(value));

    return {
      x: Number(v >> 38n),
      y: Number(BigInt.asIntN(12, v)),
      z: Number(BigInt.asIntN(26, v >> 12n)),
    };
  }

  function longAt(array, index) {
    return array.view.getBigInt64(array.longs + 8 * index);
  }

  /** long に bits ビットずつ詰めた値の index 番目（1.16 以降の詰め方）。 */
  function packed(array, index, bits) {
    const perLong = Math.floor(64 / bits);
    const at = array.longs + 8 * Math.floor(index / perLong);
    const shift = (index % perLong) * bits;

    if (at + 8 > array.view.byteLength) return 0;

    const high = array.view.getUint32(at);
    const low = array.view.getUint32(at + 4);
    let value;

    if (shift >= 32) value = high >>> (shift - 32);
    else if (shift + bits <= 32) value = low >>> shift;
    else value = (low >>> shift) | (high << (32 - shift));

    return value & ((1 << bits) - 1);
  }

  // ------------------------------------------------------------------
  // チームデスマッチの状態
  // ------------------------------------------------------------------

  /** ashvehicles_deathmatch.dat（gzip の NBT）から、陣地・拠点・戦域・ディメンション。 */
  async function readMatch(buffer) {
    const root = readNbt(await inflate(new Uint8Array(buffer), "gzip"));
    const data = root.data || root;
    const teams = [];

    for (const team of data.Teams || []) {
      const spawns = [];
      const list = team.Spawns;

      if (list && list.longs !== undefined) {
        for (let i = 0; i < list.length; i++) spawns.push(blockPos(longAt(list, i)));
      }

      teams.push({ id: team.Id, name: team.Name || team.Id, color: team.Color, spawns });
    }

    const points = (data.Points || []).map((point) => {
      const pos = blockPos(point.Pos);

      return {
        name: point.Name, x: pos.x + 0.5, y: pos.y, z: pos.z + 0.5, radius: point.Radius || 16,
        owner: point.Owner || null, home: point.Home || null,
      };
    });

    return {
      teams, points, arena: typeof data.Arena === "number" ? data.Arena : 0,
      dimension: data.World || "minecraft:overworld",
    };
  }

  // ------------------------------------------------------------------
  // 列の見分け
  // ------------------------------------------------------------------

  const WATERY = /^minecraft:(water|bubble_column|kelp|kelp_plant|seagrass|tall_seagrass)$/;
  const NATURAL = new RegExp("^minecraft:(grass_block|dirt|coarse_dirt|rooted_dirt|podzol|mycelium|mud|muddy_mangrove_roots|"
    + "clay|gravel|sand|red_sand|sandstone|red_sandstone|stone|deepslate|granite|diorite|andesite|tuff|calcite|"
    + "dripstone_block|pointed_dripstone|terracotta|[a-z_]+_terracotta|snow_block|powder_snow|ice|packed_ice|blue_ice|"
    + "moss_block|netherrack|basalt|smooth_basalt|blackstone|soul_sand|soul_soil|end_stone|bedrock|[a-z_]*_ore|"
    + "dirt_path|farmland|obsidian|magma_block|lava|amethyst_block|budding_amethyst|raw_[a-z]+_block|pale_moss_block)$");
  const WOODY = /^minecraft:([a-z_]+_(log|wood|leaves)|mangrove_roots|azalea|flowering_azalea|[a-z_]+_stem|nether_wart_block|warped_wart_block|shroomlight|brown_mushroom_block|red_mushroom_block|mushroom_stem|bamboo)$/;
  /** 車両も視線も止めない物。掘るときは飛ばす。 */
  const PASSABLE = /(^minecraft:(air|cave_air|void_air|short_grass|tall_grass|grass|fern|large_fern|dead_bush|snow|vine|glow_lichen|moss_carpet|pink_petals|leaf_litter|sugar_cane|wheat|carrots|potatoes|beetroots|sweet_berry_bush|cobweb|lily_pad|big_dripleaf|small_dripleaf|hanging_roots|spore_blossom|seagrass|tall_seagrass|kelp|kelp_plant|bush|firefly_bush|short_dry_grass|tall_dry_grass|cactus_flower|wildflowers)$)|flower|sapling|tulip|orchid|allium|bluet|daisy|poppy|dandelion|cornflower|lily_of|rose_bush|peony|lilac|sunflower|torch|carpet|_rail$|^minecraft:rail$|button|pressure_plate|_sign$|banner|flower_pot|lantern|mushroom$|fungus$|roots$|sprouts$/;

  const KIND = { OPEN: 0, WOOD: 1, BUILDING: 2, WATER: 3 };

  /** チャンク1つ（展開済みの NBT）の列を area へ書く。 */
  function absorbChunk(root, area) {
    if (root.Status !== undefined && root.Status !== "minecraft:full" && root.Status !== "full") return false;

    const maps = root.Heightmaps;
    const heights = maps && (maps.MOTION_BLOCKING || maps.WORLD_SURFACE);

    if (!heights || !heights.length) return false;

    const minY = (typeof root.yPos === "number" ? root.yPos : -4) * 16;
    const heightBits = Math.floor(64 / Math.ceil(256 / heights.length));
    const sections = new Map();

    for (const section of root.sections || []) {
      const states = section.block_states;

      if (states && states.palette && states.palette.length) sections.set(section.Y, states);
    }

    const blockAt = (x, y, z) => {
      const states = sections.get(Math.floor(y / 16));

      if (!states) return "minecraft:air";

      const palette = states.palette;

      if (palette.length === 1 || !states.data) return palette[0].Name;

      const bits = Math.max(4, Math.ceil(Math.log2(palette.length)));
      const block = palette[packed(states.data, (((y & 15) * 16) + z) * 16 + x, bits)];

      return block ? block.Name : "minecraft:air";
    };

    const baseX = root.xPos * 16;
    const baseZ = root.zPos * 16;

    for (let column = 0; column < 256; column++) {
      const x = column & 15;
      const z = column >> 4;
      const ax = baseX + x - area.x;
      const az = baseZ + z - area.z;

      if (ax < 0 || az < 0 || ax >= area.side || az >= area.side) continue;

      const value = packed(heights, column, heightBits);

      if (value === 0) continue;

      const at = az * area.side + ax;
      const top = value + minY - 1;
      let name = blockAt(x, top, z);

      if (WATERY.test(name)) {
        let under = 1;

        while (under < 64 && WATERY.test(blockAt(x, top - under, z))) under++;

        area.ground[at] = top - under + 1;
        area.top[at] = top + 1;
        area.depth[at] = under;
        area.kind[at] = under >= 2 ? KIND.WATER : KIND.OPEN;
        continue;
      }

      // 自然の地面まで掘る。途中の物で林か建物かを決める。
      let y = top;
      let wood = 0;
      let built = 0;

      for (let steps = 0; steps < 48 && !NATURAL.test(name); steps++) {
        if (WOODY.test(name)) wood++;
        else if (!PASSABLE.test(name) && !WATERY.test(name)) built++;

        y--;
        name = blockAt(x, y, z);
      }

      const found = NATURAL.test(name);
      const ground = found ? y + 1 : top + 1;
      const rise = top + 1 - ground;

      area.ground[at] = ground;
      area.top[at] = top + 1;
      area.depth[at] = 0;
      area.kind[at] = !found ? (built > 0 ? KIND.BUILDING : KIND.OPEN)
        : rise <= 1 ? KIND.OPEN : built > wood ? KIND.BUILDING : wood > 0 ? KIND.WOOD : KIND.OPEN;
    }

    return true;
  }

  // ------------------------------------------------------------------
  // リージョン
  // ------------------------------------------------------------------

  function regionChunks(buffer) {
    const bytes = new Uint8Array(buffer);
    const view = new DataView(buffer);
    const chunks = [];

    if (bytes.length < 8192) return chunks;

    for (let index = 0; index < 1024; index++) {
      const entry = view.getUint32(index * 4);
      const sector = entry >>> 8;

      if (sector === 0 || (entry & 0xff) === 0) continue;

      const base = sector * 4096;

      if (base + 5 > bytes.length) continue;

      const length = view.getUint32(base);
      const kind = bytes[base + 4];

      if (length < 1 || base + 4 + length > bytes.length || (kind & 0x80) !== 0) continue;

      chunks.push({ index, kind, bytes: bytes.subarray(base + 5, base + 4 + length) });
    }

    return chunks;
  }

  function regionFolder(dimension) {
    const [namespace, path] = dimension.includes(":") ? dimension.split(":") : ["minecraft", dimension];

    if (namespace === "minecraft" && path === "overworld") return ["region"];
    if (namespace === "minecraft" && path === "the_nether") return ["DIM-1", "region"];
    if (namespace === "minecraft" && path === "the_end") return ["DIM1", "region"];

    return ["dimensions", namespace, ...path.split("/"), "region"];
  }

  /**
   * 陣地と拠点から、読む範囲と会場の配置。陣地を持つ最初の2陣営を使う。
   */
  function layout(match, fallback) {
    const teams = match ? match.teams.filter((t) => t.spawns.length > 0).slice(0, 2) : [];
    let spawns;
    let points;

    if (teams.length === 2) {
      spawns = teams.map((t) => {
        const x = t.spawns.reduce((a, s) => a + s.x, 0) / t.spawns.length + 0.5;
        const z = t.spawns.reduce((a, s) => a + s.z, 0) / t.spawns.length + 0.5;

        return { x, z, team: t.id, name: t.name };
      });
      points = match.points;
    } else {
      // 陣地の無いワールド: 中心と一辺から、東西に陣地、その間に拠点3つ。
      const { x, z, side } = fallback;

      spawns = [{ x: x - side * 0.4, z, team: "a", name: "A" }, { x: x + side * 0.4, z, team: "b", name: "B" }];
      points = [-0.2, 0, 0.2].map((f, i) => ({ name: "P" + (i + 1), x: x + side * f, z, radius: 16, owner: null, home: null }));
    }

    const all = spawns.concat(points);
    const cx = all.reduce((a, p) => a + p.x, 0) / all.length;
    const cz = all.reduce((a, p) => a + p.z, 0) / all.length;
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity, farthest = 0;

    for (const p of all) {
      minX = Math.min(minX, p.x - (p.radius || 0));
      maxX = Math.max(maxX, p.x + (p.radius || 0));
      minZ = Math.min(minZ, p.z - (p.radius || 0));
      maxZ = Math.max(maxZ, p.z + (p.radius || 0));
      farthest = Math.max(farthest, Math.hypot(p.x - cx, p.z - cz));
    }

    let side = Math.max(maxX - minX, maxZ - minZ) + MARGIN * 2;

    side = Math.min(MOST_SIDE, Math.ceil(side / 64) * 64);

    const x = Math.floor((cx - side / 2) / CELL) * CELL;
    const z = Math.floor((cz - side / 2) / CELL) * CELL;
    // Bots.arenaRadius: 設定の半径（0 なら既定の 500）と、一番遠い旗・拠点 + 96 の大きい方。
    const arena = Math.max(match && match.arena > 0 ? match.arena : 500, farthest + 96);

    return { area: { x, z, side }, spawns, points, centreX: cx, centreZ: cz, arena };
  }

  /**
   * ワールドを読んで会場にする。
   *
   * @param source  { name, file(path) → Promise<File|null>, list(path) → Promise<string[]> }（パスは "/" 区切り）
   * @param options { dimension?, fallback: {x, z, side} }
   * @param progress (done, total, text) → void
   */
  async function build(source, options, progress) {
    const report = progress || (() => {});
    const datFile = await source.file("data/ashvehicles_deathmatch.dat");
    const match = datFile ? await readMatch(await datFile.arrayBuffer()) : null;
    const plan = layout(match, options.fallback || { x: 0, z: 0, side: 768 });
    const { area } = plan;
    const dimension = options.dimension || (match ? match.dimension : "minecraft:overworld");
    const folder = regionFolder(dimension).join("/");
    const side = area.side;
    const columns = {
      side, x: area.x, z: area.z,
      ground: new Float32Array(side * side).fill(NaN), top: new Float32Array(side * side).fill(NaN),
      depth: new Uint8Array(side * side), kind: new Uint8Array(side * side),
    };
    const regions = [];

    for (let rx = Math.floor(area.x / 512); rx <= Math.floor((area.x + side - 1) / 512); rx++) {
      for (let rz = Math.floor(area.z / 512); rz <= Math.floor((area.z + side - 1) / 512); rz++) {
        regions.push({ rx, rz });
      }
    }

    let chunksRead = 0;
    let done = 0;

    for (const { rx, rz } of regions) {
      report(done, regions.length, "r." + rx + "." + rz + ".mca");

      const file = await source.file(folder + "/r." + rx + "." + rz + ".mca");

      done++;

      if (!file) continue;

      const chunks = regionChunks(await file.arrayBuffer());
      const wanted = chunks.filter((chunk) => {
        const cx = rx * 32 + (chunk.index % 32);
        const cz = rz * 32 + (chunk.index >> 5);

        return cx * 16 + 16 > area.x && cx * 16 < area.x + side && cz * 16 + 16 > area.z && cz * 16 < area.z + side;
      });

      for (let i = 0; i < wanted.length; i += 16) {
        const part = wanted.slice(i, i + 16);
        const inflated = await Promise.all(part.map((chunk) => {
          const format = chunk.kind === 1 ? "gzip" : chunk.kind === 2 ? "deflate" : null;

          return chunk.kind === 3 ? Promise.resolve(chunk.bytes) : format ? inflate(chunk.bytes, format).catch(() => null) : null;
        }));

        for (const bytes of inflated) {
          if (!bytes) continue;

          try {
            if (absorbChunk(readNbt(bytes), columns)) chunksRead++;
          } catch (error) {
            // 壊れたチャンクは飛ばす。会場に穴が1つ開くだけ。
          }
        }

        report(done - 1 + Math.min(1, (i + 16) / wanted.length), regions.length, "r." + rx + "." + rz + ".mca");
      }
    }

    report(regions.length, regions.length, "マスに畳む");

    return fold(plan, columns, { name: source.name, dimension, chunks: chunksRead, hasMatch: !!match, teams: match ? match.teams : [] });
  }

  /** 列を4ブロックのマスへ。座標は範囲の北西の角を 0 にする。 */
  function fold(plan, columns, info) {
    const side = columns.side;
    const n = side / CELL;
    const ground = new Float32Array(n * n);
    const top = new Float32Array(n * n);
    const kind = new Uint8Array(n * n);
    let known = 0;

    for (let cz = 0; cz < n; cz++) {
      for (let cx = 0; cx < n; cx++) {
        let g = 0, t = 0, tMax = -Infinity, count = 0;
        const votes = [0, 0, 0, 0];

        for (let dz = 0; dz < CELL; dz++) {
          for (let dx = 0; dx < CELL; dx++) {
            const at = (cz * CELL + dz) * side + cx * CELL + dx;
            const value = columns.ground[at];

            if (value !== value) continue;

            count++;
            g += value;
            t += columns.top[at];
            tMax = Math.max(tMax, columns.top[at]);
            votes[columns.kind[at]]++;
          }
        }

        const i = cz * n + cx;

        if (count === 0) {
          // 読めなかった土地: 通れず、視線は分からない（未ロードは危ない側に倒す）。
          ground[i] = NaN;
          top[i] = NaN;
          kind[i] = KIND.BUILDING;
          continue;
        }

        known++;
        ground[i] = g / count;

        const share = (k) => votes[k] / count;

        if (share(KIND.BUILDING) >= 0.4) {
          kind[i] = KIND.BUILDING;
          top[i] = tMax;
        } else if (share(KIND.WATER) >= 0.5) {
          kind[i] = KIND.WATER;
          top[i] = t / count;
        } else if (share(KIND.WOOD) >= 0.4) {
          kind[i] = KIND.WOOD;
          top[i] = t / count;
        } else {
          kind[i] = KIND.OPEN;
          top[i] = Math.max(t / count, ground[i]);
        }
      }
    }

    // 読めなかったマスの地面は、測った地面の平均で埋める（高さの差で崖を作らない）。
    let mean = 0, samples = 0;

    for (let i = 0; i < n * n; i++) {
      if (ground[i] === ground[i]) { mean += ground[i]; samples++; }
    }

    mean = samples ? mean / samples : 64;

    for (let i = 0; i < n * n; i++) {
      if (ground[i] !== ground[i]) ground[i] = mean;
    }

    const local = (p) => ({ x: p.x - columns.x, z: p.z - columns.z });

    return {
      name: info.name,
      dimension: info.dimension,
      originX: columns.x,
      originZ: columns.z,
      size: side,
      n,
      ground, top, kind,
      known: known / (n * n),
      chunks: info.chunks,
      hasMatch: info.hasMatch,
      spawns: plan.spawns.map((s) => Object.assign(local(s), { name: s.name })),
      points: plan.points.map((p, i) => Object.assign(local(p), {
        id: p.name || "P" + (i + 1), radius: p.radius || 16,
        owner: p.owner && plan.spawns[0].team === p.owner ? 0 : p.owner && plan.spawns[1].team === p.owner ? 1 : null,
        home: p.home && plan.spawns[0].team === p.home ? 0 : p.home && plan.spawns[1].team === p.home ? 1 : null,
      })),
      centreX: plan.centreX - columns.x,
      centreZ: plan.centreZ - columns.z,
      arena: plan.arena,
    };
  }

  // ------------------------------------------------------------------
  // 読み元
  // ------------------------------------------------------------------

  /** showDirectoryPicker のフォルダから。 */
  function fromHandle(handle) {
    return {
      name: handle.name,
      async file(path) {
        let dir = handle;
        const parts = path.split("/");

        try {
          for (const part of parts.slice(0, -1)) dir = await dir.getDirectoryHandle(part);

          return await (await dir.getFileHandle(parts[parts.length - 1])).getFile();
        } catch (error) {
          return null;
        }
      },
    };
  }

  /** <input webkitdirectory> のファイル一覧から。先頭のフォルダ名を落として引く。 */
  function fromFiles(files) {
    const map = new Map();
    let name = "";

    for (const file of files) {
      const parts = (file.webkitRelativePath || file.name).split("/");

      name = parts[0];
      map.set(parts.slice(1).join("/"), file);
    }

    return { name, async file(path) { return map.get(path) || null; } };
  }

  /** このページを配っているサーバーの上のフォルダから（リポジトリの根で http.server を立てたとき: /run/saves/<ワールド>/）。 */
  function fromUrl(base) {
    const root = base.endsWith("/") ? base : base + "/";
    const parts = root.split("/").filter(Boolean);

    return {
      name: decodeURIComponent(parts[parts.length - 1] || "world"),
      async file(path) {
        const response = await fetch(root + path);

        return response.ok ? { arrayBuffer: () => response.arrayBuffer() } : null;
      },
    };
  }

  global.AVWorld = { build, readMatch, readNbt, blockPos, absorbChunk, regionChunks, layout, fold, fromHandle, fromFiles, fromUrl, KIND };
})(typeof window !== "undefined" ? window : globalThis);
