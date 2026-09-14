/*
 * 組み立て。読み込み・最新への更新・選択・レイヤーの操作・再生・地図の上の吹き出し。
 */
(function (AV) {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const settings = AV.map.settings;
  const short = (id) => String(id || "").replace(/^[^:]*:/, "");

  /** 自動更新の間隔（ミリ秒）。 */
  const AUTO_EVERY = 10000;

  const state = AV.state = {
    memoryKey: null, memoryEntry: null, memory: null,
    headers: [], battleId: null, detail: null, loadingBattle: false,
    stats: new Map(), metrics: new Map(), counting: false,
    terrainBusy: false, info: null, refreshing: false, filesTask: Promise.resolve(),
  };

  let headersKey = "";
  let replaying = false;
  let lastFrame = 0;
  let terrainTimer = null;
  let autoTimer = null;

  // ------------------------------------------------------------------
  // 進み具合
  // ------------------------------------------------------------------

  function progress(fraction, label) {
    const box = $("progress");

    if (fraction === null) {
      box.hidden = true;

      return;
    }

    box.hidden = false;
    box.querySelector(".bar").style.width = Math.round(Math.max(0, Math.min(1, fraction)) * 100) + "%";
    box.querySelector(".label").textContent = label;
  }

  function loaded() {
    $("empty").style.display = state.memory || state.headers.length || AV.terrain.tiles.size ? "none" : "";
  }

  /** 読めなかった理由を出す。<input> で開いた後にゲームが書き換えたファイルは、開き直さない限り読めない。 */
  function problem(error, what) {
    console.error(error);

    const stale = error && error.name === "NotReadableError";

    progress(1, what + ": " + (stale ? "開いた後にゲームが書き換えた。「最新に更新」を押すか、フォルダを開き直す"
        : error && error.message ? error.message : String(error)));
  }

  const walkProgress = (path, count) => progress(0, "歩いている: " + path + "（" + count + "）");

  // ------------------------------------------------------------------
  // 読み込み
  // ------------------------------------------------------------------

  async function load(adding) {
    progress(0, "ファイルを探している…");

    try {
      await adding();
      await state.filesTask;
    } catch (error) {
      problem(error, "読めなかった");
      refreshControls();

      return;
    }

    progress(null);
    refreshControls();
  }

  $("open-folder").addEventListener("click", async () => {
    if (typeof window.showDirectoryPicker !== "function") {
      $("pick-folder").click();

      return;
    }

    let handle;

    try {
      handle = await window.showDirectoryPicker({ id: "ashvehicles-ai-viewer", mode: "read" });
    } catch (error) {
      if (error && error.name === "AbortError") {
        return;
      }

      // file:// を安全な場所と見なさないブラウザなど。古い選び方へ。
      console.warn("showDirectoryPicker", error);
      $("pick-folder").click();

      return;
    }

    await load(() => AV.files.addDirectoryHandle(handle, walkProgress));
  });

  $("pick-folder").addEventListener("change", async (event) => {
    const files = event.target.files;

    await load(() => AV.files.addFileList(files));
    event.target.value = "";
  });

  $("pick-files").addEventListener("change", async (event) => {
    const files = event.target.files;

    await load(() => AV.files.addFileList(files));
    event.target.value = "";
  });

  const stage = $("stage");

  document.addEventListener("dragover", (event) => event.preventDefault());
  document.addEventListener("drop", (event) => event.preventDefault());
  stage.addEventListener("dragover", (event) => {
    event.preventDefault();
    stage.classList.add("drop");
    $("empty").style.display = "";
  });
  stage.addEventListener("dragleave", () => {
    stage.classList.remove("drop");
    loaded();
  });
  stage.addEventListener("drop", async (event) => {
    event.preventDefault();
    stage.classList.remove("drop");

    const reading = AV.files.addDataTransfer(event.dataTransfer, walkProgress);

    await load(() => reading);
    loaded();
  });

  function refreshControls() {
    const can = AV.files.canRefresh();

    $("refresh").disabled = !can || state.refreshing;
    $("auto-refresh").disabled = !can;

    if (!can && $("auto-refresh").checked) {
      $("auto-refresh").checked = false;
      clearInterval(autoTimer);
      autoTimer = null;
    }

    $("refresh-hint").textContent = can ? ""
        : typeof window.showDirectoryPicker === "function"
            ? "「フォルダを開く…」かドラッグで開くと、ゲームが書き足した記録をここから読み直せる。"
            : "このブラウザではフォルダを開き直すと最新になる（Chrome か Edge なら「最新に更新」が使える）。";
  }

  AV.on("files", (catalog) => {
    state.filesTask = onFiles(catalog).catch((error) => problem(error, "読めなかった"));
  });

  async function onFiles(catalog) {
    showFound(catalog);
    await fillMemories(catalog);
    fillWorlds(catalog);
    await Promise.all([readHeaders(catalog, false), readStats(catalog)]);
    loaded();
  }

  function showFound(catalog) {
    let regions = 0;
    let flights = 0;

    for (const world of catalog.worlds.values()) {
      for (const files of world.dimensions.values()) {
        regions += files.size;
      }
    }

    for (const entry of catalog.battles.values()) {
      flights += entry.flights ? 1 : 0;
    }

    $("found").innerHTML = "<dt>覚えた地図</dt><dd>" + catalog.memories.size + "</dd>"
        + "<dt>地形</dt><dd>" + regions + " リージョン</dd>"
        + "<dt>戦闘</dt><dd>" + catalog.battles.size + (flights ? "（航空機の跡 " + flights + "）" : "") + "</dd>"
        + "<dt>版の戦績</dt><dd>" + catalog.stats.size + "</dd>";
  }

  async function fillMemories(catalog) {
    const select = $("memory-select");
    const keys = Array.from(catalog.memories.keys());

    select.innerHTML = keys.length ? keys.map((key) => {
      const entry = catalog.memories.get(key);

      return "<option value=\"" + key.replace(/"/g, "&quot;") + "\">" + entry.worldName + " / " + entry.dimension + "</option>";
    }).join("") : "<option value=\"\">（無い）</option>";

    if (!keys.length) {
      return;
    }

    const wanted = keys.includes(state.memoryKey) ? state.memoryKey : keys[0];

    select.value = wanted;

    if (wanted !== state.memoryKey) {
      await selectMemory(wanted, false);
    }
  }

  function fillWorlds(catalog) {
    const select = $("world-select");
    const options = [];

    for (const world of catalog.worlds.values()) {
      for (const [dimension, files] of world.dimensions) {
        options.push({ value: world.key + "|" + dimension, label: world.name + " / " + dimension + "（" + files.size + "）", world, dimension });
      }
    }

    select.innerHTML = options.length ? options.map((option) => "<option value=\"" + option.value.replace(/"/g, "&quot;") + "\">"
        + option.label + "</option>").join("") : "<option value=\"\">（無い）</option>";

    if (!options.length) {
      return;
    }

    // 覚えた地図と同じワールドとディメンションを選ぶ。無ければ、今選んでいる物か先頭。
    const entry = state.memoryEntry;
    const match = entry && options.find((option) => option.value === entry.world + "|" + entry.dimension);
    const current = options.find((option) => option.value === AV.terrain.key);
    const chosen = match || current || options[0];

    select.value = chosen.value;
    selectWorld(chosen.value);
  }

  function selectWorld(value) {
    const [worldKey, dimension] = value.split("|");
    const world = AV.files.catalog.worlds.get(worldKey);
    const files = world ? world.dimensions.get(dimension) : null;

    AV.terrain.setSource(value, files);
    scheduleTerrain();
  }

  $("memory-select").addEventListener("change", (event) => selectMemory(event.target.value, false));
  $("world-select").addEventListener("change", (event) => selectWorld(event.target.value));

  /**
   * 覚えた地図を読む。
   *
   * @param keepView 最新に更新するとき。表示は動かさず、変わっていなければ何もしない
   */
  async function selectMemory(key, keepView) {
    const entry = AV.files.catalog.memories.get(key);

    if (!entry) {
      return;
    }

    state.memoryKey = key;
    state.memoryEntry = entry;

    if (!keepView) {
      progress(0.2, "覚えた地図を読んでいる…");
    }

    let memory;

    try {
      memory = await AV.data.readMemory(entry.file);
    } catch (error) {
      problem(error, "覚えた地図を読めなかった");

      return;
    }

    if (keepView && state.memory && state.memory.modified === memory.modified && state.memory.size === memory.size) {
      return;
    }

    state.memory = memory;
    AV.map.setMemory(memory);

    if (!keepView) {
      progress(null);
      fitMemory();
      fillWorlds(AV.files.catalog);
    }

    AV.panels.memory();

    if (state.info) {
      state.info.index = AV.data.cellAt(memory, state.info.world.x, state.info.world.z);
      AV.panels.place(state.info);
    }

    loaded();
  }

  function fitMemory() {
    const memory = state.memory;

    if (memory && memory.bounds) {
      const cell = memory.cell;

      AV.map.fit(memory.bounds.minX * cell, memory.bounds.minZ * cell, (memory.bounds.maxX + 1) * cell, (memory.bounds.maxZ + 1) * cell);
    } else if (state.detail) {
      fitBattle();
    }
  }

  /** 選んだ戦闘の全部（地上の跡・飛んだ跡・拠点・戦域）が入る大きさ。 */
  function fitBattle() {
    const detail = state.detail;

    if (!detail) {
      fitMemory();

      return;
    }

    let minX = Infinity;
    let minZ = Infinity;
    let maxX = -Infinity;
    let maxZ = -Infinity;
    const include = (x, z) => {
      if (typeof x !== "number" || typeof z !== "number") {
        return;
      }

      minX = Math.min(minX, x);
      minZ = Math.min(minZ, z);
      maxX = Math.max(maxX, x);
      maxZ = Math.max(maxZ, z);
    };

    for (const bot of detail.bots) {
      for (const sample of bot.samples) {
        include(sample.x, sample.z);
      }
    }

    for (const point of detail.header.points) {
      include(point.x, point.z);
    }

    if (detail.header.arena && detail.header.arena.radius) {
      const arena = detail.header.arena;

      include(arena.x - arena.radius, arena.z - arena.radius);
      include(arena.x + arena.radius, arena.z + arena.radius);
    }

    if (minX !== Infinity) {
      AV.map.fit(minX, minZ, maxX, maxZ);
    }
  }

  async function readHeaders(catalog, force) {
    const ids = Array.from(catalog.battles.keys()).sort();
    const key = ids.map((id) => {
      const entry = catalog.battles.get(id);

      return id + (entry.meta ? "m" : "") + (entry.summary ? "s" : "") + (entry.events ? "e" : "") + (entry.decisions ? "d" : "")
          + (entry.flights ? "f" : "");
    }).join(",");

    if (key === headersKey && !force) {
      return;
    }

    headersKey = key;

    const headers = await Promise.all(ids.map((id) => AV.data.readHeader(catalog.battles.get(id)).catch(() => null)));

    state.headers = headers.filter(Boolean).sort((a, b) => String(b.started).localeCompare(String(a.started)));
    AV.panels.battles();
    AV.panels.growth();
    loaded();
  }

  async function readStats(catalog) {
    for (const [version, ref] of catalog.stats) {
      try {
        state.stats.set(version, await AV.data.readJson(ref));
      } catch (error) {
        // 壊れた戦績は飛ばす。
      }
    }

    AV.panels.growth();
  }

  // ------------------------------------------------------------------
  // 最新に更新
  // ------------------------------------------------------------------

  /**
   * 開いたフォルダを歩き直し、新しい戦闘・書き足された記録・締め直された覚えた地図を読み直す。地形は読み直さない
   * （試合の間に変わるのは柵と木くらいで、1つ数秒かかる）。
   *
   * @param quiet 自動更新から。進み具合を出さない
   */
  async function refreshLatest(quiet) {
    if (!AV.files.canRefresh() || state.refreshing) {
      return;
    }

    state.refreshing = true;
    refreshControls();

    let failed = false;
    const unfinished = new Set(state.headers.filter((header) => !header.finished).map((header) => header.id));

    try {
      if (!quiet) {
        progress(0, "最新を探している…");
      }

      await AV.files.refresh(quiet ? null : walkProgress);
      await state.filesTask;
      await readHeaders(AV.files.catalog, true);

      // 進行中だった戦闘の数えた数は古い。
      for (const header of state.headers) {
        if (!header.finished || unfinished.has(header.id)) {
          state.metrics.delete(header.id);
        }
      }

      if (state.memoryKey) {
        await selectMemory(state.memoryKey, true);
      }

      await readStats(AV.files.catalog);

      if (state.battleId) {
        await reloadBattle();
      }

      AV.panels.growth();
      $("refreshed").textContent = "最新 " + new Date().toLocaleTimeString();
    } catch (error) {
      failed = true;
      problem(error, "最新にできなかった");
    } finally {
      state.refreshing = false;

      if (!quiet && !failed) {
        progress(null);
      }

      refreshControls();
    }
  }

  $("refresh").addEventListener("click", () => refreshLatest(false));
  $("auto-refresh").addEventListener("change", (event) => {
    clearInterval(autoTimer);
    autoTimer = event.target.checked ? setInterval(() => refreshLatest(true), AUTO_EVERY) : null;
  });

  /** 選んでいる戦闘を読み直す。再生の位置は保ち、終わりにいたなら新しい終わりへ進める。 */
  async function reloadBattle() {
    const header = state.headers.find((item) => item.id === state.battleId);

    if (!header) {
      return;
    }

    const wasAtEnd = state.detail && settings.time !== null && settings.time >= state.detail.t1;
    const detail = await AV.data.readDetail(header);

    if (state.battleId !== header.id) {
      return;
    }

    state.detail = detail;
    AV.map.setDetail(detail);

    const slider = $("time");

    slider.min = detail.t0;
    slider.max = detail.t1;

    if (settings.time !== null) {
      settings.time = wasAtEnd && !replaying ? detail.t1 : Math.min(settings.time, detail.t1);
      slider.value = settings.time;
    } else {
      slider.value = detail.t1;
    }

    timeLabel();
    AV.panels.battles();
  }

  // ------------------------------------------------------------------
  // 地形
  // ------------------------------------------------------------------

  function scheduleTerrain() {
    clearTimeout(terrainTimer);
    terrainTimer = setTimeout(() => readVisibleTerrain(4, false), 450);
  }

  async function readVisibleTerrain(limit, forced) {
    if (state.terrainBusy || !AV.terrain.count() || (!settings.showTerrain && !forced)) {
      return;
    }

    const bounds = AV.map.visibleBounds();
    const size = AV.terrain.SIZE;
    const across = (Math.floor(bounds.maxX / size) - Math.floor(bounds.minX / size) + 1)
        * (Math.floor(bounds.maxZ / size) - Math.floor(bounds.minZ / size) + 1);

    // 引きすぎ。数十のリージョンを勝手に読まない。
    if (!forced && across > 16) {
      return;
    }

    state.terrainBusy = true;

    let result;

    try {
      result = await AV.terrain.request(bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ, limit);
    } finally {
      state.terrainBusy = false;
      progress(null);
    }

    loaded();

    if (result && result.loaded && result.waiting) {
      readVisibleTerrain(limit, forced);
    }
  }

  $("load-terrain").addEventListener("click", () => readVisibleTerrain(24, true));

  AV.on("terrain-progress", (tile) => {
    progress(tile.done, "地形 r." + tile.rx + "." + tile.rz + ".mca を読んでいる " + Math.round(tile.done * 100) + "%");
    AV.map.request();
  });

  AV.on("terrain", (tile) => {
    if (tile && tile.state === "error") {
      console.warn("terrain", tile.rx, tile.rz, tile.error);
    }

    // 地形を読む前にクリックした場所は「まだ読んでいない」と出ている。読み終えたら出し直す。
    if (tile && tile.state === "ready" && state.info) {
      AV.panels.place(state.info);
    }

    AV.map.request();
  });

  // ------------------------------------------------------------------
  // レイヤー
  // ------------------------------------------------------------------

  $("learned-mode").innerHTML = AV.map.MODES.map(([value, label]) => "<label><input type=\"radio\" name=\"learned\" value=\""
      + value + "\"" + (value === settings.learnedMode ? " checked" : "") + ">" + label + "</label>").join("");
  $("learned-mode").addEventListener("change", (event) => {
    settings.learnedMode = event.target.value;
    legend();
    AV.map.request();
  });

  const checks = {
    "show-terrain": "showTerrain", "fade-unsure": "fadeUnsure", "show-points": "showPoints", "show-anchor": "showAnchor",
    "show-tracks": "showTracks", "show-aircraft": "showAircraft", "show-stuck": "showStuck", "show-water": "showWater",
    "show-deaths": "showDeaths", "show-releases": "showReleases", "show-kills": "showKills", "show-captures": "showCaptures",
    "replay-trail": "trail",
  };

  for (const [id, key] of Object.entries(checks)) {
    $(id).addEventListener("change", (event) => {
      settings[key] = event.target.checked;

      if (key === "showTerrain" && settings.showTerrain) {
        scheduleTerrain();
      }

      AV.map.request();
    });
  }

  $("terrain-light").addEventListener("input", (event) => {
    settings.terrainLight = Number(event.target.value) / 100;
    AV.map.request();
  });
  $("learned-alpha").addEventListener("input", (event) => {
    settings.learnedAlpha = Number(event.target.value) / 100;
    AV.map.request();
  });

  for (const name of ["trap", "water", "death", "flow", "risk"]) {
    $("w-" + name).addEventListener("input", (event) => {
      const value = Number(event.target.value);

      if (Number.isFinite(value)) {
        settings.weights = Object.assign({}, settings.weights, { [name]: value });
        AV.map.request();

        if (state.info) {
          AV.panels.place(state.info);
        }
      }
    });
  }

  function legend() {
    const mode = settings.learnedMode;
    const ramps = {
      trap: ["rgba(255,72,58,0)", "rgb(255,72,58)", "詰まった割合"],
      water: ["rgba(74,152,255,0)", "rgb(74,152,255)", "沈んだ割合"],
      death: ["rgba(206,98,255,0)", "rgb(206,98,255)", "倒された割合"],
      flow: ["rgba(92,222,112,0)", "rgb(92,222,112)", "すんなり抜けた割合"],
    };
    const row = (swatch, label) => "<div class=\"legend-row\">" + swatch + label + "</div>";
    let html = "<h3>覚えた地図</h3>";

    if (mode === "combined") {
      html += [["var(--trap)", "詰まる"], ["var(--water)", "水に落ちる"], ["var(--death)", "倒される"], ["var(--flow)", "抜けやすい"]]
          .map(([color, label]) => row("<span class=\"swatch\" style=\"background:" + color + "\"></span>", label)).join("")
          + "<p class=\"hint\">色が濃いほど、そこで地上の AI がそうなった割合が高い。</p>";
    } else if (ramps[mode]) {
      html += "<div class=\"legend-ramp\" style=\"background:linear-gradient(90deg," + ramps[mode][0] + "," + ramps[mode][1]
          + ")\"></div><div class=\"legend-scale\"><span>0</span><span>" + ramps[mode][2] + "</span><span>1</span></div>";
    } else if (mode === "cost") {
      html += "<div class=\"legend-ramp\" style=\"background:linear-gradient(90deg,#46c85a,#f0dc3c,#ff7828,#ff3232)\"></div>"
          + "<div class=\"legend-scale\"><span>0</span><span>+2</span><span>+4</span><span>+6以上</span></div>"
          + "<p class=\"hint\">道の1歩に足す倍率。薄い緑は負（AI が少し好む所）。</p>";
    } else {
      html += "<div class=\"legend-ramp\" style=\"background:linear-gradient(90deg,#3c2878,#2878b4,#46c878,#fae650)\"></div>"
          + "<div class=\"legend-scale\"><span>少ない</span><span>入った回数（対数）</span><span>多い</span></div>";
    }

    html += "<h3>地上の車両</h3>"
        + row("<span class=\"swatch round\" style=\"background:#ff6b5e\"></span><span class=\"swatch round\" style=\"background:#5ea8ff\"></span>", "跡と再生中の位置（陣営）")
        + row("<span class=\"swatch round\" style=\"border:2px solid #4f9dff;background:none\"></span>", "水に入った")
        + row("<span class=\"swatch\" style=\"background:#ff5a4f;clip-path:polygon(50% 0,100% 100%,0 100%)\"></span>", "抜け出し（来た道を戻った）")
        + row("<span class=\"swatch\" style=\"background:#f2ffd9;clip-path:polygon(50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%)\"></span>", "敵を撃破した（撃った側の位置）")
        + row("<span class=\"swatch\" style=\"background:none;border:0;color:#d06cff;font-weight:700;line-height:14px;text-align:center\">✕</span>", "撃破された")
        + row("<span class=\"swatch\" style=\"background:#ffcf4d;clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%)\"></span>", "制圧・奪還")
        + row("<span class=\"swatch round\" style=\"background:rgba(255,207,77,.2);border-color:#ffcf4d\"></span>", "拠点（内側の点線は居座る円、外の点線の円は戦域）")
        + "<h3>航空機（跡の色は段階）</h3>"
        + [["TRANSIT", "待機・移動"], ["RUN", "航過（攻撃）"], ["EXTEND", "離脱"], ["SPENT", "弾切れ・帰投"]]
            .map(([phase, label]) => row("<span class=\"swatch line\" style=\"background:" + AV.map.PHASE[phase] + "\"></span>", label)).join("")
        + row("<span class=\"swatch line\" style=\"background:" + AV.map.EVADE + "\"></span>", "ミサイル回避")
        + row("<span class=\"swatch round\" style=\"background:#ff9f43\"></span>", "爆弾を落とした")
        + row("<span class=\"swatch round\" style=\"background:none;border:2px solid #ff9f43\"></span>", "機関砲を撃った")
        + row("<span class=\"swatch\" style=\"background:#4de0c0;clip-path:polygon(50% 0,100% 100%,0 100%)\"></span>", "空対空ミサイルを撃った")
        + row("<span class=\"swatch\" style=\"background:#ffd740;clip-path:polygon(50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%)\"></span>", "航空機が撃破した")
        + "<p class=\"hint\">再生中は機体の形が向きを、横の数字が高さ（Y）を表す。航過の間は狙っている相手へ点線を引く。</p>";

    $("legend").innerHTML = html;
  }

  // ------------------------------------------------------------------
  // 地図の上
  // ------------------------------------------------------------------

  const tooltip = $("tooltip");

  AV.map.on("hover", (hover) => {
    if (!hover) {
      tooltip.hidden = true;
      $("hud").textContent = "";

      return;
    }

    const x = hover.world.x;
    const z = hover.world.z;
    const lines = [];
    const picked = AV.map.pick(hover.px, hover.py);

    $("hud").textContent = "X " + Math.floor(x) + "　Z " + Math.floor(z) + "　（1ブロック " + AV.map.view.scale.toFixed(2) + " 画素）";

    if (picked && picked.kind === "marker") {
      const marker = picked.marker;

      lines.push("<b>" + AV.panels.eventName(marker) + "</b> " + short(marker.vehicle) + "（" + (marker.team || "") + "）"
          + (state.detail ? " " + AV.data.clock(marker.t - state.detail.t0) : ""));
    } else if (picked && picked.kind === "bot" && picked.bot.air) {
      const sample = picked.position.sample;

      lines.push("<b>" + picked.bot.vehicle + "</b>（" + picked.bot.team + "） " + (AV.panels.PHASES[sample.phase] || sample.phase)
          + "　Y" + Math.round(picked.position.y) + "　" + Math.round(sample.speed * 72) + " km/h"
          + (sample.evading ? "　ミサイル回避" : "")
          + (typeof sample.firedGun === "number"
              ? "<br>撃った 機関砲" + sample.firedGun + "・爆弾" + (sample.firedBomb || 0) + "・ミサイル" + (sample.firedAam || 0) : ""));
    } else if (picked && picked.kind === "bot") {
      const sample = picked.position.sample;

      lines.push("<b>" + short(picked.bot.vehicle) + "</b>（" + picked.bot.team + "） " + (AV.panels.ACTIONS[sample.action] || sample.action)
          + (sample.water >= 2 ? " 沈んでいる" : "") + (sample.escaping ? " 抜け出し中" : ""));
    }

    const ground = AV.terrain.sample(x, z);

    if (ground) {
      lines.push(short(ground.block) + " Y" + ground.y + (ground.depth ? "　水深 " + ground.depth : ""));
    }

    if (state.memory) {
      const index = AV.data.cellAt(state.memory, x, z);

      if (index >= 0) {
        const read = AV.data.reading(state.memory, index);

        lines.push("詰まる " + read.trap.toFixed(2) + "　水 " + read.water.toFixed(2) + "　倒される " + read.death.toFixed(2)
            + "　抜け " + read.flow.toFixed(2));
        lines.push("入った " + read.visits.toFixed(1) + "回　代償 " + AV.data.cost(read, settings.weights).toFixed(2));
      }
    }

    if (!lines.length) {
      tooltip.hidden = true;

      return;
    }

    tooltip.innerHTML = lines.join("<br>");
    tooltip.hidden = false;

    const size = AV.map.size();
    const left = Math.min(hover.px + 16, size.width - tooltip.offsetWidth - 8);
    const top = Math.min(hover.py + 16, size.height - tooltip.offsetHeight - 8);

    tooltip.style.left = Math.max(8, left) + "px";
    tooltip.style.top = Math.max(8, top) + "px";
  });

  AV.map.on("click", (click) => {
    const cell = state.memory ? state.memory.cell : 4;

    settings.selectedCell = { x: Math.floor(click.world.x / cell), z: Math.floor(click.world.z / cell) };
    state.info = { world: click.world, index: state.memory ? AV.data.cellAt(state.memory, click.world.x, click.world.z) : -1,
      picked: click.picked };
    AV.panels.place(state.info);
    showTab("place");
    AV.map.request();
  });

  AV.map.on("view", scheduleTerrain);

  $("zoom-in").addEventListener("click", () => {
    const size = AV.map.size();

    AV.map.zoomAt(size.width / 2, size.height / 2, 1.6);
  });
  $("zoom-out").addEventListener("click", () => {
    const size = AV.map.size();

    AV.map.zoomAt(size.width / 2, size.height / 2, 1 / 1.6);
  });
  $("zoom-fit").addEventListener("click", fitMemory);
  $("zoom-battle").addEventListener("click", fitBattle);

  // ------------------------------------------------------------------
  // タブ
  // ------------------------------------------------------------------

  function showTab(name) {
    document.querySelectorAll(".tabs button").forEach((button) => button.classList.toggle("on", button.dataset.tab === name));
    document.querySelectorAll(".tab").forEach((tab) => tab.classList.toggle("on", tab.id === "tab-" + name));
  }

  document.querySelectorAll(".tabs button").forEach((button) => button.addEventListener("click", () => showTab(button.dataset.tab)));

  // ------------------------------------------------------------------
  // 戦闘と再生
  // ------------------------------------------------------------------

  async function selectBattle(id) {
    stopReplay();

    if (state.battleId === id) {
      state.battleId = null;
      state.detail = null;
      settings.time = null;
      AV.map.setDetail(null);
      $("timeline").hidden = true;
      AV.panels.battles();

      return;
    }

    const header = state.headers.find((item) => item.id === id);

    if (!header) {
      return;
    }

    state.battleId = id;
    state.detail = null;
    state.loadingBattle = true;
    AV.panels.battles();
    progress(0.4, "戦闘 " + id + " を読んでいる…");

    let detail;

    try {
      detail = await AV.data.readDetail(header);
    } catch (error) {
      state.loadingBattle = false;
      problem(error, "戦闘を読めなかった");

      return;
    }

    progress(null);

    if (state.battleId !== id) {
      return;
    }

    state.detail = detail;
    state.loadingBattle = false;
    settings.time = null;
    AV.map.setDetail(detail);
    setupTimeline(detail);

    if (!state.memory) {
      fitBattle();
    }

    AV.panels.battles();
    loaded();
  }

  function setupTimeline(detail) {
    const slider = $("time");

    slider.min = detail.t0;
    slider.max = detail.t1;
    slider.step = 1;
    slider.value = detail.t1;
    $("timeline").hidden = false;
    timeLabel();
  }

  function timeLabel() {
    const detail = state.detail;
    const label = $("time-label");

    if (!detail) {
      return;
    }

    if (settings.time === null) {
      label.textContent = "全体 " + AV.data.clock(detail.t1 - detail.t0);
      label.title = "";
    } else {
      label.textContent = AV.data.clock(settings.time - detail.t0) + " / " + AV.data.clock(detail.t1 - detail.t0);
      label.title = "クリックで戦闘全体の表示に戻す";
    }
  }

  $("time").addEventListener("input", (event) => {
    settings.time = Number(event.target.value);
    timeLabel();
    AV.map.request();
  });

  $("time-label").addEventListener("click", () => {
    stopReplay();
    settings.time = null;
    timeLabel();
    AV.map.request();
  });

  $("play").addEventListener("click", () => (replaying ? stopReplay() : startReplay()));

  function startReplay() {
    const slider = $("time");

    if (!state.detail) {
      return;
    }

    if (settings.time === null || Number(slider.value) >= Number(slider.max)) {
      slider.value = slider.min;
    }

    settings.time = Number(slider.value);
    replaying = true;
    $("play").textContent = "❚❚";
    lastFrame = performance.now();
    requestAnimationFrame(step);
  }

  function step(now) {
    if (!replaying || !state.detail) {
      return;
    }

    const slider = $("time");
    const seconds = Math.min(0.25, (now - lastFrame) / 1000);
    let time = settings.time + seconds * 20 * Number($("speed").value);

    lastFrame = now;

    if (time >= Number(slider.max)) {
      time = Number(slider.max);
      stopReplay();
    }

    settings.time = time;
    slider.value = time;
    timeLabel();
    AV.map.request();

    if (replaying) {
      requestAnimationFrame(step);
    }
  }

  function stopReplay() {
    replaying = false;
    $("play").textContent = "▶";
  }

  // ------------------------------------------------------------------
  // 外から呼ばれる物
  // ------------------------------------------------------------------

  function focus(x, z) {
    const cell = state.memory ? state.memory.cell : 4;

    AV.map.centerOn(x, z, Math.max(AV.map.view.scale, 3));
    settings.selectedCell = { x: Math.floor(x / cell), z: Math.floor(z / cell) };
    state.info = { world: { x, z }, index: state.memory ? AV.data.cellAt(state.memory, x, z) : -1, picked: null };
    AV.panels.place(state.info);
    showTab("place");
  }

  async function countAll() {
    if (state.counting) {
      return;
    }

    state.counting = true;
    AV.panels.growth();

    const targets = state.headers.filter((header) => header.finished && !header.aborted && !state.metrics.has(header.id));

    for (let i = 0; i < targets.length; i++) {
      progress(i / targets.length, "数えている " + targets[i].id);

      try {
        state.metrics.set(targets[i].id, await AV.data.quickMetrics(targets[i].entry));
      } catch (error) {
        state.metrics.set(targets[i].id, {});
      }
    }

    progress(null);
    state.counting = false;
    AV.panels.growth();
  }

  /** 再生の時刻を置く（視点の「出撃の時刻へ」など）。戦闘の中に収める。 */
  function setTime(t) {
    const detail = state.detail;

    if (!detail || !Number.isFinite(t)) {
      return;
    }

    settings.time = Math.max(detail.t0, Math.min(detail.t1, t));
    $("time").value = settings.time;
    timeLabel();
    AV.map.request();
  }

  /**
   * 視点（pov.js）から。その位置の周りのリージョンを、近い順に limit 個まで読む。地図の「地形」を切っていても読む
   * ——視点は地形が無いと何も見えない。
   */
  async function readTerrainAround(x, z, radius, limit) {
    if (state.terrainBusy || !AV.terrain.count()) {
      return null;
    }

    state.terrainBusy = true;

    try {
      return await AV.terrain.request(x - radius, z - radius, x + radius, z + radius, limit || 1);
    } finally {
      state.terrainBusy = false;
      progress(null);
      loaded();
    }
  }

  /** 試験と、ファイルを URL で持っているとき用。[{path, url}] を取ってきて足す。 */
  async function loadUrls(entries) {
    const files = [];

    for (const { path, url } of entries) {
      const response = await fetch(url);

      if (!response.ok) {
        throw new Error(url + ": " + response.status);
      }

      files.push({ path, file: new File([await response.blob()], path.split("/").pop()) });
    }

    await AV.files.add(files);
    await state.filesTask;
  }

  AV.app = { focus, selectBattle, countAll, loadUrls, showTab, readVisibleTerrain, refreshLatest, fitBattle, setTime,
    readTerrainAround };

  AV.map.init($("map"));
  legend();
  refreshControls();
  AV.panels.place(null);
  AV.panels.memory();
  AV.panels.battles();
  AV.panels.growth();
})(window.AV = window.AV || {});
