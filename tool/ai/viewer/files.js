/*
 * 開いたフォルダとファイルから、ビューアが読む物を見付けて振り分ける。
 *
 *   <ワールド>/ashvehicles_ai/map_memory/<名前空間>/<ディメンション>.json   覚えた地図
 *   <ワールド>/region/r.X.Z.mca（DIM-1/ DIM1/ dimensions/<ns>/<名前>/ も）  地形
 *   <ゲーム>/ashvehicles_ai/battles/<戦闘>/{meta,summary}.json {events,decisions,flights}.jsonl
 *   <ゲーム>/ashvehicles_ai/stats/<版>.json   exports/<版>.json   versions/<版>.json
 *
 * ファイルはまだ読まない。持つのは「読むための札」で、File（<input> やドラッグから）か FileSystemFileHandle
 * （「フォルダを開く…」から）のどちらか。読むときに {@link open} で今の中身を取る。
 *
 * <b>ハンドルで開いたフォルダは「最新に更新」で歩き直せる。</b> <input> の File はフォルダを選んだ瞬間の写しで、
 * その後にゲームが書き足すと Chrome は読むのを断る（NotReadableError）。進行中の戦闘を見るにはハンドルが要る。
 */
(function (AV) {
  "use strict";

  const listeners = new Map();

  AV.on = (name, handler) => {
    if (!listeners.has(name)) {
      listeners.set(name, []);
    }

    listeners.get(name).push(handler);
  };

  AV.emit = (name, value) => {
    for (const handler of listeners.get(name) || []) {
      handler(value);
    }
  };

  /** 入らないフォルダ。インスタンスを丸ごと開いたとき、MOD や設定の何千ものファイルを歩かない。 */
  const SKIP = new Set([
    "entities", "poi", "playerdata", "advancements", "datapacks", "mods", "config", "defaultconfigs",
    "resourcepacks", "shaderpacks", "logs", "crash-reports", "screenshots", "libraries", "kubejs",
    "journeymap", "xaero", "moddata", "backups", ".git", "node_modules", "assets", "natives",
  ]);

  const catalog = {
    memories: new Map(),
    worlds: new Map(),
    battles: new Map(),
    stats: new Map(),
    exports: new Map(),
  };

  /** 「最新に更新」で歩き直すフォルダ。 */
  const roots = [];

  const MEMORY = /^(?:(.*)\/)?ashvehicles_ai\/map_memory\/([^/]+)\/(.+)\.json$/;
  const MEMORY_BARE = /^(?:(.*)\/)?map_memory\/([^/]+)\/(.+)\.json$/;
  const REGION = /^(?:(.*)\/)?region\/r\.(-?\d+)\.(-?\d+)\.mca$/;
  const LOOSE_REGION = /^r\.(-?\d+)\.(-?\d+)\.mca$/;
  const BATTLE = /^(?:.*\/)?battles\/([^/]+)\/(meta\.json|summary\.json|events\.jsonl|decisions\.jsonl|flights\.jsonl)$/;
  const FIELDS = {
    "meta.json": "meta", "summary.json": "summary", "events.jsonl": "events", "decisions.jsonl": "decisions",
    "flights.jsonl": "flights",
  };
  const STATS = /^(?:.*\/)?ashvehicles_ai\/stats\/([^/]+)\.json$/;
  const EXPORTS = /^(?:.*\/)?ashvehicles_ai\/(?:exports|versions)\/([^/]+)\.json$/;
  const WANTED = /\.(json|jsonl|mca)$/;

  /** 札から、今の中身の File。 */
  async function open(ref) {
    if (!ref) {
      return null;
    }

    return typeof ref.getFile === "function" ? ref.getFile() : ref;
  }

  function lastPart(path) {
    const parts = (path || "").split("/").filter(Boolean);

    return parts.length ? parts[parts.length - 1] : "（ワールド）";
  }

  /** region/ の入っているフォルダから、ワールドとディメンション。 */
  function regionPlace(root) {
    const parts = (root || "").split("/").filter(Boolean);
    const last = parts[parts.length - 1];

    if (last === "DIM-1") {
      return { world: parts.slice(0, -1).join("/"), dimension: "minecraft:the_nether" };
    }

    if (last === "DIM1") {
      return { world: parts.slice(0, -1).join("/"), dimension: "minecraft:the_end" };
    }

    const at = parts.lastIndexOf("dimensions");

    if (at >= 0 && parts.length - at >= 3) {
      return { world: parts.slice(0, at).join("/"), dimension: parts[at + 1] + ":" + parts.slice(at + 2).join("/") };
    }

    return { world: parts.join("/"), dimension: "minecraft:overworld" };
  }

  function world(root) {
    let entry = catalog.worlds.get(root);

    if (!entry) {
      entry = { key: root, name: lastPart(root), dimensions: new Map() };
      catalog.worlds.set(root, entry);
    }

    return entry;
  }

  function battle(id) {
    let entry = catalog.battles.get(id);

    if (!entry) {
      entry = { id, meta: null, summary: null, events: null, decisions: null, flights: null };
      catalog.battles.set(id, entry);
    }

    return entry;
  }

  function addRegion(root, rx, rz, ref) {
    const place = regionPlace(root);
    const target = world(place.world);

    if (!target.dimensions.has(place.dimension)) {
      target.dimensions.set(place.dimension, new Map());
    }

    target.dimensions.get(place.dimension).set(rx + "," + rz, ref);
  }

  function addMemory(root, namespace, name, ref, path) {
    const dimension = namespace + ":" + name;
    const key = (root || "") + "|" + dimension;

    catalog.memories.set(key, { key, world: root || "", worldName: lastPart(root), dimension, file: ref, path });
  }

  /** 決まった形のパスなら振り分けて true。 */
  function classify(path, ref) {
    let match;

    if ((match = path.match(MEMORY))) {
      addMemory(match[1], match[2], match[3], ref, path);
    } else if ((match = path.match(MEMORY_BARE))) {
      addMemory(match[1], match[2], match[3], ref, path);
    } else if ((match = path.match(REGION))) {
      addRegion(match[1], Number(match[2]), Number(match[3]), ref);
    } else if ((match = path.match(BATTLE))) {
      battle(match[1])[FIELDS[match[2]]] = ref;
    } else if ((match = path.match(STATS))) {
      catalog.stats.set(match[1], ref);
    } else if ((match = path.match(EXPORTS))) {
      catalog.exports.set(match[1], ref);
    } else if ((match = path.match(LOOSE_REGION))) {
      addRegion("", Number(match[1]), Number(match[2]), ref);
    } else {
      return false;
    }

    return true;
  }

  /** パスからは分からない JSON。中身の頭を読んで当てる。 */
  async function sniff(ref) {
    const file = await open(ref);
    const head = await file.slice(0, 4096).text();
    const name = file.name;

    if (name.endsWith(".jsonl")) {
      const id = (head.match(/"battle":"([^"]+)"/) || [])[1];

      if (!id) {
        return;
      }

      if (head.includes('"features"')) {
        battle(id).decisions = ref;
      } else if (head.includes('"heading"') && head.includes('"speed"')) {
        battle(id).flights = ref;
      } else if (head.includes('"type"')) {
        battle(id).events = ref;
      }

      return;
    }

    if (head.includes('"columns"') && head.includes('"cells"')) {
      addMemory("", "minecraft", name.replace(/\.json$/, ""), ref, name);
    } else if (head.includes('"start_tick"') && head.includes('"points"')) {
      const id = (head.match(/"battle":\s*"([^"]+)"/) || [])[1] || name;

      battle(id).meta = ref;
    } else if (head.includes('"lives"') && head.includes('"winner"')) {
      const id = (head.match(/"battle":\s*"([^"]+)"/) || [])[1] || name;

      battle(id).summary = ref;
    } else if (head.includes('"overall"') && head.includes('"versus"')) {
      const version = (head.match(/"version":\s*"([^"]+)"/) || [])[1] || name.replace(/\.json$/, "");

      catalog.stats.set(version, ref);
    }
  }

  /** {path, file} の並びを足す。file は File か FileSystemFileHandle。 */
  async function add(entries) {
    const unknown = [];

    for (const { path, file } of entries) {
      const clean = path.replace(/\\/g, "/");

      if (!classify(clean, file) && /\.jsonl?$/.test(clean) && !clean.includes("/")) {
        unknown.push(file);
      }
    }

    for (const ref of unknown) {
      await sniff(ref);
    }

    AV.emit("files", catalog);
  }

  /** <input type=file> の FileList から。フォルダを選んだときは webkitRelativePath にフォルダからのパスがある。 */
  function addFileList(list) {
    return add(Array.from(list, (file) => ({ path: file.webkitRelativePath || file.name, file })));
  }

  function skipped(name, prefix) {
    const insideAi = prefix.includes("ashvehicles_ai/");

    return SKIP.has(name) || ((name === "versions" || name === "stats") && !insideAi);
  }

  async function walkHandle(directory, prefix, entries, onWalk) {
    for await (const [name, child] of directory.entries()) {
      if (child.kind === "file") {
        if (WANTED.test(name)) {
          entries.push({ path: prefix + name, file: child });
        }
      } else if (!skipped(name, prefix)) {
        if (onWalk) {
          onWalk(prefix + name, entries.length);
        }

        await walkHandle(child, prefix + name + "/", entries, onWalk);
      }
    }
  }

  async function remember(handle) {
    for (const root of roots) {
      if (await root.isSameEntry(handle)) {
        return;
      }
    }

    roots.push(handle);
  }

  /** 「フォルダを開く…」（showDirectoryPicker）で開いたフォルダから。 */
  async function addDirectoryHandle(handle, onWalk) {
    const entries = [];

    await remember(handle);
    await walkHandle(handle, handle.name + "/", entries, onWalk);

    return add(entries);
  }

  /** ハンドルで開いたフォルダを全部歩き直す。新しい戦闘と、書き足された記録を拾う。 */
  async function refresh(onWalk) {
    const entries = [];

    for (const root of roots) {
      await walkHandle(root, root.name + "/", entries, onWalk);
    }

    return add(entries);
  }

  function entryFile(entry) {
    return new Promise((resolve, reject) => entry.file(resolve, reject));
  }

  function entryChildren(entry) {
    const reader = entry.createReader();
    const all = [];

    return new Promise((resolve, reject) => {
      const next = () => reader.readEntries((batch) => {
        if (!batch.length) {
          resolve(all);
        } else {
          all.push(...batch);
          next();
        }
      }, reject);

      next();
    });
  }

  /**
   * ドラッグで落とされたファイルとフォルダから。取れればハンドルで（「最新に更新」が効く）、取れなければ古い
   * エントリーの形で歩く。<b>どちらの取り出し方も drop の中で同期に呼ばないと空になる</b>ので、先に両方を頼んでおく。
   */
  async function addDataTransfer(transfer, onWalk) {
    const items = Array.from(transfer.items || []).filter((item) => item.kind === "file");
    const handles = items.map((item) => (typeof item.getAsFileSystemHandle === "function"
        ? item.getAsFileSystemHandle().catch(() => null) : null));
    const legacy = items.map((item) => (typeof item.webkitGetAsEntry === "function" ? item.webkitGetAsEntry() : null));
    const files = Array.from(transfer.files || []);
    const resolved = (await Promise.all(handles)).filter(Boolean);

    if (resolved.length) {
      const entries = [];

      for (const handle of resolved) {
        if (handle.kind === "directory") {
          await remember(handle);
          await walkHandle(handle, handle.name + "/", entries, onWalk);
        } else if (WANTED.test(handle.name)) {
          entries.push({ path: handle.name, file: handle });
        }
      }

      return add(entries);
    }

    const tops = legacy.filter(Boolean);

    if (!tops.length) {
      return addFileList(files);
    }

    const entries = [];
    const walk = async (entry, prefix) => {
      if (entry.isFile) {
        if (WANTED.test(entry.name)) {
          entries.push({ path: prefix + entry.name, file: await entryFile(entry) });
        }

        return;
      }

      if (skipped(entry.name, prefix)) {
        return;
      }

      const children = await entryChildren(entry);

      if (onWalk) {
        onWalk(prefix + entry.name, entries.length);
      }

      for (const child of children) {
        await walk(child, prefix + entry.name + "/");
      }
    };

    for (const top of tops) {
      await walk(top, "");
    }

    return add(entries);
  }

  AV.files = {
    catalog, open, add, addFileList, addDataTransfer, addDirectoryHandle, refresh, regionPlace,
    canRefresh: () => roots.length > 0,
  };
})(window.AV = window.AV || {});
