/*
 * 機体の視点。再生中の1機（地上の車両でも航空機でも）を選び、その位置と向きからワールドの地形を見た絵を、地図の上の窓に
 * 描く（2026-09-13 の指示「ビュワーのほうで、機体の視点を見れるようにしたい」）。
 *
 * 地形は高さ地図の光線追跡（ボクセルスペース）。terrain.js が読んだリージョンの列の高さと色（上から見た地図と同じ色と陰）を、
 * 画面の列ごとに手前から奥へ辿り、前に塗った所より上に出た分だけ縦の帯で塗る。持っているのは列の一番上のブロックだけなので、
 * 橋の下・張り出し・洞窟は無く、木は葉の高さの柱に見える。縦の傾き（ピッチ）は地平線を上下にずらし（60度まで。深く傾けると
 * 縦に伸びて見える）、横の傾き（ロール）は描いた絵を回して出す。
 *
 * 向きは記録から推す:
 *   航空機  flights.jsonl の heading（動いていれば速度の向き。yRot の取り方で南が0・西が90）を1秒おきの行の間で補間する。
 *           ピッチは climb ÷ speed（速度の傾き）、ロールは向きの変わる速さからの釣り合い旋回のバンク
 *           （tan = 速さ × 回る速さ ÷ 重力 0.0245）。どちらも記録には無い推定
 *   地上    decisions.jsonl に向きは無い。次の行への動きの向き（後退中は逆）で、止まっていれば前の向き。行は最長5秒おき
 *
 * 地形は地図に見えている範囲しか読まれないので、開いている間は機体の周りのリージョンを近い順に読ませる
 * （AV.app.readTerrainAround）。
 */
(function (AV) {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const short = (id) => String(id || "").replace(/^[^:]*:/, "");
  const ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" };
  const esc = (text) => String(text === undefined || text === null ? "" : text).replace(/[&<>"]/g, (c) => ESCAPES[c]);

  const RAD = Math.PI / 180;
  /** terrain.js の「保存されていない列」。 */
  const NONE = -32768;
  /** 釣り合い旋回のバンクを出す重力（ブロック/tick²、9.8 m/s²）。 */
  const GRAVITY = 0.0245;
  const TEAM = { red: "#ff6b5e", blue: "#5ea8ff" };
  const HUD = "#d8ff4d";
  const SKY_TOP = [34, 64, 112];
  const SKY_HAZE = [156, 180, 198];
  const GROUND_HAZE = [104, 116, 108];
  const VOID = [30, 36, 32];
  /** 見える距離（ブロック）。 */
  const FAR_AIR = 1600;
  const FAR_GROUND = 800;
  /** 描く絵の横の画素数の目安。窓がこれより大きければ引き伸ばす。 */
  const DETAIL = 640;
  /** 縦の傾きの上限（度）。 */
  const PITCH_LIMIT = 60;
  /**
   * 地上の車両の操縦席で地形を見始める距離（ブロック）。車両自身の列を飛ばす。高さ地図は屋根と木の葉も地面と数えるので、目の上に
   * 被さる列（木の下、行の間を真っ直ぐ結んで建物を突っ切った所）から塗ると画面を全部塞ぐ壁になる。そういう列は、目より低い列に
   * 出るまで塗らない（paint の covered）。
   */
  const NEAR_GROUND = 1.5;
  /** 機体の周りの地形を読む半径（ブロック）。 */
  const TERRAIN_AIR = 1100;
  const TERRAIN_GROUND = 700;
  /** 描き直しの最短の間（ミリ秒）。再生中に1コマの光線追跡が再生を詰まらせないように。 */
  const FRAME_GAP = 28;
  /** yRot の取り方の方位（0 が南、45度おき）。 */
  const COMPASS = ["南", "南西", "西", "北西", "北", "北東", "東", "南東"];
  const FONT = "'Yu Gothic UI', Meiryo, sans-serif";
  const MONO = "ui-monospace, Consolas, monospace";

  const view = { botId: null, mode: "cockpit", fov: 80, lookYaw: 0, lookPitch: 0, follow: false, big: false };

  const root = $("pov");
  const canvas = $("pov-canvas");
  const context = canvas.getContext("2d");
  const note = $("pov-note");
  const picker = $("pov-bot");

  let width = 0;
  let height = 0;
  let ratio = 1;
  let open = false;
  let loop = 0;
  let drawnKey = "";
  let paintedAt = 0;
  let filledFor = null;
  let terrainEpoch = 0;
  let buffer = null;
  let shot = null;
  let drag = null;
  let noteHtml = "";

  const colorCache = new WeakMap();
  const yawCache = new WeakMap();
  let tileRx = 0x7fffffff;
  let tileRz = 0x7fffffff;
  let tileHit = null;
  let tileColors = null;

  function clamp(value, low, high) {
    return Math.max(low, Math.min(high, value));
  }

  /** -180〜180 度に。 */
  function wrap(degrees) {
    return ((((degrees + 180) % 360) + 360) % 360) - 180;
  }

  /** 角 from から to へ、近い回り方で f だけ。 */
  function turn(from, to, f) {
    return from + wrap(to - from) * f;
  }

  function detail() {
    return AV.state ? AV.state.detail : null;
  }

  function time() {
    return AV.map.settings.time;
  }

  function sameId(a, b) {
    return String(a) === String(b);
  }

  function current() {
    const battle = detail();

    return battle && view.botId !== null ? battle.bots.find((item) => sameId(item.id, view.botId)) || null : null;
  }

  function compass(yaw) {
    return COMPASS[Math.round((((yaw % 360) + 360) % 360) / 45) % 8];
  }

  function distanceText(blocks) {
    return blocks >= 1000 ? (blocks / 1000).toFixed(1) + " km" : Math.round(blocks) + " m";
  }

  // ------------------------------------------------------------------
  // 姿勢
  // ------------------------------------------------------------------

  /** t 以前で一番新しい行の番号。 */
  function indexAt(samples, t) {
    let low = 0;
    let high = samples.length - 1;

    while (low < high) {
      const middle = (low + high + 1) >> 1;

      if (samples[middle].t <= t) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }

    return low;
  }

  /** 速度の傾き（度）。回転翼機は機首と進む向きが一致しないので浅く抑える。 */
  function climbAngle(sample, rotorcraft) {
    const speed = sample.speed || 0;

    if (speed < 0.05) {
      return 0;
    }

    const pitch = Math.asin(clamp((sample.climb || 0) / speed, -1, 1)) / RAD;

    return rotorcraft ? clamp(pitch, -20, 20) : pitch;
  }

  /** その行の前後の向きの変わる速さから、釣り合い旋回のバンク（度、右が正）。 */
  function bankAt(samples, i, rotorcraft) {
    const here = samples[i];
    const before = i > 0 && here.t - samples[i - 1].t <= 200 ? samples[i - 1] : here;
    const after = i + 1 < samples.length && samples[i + 1].t - here.t <= 200 ? samples[i + 1] : here;

    if (after.t <= before.t) {
      return 0;
    }

    // yRot は右へ回ると増える（南を向いて右は西）。
    const rate = (wrap(after.heading - before.heading) / (after.t - before.t)) * RAD;
    const limit = rotorcraft ? 30 : 75;

    return clamp(Math.atan(((here.speed || 0) * rate) / GRAVITY) / RAD, -limit, limit);
  }

  function airPose(item, t, position) {
    const samples = item.samples;
    const i = indexAt(samples, t);
    const a = samples[i];
    const b = i + 1 < samples.length && samples[i + 1].t - a.t <= 200 ? samples[i + 1] : null;
    const f = b ? clamp((t - a.t) / Math.max(1, b.t - a.t), 0, 1) : 0;
    const mix = (from, to) => (b ? from + (to - from) * f : from);

    return {
      air: true, known: true, x: position.x, y: position.y, z: position.z, sample: a,
      yaw: b ? turn(a.heading, b.heading, f) : a.heading,
      pitch: mix(climbAngle(a, item.rotorcraft), b ? climbAngle(b, item.rotorcraft) : 0),
      roll: mix(bankAt(samples, i, item.rotorcraft), b ? bankAt(samples, i + 1, item.rotorcraft) : 0),
      speed: mix(a.speed || 0, b ? b.speed || 0 : 0),
    };
  }

  /** 地上の行ごとの向き（yRot の度）。次の行への動きから。止まっている行は前の向き、最初に動くまでは最初の向き。 */
  function groundYaws(item) {
    let yaws = yawCache.get(item);

    if (yaws) {
      return yaws;
    }

    const samples = item.samples;

    yaws = new Float32Array(samples.length).fill(NaN);

    for (let i = 0; i + 1 < samples.length; i++) {
      const a = samples[i];
      const b = samples[i + 1];
      const dx = b.x - a.x;
      const dz = b.z - a.z;

      if (b.t - a.t > 600 || dx * dx + dz * dz < 1) {
        continue;
      }

      yaws[i] = Math.atan2(-dx, dz) / RAD + (a.backing ? 180 : 0);
    }

    let carry = NaN;

    for (let i = 0; i < yaws.length; i++) {
      if (Number.isNaN(yaws[i])) {
        yaws[i] = carry;
      } else {
        carry = yaws[i];
      }
    }

    carry = NaN;

    for (let i = yaws.length - 1; i >= 0; i--) {
      if (Number.isNaN(yaws[i])) {
        yaws[i] = carry;
      } else {
        carry = yaws[i];
      }
    }

    yawCache.set(item, yaws);

    return yaws;
  }

  /** 一度も動いていない車両の向き。持ち場の拠点へ、無ければ戦域の中心へ。 */
  function facingGoal(sample, position) {
    const header = detail().header;
    const point = header.points.find((entry) => entry.name === short(sample.objective)) || null;
    const goal = point ? { x: point.x + 0.5, z: point.z + 0.5 }
        : header.arena && typeof header.arena.x === "number" ? header.arena : null;

    if (!goal) {
      return 0;
    }

    const dx = goal.x - position.x;
    const dz = goal.z - position.z;

    return dx * dx + dz * dz < 1 ? 0 : Math.atan2(-dx, dz) / RAD;
  }

  function groundPose(item, t, position) {
    const samples = item.samples;
    const i = indexAt(samples, t);
    const a = samples[i];
    const b = i + 1 < samples.length && samples[i + 1].t - a.t <= 600 ? samples[i + 1] : null;
    const yaws = groundYaws(item);
    const known = !Number.isNaN(yaws[i]);
    let yaw = known ? yaws[i] : facingGoal(a, position);

    // 行が替わる所で向きが跳ばないように、前の行の向きから1秒かけて回す。
    if (known && i > 0 && !Number.isNaN(yaws[i - 1])) {
      yaw = turn(yaws[i - 1], yaws[i], clamp((t - a.t) / 20, 0, 1));
    }

    return {
      air: false, known, x: position.x, y: position.y, z: position.z, sample: a, yaw, pitch: 0, roll: 0,
      speed: b ? Math.hypot(b.x - a.x, b.z - a.z) / Math.max(1, b.t - a.t) : 0,
    };
  }

  function poseAt(item, t) {
    if (!item || t === null) {
      return null;
    }

    const position = AV.data.positionAt(item, t);

    if (!position) {
      return null;
    }

    return item.air ? airPose(item, t, position) : groundPose(item, t, position);
  }

  /** 目の位置と向き。操縦席は機体の中、後方は機体の後ろ上。ドラッグで足した見回しと、地面より下へ潜らない分を入れる。 */
  function cameraFor(pose) {
    const yaw = pose.yaw + view.lookYaw;
    let eye;
    let pitch;
    let roll;

    if (view.mode === "chase") {
      const back = pose.air ? 45 : 18;
      const rise = pose.air ? 10 : 7;

      eye = { x: pose.x + Math.sin(yaw * RAD) * back, y: pose.y + rise, z: pose.z - Math.cos(yaw * RAD) * back };
      pitch = pose.pitch * 0.5 - Math.atan2(rise - 2, back) / RAD + view.lookPitch;
      roll = pose.roll * 0.35;
    } else {
      const ahead = pose.air ? 2 : 0;

      eye = {
        x: pose.x - Math.sin(pose.yaw * RAD) * ahead, y: pose.y + (pose.air ? 1.6 : 2.8),
        z: pose.z + Math.cos(pose.yaw * RAD) * ahead,
      };
      pitch = pose.pitch + view.lookPitch;
      roll = pose.roll;
    }

    // 地上の車両の高さは記録の通り（真上に屋根や木の葉があっても、その下を走っている）。地面へ潜らせないのは航空機と後方の目だけ。
    const lifted = pose.air || view.mode === "chase";
    const ground = lifted ? surfaceAt(eye.x, eye.z) : null;

    if (ground !== null && eye.y < ground + 1.5) {
      eye.y = ground + 1.5;
    }

    return {
      eye, yaw, pitch: clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT), roll, far: pose.air ? FAR_AIR : FAR_GROUND,
      near: lifted ? 0.5 : NEAR_GROUND, covered: !lifted,
    };
  }

  // ------------------------------------------------------------------
  // 地形
  // ------------------------------------------------------------------

  /** リージョンは 512（2^9）ブロック。読み終えていなければ null。色は地図の絵から一度だけ写す。 */
  function tileAt(rx, rz) {
    if (rx !== tileRx || rz !== tileRz) {
      const tile = AV.terrain.tile(rx, rz);

      tileRx = rx;
      tileRz = rz;
      tileHit = tile && tile.state === "ready" && tile.canvas ? tile : null;
      tileColors = tileHit ? colorsOf(tileHit) : null;
    }

    return tileHit;
  }

  function colorsOf(tile) {
    let colors = colorCache.get(tile);

    if (!colors) {
      colors = tile.canvas.getContext("2d").getImageData(0, 0, AV.terrain.SIZE, AV.terrain.SIZE).data;
      colorCache.set(tile, colors);
    }

    return colors;
  }

  /** その位置の地面の面の高さ（一番上のブロックの上）。読んでいなければ null。 */
  function surfaceAt(x, z) {
    const bx = Math.floor(x);
    const bz = Math.floor(z);
    const tile = tileAt(bx >> 9, bz >> 9);

    if (!tile) {
      return null;
    }

    const top = tile.heights[((bz & 511) << 9) | (bx & 511)];

    return top === NONE ? null : top + 1;
  }

  /** 周りにまだ読んでいないリージョンがあるか。 */
  function wantsTerrain(x, z, radius) {
    const source = AV.terrain.source;

    if (!source) {
      return false;
    }

    for (let rx = Math.floor((x - radius) / 512); rx <= Math.floor((x + radius) / 512); rx++) {
      for (let rz = Math.floor((z - radius) / 512); rz <= Math.floor((z + radius) / 512); rz++) {
        const key = rx + "," + rz;

        if (source.has(key) && !AV.terrain.tiles.has(key)) {
          return true;
        }
      }
    }

    return false;
  }

  // ------------------------------------------------------------------
  // 描く
  // ------------------------------------------------------------------

  function ensureBuffer(side) {
    if (buffer && buffer.side >= side) {
      return buffer;
    }

    const surface = document.createElement("canvas");

    surface.width = side;
    surface.height = side;

    const drawing = surface.getContext("2d");
    const image = drawing.createImageData(side, side);

    buffer = {
      side, canvas: surface, context: drawing, image, pixels: new Uint32Array(image.data.buffer),
      depth: new Float32Array(side * side),
    };

    return buffer;
  }

  function pack(red, green, blue) {
    return (255 << 24) | (blue << 16) | (green << 8) | red;
  }

  function blend(a, b, f) {
    return pack(Math.round(a[0] + (b[0] - a[0]) * f), Math.round(a[1] + (b[1] - a[1]) * f),
        Math.round(a[2] + (b[2] - a[2]) * f));
  }

  /**
   * 地形の絵を作る。回さない向きで、回したときに窓の角まで届く大きさに描く。返すのは絵の大きさと、世界を絵に写す数字
   * （focal・horizon）と、1画素の窓の上の大きさの逆数（quality）。
   */
  function paint(cam) {
    const quality = clamp(DETAIL / width, 0.3, 1);
    const across = Math.max(32, Math.round(width * quality));
    const tall = Math.max(24, Math.round(height * quality));
    const cos = Math.abs(Math.cos(cam.roll * RAD));
    const sin = Math.abs(Math.sin(cam.roll * RAD));
    const bw = Math.ceil(across * cos + tall * sin) + 2;
    const bh = Math.ceil(across * sin + tall * cos) + 2;
    const store = ensureBuffer(Math.ceil(Math.hypot(across, tall)) + 3);
    const side = store.side;
    const pixels = store.pixels;
    const depth = store.depth;
    const focal = across / 2 / Math.tan((view.fov * RAD) / 2);
    const horizon = bh / 2 + focal * Math.tan(cam.pitch * RAD);
    const far = cam.far;

    // 空と、読んでいない地面。
    for (let row = 0; row < bh; row++) {
      const above = horizon - row;
      const color = above > 0 ? blend(SKY_HAZE, SKY_TOP, Math.sqrt(clamp(above / (focal * 1.2), 0, 1)))
          : blend(GROUND_HAZE, VOID, Math.sqrt(clamp(-above / (focal * 0.8), 0, 1)));
      const start = row * side;

      pixels.fill(color, start, start + bw);
      depth.fill(Infinity, start, start + bw);
    }

    const eyeX = cam.eye.x;
    const eyeY = cam.eye.y;
    const eyeZ = cam.eye.z;
    const forwardX = -Math.sin(cam.yaw * RAD);
    const forwardZ = Math.cos(cam.yaw * RAD);
    const rightX = -Math.cos(cam.yaw * RAD);
    const rightZ = -Math.sin(cam.yaw * RAD);
    const fogFrom = far * 0.3;
    const fogSpan = far - fogFrom;

    // 前の絵の後に読み終えたリージョンを拾う。
    tileRx = 0x7fffffff;

    for (let column = 0; column < bw; column++) {
      // 列の光線。奥行き z（前向きの距離）1つぶん進むと、横へ lateral だけずれる。
      const lateral = (column + 0.5 - bw / 2) / focal;
      const stepX = forwardX + lateral * rightX;
      const stepZ = forwardZ + lateral * rightZ;
      let floor = bh;
      let last = NaN;
      let z = cam.near;
      let covered = cam.covered;

      while (z < far) {
        const bx = Math.floor(eyeX + stepX * z);
        const bz = Math.floor(eyeZ + stepZ * z);
        const tile = tileAt(bx >> 9, bz >> 9);

        if (tile !== null) {
          const at = ((bz & 511) << 9) | (bx & 511);
          const top = tile.heights[at];

          // 目の上に被さっている列は、目より低い列に出るまで塗らない（NEAR_GROUND）。
          if (covered && top !== NONE && top + 1 > eyeY) {
            z += z < 40 ? 0.5 : z * 0.0125;
            continue;
          }

          if (top !== NONE) {
            covered = false;

            const surface = top + 1;
            const row = horizon + ((eyeY - surface) * focal) / z;

            if (row < floor) {
              const first = row <= 0 ? 0 : Math.ceil(row);

              if (first < floor) {
                const k = at << 2;
                // 奥へ向かって地面が上がった所は、こちらを向いた壁。少し暗く。
                const shade = surface > last + 0.5 ? 0.8 : 1;
                let red = tileColors[k] * shade;
                let green = tileColors[k + 1] * shade;
                let blue = tileColors[k + 2] * shade;

                if (z > fogFrom) {
                  const fog = (z - fogFrom) / fogSpan;
                  const f = fog * fog;

                  red += (SKY_HAZE[0] - red) * f;
                  green += (SKY_HAZE[1] - green) * f;
                  blue += (SKY_HAZE[2] - blue) * f;
                }

                const color = pack(red | 0, green | 0, blue | 0);

                for (let r = first; r < floor; r++) {
                  const p = r * side + column;

                  pixels[p] = color;
                  depth[p] = z;
                }

                floor = first;

                if (floor <= 0) {
                  break;
                }
              }
            }

            last = surface;
          }
        }

        z += z < 40 ? 0.5 : z * 0.0125;
      }
    }

    store.context.putImageData(store.image, 0, 0, 0, 0, bw, bh);

    return { bw, bh, quality, focal, horizon };
  }

  /** 世界の点を窓の上の点へ。後ろの点は behind と、横と上下のずれ（矢印の向きのため）。 */
  function projector(cam, frame) {
    const forwardX = -Math.sin(cam.yaw * RAD);
    const forwardZ = Math.cos(cam.yaw * RAD);
    const rightX = -Math.cos(cam.yaw * RAD);
    const rightZ = -Math.sin(cam.yaw * RAD);
    const cos = Math.cos(-cam.roll * RAD);
    const sin = Math.sin(-cam.roll * RAD);

    return (x, y, z) => {
      const dx = x - cam.eye.x;
      const dy = y - cam.eye.y;
      const dz = z - cam.eye.z;
      const ahead = dx * forwardX + dz * forwardZ;
      const across = dx * rightX + dz * rightZ;

      if (ahead < 1) {
        return { behind: true, across, up: dy, ahead };
      }

      const bx = frame.bw / 2 + (across * frame.focal) / ahead;
      const by = frame.horizon - (dy * frame.focal) / ahead;
      const sx = (bx - frame.bw / 2) / frame.quality;
      const sy = (by - frame.bh / 2) / frame.quality;

      return {
        behind: false, x: width / 2 + sx * cos - sy * sin, y: height / 2 + sx * sin + sy * cos, bx, by, ahead,
        scale: frame.focal / ahead / frame.quality,
      };
    };
  }

  function onScreen(p, margin) {
    return !p.behind && p.x > -margin && p.y > -margin && p.x < width + margin && p.y < height + margin;
  }

  /** その点の手前に地形があるか。 */
  function occluded(p, frame) {
    const bx = Math.floor(p.bx);
    const by = Math.floor(p.by);

    if (bx < 0 || by < 0 || bx >= frame.bw || by >= frame.bh) {
      return false;
    }

    return buffer.depth[by * buffer.side + bx] < p.ahead * 0.97 - 3;
  }

  function draw(item, t) {
    const pose = poseAt(item, t);

    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.fillStyle = "#000";
    context.fillRect(0, 0, width, height);

    if (!pose) {
      shot = null;
      explain(item, t);

      return;
    }

    explain(null, t);

    const cam = cameraFor(pose);
    const frame = paint(cam);
    const project = projector(cam, frame);

    context.save();
    context.translate(width / 2, height / 2);
    context.rotate(-cam.roll * RAD);
    context.imageSmoothingEnabled = true;
    context.drawImage(buffer.canvas, 0, 0, frame.bw, frame.bh, -frame.bw / frame.quality / 2,
        -frame.bh / frame.quality / 2, frame.bw / frame.quality, frame.bh / frame.quality);
    context.restore();

    shot = { pose, cam, frame, marks: [] };
    drawPoints(project);
    drawBlasts(project, item, t);
    drawMachines(project, frame, cam, item, t);
    drawOwn(project, pose, item);
    drawTarget(project, cam, pose);
    drawHud(project, cam, frame, pose, item, t);
  }

  /** 拠点。地面の円と、名前を載せた柱。 */
  function drawPoints(project) {
    for (const point of detail().header.points) {
      const cx = point.x + 0.5;
      const cz = point.z + 0.5;
      const ground = surfaceAt(cx, cz);
      const base = ground !== null ? ground : point.y + 1;
      const radius = point.radius || 10;
      let pen = false;

      context.strokeStyle = "rgba(255,207,77,.85)";
      context.lineWidth = 2;
      context.beginPath();

      for (let i = 0; i <= 40; i++) {
        const angle = (i / 40) * Math.PI * 2;
        const p = project(cx + Math.cos(angle) * radius, base + 0.3, cz + Math.sin(angle) * radius);

        if (p.behind) {
          pen = false;
          continue;
        }

        if (pen) {
          context.lineTo(p.x, p.y);
        } else {
          context.moveTo(p.x, p.y);
        }

        pen = true;
      }

      context.stroke();

      const foot = project(cx, base, cz);
      const head = project(cx, base + 40, cz);

      if (foot.behind || head.behind || !(onScreen(foot, 40) || onScreen(head, 40))) {
        continue;
      }

      context.strokeStyle = "rgba(255,207,77,.9)";
      context.lineWidth = 3;
      context.beginPath();
      context.moveTo(foot.x, foot.y);
      context.lineTo(head.x, head.y);
      context.stroke();
      context.fillStyle = "#ffcf4d";
      context.font = "bold 14px " + FONT;
      context.textAlign = "center";
      context.fillText(point.name, head.x, head.y - 6);
      context.font = "11px " + FONT;
      context.fillText(distanceText(Math.hypot(cx - shot.cam.eye.x, base - shot.cam.eye.y, cz - shot.cam.eye.z)),
          head.x, head.y - 22);
      context.textAlign = "start";
    }
  }

  /** 直前5秒に倒された所の爆発と、ほかの機体が直前に撃った所。 */
  function drawBlasts(project, item, t) {
    const markers = detail().markers;
    let low = 0;
    let high = markers.length;

    while (low < high) {
      const middle = (low + high) >> 1;

      if (markers[middle].t < t - 100) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }

    for (let i = low; i < markers.length && markers[i].t <= t; i++) {
      const marker = markers[i];
      const age = (t - marker.t) / 100;
      const shooting = marker.type === "WeaponReleased";

      if ((marker.type !== "VehicleDestroyed" && !shooting) || (shooting && (age > 0.3 || sameId(marker.bot, item.id)))) {
        continue;
      }

      const ground = typeof marker.y === "number" ? marker.y : surfaceAt(marker.x, marker.z);

      if (ground === null) {
        continue;
      }

      const p = project(marker.x, ground + 1.5, marker.z);

      if (!onScreen(p, 60)) {
        continue;
      }

      context.globalAlpha = clamp(1 - (shooting ? age / 0.3 : age), 0, 1);
      context.strokeStyle = shooting ? "#ff9f43" : "#ffb14a";
      context.lineWidth = shooting ? 1.5 : 3;
      context.beginPath();
      context.arc(p.x, p.y, shooting ? 5 : clamp(p.scale * (3 + age * 12), 6, 90), 0, Math.PI * 2);
      context.stroke();

      if (!shooting) {
        context.fillStyle = "#ffb14a";
        context.font = "11px " + FONT;
        context.fillText("撃破 " + short(marker.vehicle), p.x + 8, p.y - 8);
      }

      context.globalAlpha = 1;
    }
  }

  /** ほかの車両と機体。陣営の色の枠で、地形の向こうは点線。 */
  function drawMachines(project, frame, cam, item, t) {
    for (const other of detail().bots) {
      if (other === item) {
        continue;
      }

      const position = AV.data.positionAt(other, t);

      if (!position) {
        continue;
      }

      const p = project(position.x, position.y + (other.air ? 1.5 : 1.8), position.z);

      if (!onScreen(p, 30)) {
        continue;
      }

      const hidden = occluded(p, frame);
      const size = clamp(p.scale * (other.air ? 7 : 4), 4, 48);
      const color = TEAM[other.team] || "#e9f4de";
      const far = Math.hypot(position.x - cam.eye.x, position.y - cam.eye.y, position.z - cam.eye.z);

      context.globalAlpha = hidden ? 0.55 : 1;
      context.strokeStyle = color;
      context.lineWidth = 2;
      context.setLineDash(hidden ? [3, 3] : []);
      context.beginPath();

      if (other.air) {
        context.moveTo(p.x, p.y - size);
        context.lineTo(p.x + size, p.y);
        context.lineTo(p.x, p.y + size);
        context.lineTo(p.x - size, p.y);
        context.closePath();
      } else {
        context.rect(p.x - size, p.y - size * 0.7, size * 2, size * 1.4);
      }

      context.stroke();
      context.setLineDash([]);

      if (far < 2500) {
        context.fillStyle = color;
        context.font = "11px " + FONT;
        context.fillText((other.air ? "✈" : "") + short(other.vehicle) + " " + distanceText(far), p.x + size + 4,
            p.y - size + 2);
      }

      context.globalAlpha = 1;
      shot.marks.push({ x: p.x, y: p.y, id: other.id });
    }
  }

  /** 後方から見ているときの自分の機体。向きと傾きの分かる線の形。 */
  function drawOwn(project, pose, item) {
    if (view.mode !== "chase") {
      return;
    }

    const yaw = pose.yaw * RAD;
    const pitch = pose.pitch * RAD;
    const roll = pose.roll * RAD;
    const forward = [-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)];
    const right = [-Math.cos(yaw), 0, -Math.sin(yaw)];
    const up = [right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2],
      right[0] * forward[1] - right[1] * forward[0]];
    // 右へ傾けると右の翼が下がり、背が右へ倒れる。
    const wing = right.map((value, i) => value * Math.cos(roll) - up[i] * Math.sin(roll));
    const back = up.map((value, i) => value * Math.cos(roll) + right[i] * Math.sin(roll));
    const lift = pose.air ? 1.5 : 0.3;
    const at = (along, aside, above) => project(
        pose.x + forward[0] * along + wing[0] * aside + back[0] * above,
        pose.y + lift + forward[1] * along + wing[1] * aside + back[1] * above,
        pose.z + forward[2] * along + wing[2] * aside + back[2] * above);
    const outline = pose.air
        ? [at(7, 0, 0), at(-1.5, 7, 0), at(-6, 0, 0), at(-1.5, -7, 0)]
        : [at(4, 2, 0), at(-4, 2, 0), at(-4, -2, 0), at(4, -2, 0)];

    if (outline.some((p) => p.behind)) {
      return;
    }

    const color = TEAM[item.team] || "#e9f4de";

    context.beginPath();
    outline.forEach((p, i) => (i ? context.lineTo(p.x, p.y) : context.moveTo(p.x, p.y)));
    context.closePath();
    context.globalAlpha = 0.35;
    context.fillStyle = color;
    context.fill();
    context.globalAlpha = 1;
    context.strokeStyle = color;
    context.lineWidth = 2;
    context.stroke();

    const from = pose.air ? at(-5.5, 0, 0) : at(0, 0, 1.4);
    const to = pose.air ? at(-5.5, 0, 3) : at(6, 0, 1.4);

    if (!from.behind && !to.behind) {
      context.beginPath();
      context.moveTo(from.x, from.y);
      context.lineTo(to.x, to.y);
      context.stroke();
    }
  }

  /** 航空機の狙っている相手。窓の中なら枠、外なら縁の矢印。 */
  function drawTarget(project, cam, pose) {
    const sample = pose.sample;

    if (!pose.air || typeof sample.tx !== "number" || typeof sample.tz !== "number") {
      return;
    }

    const ground = surfaceAt(sample.tx, sample.tz);
    const ty = typeof sample.ty === "number" ? sample.ty : ground !== null ? ground : pose.y;
    const p = project(sample.tx, ty + 1.5, sample.tz);
    const range = Math.hypot(sample.tx - pose.x, ty - pose.y, sample.tz - pose.z);
    const color = AV.map.PHASE[sample.phase] || HUD;
    const label = "狙い " + (sample.target || "") + " " + distanceText(range);

    context.strokeStyle = color;
    context.fillStyle = color;
    context.lineWidth = 2;
    context.font = "12px " + FONT;

    if (onScreen(p, -12)) {
      context.strokeRect(p.x - 13, p.y - 13, 26, 26);
      context.fillText(label, p.x + 18, p.y + 4);

      return;
    }

    let dx;
    let dy;

    if (p.behind) {
      const cos = Math.cos(-cam.roll * RAD);
      const sin = Math.sin(-cam.roll * RAD);

      dx = p.across * cos + p.up * sin;
      dy = p.across * sin - p.up * cos;
    } else {
      dx = p.x - width / 2;
      dy = p.y - height / 2;
    }

    const length = Math.hypot(dx, dy) || 1;
    const ux = dx / length;
    const uy = dy / length;
    const reach = Math.min(width, height) / 2 - 30;
    const x = width / 2 + ux * reach;
    const y = height / 2 + uy * reach;

    context.beginPath();
    context.moveTo(x + ux * 12, y + uy * 12);
    context.lineTo(x - uy * 8, y + ux * 8);
    context.lineTo(x + uy * 8, y - ux * 8);
    context.closePath();
    context.fill();
    context.textAlign = ux < -0.3 ? "start" : ux > 0.3 ? "end" : "center";
    context.fillText(label, x - ux * 14, y - uy * 14 + 4);
    context.textAlign = "start";
  }

  function drawTape(yaw) {
    const span = 100;
    const scale = Math.min(5, (width * 0.6) / span);
    const centre = width / 2;
    const top = 6;
    const first = Math.ceil((yaw - span / 2) / 5) * 5;

    context.fillStyle = "rgba(0,0,0,.45)";
    context.fillRect(centre - (span / 2) * scale - 6, top, span * scale + 12, 30);
    context.strokeStyle = HUD;
    context.fillStyle = HUD;
    context.lineWidth = 1;
    context.font = "11px " + FONT;
    context.textAlign = "center";
    context.beginPath();

    for (let degrees = first; degrees <= yaw + span / 2; degrees += 5) {
      const x = centre + (degrees - yaw) * scale;
      const normal = ((degrees % 360) + 360) % 360;
      const tall = normal % 45 === 0 ? 9 : normal % 15 === 0 ? 6 : 3;

      context.moveTo(x, top + 30);
      context.lineTo(x, top + 30 - tall);

      if (normal % 45 === 0) {
        context.fillText(COMPASS[normal / 45], x, top + 14);
      }
    }

    context.stroke();
    context.beginPath();
    context.moveTo(centre, top + 31);
    context.lineTo(centre - 5, top + 39);
    context.lineTo(centre + 5, top + 39);
    context.closePath();
    context.fill();
    context.font = "bold 12px " + MONO;
    context.fillText(compass(yaw) + " " + Math.round((((yaw % 360) + 360) % 360)) + "°", centre, top + 53);
    context.textAlign = "start";
  }

  /** 縦の目盛り（10度おき）。窓と一緒に傾く。 */
  function drawLadder(cam, frame) {
    context.save();
    context.translate(width / 2, height / 2);
    context.rotate(-cam.roll * RAD);
    context.strokeStyle = "rgba(216,255,77,.8)";
    context.fillStyle = "rgba(216,255,77,.9)";
    context.lineWidth = 1.5;
    context.font = "10px " + MONO;

    for (let angle = -60; angle <= 60; angle += 10) {
      const y = (frame.horizon - frame.focal * Math.tan(angle * RAD) - frame.bh / 2) / frame.quality;

      if (Math.abs(y) > Math.max(width, height) * 0.6) {
        continue;
      }

      const half = angle === 0 ? Math.min(width * 0.35, 260) : 46;
      const gap = angle === 0 ? 34 : 16;

      context.setLineDash(angle < 0 ? [5, 4] : []);
      context.beginPath();
      context.moveTo(-half, y);
      context.lineTo(-gap, y);
      context.moveTo(gap, y);
      context.lineTo(half, y);
      context.stroke();

      if (angle !== 0) {
        context.fillText(String(angle), half + 4, y + 3);
      }
    }

    context.setLineDash([]);
    context.restore();
  }

  /** 進んでいる向きの印（航空機）。 */
  function drawPathMarker(project, cam, pose) {
    const yaw = pose.yaw * RAD;
    const pitch = pose.pitch * RAD;
    const p = project(cam.eye.x - Math.sin(yaw) * Math.cos(pitch) * 500, cam.eye.y + Math.sin(pitch) * 500,
        cam.eye.z + Math.cos(yaw) * Math.cos(pitch) * 500);

    if (!onScreen(p, -8)) {
      return;
    }

    context.strokeStyle = HUD;
    context.lineWidth = 2;
    context.beginPath();
    context.arc(p.x, p.y, 6, 0, Math.PI * 2);
    context.moveTo(p.x - 16, p.y);
    context.lineTo(p.x - 6, p.y);
    context.moveTo(p.x + 6, p.y);
    context.lineTo(p.x + 16, p.y);
    context.moveTo(p.x, p.y - 6);
    context.lineTo(p.x, p.y - 12);
    context.stroke();
  }

  function readout(x, y, alignRight, label, value, sub) {
    context.font = "bold 15px " + MONO;

    const measured = Math.max(context.measureText(value).width, 48);

    context.font = "11px " + FONT;

    const boxWidth = Math.max(measured, sub ? context.measureText(sub).width : 0) + 16;
    const boxHeight = sub ? 56 : 40;
    const left = alignRight ? x - boxWidth : x;

    context.fillStyle = "rgba(0,0,0,.5)";
    context.fillRect(left, y, boxWidth, boxHeight);
    context.strokeStyle = "rgba(216,255,77,.55)";
    context.lineWidth = 1;
    context.strokeRect(left + 0.5, y + 0.5, boxWidth - 1, boxHeight - 1);
    context.fillStyle = "#b5c9a4";
    context.fillText(label, left + 8, y + 14);

    if (sub) {
      context.fillText(sub, left + 8, y + 50);
    }

    context.fillStyle = HUD;
    context.font = "bold 15px " + MONO;
    context.fillText(value, left + 8, y + 32);
  }

  /** 行の束。bottom は束の下の縁。alignRight なら x は右の縁。 */
  function lines(rows, x, bottom, alignRight) {
    context.font = "12px " + FONT;

    let widest = 0;

    for (const row of rows) {
      widest = Math.max(widest, context.measureText(row.text).width);
    }

    const boxHeight = rows.length * 17 + 9;
    const left = alignRight ? x - widest - 16 : x;

    context.fillStyle = "rgba(0,0,0,.55)";
    context.fillRect(left, bottom - boxHeight, widest + 16, boxHeight);
    rows.forEach((row, i) => {
      context.fillStyle = row.color || "#e9f4de";
      context.fillText(row.text, left + 8, bottom - boxHeight + 18 + i * 17);
    });
  }

  function drawHud(project, cam, frame, pose, item, t) {
    const sample = pose.sample;
    const battle = detail();
    const ground = surfaceAt(pose.x, pose.z);
    const rows = [];
    const health = Math.round((sample.health || 0) * 100) + "%";

    if (pose.air) {
      drawLadder(cam, frame);
      drawPathMarker(project, cam, pose);
    }

    drawTape(cam.yaw);
    readout(10, height / 2 - 28, false, "速さ", Math.round(pose.speed * 72) + " km/h",
        pose.air ? "上昇 " + Math.round((sample.climb || 0) * 20) + " m/s" : null);
    readout(width - 10, height / 2 - 28, true, "高さ", "Y " + Math.round(pose.y),
        pose.air && ground !== null ? "地面から " + Math.round(pose.y - ground) : null);

    if (pose.air) {
      const phase = AV.panels.PHASES[sample.phase] || sample.phase;
      const range = typeof sample.tx === "number"
          ? Math.hypot(sample.tx - pose.x, (typeof sample.ty === "number" ? sample.ty : pose.y) - pose.y, sample.tz - pose.z)
          : null;

      rows.push({ text: phase + (sample.exact ? "" : "（行動から推定）"), color: AV.map.PHASE[sample.phase] || HUD });
      rows.push({ text: "兵装 " + (sample.weapon ? short(sample.weapon) : "—") });
      rows.push({ text: "狙い " + (sample.target || "—") + (range !== null ? "　" + distanceText(range) : "") });

      if (sample.score !== null) {
        rows.push({ text: "相手の点数 " + sample.score.toFixed(2) });
      }

      if (sample.missiles !== null) {
        rows.push({ text: "空対空ミサイル " + (sample.missiles ? "残っている" : "無い") });
      }

      if (sample.firedGun !== null || sample.firedBomb !== null || sample.firedAam !== null) {
        rows.push({ text: "撃った 機関砲 " + (sample.firedGun || 0) + "・爆弾 " + (sample.firedBomb || 0) + "・ミサイル "
            + (sample.firedAam || 0) });
      }

      rows.push({ text: "見る拠点 " + (sample.objective || "—") + "　体力 " + health });
    } else {
      const flags = [];

      if (sample.water) {
        flags.push("水 " + sample.water);
      }

      if (sample.jams) {
        flags.push("詰まり " + sample.jams);
      }

      if (sample.escaping) {
        flags.push("抜け出し中");
      }

      if (sample.backing) {
        flags.push("後退");
      }

      if (sample.lost) {
        flags.push("迷子");
      }

      rows.push({ text: AV.panels.ACTIONS[sample.action] || sample.action || "—", color: HUD });
      rows.push({ text: "持ち場 " + (sample.objective || "—") });
      rows.push({ text: "体力 " + health });

      if (flags.length) {
        rows.push({ text: flags.join("・"), color: "#ff9a90" });
      }
    }

    lines(rows, 10, height - 10, false);
    lines([
      { text: short(item.vehicle) + "（" + item.team + "）　" + AV.data.clock(t - battle.t0) },
      { text: "出撃から " + AV.data.clock(t - item.samples[0].t) + "　視野 " + Math.round(view.fov) + "°" },
      {
        text: pose.air ? "ピッチとロールは記録からの推定" : pose.known ? "向きは走った向きからの推定" : "向きは不明（持ち場の方へ向けた）",
        color: "#7e9270",
      },
    ], width - 10, height - 10, true);

    if (pose.air && sample.evading) {
      banner("ミサイル回避", AV.map.EVADE);
    } else if (pose.air && sample.phase === "SPENT") {
      banner("弾切れ・帰投", "#c0c0c0");
    }

    const status = !AV.terrain.count() ? "地形が無い（ワールドを含むフォルダを開くと地面が出る）"
        : AV.state.terrainBusy ? "周りの地形を読んでいる…" : "";

    if (status) {
      context.font = "11px " + FONT;
      context.fillStyle = "rgba(233,244,222,.8)";
      context.fillText(status, 10, 18);
    }
  }

  function banner(text, color) {
    context.font = "bold 20px " + FONT;
    context.textAlign = "center";
    context.fillStyle = "rgba(0,0,0,.5)";
    context.fillRect(width / 2 - 90, 68, 180, 30);
    context.fillStyle = color;
    context.fillText(text, width / 2, 90);
    context.textAlign = "start";
  }

  /** 絵を出せないときの言葉。item が null なら消す。 */
  function explain(item, t) {
    let html = "";

    if (item === undefined || item === null) {
      html = t === undefined ? "<p>見る機体を上の一覧から選ぶ</p>" : "";
    }

    if (item) {
      const battle = detail();
      const first = item.samples[0].t;
      const last = item.samples[item.samples.length - 1].t;
      const jump = "<button class=\"btn small\" type=\"button\" data-pov-time=\"" + first + "\">出撃の時刻へ</button>";

      if (t === null) {
        html = "<p>再生バーで時刻を選ぶと、その時の視点を出す</p>" + jump;
      } else if (t < first) {
        html = "<p>この機体はまだ出ていない（" + AV.data.clock(first - battle.t0) + " に出る）</p>" + jump;
      } else if (t > last) {
        const lost = battle.markers.some((marker) => marker.type === "VehicleDestroyed" && sameId(marker.bot, item.id));

        html = "<p>この時刻にはもういない（" + AV.data.clock(last - battle.t0) + " まで" + (lost ? "、撃破された" : "")
            + "）</p>" + jump;
      } else {
        html = "<p>この時刻の記録が無い（行の間が空いている）</p>";
      }
    }

    if (html === noteHtml) {
      return;
    }

    noteHtml = html;
    note.innerHTML = html;
    note.hidden = !html;
    note.querySelectorAll("[data-pov-time]").forEach((button) => button.addEventListener("click", () => {
      AV.app.setTime(Number(button.dataset.povTime));
    }));
  }

  // ------------------------------------------------------------------
  // 開く・閉じる・回す
  // ------------------------------------------------------------------

  function order(a, b) {
    return String(a.team).localeCompare(String(b.team)) || a.samples[0].t - b.samples[0].t;
  }

  function fill() {
    const battle = detail();

    if (!battle) {
      picker.innerHTML = "";

      return;
    }

    picker.innerHTML = battle.bots.filter((item) => item.samples.length).sort(order).map((item) => "<option value=\""
        + esc(item.id) + "\">" + (item.air ? "✈ " : "") + esc(short(item.vehicle)) + "（" + esc(item.team) + "）"
        + AV.data.clock(item.samples[0].t - battle.t0) + "〜"
        + AV.data.clock(item.samples[item.samples.length - 1].t - battle.t0) + "</option>").join("");
    picker.value = String(view.botId);
  }

  function modes() {
    root.querySelectorAll("[data-pov-mode]").forEach((button) => {
      button.classList.toggle("on", button.dataset.povMode === view.mode);
    });
  }

  /** その機体の視点を開く。今の時刻に出ていなければ、その出撃の始めへ時刻を動かす。 */
  function openFor(id) {
    const battle = detail();
    const item = battle ? battle.bots.find((entry) => sameId(entry.id, id)) : null;

    if (!item || !item.samples.length) {
      return;
    }

    if (!sameId(view.botId, item.id)) {
      view.lookYaw = 0;
      view.lookPitch = 0;
    }

    view.botId = item.id;
    open = true;
    root.hidden = false;
    filledFor = battle;
    fill();
    modes();

    const t = time();

    if (t === null || !AV.data.positionAt(item, t)) {
      AV.app.setTime(t !== null && t >= item.samples[0].t && t <= item.samples[item.samples.length - 1].t
          ? t : item.samples[0].t);
    }

    drawnKey = "";

    if (!loop) {
      loop = requestAnimationFrame(tick);
    }
  }

  function close() {
    open = false;
    shot = null;
    root.hidden = true;

    if (loop) {
      cancelAnimationFrame(loop);
      loop = 0;
    }

    AV.map.request();
  }

  /** この時刻に出ている機体（いなければ全部）を、陣営と出た順に回す。 */
  function cycle(step) {
    const battle = detail();

    if (!battle) {
      return;
    }

    const t = time();
    const alive = t === null ? [] : battle.bots.filter((item) => item.samples.length && AV.data.positionAt(item, t));
    const list = (alive.length ? alive : battle.bots.filter((item) => item.samples.length)).sort(order);
    const at = list.findIndex((item) => sameId(item.id, view.botId));
    const next = list[(at + step + list.length) % list.length];

    if (next) {
      openFor(next.id);
    }
  }

  function tick() {
    loop = requestAnimationFrame(tick);

    const battle = detail();

    if (battle !== filledFor) {
      filledFor = battle;

      if (!battle || !current()) {
        close();

        return;
      }

      fill();
    }

    if (width < 40 || height < 40) {
      return;
    }

    const item = current();
    const t = time();
    const key = [view.botId, t, view.mode, view.fov, view.lookYaw, view.lookPitch, width, height, terrainEpoch,
      battle.t1, AV.state.terrainBusy, AV.terrain.count()].join("|");

    if (key === drawnKey) {
      return;
    }

    const clock = performance.now();

    if (clock - paintedAt < FRAME_GAP) {
      return;
    }

    paintedAt = clock;
    drawnKey = key;
    draw(item, t);

    if (!shot) {
      AV.map.request();

      return;
    }

    const pose = shot.pose;

    if (view.follow) {
      const size = AV.map.size();
      const at = AV.map.toScreen(pose.x, pose.z);

      if (Math.abs(at.x - size.width / 2) > 2 || Math.abs(at.y - size.height / 2) > 2) {
        AV.map.centerOn(pose.x, pose.z);
      }
    }

    AV.map.request();

    const radius = pose.air ? TERRAIN_AIR : TERRAIN_GROUND;

    if (!AV.state.terrainBusy && wantsTerrain(pose.x, pose.z, radius)) {
      AV.app.readTerrainAround(pose.x, pose.z, radius, 1);
    }
  }

  /** 地図に描く扇（map.js）。開いていて絵を出しているときだけ。 */
  function cone() {
    if (!open || !shot) {
      return null;
    }

    return { x: shot.pose.x, z: shot.pose.z, yaw: shot.cam.yaw, fov: view.fov, far: shot.cam.far };
  }

  function resize() {
    const rect = canvas.getBoundingClientRect();

    ratio = window.devicePixelRatio || 1;
    width = rect.width;
    height = rect.height;
    canvas.width = Math.max(1, Math.round(width * ratio));
    canvas.height = Math.max(1, Math.round(height * ratio));
    drawnKey = "";
  }

  function local(event) {
    const rect = canvas.getBoundingClientRect();

    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  new ResizeObserver(resize).observe(canvas);

  canvas.addEventListener("pointerdown", (event) => {
    canvas.setPointerCapture(event.pointerId);
    canvas.classList.add("dragging");
    drag = { x: event.clientX, y: event.clientY, yaw: view.lookYaw, pitch: view.lookPitch, moved: false };
  });
  canvas.addEventListener("pointermove", (event) => {
    if (!drag) {
      return;
    }

    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    const perPixel = view.fov / Math.max(1, width);

    if (Math.abs(dx) + Math.abs(dy) > 3) {
      drag.moved = true;
    }

    // 景色を掴んで動かす向き。右へ引けば左を見る。
    view.lookYaw = wrap(drag.yaw - dx * perPixel);
    view.lookPitch = clamp(drag.pitch + dy * perPixel, -80, 80);
  });
  canvas.addEventListener("pointerup", (event) => {
    canvas.classList.remove("dragging");

    if (drag && !drag.moved && shot) {
      const at = local(event);
      let best = null;
      let bestDistance = 18;

      for (const mark of shot.marks) {
        const distance = Math.hypot(mark.x - at.x, mark.y - at.y);

        if (distance < bestDistance) {
          bestDistance = distance;
          best = mark;
        }
      }

      if (best) {
        openFor(best.id);
      }
    }

    drag = null;
  });
  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    view.fov = clamp(view.fov * Math.exp(event.deltaY * 0.0012), 12, 110);
  }, { passive: false });
  canvas.addEventListener("dblclick", () => {
    view.lookYaw = 0;
    view.lookPitch = 0;
    view.fov = 80;
  });

  picker.addEventListener("change", () => openFor(picker.value));
  $("pov-prev").addEventListener("click", () => cycle(-1));
  $("pov-next").addEventListener("click", () => cycle(1));
  root.querySelectorAll("[data-pov-mode]").forEach((button) => button.addEventListener("click", () => {
    view.mode = button.dataset.povMode;
    modes();
  }));
  $("pov-follow").addEventListener("change", (event) => {
    view.follow = event.target.checked;
    drawnKey = "";
  });
  $("pov-size").addEventListener("click", () => {
    view.big = !view.big;
    root.classList.toggle("big", view.big);
  });
  $("pov-close").addEventListener("click", close);
  document.addEventListener("keydown", (event) => {
    const typing = event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement;

    if (event.key === "Escape" && open && !typing) {
      close();
    }
  });

  AV.on("terrain", () => {
    terrainEpoch++;
  });

  AV.pov = { open: openFor, close, cone, isOpen: () => open };
})(window.AV = window.AV || {});
