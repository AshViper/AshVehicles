/*
 * 地図のキャンバス。地形の絵・覚えた地図・戦闘の重ねを、同じ座標で描く。
 *
 * 座標はワールドのブロックそのまま。右が +X（東）、下が +Z（南）なので、上が北。覚えた地図は1マス1画素の
 * 絵を作っておき、描くときに4倍して置く——2万マスを毎回四角で塗ると、引きずるたびに重い。
 *
 * 航空機（flights.jsonl）は段階で色を分ける: 待機・移動＝水色、航過＝橙、離脱＝黄、弾切れ＝灰、ミサイル回避＝桃。
 */
(function (AV) {
  "use strict";

  const TEAM = { red: "#ff6b5e", blue: "#5ea8ff" };
  const PHASE = { TRANSIT: "#7fd7ff", RUN: "#ff6e40", EXTEND: "#ffd54f", SPENT: "#a0a0a0" };
  const EVADE = "#ff5cf0";
  const MODES = [
    ["combined", "総合"], ["trap", "詰まる"], ["water", "水に落ちる"], ["death", "倒される"],
    ["flow", "抜けやすい"], ["cost", "道の代償"], ["visits", "通った量"],
  ];

  const view = { x: 0, z: 0, scale: 1 };
  const handlers = new Map();
  const settings = {
    showTerrain: true, terrainLight: 0.75, learnedMode: "combined", learnedAlpha: 0.8, fadeUnsure: true,
    weights: { trap: 3, water: 6, death: 1, flow: 0.2, risk: 1 },
    showPoints: true, showAnchor: true, showTracks: true, showAircraft: true, showStuck: true, showWater: true,
    showDeaths: true, showCaptures: true, showReleases: true, showKills: true,
    time: null, trail: true, selectedCell: null,
  };

  let canvas = null;
  let context = null;
  let width = 1;
  let height = 1;
  let memory = null;
  let detail = null;
  let learned = null;
  let learnedKey = "";
  let hover = null;
  let pending = false;
  let lastFit = null;
  let moved = false;

  function emit(name, value) {
    for (const handler of handlers.get(name) || []) {
      handler(value);
    }
  }

  function on(name, handler) {
    if (!handlers.has(name)) {
      handlers.set(name, []);
    }

    handlers.get(name).push(handler);
  }

  function clamp(value, low, high) {
    return Math.max(low, Math.min(high, value));
  }

  function toWorld(px, py) {
    return { x: view.x + (px - width / 2) / view.scale, z: view.z + (py - height / 2) / view.scale };
  }

  function toScreen(x, z) {
    return { x: (x - view.x) * view.scale + width / 2, y: (z - view.z) * view.scale + height / 2 };
  }

  function request() {
    if (pending || !canvas) {
      return;
    }

    pending = true;
    requestAnimationFrame(() => {
      pending = false;
      draw();
    });
  }

  function changed() {
    request();
    emit("view", view);
  }

  function local(event) {
    const rect = canvas.getBoundingClientRect();

    return { px: event.clientX - rect.left, py: event.clientY - rect.top };
  }

  function init(element) {
    canvas = element;
    context = canvas.getContext("2d");
    new ResizeObserver(resize).observe(canvas);
    resize();

    let drag = null;

    canvas.addEventListener("pointerdown", (event) => {
      canvas.setPointerCapture(event.pointerId);
      drag = { x: event.clientX, y: event.clientY, viewX: view.x, viewZ: view.z, moved: false };
      canvas.classList.add("dragging");
    });
    canvas.addEventListener("pointermove", (event) => {
      const { px, py } = local(event);

      if (drag) {
        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;

        if (Math.abs(dx) + Math.abs(dy) > 3) {
          drag.moved = true;
        }

        view.x = drag.viewX - dx / view.scale;
        view.z = drag.viewZ - dy / view.scale;
        moved = true;
        changed();
      }

      hover = { px, py, world: toWorld(px, py) };
      emit("hover", hover);
      request();
    });
    canvas.addEventListener("pointerup", (event) => {
      canvas.classList.remove("dragging");

      if (drag && !drag.moved) {
        const { px, py } = local(event);

        emit("click", { px, py, world: toWorld(px, py), picked: pick(px, py) });
      }

      drag = null;
    });
    canvas.addEventListener("pointerleave", () => {
      hover = null;
      emit("hover", null);
      request();
    });
    canvas.addEventListener("wheel", (event) => {
      event.preventDefault();

      const { px, py } = local(event);

      zoomAt(px, py, Math.exp(-event.deltaY * 0.0015));
    }, { passive: false });
  }

  function resize() {
    const ratio = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const wasHidden = width < 50 || height < 50;

    width = Math.max(1, rect.width);
    height = Math.max(1, rect.height);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);

    // 見えない間（幅0）に全体へ合わせると、縮尺が一番小さい所に張り付く。見えたら合わせ直す。
    if (wasHidden && lastFit && !moved && width >= 50 && height >= 50) {
      fit(lastFit[0], lastFit[1], lastFit[2], lastFit[3]);
    }

    request();
  }

  function zoomAt(px, py, factor) {
    const before = toWorld(px, py);

    view.scale = clamp(view.scale * factor, 0.02, 32);

    const after = toWorld(px, py);

    view.x += before.x - after.x;
    view.z += before.z - after.z;
    moved = true;
    changed();
  }

  function centerOn(x, z, scale) {
    moved = true;
    view.x = x;
    view.z = z;

    if (scale) {
      view.scale = clamp(scale, 0.02, 32);
    }

    changed();
  }

  function fit(minX, minZ, maxX, maxZ) {
    const spanX = Math.max(16, maxX - minX);
    const spanZ = Math.max(16, maxZ - minZ);

    view.x = (minX + maxX) / 2;
    view.z = (minZ + maxZ) / 2;
    view.scale = clamp(Math.min(width / spanX, height / spanZ) * 0.92, 0.02, 32);
    lastFit = [minX, minZ, maxX, maxZ];
    moved = false;
    changed();
  }

  function visibleBounds() {
    const a = toWorld(0, 0);
    const b = toWorld(width, height);

    return { minX: a.x, minZ: a.z, maxX: b.x, maxZ: b.z };
  }

  // ------------------------------------------------------------------
  // 覚えた地図の色
  // ------------------------------------------------------------------

  function over(under, color, alpha) {
    const a = alpha + under[3] * (1 - alpha);

    if (a <= 0) {
      return [0, 0, 0, 0];
    }

    return [
      (color[0] * alpha + under[0] * under[3] * (1 - alpha)) / a,
      (color[1] * alpha + under[1] * under[3] * (1 - alpha)) / a,
      (color[2] * alpha + under[2] * under[3] * (1 - alpha)) / a,
      a,
    ];
  }

  function ramp(stops, t) {
    const f = clamp(t, 0, 1) * (stops.length - 1);
    const i = Math.min(stops.length - 2, Math.floor(f));

    return AV.colors.mix(stops[i], stops[i + 1], f - i);
  }

  const COST_STOPS = [[70, 200, 90], [240, 220, 60], [255, 120, 40], [255, 50, 50]];
  const VISIT_STOPS = [[60, 40, 120], [40, 120, 180], [70, 200, 120], [250, 230, 80]];

  /** そのマスの色 [r, g, b, a(0..1)]。 */
  function learnedColor(read, mode, weights, most) {
    switch (mode) {
      case "trap":
        return [255, 72, 58, read.trap];
      case "water":
        return [74, 152, 255, read.water];
      case "death":
        return [206, 98, 255, read.death];
      case "flow":
        return [92, 222, 112, read.flow];
      case "cost": {
        const value = AV.data.cost(read, weights);

        if (value < 0) {
          return [92, 222, 112, clamp(-value / Math.max(0.01, weights.flow), 0, 1) * 0.5];
        }

        const color = ramp(COST_STOPS, value / 6);

        return [color[0], color[1], color[2], clamp(0.3 + value / 6, 0.3, 0.95)];
      }
      case "visits": {
        const color = ramp(VISIT_STOPS, Math.log2(1 + read.visits) / Math.log2(1 + Math.max(1, most)));

        return [color[0], color[1], color[2], 0.85];
      }
      default: {
        let out = [0, 0, 0, 0];

        out = over(out, [92, 222, 112], read.flow * 0.45);
        out = over(out, [74, 152, 255], read.water);
        out = over(out, [255, 72, 58], read.trap);
        out = over(out, [206, 98, 255], read.death * 0.9);

        return out;
      }
    }
  }

  function buildLearned() {
    const key = [memory ? memory.size : 0, memory ? memory.battles : 0, memory ? memory.modified : 0,
      settings.learnedMode, settings.fadeUnsure, JSON.stringify(settings.weights)].join("|");

    if (!memory || !memory.bounds) {
      learned = null;
      learnedKey = key;

      return;
    }

    if (learned && key === learnedKey && learned.memory === memory) {
      return;
    }

    const bounds = memory.bounds;
    const w = bounds.maxX - bounds.minX + 1;
    const h = bounds.maxZ - bounds.minZ + 1;
    const image = document.createElement("canvas");

    image.width = w;
    image.height = h;

    const imageContext = image.getContext("2d");
    const data = imageContext.createImageData(w, h);
    let most = 1;

    for (let i = 0; i < memory.size; i++) {
      most = Math.max(most, memory.visits[i]);
    }

    for (let i = 0; i < memory.size; i++) {
      const read = AV.data.reading(memory, i);
      const color = learnedColor(read, settings.learnedMode, settings.weights, most);
      let alpha = color[3];

      if (settings.fadeUnsure && settings.learnedMode !== "visits") {
        alpha *= 1 - Math.exp(-(read.visits + read.stalls * 0.5 + read.wet) / 1.5);
      }

      const out = ((memory.z[i] - bounds.minZ) * w + (memory.x[i] - bounds.minX)) * 4;

      data.data[out] = color[0];
      data.data[out + 1] = color[1];
      data.data[out + 2] = color[2];
      data.data[out + 3] = Math.round(clamp(alpha, 0, 1) * 255);
    }

    imageContext.putImageData(data, 0, 0);
    learned = { canvas: image, memory };
    learnedKey = key;
  }

  // ------------------------------------------------------------------
  // 描く
  // ------------------------------------------------------------------

  function draw() {
    context.save();
    context.fillStyle = "#070906";
    context.fillRect(0, 0, width, height);
    context.imageSmoothingEnabled = false;

    if (settings.showTerrain) {
      drawTerrain();
    }

    drawGrid();
    drawLearned();
    drawBattle();
    drawHover();
    drawScale();
    context.restore();
  }

  function drawTerrain() {
    const size = AV.terrain.SIZE;
    const bounds = visibleBounds();

    context.globalAlpha = settings.terrainLight;

    for (const tile of AV.terrain.tiles.values()) {
      const x = tile.rx * size;
      const z = tile.rz * size;

      if (x > bounds.maxX || z > bounds.maxZ || x + size < bounds.minX || z + size < bounds.minZ) {
        continue;
      }

      const at = toScreen(x, z);

      if (tile.canvas) {
        context.drawImage(tile.canvas, at.x, at.y, size * view.scale, size * view.scale);
      } else {
        // 読んでいる途中。枠だけ。
        context.globalAlpha = 1;
        context.strokeStyle = "rgba(158,222,26,.35)";
        context.setLineDash([6, 6]);
        context.strokeRect(at.x, at.y, size * view.scale, size * view.scale);
        context.setLineDash([]);
        context.fillStyle = "rgba(158,222,26,.8)";
        context.font = "12px sans-serif";
        context.fillText("地形を読んでいる " + Math.round(tile.done * 100) + "%", at.x + 8, at.y + 18);
        context.globalAlpha = settings.terrainLight;
      }
    }

    context.globalAlpha = 1;
  }

  function drawGrid() {
    const cell = memory ? memory.cell : 4;
    const step = view.scale >= 6 ? cell : view.scale >= 1.2 ? 16 : 0;

    if (!step) {
      return;
    }

    const bounds = visibleBounds();

    context.strokeStyle = step === cell ? "rgba(255,255,255,.07)" : "rgba(255,255,255,.05)";
    context.lineWidth = 1;
    context.beginPath();

    for (let x = Math.floor(bounds.minX / step) * step; x <= bounds.maxX; x += step) {
      const sx = Math.round(toScreen(x, 0).x) + 0.5;

      context.moveTo(sx, 0);
      context.lineTo(sx, height);
    }

    for (let z = Math.floor(bounds.minZ / step) * step; z <= bounds.maxZ; z += step) {
      const sy = Math.round(toScreen(0, z).y) + 0.5;

      context.moveTo(0, sy);
      context.lineTo(width, sy);
    }

    context.stroke();
  }

  function drawLearned() {
    buildLearned();

    if (!learned || settings.learnedAlpha <= 0) {
      return;
    }

    const bounds = memory.bounds;
    const at = toScreen(bounds.minX * memory.cell, bounds.minZ * memory.cell);

    context.globalAlpha = settings.learnedAlpha;
    context.drawImage(learned.canvas, at.x, at.y, learned.canvas.width * memory.cell * view.scale,
        learned.canvas.height * memory.cell * view.scale);
    context.globalAlpha = 1;
  }

  function rgba(hex, alpha) {
    const value = parseInt(hex.slice(1), 16);

    return "rgba(" + (value >> 16) + "," + ((value >> 8) & 255) + "," + (value & 255) + "," + alpha + ")";
  }

  function teamColor(team, alpha) {
    return rgba(TEAM[team] || "#e9f4de", alpha);
  }

  function phaseColor(sample, alpha) {
    return rgba(sample.evading ? EVADE : PHASE[sample.phase] || PHASE.TRANSIT, alpha);
  }

  function visibleMarker(marker) {
    switch (marker.type) {
      case "WaterEntered":
        return settings.showWater;
      case "VehicleDestroyed":
        return settings.showDeaths;
      case "Escape":
        return settings.showStuck;
      case "ObjectiveCaptured":
      case "ObjectiveRecaptured":
        return settings.showCaptures;
      case "WeaponReleased":
        return settings.showReleases;
      case "EnemyDestroyed":
        return settings.showKills;
      default:
        return false;
    }
  }

  function star(x, y, outer, inner) {
    context.beginPath();

    for (let i = 0; i < 10; i++) {
      const r = i % 2 === 0 ? outer : inner;
      const angle = -Math.PI / 2 + (i * Math.PI) / 5;
      const px = x + Math.cos(angle) * r;
      const py = y + Math.sin(angle) * r;

      if (i === 0) {
        context.moveTo(px, py);
      } else {
        context.lineTo(px, py);
      }
    }

    context.closePath();
  }

  function drawMarker(marker, faded) {
    const at = toScreen(marker.x, marker.z);
    const r = 5;

    if (at.x < -20 || at.y < -20 || at.x > width + 20 || at.y > height + 20) {
      return;
    }

    context.globalAlpha = faded ? 0.35 : 1;
    context.lineWidth = 2;

    switch (marker.type) {
      case "WaterEntered":
        context.strokeStyle = "#4f9dff";
        context.beginPath();
        context.arc(at.x, at.y, r, 0, Math.PI * 2);
        context.stroke();
        break;
      case "VehicleDestroyed":
        context.strokeStyle = "#d06cff";
        context.beginPath();
        context.moveTo(at.x - r, at.y - r);
        context.lineTo(at.x + r, at.y + r);
        context.moveTo(at.x + r, at.y - r);
        context.lineTo(at.x - r, at.y + r);
        context.stroke();

        if (marker.air) {
          context.beginPath();
          context.arc(at.x, at.y, r + 3, 0, Math.PI * 2);
          context.stroke();
        }

        break;
      case "Escape":
        context.fillStyle = "#ff5a4f";
        context.beginPath();
        context.moveTo(at.x, at.y - r - 1);
        context.lineTo(at.x + r, at.y + r - 1);
        context.lineTo(at.x - r, at.y + r - 1);
        context.closePath();
        context.fill();
        break;
      case "WeaponReleased": {
        const kind = AV.data.releaseKind(marker.detail.weapon, marker.detail.rounds);

        if (kind === "bomb") {
          context.fillStyle = "#ff9f43";
          context.strokeStyle = "#050704";
          context.lineWidth = 1.5;
          context.beginPath();
          context.arc(at.x, at.y, r - 1, 0, Math.PI * 2);
          context.fill();
          context.stroke();
        } else if (kind === "missile") {
          context.strokeStyle = "#4de0c0";
          context.beginPath();
          context.moveTo(at.x, at.y - r);
          context.lineTo(at.x + r, at.y + r - 1);
          context.lineTo(at.x - r, at.y + r - 1);
          context.closePath();
          context.stroke();
        } else {
          context.strokeStyle = "#ff9f43";
          context.lineWidth = 1.5;
          context.beginPath();
          context.arc(at.x, at.y, r - 1, 0, Math.PI * 2);
          context.stroke();
        }

        break;
      }
      case "EnemyDestroyed":
        context.fillStyle = marker.air ? "#ffd740" : "#f2ffd9";
        star(at.x, at.y, r + 1.5, (r + 1.5) * 0.45);
        context.fill();
        context.strokeStyle = "#050704";
        context.lineWidth = 1;
        context.stroke();
        break;
      default:
        context.fillStyle = "#ffcf4d";
        context.beginPath();
        context.moveTo(at.x, at.y - r - 1);
        context.lineTo(at.x + r + 1, at.y);
        context.lineTo(at.x, at.y + r + 1);
        context.lineTo(at.x - r - 1, at.y);
        context.closePath();
        context.fill();
    }

    context.globalAlpha = 1;
  }

  function drawPoints(header) {
    if (header.arena && header.arena.radius) {
      const centre = toScreen(header.arena.x, header.arena.z);

      context.strokeStyle = "rgba(158,222,26,.55)";
      context.setLineDash([10, 8]);
      context.lineWidth = 1.5;
      context.beginPath();
      context.arc(centre.x, centre.y, header.arena.radius * view.scale, 0, Math.PI * 2);
      context.stroke();
      context.setLineDash([]);
    }

    for (const point of header.points) {
      const centre = toScreen(point.x + 0.5, point.z + 0.5);
      const blocks = point.radius || 10;
      const radius = Math.max(6, blocks * view.scale);

      context.fillStyle = "rgba(255,207,77,.14)";
      context.strokeStyle = "#ffcf4d";
      context.lineWidth = 2;
      context.beginPath();
      context.arc(centre.x, centre.y, radius, 0, Math.PI * 2);
      context.fill();
      context.stroke();

      // 居座る円: この内側に入った車両はその場に留まる（ゲームの Tactics.anchors、縁から4ブロックか半径の35%）。
      if (settings.showAnchor && blocks * view.scale >= 12) {
        context.strokeStyle = "rgba(255,207,77,.7)";
        context.lineWidth = 1;
        context.setLineDash([4, 4]);
        context.beginPath();
        context.arc(centre.x, centre.y, Math.max(0, blocks - Math.min(4, blocks * 0.35)) * view.scale, 0, Math.PI * 2);
        context.stroke();
        context.setLineDash([]);
      }

      context.fillStyle = "#ffcf4d";
      context.font = "bold 14px sans-serif";
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.fillText(point.name, centre.x, centre.y);
      context.textAlign = "start";
      context.textBaseline = "alphabetic";
    }
  }

  function drawGroundTrack(bot, now) {
    const recent = now !== null;
    const from = recent && settings.trail ? now - 600 : -Infinity;
    const to = recent ? now : Infinity;

    context.lineWidth = 1.5;
    context.strokeStyle = teamColor(bot.team, recent ? 0.9 : 0.4);
    context.beginPath();

    let last = null;

    for (const sample of bot.samples) {
      if (sample.t < from || sample.t > to) {
        last = null;
        continue;
      }

      const at = toScreen(sample.x, sample.z);

      if (last && sample.t - last.t <= 600) {
        context.lineTo(at.x, at.y);
      } else {
        context.moveTo(at.x, at.y);
      }

      last = sample;
    }

    if (recent && last) {
      const position = AV.data.positionAt(bot, now);

      if (position) {
        const at = toScreen(position.x, position.z);

        context.lineTo(at.x, at.y);
      }
    }

    context.stroke();
  }

  function drawAirTrack(bot, now) {
    const recent = now !== null;
    const from = recent && settings.trail ? now - 600 : -Infinity;
    const to = recent ? now : Infinity;
    let last = null;

    context.lineWidth = recent ? 2 : 1.4;

    for (const sample of bot.samples) {
      if (sample.t < from || sample.t > to) {
        last = null;
        continue;
      }

      if (last && sample.t - last.t <= 100) {
        const a = toScreen(last.x, last.z);
        const b = toScreen(sample.x, sample.z);

        context.strokeStyle = phaseColor(last, recent ? 0.95 : 0.6);
        context.beginPath();
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
        context.stroke();
      }

      last = sample;
    }

    if (recent && last) {
      const position = AV.data.positionAt(bot, now);

      if (position) {
        const a = toScreen(last.x, last.z);
        const b = toScreen(position.x, position.z);

        context.strokeStyle = phaseColor(last, 0.95);
        context.beginPath();
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
        context.stroke();
      }
    }
  }

  /** 機体の印。heading は yRot と同じ取り方（南が0、西が90）。 */
  function drawPlane(at, heading, fill, outline) {
    const radians = (heading * Math.PI) / 180;
    const dx = -Math.sin(radians);
    const dy = Math.cos(radians);
    const px = -dy;
    const py = dx;
    const size = 9;

    context.beginPath();
    context.moveTo(at.x + dx * size, at.y + dy * size);
    context.lineTo(at.x - dx * size * 0.7 + px * size * 0.7, at.y - dy * size * 0.7 + py * size * 0.7);
    context.lineTo(at.x - dx * size * 0.3, at.y - dy * size * 0.3);
    context.lineTo(at.x - dx * size * 0.7 - px * size * 0.7, at.y - dy * size * 0.7 - py * size * 0.7);
    context.closePath();
    context.fillStyle = fill;
    context.fill();
    context.strokeStyle = outline;
    context.lineWidth = 2;
    context.stroke();
  }

  function drawBattle() {
    if (!detail) {
      return;
    }

    const now = settings.time;

    if (settings.showPoints) {
      drawPoints(detail.header);
    }

    for (const bot of detail.bots) {
      if (bot.air && settings.showAircraft) {
        drawAirTrack(bot, now);
      } else if (!bot.air && settings.showTracks) {
        drawGroundTrack(bot, now);
      }
    }

    for (const marker of detail.markers) {
      if (!visibleMarker(marker) || (now !== null && marker.t > now)) {
        continue;
      }

      drawMarker(marker, now !== null && now - marker.t > 1200);
    }

    if (now === null) {
      return;
    }

    for (const bot of detail.bots) {
      if (bot.air && !settings.showAircraft) {
        continue;
      }

      const position = AV.data.positionAt(bot, now);

      if (!position) {
        continue;
      }

      const at = toScreen(position.x, position.z);
      const sample = position.sample;

      if (bot.air) {
        if (sample.phase === "RUN" && typeof sample.tx === "number" && typeof sample.tz === "number") {
          const aim = toScreen(sample.tx, sample.tz);

          context.strokeStyle = phaseColor(sample, 0.8);
          context.lineWidth = 1.5;
          context.setLineDash([6, 5]);
          context.beginPath();
          context.moveTo(at.x, at.y);
          context.lineTo(aim.x, aim.y);
          context.stroke();
          context.setLineDash([]);
        }

        drawPlane(at, sample.heading, teamColor(bot.team, 1), phaseColor(sample, 1));
        context.fillStyle = "#e9f4de";
        context.font = "11px sans-serif";
        context.fillText("Y" + Math.round(position.y), at.x + 11, at.y - 7);
        continue;
      }

      context.fillStyle = teamColor(bot.team, 1);
      context.strokeStyle = "#050704";
      context.lineWidth = 2;
      context.beginPath();
      context.arc(at.x, at.y, 5, 0, Math.PI * 2);
      context.fill();
      context.stroke();

      if (sample.water >= 2 || sample.escaping) {
        context.strokeStyle = sample.water >= 2 ? "#4f9dff" : "#ff5a4f";
        context.beginPath();
        context.arc(at.x, at.y, 9, 0, Math.PI * 2);
        context.stroke();
      }
    }

    drawPov();
  }

  /** 視点（pov.js）で見ている機体の輪と、見ている向きの扇。 */
  function drawPov() {
    const cone = AV.pov ? AV.pov.cone() : null;

    if (!cone) {
      return;
    }

    const at = toScreen(cone.x, cone.z);
    const yaw = (cone.yaw * Math.PI) / 180;
    // yRot の前は (−sin, cos)。画面の下が +Z なので、そのまま画面の向き。
    const angle = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
    const half = (cone.fov * Math.PI) / 360;
    const reach = clamp(cone.far * view.scale, 70, 240);

    context.fillStyle = "rgba(216,255,77,.13)";
    context.strokeStyle = "rgba(216,255,77,.8)";
    context.lineWidth = 1.5;
    context.beginPath();
    context.moveTo(at.x, at.y);
    context.arc(at.x, at.y, reach, angle - half, angle + half);
    context.closePath();
    context.fill();
    context.stroke();
    context.lineWidth = 2;
    context.beginPath();
    context.arc(at.x, at.y, 13, 0, Math.PI * 2);
    context.stroke();
  }

  function drawHover() {
    const cell = memory ? memory.cell : 4;
    const target = settings.selectedCell;

    if (target) {
      const at = toScreen(target.x * cell, target.z * cell);

      context.strokeStyle = "#d8ff4d";
      context.lineWidth = 2;
      context.strokeRect(at.x, at.y, cell * view.scale, cell * view.scale);
    }

    if (!hover || view.scale < 1.5) {
      return;
    }

    const cx = Math.floor(hover.world.x / cell);
    const cz = Math.floor(hover.world.z / cell);
    const at = toScreen(cx * cell, cz * cell);

    context.strokeStyle = "rgba(242,255,217,.8)";
    context.lineWidth = 1;
    context.strokeRect(at.x + 0.5, at.y + 0.5, cell * view.scale, cell * view.scale);
  }

  function drawScale() {
    const targets = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096];
    let blocks = targets[targets.length - 1];

    for (const candidate of targets) {
      if (candidate * view.scale >= 80) {
        blocks = candidate;
        break;
      }
    }

    const length = blocks * view.scale;
    const x = width - length - 16;
    const y = height - 18;

    context.fillStyle = "rgba(5,7,4,.8)";
    context.fillRect(x - 8, y - 18, length + 16, 26);
    context.strokeStyle = "#e9f4de";
    context.lineWidth = 2;
    context.beginPath();
    context.moveTo(x, y - 4);
    context.lineTo(x, y);
    context.lineTo(x + length, y);
    context.lineTo(x + length, y - 4);
    context.stroke();
    context.fillStyle = "#e9f4de";
    context.font = "11px sans-serif";
    context.textAlign = "center";
    context.fillText(blocks + " ブロック　↑北", x + length / 2, y - 6);
    context.textAlign = "start";
  }

  /** その画面位置に一番近い、再生中の車両か出来事。 */
  function pick(px, py) {
    if (!detail) {
      return null;
    }

    let best = null;
    let bestDistance = 11;
    const now = settings.time;

    if (now !== null) {
      for (const bot of detail.bots) {
        if (bot.air && !settings.showAircraft) {
          continue;
        }

        const position = AV.data.positionAt(bot, now);

        if (!position) {
          continue;
        }

        const at = toScreen(position.x, position.z);
        const distance = Math.hypot(at.x - px, at.y - py);

        if (distance < bestDistance) {
          bestDistance = distance;
          best = { kind: "bot", bot, position };
        }
      }
    }

    for (const marker of detail.markers) {
      if (!visibleMarker(marker) || (now !== null && marker.t > now)) {
        continue;
      }

      const at = toScreen(marker.x, marker.z);
      const distance = Math.hypot(at.x - px, at.y - py);

      if (distance < bestDistance) {
        bestDistance = distance;
        best = { kind: "marker", marker };
      }
    }

    return best;
  }

  function setMemory(value) {
    memory = value;
    learned = null;
    request();
  }

  function setDetail(value) {
    detail = value;
    request();
  }

  AV.map = { init, on, request, centerOn, fit, zoomAt, visibleBounds, toWorld, toScreen, pick, setMemory, setDetail,
    learnedColor, settings, view, MODES, PHASE, EVADE, size: () => ({ width, height }) };
})(window.AV = window.AV || {});
