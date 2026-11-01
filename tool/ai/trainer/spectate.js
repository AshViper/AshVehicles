/*
 * 観戦。試合を1つページの中で回して、上から見た絵にする。学習の Worker とは別に、ページの主スレッドで回す。
 *
 * 見る物: 地形（高さの陰・林・建物・水）、拠点（持ち主の色と取りかけの弧）、車両（陣営の色の三角と砲塔の線、
 * 体力）、発砲の線。車両をクリックすると、その車両の行動・目標・持ち場・行動ごとの点数が出る。
 */
(function (global) {
  "use strict";

  const AV = {};

  AVTrainerData(AV);
  AVSim_core(AV);
  AVSim_terrain(AV);
  AVSim_brain(AV);
  AVSim_tactics(AV);
  AVSim_air(AV);
  AVSim_match(AV);

  const ROLE_LETTER = { tank: "T", ifv: "I", apc: "A", scout: "S", aa: "D", artillery: "R", support: "U" };
  const ACTION_JA = {
    capture_objective: "制圧", recapture_objective: "奪還", defend_objective: "防衛", advance: "前進", attack: "攻撃",
    flank: "側面", seek_cover: "遮蔽", retreat: "後退", search_enemy: "索敵", support_ally: "支援",
  };

  function css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  class Spectator {
    constructor(canvas, scoreboard, info) {
      this.canvas = canvas;
      this.ctx = canvas.getContext("2d");
      this.scoreboard = scoreboard;
      this.info = info;
      this.match = null;
      this.speed = 4;
      this.paused = false;
      this.selected = null;
      this.labels = ["", ""];
      this.backdrop = null;
      this.last = 0;
      this.carry = 0;

      canvas.addEventListener("click", (event) => this.pick(event));
      requestAnimationFrame((t) => this.frame(t));
    }

    start(versions, labels, seed, options) {
      this.match = new AV.Match(Object.assign({}, options, { seed, versions }));
      // 航空機は会場の外まで回るので、航空機を出す試合は周りに余白を取って描く。
      this.pad = this.match.air ? Math.round(this.match.terrain.size * 0.3) : 0;
      this.labels = labels;
      this.selected = null;
      this.backdrop = this.paintTerrain(this.match.terrain);
      this.carry = 0;
    }

    paintTerrain(terrain) {
      const n = terrain.n;
      const image = new ImageData(n, n);
      let low = Infinity;
      let high = -Infinity;

      for (let i = 0; i < n * n; i++) {
        low = Math.min(low, terrain.ground[i]);
        high = Math.max(high, terrain.ground[i]);
      }

      for (let cz = 0; cz < n; cz++) {
        for (let cx = 0; cx < n; cx++) {
          const i = cz * n + cx;
          const h = (terrain.ground[i] - low) / Math.max(high - low, 1);
          const west = cx > 0 ? terrain.ground[i - 1] : terrain.ground[i];
          const north = cz > 0 ? terrain.ground[i - n] : terrain.ground[i];
          const shade = AV.clamp(1 + (terrain.ground[i] - west + terrain.ground[i] - north) * 0.08, 0.7, 1.25);
          let r, g, b;

          if (terrain.ground[i] !== terrain.ground[i] || terrain.top[i] !== terrain.top[i]) {
            const at = i * 4;

            image.data[at] = 40; image.data[at + 1] = 40; image.data[at + 2] = 46; image.data[at + 3] = 255;
            continue;
          }

          switch (terrain.kind[i]) {
            case AV.KIND.WATER: r = 70; g = 120; b = 175; break;
            case AV.KIND.BUILDING: r = 120; g = 116; b = 110; break;
            case AV.KIND.WOOD: r = 58 + h * 30; g = 102 + h * 30; b = 52; break;
            default: r = 150 + h * 60; g = 160 + h * 50; b = 110 + h * 30;
          }

          const at = i * 4;

          image.data[at] = AV.clamp(r * shade, 0, 255);
          image.data[at + 1] = AV.clamp(g * shade, 0, 255);
          image.data[at + 2] = AV.clamp(b * shade, 0, 255);
          image.data[at + 3] = 255;
        }
      }

      const canvas = document.createElement("canvas");

      canvas.width = n;
      canvas.height = n;
      canvas.getContext("2d").putImageData(image, 0, 0);

      return canvas;
    }

    frame(time) {
      const elapsed = Math.min(time - (this.last || time), 250);

      this.last = time;

      if (this.match && !this.paused && !this.match.finished) {
        this.carry += elapsed / 50 * this.speed;

        const steps = Math.min(Math.floor(this.carry), 400);

        this.carry -= steps;

        for (let i = 0; i < steps && !this.match.step(); i++) {
          // 進める
        }
      }

      if (this.match) this.draw();

      requestAnimationFrame((t) => this.frame(t));
    }

    draw() {
      const ctx = this.ctx;
      const match = this.match;
      const size = match.terrain.size;
      const pad = this.pad || 0;
      const px = this.canvas.width;
      const scale = px / (size + pad * 2);
      const team = [css("--team0") || "#2f6fed", css("--team1") || "#d9542f"];

      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = "#1e2530";
      ctx.fillRect(0, 0, px, px);
      ctx.translate(pad * scale, pad * scale);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(this.backdrop, 0, 0, size * scale, size * scale);

      if (pad > 0) {
        ctx.strokeStyle = "rgba(255,255,255,0.25)";
        ctx.lineWidth = 1;
        ctx.setLineDash([6, 6]);
        ctx.beginPath();
        ctx.arc(match.centre.x * scale, match.centre.z * scale, match.arena * scale, 0, Math.PI * 2);
        ctx.stroke();
        ctx.setLineDash([]);
      }

      // 陣地
      match.spawns.forEach((spawn, index) => {
        ctx.strokeStyle = team[index];
        ctx.lineWidth = 2;
        ctx.strokeRect(spawn.x * scale - 10, spawn.z * scale - 10, 20, 20);
      });

      // 拠点
      for (const point of match.points) {
        const x = point.x * scale;
        const z = point.z * scale;
        const r = Math.max(point.radius * scale, 8);

        ctx.fillStyle = point.owner === null ? "rgba(255,255,255,0.35)" : team[point.owner] + "88";
        ctx.beginPath();
        ctx.arc(x, z, r, 0, Math.PI * 2);
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = point.contested ? "#ffd400" : "rgba(0,0,0,0.55)";
        ctx.stroke();

        if (point.taking !== null && point.progress > 0) {
          ctx.strokeStyle = team[point.taking];
          ctx.lineWidth = 4;
          ctx.beginPath();
          ctx.arc(x, z, r + 4, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * point.progress / AV.CAPTURE_TICKS);
          ctx.stroke();
        }

        ctx.fillStyle = "#111";
        ctx.font = "bold 11px system-ui";
        ctx.textAlign = "center";
        ctx.fillText(point.id, x, z + 4);
      }

      // 発砲
      for (const shot of match.shots) {
        if (match.tick - shot.tick > 3) continue;

        ctx.strokeStyle = shot.hit ? "rgba(255,230,120,0.9)" : "rgba(255,255,255,0.35)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(shot.x0 * scale, shot.z0 * scale);
        ctx.lineTo(shot.x1 * scale, shot.z1 * scale);
        ctx.stroke();
      }

      // 爆発と誘導弾
      for (const blast of match.blasts) {
        const age = match.tick - blast.tick;

        if (age > 20) continue;

        ctx.fillStyle = "rgba(255,160,40," + (0.6 * (1 - age / 20)).toFixed(2) + ")";
        ctx.beginPath();
        ctx.arc(blast.x * scale, blast.z * scale, Math.max(blast.radius * scale, 4), 0, Math.PI * 2);
        ctx.fill();
      }

      for (const missile of match.missiles) {
        ctx.fillStyle = "#fff";
        ctx.fillRect(missile.x * scale - 1.5, missile.z * scale - 1.5, 3, 3);
      }

      // 選んだ車両の道
      const chosen = this.selected !== null ? match.byId(this.selected) : null;

      if (chosen && chosen.alive && chosen.route) {
        ctx.strokeStyle = "rgba(255,255,255,0.8)";
        ctx.setLineDash([4, 4]);
        ctx.beginPath();
        ctx.moveTo(chosen.x * scale, chosen.z * scale);

        for (const p of chosen.route.points.slice(chosen.route.index)) ctx.lineTo(p.x * scale, p.z * scale);

        ctx.stroke();
        ctx.setLineDash([]);
      }

      // 車両
      for (const unit of match.units) {
        if (!unit.alive) continue;

        if (unit.flying) {
          this.drawPlane(ctx, unit, scale, team[unit.team]);
          continue;
        }

        const x = unit.x * scale;
        const z = unit.z * scale;
        const s = 7;

        ctx.save();
        ctx.translate(x, z);
        ctx.rotate(unit.heading);
        ctx.fillStyle = team[unit.team];
        ctx.strokeStyle = unit.id === this.selected ? "#fff" : "rgba(0,0,0,0.7)";
        ctx.lineWidth = unit.id === this.selected ? 2.5 : 1;
        ctx.beginPath();
        ctx.moveTo(s, 0);
        ctx.lineTo(-s * 0.8, -s * 0.7);
        ctx.lineTo(-s * 0.8, s * 0.7);
        ctx.closePath();
        ctx.fill();
        ctx.stroke();
        ctx.restore();

        ctx.strokeStyle = "rgba(0,0,0,0.8)";
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.moveTo(x, z);
        ctx.lineTo(x + Math.cos(unit.turret) * 11, z + Math.sin(unit.turret) * 11);
        ctx.stroke();

        const health = unit.health / unit.def.health;

        ctx.fillStyle = "rgba(0,0,0,0.5)";
        ctx.fillRect(x - 8, z - 13, 16, 3);
        ctx.fillStyle = health > 0.5 ? "#3cc486" : health > 0.25 ? "#e5b53a" : "#e0645d";
        ctx.fillRect(x - 8, z - 13, 16 * health, 3);
        ctx.fillStyle = "#fff";
        ctx.font = "bold 9px system-ui";
        ctx.textAlign = "center";
        ctx.fillText(ROLE_LETTER[unit.role] || "?", x - 1, z + 3);
      }

      this.drawBoard();
      this.drawInfo(chosen);
    }

    drawPlane(ctx, unit, scale, color) {
      const x = unit.x * scale;
      const z = unit.z * scale;
      const s = 9;

      ctx.save();
      ctx.translate(x, z);
      ctx.rotate(unit.heading);
      ctx.fillStyle = color;
      ctx.strokeStyle = unit.id === this.selected ? "#fff" : "rgba(0,0,0,0.8)";
      ctx.lineWidth = unit.id === this.selected ? 2.5 : 1;
      ctx.beginPath();
      ctx.moveTo(s, 0);
      ctx.lineTo(-s * 0.3, -s * 0.9);
      ctx.lineTo(-s * 0.1, 0);
      ctx.lineTo(-s * 0.3, s * 0.9);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.restore();

      if (unit.target && unit.id === this.selected) {
        ctx.strokeStyle = "rgba(255,255,255,0.5)";
        ctx.setLineDash([2, 4]);
        ctx.beginPath();
        ctx.moveTo(x, z);
        ctx.lineTo(unit.target.x * scale, unit.target.z * scale);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }

    drawBoard() {
      const match = this.match;
      const seconds = Math.floor(match.tick / 20);
      const clock = Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
      const alive = [0, 1].map((t) => match.units.filter((u) => u.alive && u.team === t).length);
      const result = match.finished
        ? (match.winner === null ? "引き分け" : this.labels[match.winner] + " の勝ち") : clock;

      this.scoreboard.innerHTML =
        [0, 1].map((t) => '<div class="team t' + t + '"><span>' + escapeHtml(this.labels[t]) + "</span><span>"
          + Math.max(match.teams[t].tickets, 0) + "</span></div>"
          + '<div class="muted">稼働 ' + alive[t] + " ・ 撃破 " + match.stats[t].kills + " ・ 制圧 " + match.stats[t].captures
          + " ・ 空 " + match.stats[t].airKills + "/" + match.stats[t].airLosses + " ・ 水 " + match.stats[t].waterEntries
          + " ・ 詰まり " + match.stats[t].stalls
          + "</div>").join("") + '<div class="muted">' + result + "</div>";
    }

    drawInfo(unit) {
      if (!unit || !unit.alive) {
        if (this.selected !== null) this.info.textContent = "（撃破された）";
        return;
      }

      if (unit.flying) {
        this.info.textContent = [
          unit.def.id + "（" + unit.kind + "）" + this.labels[unit.team],
          "体力 " + Math.round(unit.health) + " / " + unit.def.health + "   速さ " + unit.speed.toFixed(1),
          "状態 " + unit.state + (unit.evading(this.match.tick) ? "（回避中）" : ""),
          "相手 " + (unit.target ? unit.target.def.id + " " + Math.round(AV.dist2(unit.x, unit.z, unit.target.x, unit.target.z))
            + "m（" + unit.targetStore + "、点数 " + unit.targetScore.toFixed(2) + "）" : "なし"),
          "残り 機関砲 " + unit.ammo.gun + " ・ 爆弾 " + unit.ammo.bomb + " ・ 空対空 " + unit.ammo.aam,
        ].join("\n");
        return;
      }

      const s = unit.state;
      const lines = [
        unit.def.id + "（" + unit.role + "）" + this.labels[unit.team],
        "体力 " + Math.round(unit.health) + " / " + unit.def.health + "   弾 " + Math.round(unit.ammoFraction() * 100) + "%",
        "行動 " + (ACTION_JA[unit.action] || unit.action) + "  " + Math.round((this.match.tick - unit.actionSince) / 20) + " 秒",
        "持ち場 " + (unit.objective ? unit.objective.id + "（" + unit.objective.state + "）" : "なし"),
        "目標 " + (unit.target ? unit.target.unit.def.id + " " + Math.round(unit.target.distance) + "m"
          + (unit.target.visible ? "" : "（見えない）") : "なし"),
        "脅威 " + unit.threat.toFixed(2) + "  撃てる敵 " + unit.engaging,
      ];

      if (s) {
        const raw = {};

        AV.brain.decide(unit.weights.policy, Object.assign({}, s, { currentAction: unit.action }), raw);
        lines.push("", "行動の点数:");

        for (const [action, score] of Object.entries(raw).sort((a, b) => b[1] - a[1])) {
          lines.push("  " + (ACTION_JA[action] || action).padEnd(3, "　") + " " + score.toFixed(3));
        }
      }

      this.info.textContent = lines.join("\n");
    }

    pick(event) {
      if (!this.match) return;

      const rect = this.canvas.getBoundingClientRect();
      const pad = this.pad || 0;
      const scale = (this.match.terrain.size + pad * 2) / rect.width;
      const x = (event.clientX - rect.left) * scale - pad;
      const z = (event.clientY - rect.top) * scale - pad;
      let best = null;
      let bestDistance = 14 * scale;

      for (const unit of this.match.units) {
        if (!unit.alive) continue;

        const d = AV.dist2(unit.x, unit.z, x, z);

        if (d < bestDistance) {
          bestDistance = d;
          best = unit;
        }
      }

      this.selected = best ? best.id : null;

      if (!best) this.info.textContent = "車両をクリックすると判断が見える";
    }
  }

  function escapeHtml(text) {
    return String(text).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  global.AVSpectator = { Spectator, AV, ACTION_JA, escapeHtml };
})(window);
