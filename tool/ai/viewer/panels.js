/*
 * 右側のタブ。場所（クリックしたマス）・覚えた地図・戦闘・成長。どれも AV.state を読んで HTML を作り直すだけで、
 * 状態は持たない。
 */
(function (AV) {
  "use strict";

  const $ = (id) => document.getElementById(id);

  const ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" };
  const esc = (text) => String(text === undefined || text === null ? "" : text).replace(/[&<>"]/g, (c) => ESCAPES[c]);
  const fixed = (value, digits) => (Number.isFinite(value) ? value.toFixed(digits) : "—");
  const short = (id) => String(id || "").replace(/^[^:]*:/, "");

  const EVENTS = {
    WaterEntered: "水に入った", Underwater: "沈んだまま", VehicleDestroyed: "撃破された", EnemyDestroyed: "敵を撃破",
    ObjectiveCaptured: "拠点を制圧", ObjectiveRecaptured: "拠点を奪還", ObjectiveLost: "拠点を失った",
    ObjectiveDefended: "拠点を防衛", UnnecessaryDeath: "避けられた撃破", LongExposure: "露出し続けた",
    AllySupported: "味方を援護", GoodFlank: "側面から命中", Escape: "抜け出し（来た道を戻った）", WeaponReleased: "撃った",
  };

  const ACTIONS = {
    CAPTURE_OBJECTIVE: "拠点を取る", RECAPTURE_OBJECTIVE: "拠点を奪い返す", DEFEND_OBJECTIVE: "拠点を守る",
    ATTACK: "攻撃", ADVANCE: "前進", FLANK: "側面へ", SEEK_COVER: "遮蔽へ", RETREAT: "後退",
    SEARCH_ENEMY: "索敵", SUPPORT_ALLY: "援護",
  };

  /** 航空機の段階（ゲームの AirPilot.Phase）。 */
  const PHASES = { TRANSIT: "待機・移動", RUN: "航過（攻撃）", EXTEND: "離脱", SPENT: "弾切れ・帰投" };

  const RELEASES = { bomb: "爆弾", gun: "機関砲", missile: "空対空ミサイル" };

  function teamTag(team) {
    return team ? "<span class=\"tag " + esc(team) + "\">" + esc(team) + "</span>" : "<span class=\"muted\">—</span>";
  }

  function meter(value, color) {
    return "<div class=\"meter\"><i style=\"width:" + Math.round(Math.max(0, Math.min(1, value)) * 100)
        + "%;background:" + color + "\"></i></div>";
  }

  function card(label, value) {
    return "<div class=\"card\"><div class=\"v\">" + esc(value) + "</div><div class=\"k\">" + esc(label) + "</div></div>";
  }

  function timeOf(detail, t) {
    return detail ? AV.data.clock(t - detail.t0) : "";
  }

  /** yRot の取り方の方位（南が0、西が90、北が180、東が270）。 */
  function compass(heading) {
    const names = ["南", "南西", "西", "北西", "北", "北東", "東", "南東"];
    const index = Math.round((((heading % 360) + 360) % 360) / 45) % 8;

    return names[index] + "（" + Math.round(heading) + "°）";
  }

  /** この出撃で撃った数（flights.jsonl の fired_*）。記録に無ければ「—」。 */
  function fired(sample) {
    if (sample.firedGun === null && sample.firedBomb === null && sample.firedAam === null) {
      return "—";
    }

    return "機関砲 " + (sample.firedGun || 0) + "発・爆弾 " + (sample.firedBomb || 0) + "本・ミサイル " + (sample.firedAam || 0) + "本";
  }

  /** 1出撃の撃った数の短い形（表の1マス）。機関砲/爆弾/ミサイル。 */
  function shotText(shot) {
    if (!shot || (shot.gun === null && shot.bomb === null && shot.aam === null)) {
      return "—";
    }

    const part = (value) => (value === null ? "—" : value);

    return part(shot.gun) + "/" + part(shot.bomb) + "/" + part(shot.aam);
  }

  /** 出来事の名前。撃った物・航空機かどうかで言い分ける。 */
  function eventName(marker) {
    if (marker.type === "WeaponReleased") {
      return RELEASES[AV.data.releaseKind(marker.detail.weapon, marker.detail.rounds)] + "を撃った";
    }

    if (marker.type === "EnemyDestroyed") {
      return marker.air ? "航空機が撃破した" : "敵を撃破した";
    }

    if (marker.type === "VehicleDestroyed" && marker.air) {
      return "航空機が落とされた";
    }

    return EVENTS[marker.type] || marker.type;
  }

  // ------------------------------------------------------------------
  // 場所
  // ------------------------------------------------------------------

  function place(info) {
    const root = $("tab-place");
    const state = AV.state;

    if (!info) {
      root.innerHTML = "<h2>場所</h2><p class=\"hint\">地図の上をクリックすると、そのマスで AI が経験したことと地形を出す。"
          + "戦闘を選んで再生しているときは、印や車両・航空機をクリックするとその中身を出し、そこからその機体の視点を開ける。</p>";

      return;
    }

    const blockX = Math.floor(info.world.x);
    const blockZ = Math.floor(info.world.z);
    const cell = state.memory ? state.memory.cell : 4;
    let html = "<h2>X " + blockX + "　Z " + blockZ + "</h2><p class=\"muted\">マス (" + Math.floor(blockX / cell) + ", "
        + Math.floor(blockZ / cell) + ")・4×4 ブロック</p>";

    if (info.picked) {
      html += picked(info.picked);
    }

    const ground = AV.terrain.sample(info.world.x, info.world.z);

    html += "<h3>地形</h3>";

    if (ground) {
      html += "<dl class=\"kv\"><dt>一番上</dt><dd><code>" + esc(ground.block) + "</code></dd><dt>高さ</dt><dd>Y " + ground.y
          + "</dd>";

      if (ground.depth) {
        html += "<dt>水</dt><dd>深さ " + ground.depth + "（水底 <code>" + esc(ground.floor) + "</code>）"
            + (ground.depth >= 2 ? " <span class=\"tag warn\">車体が沈む</span>" : "") + "</dd>";
      }

      html += "</dl>";
    } else {
      html += "<p class=\"muted\">この辺りの地形はまだ読んでいない。左の「見えている範囲の地形を読む」。</p>";
    }

    html += "<h3>覚えた地図</h3>";

    if (!state.memory) {
      html += "<p class=\"muted\">覚えた地図を開いていない。</p>";
    } else if (info.index < 0) {
      html += "<p class=\"muted\">このマスに AI はまだ入っていない（代償 0）。</p>";
    } else {
      const read = AV.data.reading(state.memory, info.index);
      const cost = AV.data.cost(read, AV.map.settings.weights);

      html += "<dl class=\"kv\"><dt>入った</dt><dd>" + fixed(read.visits, 1) + "</dd><dt>すんなり抜けた</dt><dd>"
          + fixed(read.passes, 1) + "</dd><dt>詰まった</dt><dd>" + fixed(read.stalls, 1) + "</dd><dt>沈んだ</dt><dd>"
          + fixed(read.wet, 1) + "</dd><dt>倒された</dt><dd>" + fixed(read.deaths, 1) + "</dd></dl>"
          + "<h3>読み</h3>"
          + "<div class=\"legend-row\">詰まる " + fixed(read.trap, 2) + "</div>" + meter(read.trap, "var(--trap)")
          + "<div class=\"legend-row\">水に落ちる " + fixed(read.water, 2) + "</div>" + meter(read.water, "var(--water)")
          + "<div class=\"legend-row\">倒される " + fixed(read.death, 2) + "</div>" + meter(read.death, "var(--death)")
          + "<div class=\"legend-row\">抜けやすい " + fixed(read.flow, 2) + "</div>" + meter(read.flow, "var(--flow)")
          + "<p class=\"note\">道の1歩の代償に <b>" + (cost >= 0 ? "+" : "") + fixed(cost, 2) + "</b> 倍を足す"
          + "（今の重み）。" + (cost >= 2 ? "地上の AI はここを大きく避ける。" : cost < 0 ? "地上の AI はここを少し好む。" : "")
          + "</p>";
    }

    if (state.detail && state.detail.header.points.length) {
      const near = state.detail.header.points
          .map((point) => ({ point, distance: Math.hypot(point.x - info.world.x, point.z - info.world.z) }))
          .sort((a, b) => a.distance - b.distance)[0];

      html += "<h3>拠点</h3><p>一番近いのは <b>" + esc(near.point.name) + "</b>（" + Math.round(near.distance) + " ブロック）</p>";
    }

    root.innerHTML = html;
    bindPov(root);
  }

  /** data-pov を持つボタンで、その機体の視点を開く（pov.js）。 */
  function bindPov(root) {
    root.querySelectorAll("[data-pov]").forEach((button) => button.addEventListener("click", (event) => {
      event.stopPropagation();
      AV.pov.open(button.dataset.pov);
    }));
  }

  function povButton(bot, air) {
    return "<div class=\"row\" style=\"margin-top:8px\"><button class=\"btn small\" type=\"button\" data-pov=\"" + esc(bot.id)
        + "\">この" + (air ? "機体" : "車両") + "の視点で見る</button></div>";
  }

  function pickedAir(item) {
    const sample = item.position.sample;
    const ground = AV.terrain.sample(item.position.x, item.position.z);
    const above = ground ? Math.round(item.position.y - ground.y) : null;

    return "<h3>航空機（再生中の位置）</h3><dl class=\"kv\">"
        + "<dt>機体</dt><dd>" + esc(item.bot.vehicle) + " " + teamTag(item.bot.team)
        + (item.bot.rotorcraft ? " <span class=\"tag\">回転翼</span>" : "") + "</dd>"
        + "<dt>段階</dt><dd><b>" + esc(PHASES[sample.phase] || sample.phase) + "</b>"
        + (sample.exact ? "" : " <span class=\"muted\">（行動から推定）</span>")
        + (sample.evading ? " <span class=\"tag warn\">ミサイル回避</span>" : "") + "</dd>"
        + "<dt>高さ</dt><dd>Y " + Math.round(item.position.y) + (above !== null ? "（地面から " + above + "）" : "") + "</dd>"
        + "<dt>速さ</dt><dd>" + Math.round(sample.speed * 72) + " km/h</dd>"
        + "<dt>向き</dt><dd>" + compass(sample.heading) + "</dd>"
        + "<dt>狙い</dt><dd>" + esc(sample.target || "—") + "</dd>"
        + "<dt>兵装</dt><dd>" + esc(sample.weapon ? short(sample.weapon) : "—") + "</dd>"
        + (sample.score !== null && sample.score !== undefined ? "<dt>相手の点数</dt><dd>" + fixed(sample.score, 2) + "</dd>" : "")
        + (sample.missiles !== null && sample.missiles !== undefined
            ? "<dt>空対空ミサイル</dt><dd>" + (sample.missiles ? "残っている" : "無い") + "</dd>" : "")
        + "<dt>この出撃で撃った</dt><dd>" + fired(sample) + "</dd>"
        + "<dt>見る拠点</dt><dd>" + esc(sample.objective || "—") + "</dd>"
        + "<dt>体力</dt><dd>" + Math.round((sample.health || 0) * 100) + "%</dd></dl>" + povButton(item.bot, true);
  }

  function picked(item) {
    const detail = AV.state.detail;

    if (item.kind === "bot" && item.bot.air) {
      return pickedAir(item);
    }

    if (item.kind === "bot") {
      const sample = item.position.sample;

      return "<h3>車両（再生中の位置）</h3><dl class=\"kv\"><dt>車両</dt><dd>" + esc(short(item.bot.vehicle)) + " "
          + teamTag(item.bot.team) + "</dd><dt>行動</dt><dd>" + esc(ACTIONS[sample.action] || sample.action)
          + "</dd><dt>持ち場</dt><dd>" + esc(sample.objective || "—") + "</dd><dt>体力</dt><dd>"
          + Math.round((sample.health || 0) * 100) + "%</dd><dt>水</dt><dd>" + sample.water + "</dd><dt>詰まり</dt><dd>"
          + sample.jams + (sample.escaping ? " <span class=\"tag warn\">抜け出し中</span>" : "")
          + (sample.lost ? " <span class=\"tag\">迷子</span>" : "") + "</dd></dl>" + povButton(item.bot, false);
    }

    const marker = item.marker;
    const detailText = Object.entries(marker.detail || {}).map(([key, value]) => esc(key) + " " + esc(value)).join("、");

    return "<h3>出来事</h3><dl class=\"kv\"><dt>何が</dt><dd><b>" + esc(eventName(marker)) + "</b></dd>"
        + "<dt>いつ</dt><dd>" + timeOf(detail, marker.t) + "</dd><dt>" + (marker.air ? "機体" : "車両") + "</dt><dd>"
        + esc(short(marker.vehicle)) + " " + teamTag(marker.team) + "</dd>"
        + (typeof marker.y === "number" ? "<dt>高さ</dt><dd>Y " + Math.round(marker.y) + "</dd>" : "")
        + (marker.reward ? "<dt>報酬</dt><dd>" + marker.reward + "</dd>" : "")
        + (detailText ? "<dt>詳しく</dt><dd>" + detailText + "</dd>" : "") + "</dl>"
        + (marker.approximate ? "<p class=\"muted\">位置はその車両の一番近い行から推定（出来事に位置が無い古い記録）。</p>" : "");
  }

  // ------------------------------------------------------------------
  // 覚えた地図
  // ------------------------------------------------------------------

  function rankedTable(title, key, amountLabel, amount) {
    const memory = AV.state.memory;
    const rows = AV.data.ranked(memory, key, 12);

    if (!rows.length) {
      return "<h3>" + esc(title) + "</h3><p class=\"muted\">無い。</p>";
    }

    let html = "<h3>" + esc(title) + "</h3><table class=\"list\"><tr><th>位置 X, Z</th><th class=\"num\">読み</th><th class=\"num\">"
        + esc(amountLabel) + "</th><th class=\"num\">入った</th></tr>";

    for (const row of rows) {
      const x = memory.x[row.i] * memory.cell + memory.cell / 2;
      const z = memory.z[row.i] * memory.cell + memory.cell / 2;

      html += "<tr class=\"pick\" data-x=\"" + x + "\" data-z=\"" + z + "\"><td class=\"mono\">" + x + ", " + z
          + "</td><td class=\"num\">" + fixed(row.read[key], 2) + "</td><td class=\"num\">" + fixed(row.read[amount], 1)
          + "</td><td class=\"num\">" + fixed(row.read.visits, 1) + "</td></tr>";
    }

    return html + "</table>";
  }

  function memory() {
    const root = $("tab-memory");
    const state = AV.state;

    if (!state.memory) {
      root.innerHTML = "<h2>覚えた地図</h2><p class=\"note\">覚えた地図がまだ無い。ワールドの "
          + "<code>ashvehicles_ai/map_memory</code> を含むフォルダを開く（試合を1回終えると書かれる）。</p>";

      return;
    }

    const summary = AV.data.summarize(state.memory);
    const entry = state.memoryEntry;

    root.innerHTML = "<h2>覚えた地図</h2><p class=\"muted\">" + esc(entry ? entry.worldName + " / " + entry.dimension : "")
        + "</p><div class=\"cards\">" + card("締めた試合", state.memory.battles) + card("覚えたマス", summary.cells)
        + card("詰まる所（0.5以上）", summary.traps) + card("水に落ちる所（0.3以上）", summary.wet)
        + card("倒される所（0.3以上）", summary.deadly) + card("抜けやすい所（0.6以上）", summary.lanes) + "</div>"
        + "<p class=\"hint\">地上の AI の車両がマスに入る・詰まる・沈む・倒される・すんなり抜けるたびに数え、道を引くときの代償に"
        + "足している（航空機は数えない）。試合が終わるたびに前の分を0.9倍にしてから足すので、古い経験は薄れていく。"
        + "行をクリックするとその場所へ移る。</p>"
        + rankedTable("詰まる所", "trap", "詰まった", "stalls")
        + rankedTable("水に落ちる所", "water", "沈んだ", "wet")
        + rankedTable("倒される所", "death", "倒された", "deaths");

    root.querySelectorAll("tr.pick").forEach((row) => row.addEventListener("click", () => {
      AV.app.focus(Number(row.dataset.x), Number(row.dataset.z));
    }));
  }

  // ------------------------------------------------------------------
  // 戦闘
  // ------------------------------------------------------------------

  /** known は跡のある AI の ID（文字列）。あれば名前の横に「視点」を出す。 */
  function livesTable(title, lives, air, shots, known) {
    if (!lives.length) {
      return "";
    }

    const sorted = lives.slice().sort((a, b) => (a.reward || 0) - (b.reward || 0));
    let html = "<h3>" + esc(title) + "</h3><div class=\"scroll\"><table class=\"list\"><tr><th>" + (air ? "機体" : "車両")
        + "</th><th class=\"num\">生存</th><th class=\"num\">撃破</th>" + (air ? "<th class=\"num\">与ダメージ</th>"
        + "<th class=\"num\" title=\"この出撃で撃った数: 機関砲の発数 / 爆弾の本数 / 空対空ミサイルの本数\">砲/爆/ミ</th>"
        : "<th class=\"num\">制圧</th>") + "<th class=\"num\">報酬</th></tr>";

    for (const life of sorted) {
      html += "<tr><td>" + esc(short(life.vehicle)) + " " + teamTag(life.team)
          + (life.destroyed ? " <span class=\"muted\">✕</span>" : "")
          + (known && known.has(String(life.bot)) ? " <button class=\"link\" type=\"button\" data-pov=\"" + esc(life.bot)
              + "\" title=\"この出撃を機体の視点で見る\">視点</button>" : "") + "</td><td class=\"num\">"
          + Math.round(life.survival_seconds || 0) + "秒</td><td class=\"num\">" + (life.kills || 0) + "</td><td class=\"num\">"
          + (air ? Math.round(life.damage_dealt || 0) + "</td><td class=\"num\">" + shotText(shots && shots.get(life.bot))
              : (life.captures || 0) + (life.recaptures || 0)) + "</td><td class=\"num\">"
          + fixed(life.reward, 0) + "</td></tr>";
    }

    return html + "</table></div>";
  }

  function battleSummary(detail) {
    const header = detail.header;
    const lives = header.lives;
    const counts = detail.counts;
    const escapes = detail.markers.filter((marker) => marker.type === "Escape").length;
    const air = lives.filter((life) => AV.data.isAir(life.vehicle));
    const ground = lives.filter((life) => !AV.data.isAir(life.vehicle));
    const reward = ground.length ? ground.reduce((sum, life) => sum + (life.reward || 0), 0) / ground.length : NaN;
    const aircraft = detail.bots.filter((bot) => bot.air).length;
    let html = "<h3>" + esc(header.id) + "</h3><div class=\"cards\">"
        + card("水に入った", counts.WaterEntered || 0) + card("沈んだまま（2秒ごと）", counts.Underwater || 0)
        + card("抜け出し", escapes) + card("撃破された", counts.VehicleDestroyed || 0)
        + card("制圧・奪還", (counts.ObjectiveCaptured || 0) + (counts.ObjectiveRecaptured || 0))
        + card("地上の1出撃あたりの報酬", fixed(reward, 1));

    if (air.length || aircraft) {
      html += card("航空機が撃破", counts.airKills || 0) + card("航空機が撃った（まとまり）", counts.WeaponReleased || 0);
    }

    html += "</div>";

    if ((counts.WaterEntered || 0) >= 20) {
      html += "<p class=\"note warn\">水に入った回数が多い。青い輪（水に入った所）が集まる場所を地形と一緒に見ると、"
          + "どこから落ちているかが分かる。</p>";
    }

    if (air.length && !header.hasFlights) {
      html += "<p class=\"note\">この戦闘には航空機の飛んだ跡（<code>flights.jsonl</code>）が無い。跡と段階が出るのは、飛び方を"
          + "記録する jar（2026-09-13 夜以降）で戦った戦闘から。撃破した所の星は出る。</p>";
    }

    if (!header.finished) {
      html += "<p class=\"note\">summary.json がまだ無い——進行中か、サーバーが途中で止まった戦闘。「最新に更新」で読み直せる。</p>";
    }

    const teams = new Map();

    for (const life of lives) {
      const team = teams.get(life.team) || { lives: 0, kills: 0, deaths: 0, captures: 0, reward: 0, air: 0,
        version: life.version };

      team.lives++;
      team.kills += life.kills || 0;
      team.deaths += life.destroyed ? 1 : 0;
      team.captures += (life.captures || 0) + (life.recaptures || 0);
      team.reward += life.reward || 0;
      team.air += AV.data.isAir(life.vehicle) ? 1 : 0;
      teams.set(life.team, team);
    }

    if (teams.size) {
      html += "<h3>陣営</h3><table class=\"list\"><tr><th>陣営</th><th>版</th><th class=\"num\">出撃</th><th class=\"num\">うち航空</th>"
          + "<th class=\"num\">撃破</th><th class=\"num\">被撃破</th><th class=\"num\">制圧</th></tr>";

      for (const [name, team] of teams) {
        html += "<tr><td>" + teamTag(name) + (header.winner === name ? " 勝ち" : "") + "</td><td>" + esc(team.version)
            + "</td><td class=\"num\">" + team.lives + "</td><td class=\"num\">" + team.air + "</td><td class=\"num\">"
            + team.kills + "</td><td class=\"num\">" + team.deaths + "</td><td class=\"num\">" + team.captures + "</td></tr>";
      }

      html += "</table>";
    }

    // 航空機ごとの、この出撃で撃った数。行は出撃の途中の累計なので、一番大きい値が最後の数。
    const shots = new Map();

    for (const bot of detail.bots) {
      if (!bot.air) {
        continue;
      }

      const shot = { gun: null, bomb: null, aam: null };

      for (const sample of bot.samples) {
        shot.gun = sample.firedGun === null ? shot.gun : Math.max(shot.gun || 0, sample.firedGun);
        shot.bomb = sample.firedBomb === null ? shot.bomb : Math.max(shot.bomb || 0, sample.firedBomb);
        shot.aam = sample.firedAam === null ? shot.aam : Math.max(shot.aam || 0, sample.firedAam);
      }

      shots.set(bot.id, shot);
    }

    const known = new Set(detail.bots.filter((bot) => bot.samples.length).map((bot) => String(bot.id)));

    html += livesTable("航空機", air, true, shots, known) + livesTable("地上の車両（報酬の低い順）", ground, false, null, known);

    return html + "<p class=\"hint\">地図の下の再生バーで時刻を動かすと、その時の位置と直前30秒の跡が出る。航空機の跡は段階で"
        + "色が変わり（凡例）、航過の間は狙っている相手へ点線が伸びる。</p>";
  }

  function battles() {
    const root = $("tab-battles");
    const state = AV.state;

    if (!state.headers.length) {
      root.innerHTML = "<h2>戦闘</h2><p class=\"note\">戦闘の記録がまだ無い。ゲームフォルダの <code>ashvehicles_ai</code> を含むフォルダを開く。</p>";

      return;
    }

    let html = "<h2>戦闘（" + state.headers.length + "）</h2><div class=\"scroll\"><table class=\"list\"><tr><th>開始</th><th>長さ</th>"
        + "<th>勝ち</th><th class=\"num\">出撃</th></tr>";

    for (const header of state.headers) {
      const started = String(header.started).replace(/^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d).*$/, "$2/$3 $4:$5");
      const airs = header.lives.filter((life) => AV.data.isAir(life.vehicle)).length;

      html += "<tr class=\"pick" + (header.id === state.battleId ? " on" : "") + "\" data-id=\"" + esc(header.id) + "\"><td class=\"mono\">"
          + esc(started) + (header.hasFlights ? " ✈" : "") + "</td><td>"
          + (header.duration ? AV.data.clock(header.duration) : "<span class=\"muted\">—</span>")
          + "</td><td>" + (header.finished ? teamTag(header.winner) : "<span class=\"tag\">進行中</span>") + "</td><td class=\"num\">"
          + header.lives.length + (airs ? "（空" + airs + "）" : "") + "</td></tr>";
    }

    html += "</table></div>";

    if (state.loadingBattle) {
      html += "<p class=\"muted\">読んでいる…</p>";
    } else if (state.detail) {
      html += battleSummary(state.detail);
    } else {
      html += "<p class=\"hint\">戦闘を選ぶと、拠点・走った跡・飛んだ跡・出来事を地図に重ねる。✈ は航空機の飛んだ跡がある戦闘。</p>";
    }

    root.innerHTML = html;
    root.querySelectorAll("tr.pick").forEach((row) => row.addEventListener("click", () => AV.app.selectBattle(row.dataset.id)));
    bindPov(root);
  }

  // ------------------------------------------------------------------
  // 成長
  // ------------------------------------------------------------------

  function chart(title, points, digits) {
    const width = 340;
    const height = 112;
    const left = 40;
    const right = 10;
    const top = 18;
    const bottom = 16;
    const valid = points.filter((point) => Number.isFinite(point.value));

    if (valid.length < 1) {
      return "<p class=\"muted\">" + esc(title) + ": まだ数字が無い</p>";
    }

    let low = Math.min(...valid.map((point) => point.value));
    let high = Math.max(...valid.map((point) => point.value));

    if (low === high) {
      low -= 1;
      high += 1;
    }

    const x = (i) => left + (points.length === 1 ? 0.5 : i / (points.length - 1)) * (width - left - right);
    const y = (value) => top + (1 - (value - low) / (high - low)) * (height - top - bottom);
    let shade = "";
    let path = "";
    let dots = "";

    points.forEach((point, i) => {
      if (point.learning) {
        const half = (width - left - right) / Math.max(1, points.length - 1) / 2;

        shade += "<rect x=\"" + (x(i) - half) + "\" y=\"" + top + "\" width=\"" + half * 2 + "\" height=\"" + (height - top - bottom)
            + "\" fill=\"rgba(158,222,26,.07)\"/>";
      }

      if (!Number.isFinite(point.value)) {
        return;
      }

      path += (path ? "L" : "M") + x(i).toFixed(1) + " " + y(point.value).toFixed(1);
      dots += "<circle cx=\"" + x(i).toFixed(1) + "\" cy=\"" + y(point.value).toFixed(1) + "\" r=\"2.6\" fill=\"#9ede1a\"><title>"
          + esc(point.label) + ": " + fixed(point.value, digits) + "</title></circle>";
    });

    const lastValue = valid[valid.length - 1].value;

    return "<svg class=\"chart\" viewBox=\"0 0 " + width + " " + height + "\">" + shade
        + "<text x=\"8\" y=\"13\" fill=\"#b5c9a4\" font-size=\"11\">" + esc(title) + "</text>"
        + "<text x=\"" + (width - 8) + "\" y=\"13\" fill=\"#e9f4de\" font-size=\"11\" text-anchor=\"end\">最新 " + fixed(lastValue, digits) + "</text>"
        + "<text x=\"" + (left - 5) + "\" y=\"" + (top + 4) + "\" fill=\"#7e9270\" font-size=\"10\" text-anchor=\"end\">" + fixed(high, digits) + "</text>"
        + "<text x=\"" + (left - 5) + "\" y=\"" + (height - bottom) + "\" fill=\"#7e9270\" font-size=\"10\" text-anchor=\"end\">" + fixed(low, digits) + "</text>"
        + "<line x1=\"" + left + "\" y1=\"" + (height - bottom) + "\" x2=\"" + (width - right) + "\" y2=\"" + (height - bottom) + "\" stroke=\"#27331d\"/>"
        + "<path d=\"" + path + "\" fill=\"none\" stroke=\"#9ede1a\" stroke-width=\"1.6\"/>" + dots + "</svg>";
  }

  function growth() {
    const root = $("tab-growth");
    const state = AV.state;
    const finished = state.headers.filter((header) => header.finished && !header.aborted).slice().reverse();
    let html = "<h2>成長</h2>";

    if (!finished.length && !state.stats.size) {
      root.innerHTML = html + "<p class=\"note\">決着した戦闘の記録と版の戦績（<code>ashvehicles_ai/battles</code>・"
          + "<code>stats</code>）を開くと、試合を重ねてどう変わったかを出す。</p>";

      return;
    }

    html += "<p class=\"hint\">決着した戦闘を古い順に並べた。薄い緑の帯は、水と抜け出しを記録する走り方（覚えた地図を使う jar）で戦った戦闘。</p>";

    const measured = finished.filter((header) => state.metrics.has(header.id)).length;

    html += "<div class=\"row\"><button class=\"btn\" id=\"count-all\" type=\"button\"" + (state.counting ? " disabled" : "") + ">"
        + (state.counting ? "数えている…" : "全戦闘の出来事を数える") + "</button><span class=\"muted\">" + measured + " / "
        + finished.length + " 戦闘を数えた</span></div>";

    const points = (value) => finished.map((header, i) => {
      const metrics = state.metrics.get(header.id);

      return { label: "#" + (i + 1) + " " + header.id, value: value(header, metrics), learning: !!(metrics && metrics.learning) };
    });
    const groundLives = (header) => header.lives.filter((life) => !AV.data.isAir(life.vehicle));
    const perLife = (header, key) => {
      const lives = groundLives(header);

      return lives.length ? lives.reduce((sum, life) => sum + (Number(life[key]) || 0), 0) / lives.length : NaN;
    };
    const metric = (key) => (header, metrics) => (metrics && metrics[key] !== undefined ? metrics[key] : NaN);

    html += chart("地上の1出撃あたりの報酬", points((header) => perLife(header, "reward")), 1)
        + chart("水に入った回数", points(metric("WaterEntered")), 0)
        + chart("抜け出し中の判断の行", points(metric("escaping")), 0)
        + chart("詰まり3回以上の判断の行", points(metric("jammed")), 0)
        + chart("制圧＋奪還", points((header) => header.lives.reduce((sum, life) => sum + (life.captures || 0) + (life.recaptures || 0), 0)), 0)
        + chart("航空機が撃破した数", points(metric("airKills")), 0)
        + chart("地上の1出撃あたりの生存（秒）", points((header) => perLife(header, "survival_seconds")), 0);

    if (state.stats.size) {
      html += "<h3>版の戦績（stats）</h3><table class=\"list\"><tr><th>版</th><th class=\"num\">戦闘</th><th class=\"num\">勝率</th>"
          + "<th class=\"num\">撃破/出撃</th><th class=\"num\">制圧/戦闘</th><th class=\"num\">報酬/出撃</th></tr>";

      for (const [version, stats] of state.stats) {
        const overall = (stats && stats.overall) || {};

        html += "<tr><td>" + esc(version) + "</td><td class=\"num\">" + (overall.battles || 0) + "</td><td class=\"num\">"
            + fixed((overall.win_rate || 0) * 100, 0) + "%</td><td class=\"num\">" + fixed(overall.kill_rate, 2) + "</td><td class=\"num\">"
            + fixed(overall.capture_rate, 2) + "</td><td class=\"num\">" + fixed(overall.average_reward, 1) + "</td></tr>";
      }

      html += "</table>";
    }

    root.innerHTML = html;

    const button = $("count-all");

    if (button) {
      button.addEventListener("click", () => AV.app.countAll());
    }
  }

  AV.panels = { place, memory, battles, growth, EVENTS, ACTIONS, PHASES, eventName };
})(window.AV = window.AV || {});
