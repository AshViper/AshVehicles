/*
 * ページの配線。設定の入力・開始と停止・グラフ・採用版の表・書き出し・観戦を学習器（trainer.js）に繋ぐ。
 *
 * 学習の状態はこのブラウザの localStorage に保存し、開き直せば続きから（Worker の中の試合は捨てる）。読み込んだワールドの
 * 会場は大きいので IndexedDB に置く。
 */
(function () {
  "use strict";

  const { Trainer, GROUPS, AV } = window.AVTrainer;
  const { Spectator, ACTION_JA, escapeHtml } = window.AVSpectator;
  const STORE = "ashvehicles.trainer.v1";
  const $ = (id) => document.getElementById(id);

  const NUMBERS = ["workers", "teamSize", "minutes", "population", "matchesPerCandidate", "sigma", "learningRate",
    "hallShare", "gateMatches", "gateWinRate", "shapeAirIdle", "shapeAirWanted", "shapePair", "shapeWater", "shapeStall"];
  const SELECTS = ["points", "mapSize"];

  let dirty = false;
  let lastSave = 0;
  let scheduled = false;

  const trainer = new Trainer(() => {
    dirty = true;
    schedule();
  });

  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || "null");

    if (saved) trainer.load(saved);
  } catch (error) {
    console.warn("could not restore trainer state", error);
  }

  // ------------------------------------------------------------------
  // 設定
  // ------------------------------------------------------------------

  function fillSettings() {
    const s = trainer.settings;

    for (const key of NUMBERS) $(key).value = s[key];
    for (const key of SELECTS) $(key).value = String(s[key]);

    $("varyTerrain").checked = !!s.varyTerrain;
    $("air").checked = s.air !== false;

    for (const radio of document.querySelectorAll("input[name=mapSource]")) radio.checked = radio.value === s.mapSource;
    $("prefix").value = s.prefix;
    $("workers").max = Math.max(64, navigator.hardwareConcurrency || 8);

    const groups = $("groups");

    groups.innerHTML = "";

    for (const group of GROUPS) {
      const label = document.createElement("label");
      const box = document.createElement("input");

      box.type = "checkbox";
      box.checked = s.groups.includes(group.key);
      box.addEventListener("change", () => {
        const chosen = [...groups.querySelectorAll("input")].map((input, i) => (input.checked ? GROUPS[i].key : null))
          .filter(Boolean);

        trainer.setGroups(chosen);
      });
      label.append(box, document.createTextNode(group.label));
      groups.append(label);
    }
  }

  for (const key of NUMBERS) {
    $(key).addEventListener("change", () => {
      const value = Number($(key).value);

      if (!isFinite(value)) return;

      trainer.settings[key] = value;

      if (key === "workers" && trainer.pool) trainer.pool.resize(Math.max(1, Math.round(value)));

      dirty = true;
      schedule();
    });
  }

  for (const key of SELECTS) {
    $(key).addEventListener("change", () => {
      trainer.settings[key] = Number($(key).value);
      dirty = true;
      schedule();
    });
  }

  $("varyTerrain").addEventListener("change", () => { trainer.settings.varyTerrain = $("varyTerrain").checked; dirty = true; });
  $("air").addEventListener("change", () => { trainer.settings.air = $("air").checked; dirty = true; });

  for (const radio of document.querySelectorAll("input[name=mapSource]")) {
    radio.addEventListener("change", () => {
      if (!radio.checked) return;

      if (radio.value === "world" && !trainer.world) {
        alert("先にワールドを開く");
        fillSettings();
        return;
      }

      trainer.settings.mapSource = radio.value;
      dirty = true;
      schedule();
      spectate();
    });
  }

  // ------------------------------------------------------------------
  // ワールド
  // ------------------------------------------------------------------

  const DB = "ashvehicles-trainer";

  function database() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB, 1);

      request.onupgradeneeded = () => request.result.createObjectStore("worlds");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  async function storeWorld(world) {
    try {
      const db = await database();

      await new Promise((resolve, reject) => {
        const tx = db.transaction("worlds", "readwrite");

        if (world) tx.objectStore("worlds").put(world, "current");
        else tx.objectStore("worlds").delete("current");

        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
      });
    } catch (error) {
      console.warn("could not store the world", error);
    }
  }

  async function restoreWorld() {
    try {
      const db = await database();
      const world = await new Promise((resolve, reject) => {
        const request = db.transaction("worlds").objectStore("worlds").get("current");

        request.onsuccess = () => resolve(request.result || null);
        request.onerror = () => reject(request.error);
      });

      if (world) {
        trainer.setWorld(world);
        describeWorld();
      }
    } catch (error) {
      console.warn("could not restore the world", error);
    }
  }

  function describeWorld() {
    const world = trainer.world && trainer.world.data;

    $("worldForget").disabled = !world;

    if (!world) {
      $("worldInfo").textContent = "ワールドは読んでいない";
      return;
    }

    const counts = [0, 0, 0, 0];

    for (let i = 0; i < world.kind.length; i++) counts[world.kind[i]]++;

    const share = (k) => (counts[k] / world.kind.length * 100).toFixed(0) + "%";

    $("worldInfo").textContent = world.name + "（" + world.dimension + "）・ 北西の角 " + world.originX + ", " + world.originZ
      + " ・ 一辺 " + world.size + " ・ 読めた土地 " + (world.known * 100).toFixed(0) + "% ・ 林 " + share(1) + " 建物 " + share(2)
      + " 水 " + share(3) + " ・ 拠点 " + world.points.length + " ・ 戦域 " + Math.round(world.arena)
      + (world.hasMatch ? "" : "（陣地が無いので中心と一辺から置いた）");
  }

  async function loadWorld(source) {
    if (trainer.running) {
      alert("先に停止する");
      return;
    }

    $("worldOpen").disabled = true;
    $("worldInfo").textContent = source.name + " を読んでいる…";

    try {
      const world = await window.AVWorld.build(source, {
        fallback: { x: Number($("fallbackX").value) || 0, z: Number($("fallbackZ").value) || 0,
          side: Number($("fallbackSide").value) || 768 },
      }, (done, total, text) => {
        $("worldFill").style.width = (done / Math.max(total, 1) * 100).toFixed(1) + "%";
        $("worldInfo").textContent = source.name + " を読んでいる… " + text + "（" + Math.floor(done) + " / " + total + "）";
      });

      if (world.chunks === 0) {
        throw new Error("その範囲に読めるチャンクが無い（region フォルダと座標を確かめる）");
      }

      trainer.setWorld(world);
      trainer.settings.mapSource = "world";
      dirty = true;
      await storeWorld(world);
      fillSettings();
      describeWorld();
      spectate();
    } catch (error) {
      $("worldInfo").textContent = "読めなかった: " + error.message;
      console.error(error);
    } finally {
      $("worldOpen").disabled = false;
      $("worldFill").style.width = "0";
    }
  }

  $("worldOpen").addEventListener("click", async () => {
    if (!window.showDirectoryPicker) {
      alert("このブラウザはフォルダを開けない。右の「フォルダを選ぶ」を使う。");
      return;
    }

    try {
      const handle = await window.showDirectoryPicker({ id: "ashvehicles-world", mode: "read" });

      await loadWorld(window.AVWorld.fromHandle(handle));
    } catch (error) {
      if (error.name !== "AbortError") alert("開けなかった: " + error.message);
    }
  });

  $("worldFetch").addEventListener("click", async () => {
    const url = $("worldUrl").value.trim() || $("worldUrl").placeholder;

    await loadWorld(window.AVWorld.fromUrl(url));
  });

  $("worldFiles").addEventListener("change", async () => {
    const files = $("worldFiles").files;

    if (files && files.length) await loadWorld(window.AVWorld.fromFiles(files));

    $("worldFiles").value = "";
  });

  $("worldForget").addEventListener("click", async () => {
    if (trainer.running) return alert("先に停止する");

    trainer.setWorld(null);
    trainer.settings.mapSource = "generated";
    dirty = true;
    await storeWorld(null);
    fillSettings();
    describeWorld();
    spectate();
  });
  $("prefix").addEventListener("change", () => { trainer.settings.prefix = $("prefix").value.trim() || "web"; dirty = true; });

  // ------------------------------------------------------------------
  // 開始・停止・やり直し
  // ------------------------------------------------------------------

  $("start").addEventListener("click", () => trainer.start());
  $("stop").addEventListener("click", () => trainer.stop());

  $("reset").addEventListener("click", () => {
    if (trainer.running) {
      alert("先に停止する");
      return;
    }

    if (!confirm("学習の記録と採用版を捨てて、基の版からやり直す？")) return;

    trainer.reset(trainer.base);
  });

  $("baseDefault").addEventListener("click", () => {
    if (trainer.running) return alert("先に停止する");
    if (!confirm("rule_v1（コードの既定値）からやり直す？")) return;

    trainer.reset({ id: "rule_v1", values: {} });
  });

  $("baseFile").addEventListener("change", async () => {
    const file = $("baseFile").files[0];

    $("baseFile").value = "";

    if (!file) return;
    if (trainer.running) return alert("先に停止する");

    try {
      const json = JSON.parse(await file.text());
      const values = {};

      flatten("", json.parameters || {}, values);

      if (!confirm((json.id || file.name) + " から学習をやり直す？（パラメータ " + Object.keys(values).length + " 個）")) return;

      trainer.reset({ id: json.id || file.name.replace(/\.json$/, ""), values });
    } catch (error) {
      alert("読めなかった: " + error.message);
    }
  });

  /** ParameterSet.fromJson と同じ: 入れ子はドットで繋ぎ、数でない物は捨てる。 */
  function flatten(prefix, value, into) {
    for (const [key, item] of Object.entries(value)) {
      const name = prefix ? prefix + "." + key : key;

      if (item && typeof item === "object") flatten(name, item, into);
      else if (typeof item === "number") into[name] = item;
    }
  }

  // ------------------------------------------------------------------
  // 書き出し
  // ------------------------------------------------------------------

  function exported() {
    return JSON.stringify(trainer.versionJson(), null, 2) + "\n";
  }

  $("download").addEventListener("click", () => {
    const blob = new Blob([exported()], { type: "application/json" });
    const link = document.createElement("a");

    link.href = URL.createObjectURL(blob);
    link.download = trainer.champion.id + ".json";
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    $("exportNote").textContent = link.download + " を保存した。ゲームの ashvehicles_ai/versions/ へ置く。";
  });

  $("copy").addEventListener("click", async () => {
    await navigator.clipboard.writeText(exported());
    $("exportNote").textContent = "クリップボードにコピーした。";
  });

  let versionsFolder = null;

  $("saveFolder").addEventListener("click", async () => {
    if (!window.showDirectoryPicker) {
      alert("このブラウザはフォルダへ書けない（Chrome か Edge を使う）。JSON を保存して手で置く。");
      return;
    }

    try {
      if (!versionsFolder) {
        const picked = await window.showDirectoryPicker({ id: "ashvehicles-ai", mode: "readwrite" });

        // ashvehicles_ai を選んだら versions/ を掘る。versions そのものを選んでもよい。
        versionsFolder = picked.name === "versions" ? picked : await picked.getDirectoryHandle("versions", { create: true });
      }

      const name = trainer.champion.id + ".json";
      const file = await versionsFolder.getFileHandle(name, { create: true });
      const writable = await file.createWritable();

      await writable.write(exported());
      await writable.close();
      $("exportNote").textContent = "versions/" + name + " へ書いた。ゲームで /tdm ai versions reload。";
    } catch (error) {
      if (error.name !== "AbortError") alert("書けなかった: " + error.message);
    }
  });

  // ------------------------------------------------------------------
  // 観戦
  // ------------------------------------------------------------------

  const spectator = new Spectator($("field"), $("scoreboard"), $("unitInfo"));

  function spectate() {
    const who = $("spectateWho").value;
    const s = trainer.settings;
    const base = trainer.base.values;
    const mean = trainer.catalog.decode(base, trainer.mean);
    const pairs = {
      "champion-base": [[trainer.champion.values, trainer.champion.id], [base, trainer.base.id]],
      "mean-champion": [[mean, "学習中の平均"], [trainer.champion.values, trainer.champion.id]],
      "base-base": [[base, trainer.base.id + " (A)"], [base, trainer.base.id + " (B)"]],
    };
    const pair = pairs[who];

    spectator.start([pair[0][0], pair[1][0]], [pair[0][1], pair[1][1]], (Math.random() * 1e9) >>> 0, {
      teamSize: s.teamSize, minutes: s.minutes, air: s.air,
      map: trainer.usesWorld() ? { world: trainer.world.data } : { size: s.mapSize, points: s.points },
    });
  }

  $("spectateNew").addEventListener("click", spectate);
  $("spectateWho").addEventListener("change", spectate);
  $("spectateSpeed").addEventListener("change", () => { spectator.speed = Number($("spectateSpeed").value); });
  $("spectatePause").addEventListener("click", () => {
    spectator.paused = !spectator.paused;
    $("spectatePause").textContent = spectator.paused ? "再開" : "一時停止";
  });

  // ------------------------------------------------------------------
  // 描く
  // ------------------------------------------------------------------

  function schedule() {
    if (scheduled) return;

    scheduled = true;

    // 裏に回ったタブでは requestAnimationFrame が止まる。学習は Worker で進み続けるので、表示はタイマーで追う。
    const run = () => {
      scheduled = false;
      render();
    };

    if (document.hidden) setTimeout(run, 250);
    else requestAnimationFrame(run);
  }

  function render() {
    const running = trainer.running;
    const status = $("status");

    status.textContent = trainer.stopping ? "止めている…" : running ? "学習中" : "停止中";
    status.className = "status " + (trainer.stopping ? "stopping" : running ? "run" : "idle");
    $("start").disabled = running;
    $("stop").disabled = !running || trainer.stopping;
    $("reset").disabled = running;
    $("baseName").textContent = "今の基: " + trainer.base.id + "（書いてある値 " + Object.keys(trainer.base.values).length + " 個）";
    $("paramCount").textContent = "学習するパラメータ " + trainer.catalog.size + " 個";

    const phase = trainer.phase;

    if (phase && running) {
      $("phase").textContent = "第 " + phase.generation + " 世代 ・ " + (phase.kind === "gate" ? "門" : "候補") + " "
        + phase.done + " / " + phase.total + " 試合";
      $("progressFill").style.width = (phase.done / Math.max(phase.total, 1) * 100).toFixed(1) + "%";
    } else {
      $("phase").textContent = "";
      $("progressFill").style.width = "0";
    }

    const last = trainer.history[trainer.history.length - 1];

    $("summary").innerHTML = [
      ["世代", trainer.generation],
      ["採用", trainer.promotions],
      ["採用版", escapeHtml(trainer.champion.id)],
      ["σ", trainer.sigma.toFixed(3)],
      ["直近の門", last && last.gate !== null ? (last.gate * 100).toFixed(1) + "%" : "—"],
    ].map(([label, value]) => "<div><b>" + value + "</b><span>" + label + "</span></div>").join("");

    drawChart();
    renderChampion();
    renderLog();

    if (dirty && performance.now() - lastSave > 2000) {
      try {
        localStorage.setItem(STORE, JSON.stringify(trainer.save()));
        lastSave = performance.now();
        dirty = false;
      } catch (error) {
        console.warn("could not save trainer state", error);
      }
    }
  }

  function drawChart() {
    const canvas = $("chart");
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;
    const height = 220;

    if (canvas.width !== Math.round(width * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
    }

    const ctx = canvas.getContext("2d");
    const style = getComputedStyle(document.documentElement);
    const color = (name) => style.getPropertyValue(name).trim();
    const history = trainer.history;
    const left = 36, right = 10, top = 10, bottom = 22;
    const w = width - left - right;
    const h = height - top - bottom;

    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, width, height);
    ctx.font = "11px system-ui";
    ctx.fillStyle = color("--muted");
    ctx.strokeStyle = color("--line");
    ctx.lineWidth = 1;

    for (const v of [0, 0.25, 0.5, 0.75, 1]) {
      const y = top + h * (1 - v);

      ctx.beginPath();
      ctx.moveTo(left, y);
      ctx.lineTo(left + w, y);
      ctx.stroke();
      ctx.textAlign = "right";
      ctx.fillText(Math.round(v * 100) + "%", left - 4, y + 4);
    }

    const gateLine = top + h * (1 - trainer.settings.gateWinRate);

    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = color("--team1");
    ctx.beginPath();
    ctx.moveTo(left, gateLine);
    ctx.lineTo(left + w, gateLine);
    ctx.stroke();
    ctx.setLineDash([]);

    if (history.length === 0) {
      ctx.textAlign = "center";
      ctx.fillStyle = color("--muted");
      ctx.fillText("まだ世代が無い", left + w / 2, top + h / 2);
      return;
    }

    const first = history[0].generation;
    const lastGen = history[history.length - 1].generation;
    const x = (g) => left + (lastGen === first ? w / 2 : (g - first) / (lastGen - first) * w);
    const y = (v) => top + h * (1 - Math.max(0, Math.min(1, v)));
    const line = (field, stroke, widthPx) => {
      ctx.strokeStyle = stroke;
      ctx.lineWidth = widthPx;
      ctx.beginPath();

      let started = false;

      for (const record of history) {
        const value = record[field];

        if (value === null || value === undefined) continue;

        if (!started) { ctx.moveTo(x(record.generation), y(value)); started = true; }
        else ctx.lineTo(x(record.generation), y(value));
      }

      ctx.stroke();
    };

    line("meanWin", color("--muted"), 1);
    line("bestWin", color("--good"), 1.5);
    line("gate", color("--accent"), 2.5);

    ctx.fillStyle = color("--team1");

    for (const record of history) {
      if (record.promoted) {
        ctx.beginPath();
        ctx.arc(x(record.generation), y(record.gate), 4, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    ctx.fillStyle = color("--muted");
    ctx.textAlign = "left";
    ctx.fillText("第 " + first + " 世代", left, height - 6);
    ctx.textAlign = "right";
    ctx.fillText("第 " + lastGen + " 世代", left + w, height - 6);
  }

  let championShown = null;

  function renderChampion() {
    const champion = trainer.champion;
    const gate = trainer.lastGate;
    const key = champion.id + "/" + (gate ? gate.generation : "") + "/" + trainer.catalog.size;

    if (key === championShown) return;

    championShown = key;

    const g = champion.gate;

    $("champion").innerHTML = "<p><b>" + escapeHtml(champion.id) + "</b>"
      + (g ? " ・ 第 " + champion.generation + " 世代で採用 ・ 門 " + (g.winRate * 100).toFixed(1) + "%（"
        + g.tally.wins + " 勝 " + g.tally.draws + " 分 " + g.tally.losses + " 敗）" : " ・ 基の版のまま") + "</p>"
      + (gate ? "<p class=\"muted\">直近の門（第 " + gate.generation + " 世代、挑戦者の " + gate.tally.games + " 試合の合計）: 空の撃破 "
        + gate.tally.airKills + " ・ 防空 敵機なし " + gate.tally.airDefenceIdle + " / あり " + gate.tally.airDefenceWanted
        + " ・ 戦車と IFV の組 " + (gate.tally.pair / Math.max(gate.tally.games, 1) * 100).toFixed(0) + "%（採用版 "
        + (gate.tally.championPair / Math.max(gate.tally.games, 1) * 100).toFixed(0) + "%） ・ 水 " + gate.tally.water + "（採用版 "
        + gate.tally.championWater + "） ・ 詰まり " + (gate.tally.stalls || 0) + "（採用版 " + (gate.tally.championStalls || 0)
        + "）</p>" : "");

    const actions = $("actions");

    actions.innerHTML = "";

    if (gate) {
      const total = (side) => Object.values(gate.actions[side]).reduce((a, b) => a + b, 0) || 1;
      const tc = total("challenger");
      const tp = total("champion");

      for (const action of AV.ACTIONS) {
        const c = (gate.actions.challenger[action] || 0) / tc;
        const p = (gate.actions.champion[action] || 0) / tp;

        actions.insertAdjacentHTML("beforeend", "<span>" + (ACTION_JA[action] || action) + " "
          + (c * 100).toFixed(0) + "% / " + (p * 100).toFixed(0) + "%</span><div class=\"bars\">"
          + "<div class=\"bar c\" style=\"width:" + (c * 100).toFixed(1) + "%\"></div>"
          + "<div class=\"bar p\" style=\"width:" + (p * 100).toFixed(1) + "%\"></div></div>");
      }
    } else {
      actions.innerHTML = "<span class=\"muted\">門をまだ通していない</span>";
    }

    const rows = [];
    const base = trainer.base.values;

    for (const key of Object.keys(champion.values)) {
      const start = base[key] !== undefined ? base[key] : AV.DATA.parameters[key];
      const now = champion.values[key];

      if (Math.abs(now - start) < 1e-9) continue;

      const ratio = start > 0 && now > 0 ? now / start : null;

      rows.push({ key, start, now, ratio, weight: ratio ? Math.abs(Math.log(ratio)) : Math.abs(now - start) });
    }

    rows.sort((a, b) => b.weight - a.weight);

    $("params").querySelector("tbody").innerHTML = rows.length === 0
      ? "<tr><td colspan=\"4\" class=\"muted\">まだ動いていない</td></tr>"
      : rows.map((r) => "<tr><td>" + escapeHtml(r.key) + "</td><td>" + fmt(r.start) + "</td><td class=\""
        + (r.now > r.start ? "up" : "down") + "\">" + fmt(r.now) + "</td><td>"
        + (r.ratio ? "×" + r.ratio.toFixed(2) : (r.now - r.start > 0 ? "+" : "") + fmt(r.now - r.start)) + "</td></tr>").join("");
  }

  function fmt(value) {
    return Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(3);
  }

  let logShown = -1;

  function renderLog() {
    if (trainer.log.length === logShown && trainer.log.length < 200) return;

    logShown = trainer.log.length;
    $("log").innerHTML = trainer.log.slice().reverse().map((entry) => "<li><time>" + entry.at + "</time>"
      + escapeHtml(entry.text) + "</li>").join("");
  }

  setInterval(() => {
    const pool = trainer.pool;

    if (!pool) {
      $("throughput").textContent = "Worker " + trainer.settings.workers + " 個（CPU " + (navigator.hardwareConcurrency || "?") + "）";
      return;
    }

    const minutes = (performance.now() - pool.startedAt) / 60000;
    const tps = pool.ms > 0 ? pool.ticks / (pool.ms / 1000) : 0;

    $("throughput").textContent = "Worker " + pool.busy + "/" + pool.size + " ・ " + pool.done + " 試合 ・ "
      + (pool.done / Math.max(minutes, 1e-3)).toFixed(1) + " 試合/分 ・ 1本あたり " + Math.round(tps) + " tick/秒";
  }, 1000);

  window.addEventListener("resize", schedule);
  window.addEventListener("beforeunload", () => {
    try {
      localStorage.setItem(STORE, JSON.stringify(trainer.save()));
    } catch (error) {
      // 保存できなくても閉じる
    }
  });

  fillSettings();
  describeWorld();
  render();
  spectate();
  restoreWorld().then(() => {
    if (trainer.usesWorld()) spectate();
  });
})();
