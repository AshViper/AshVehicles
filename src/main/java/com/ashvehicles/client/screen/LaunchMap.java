package com.ashvehicles.client.screen;

import javax.annotation.Nullable;

import com.ashvehicles.client.LodTerrain;
import com.ashvehicles.client.MissileTrack;
import com.ashvehicles.client.SeenTerrain;
import com.ashvehicles.client.Terrain;
import com.ashvehicles.network.MissileTrackPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 射撃指揮盤の地図。真上から見た地形を描き、押された場所の座標を答える。
 *
 * <p><b>地面は3つの出所から、確かな順に来る。</b>今 chunk を持っている所は本物のブロックから
 * （{@link Terrain}）、一度でも通った所は通った時に読んだ高さから（{@link SeenTerrain}）、行ったことも無い
 * 所は Distant Horizons の粗い地形から（{@link LodTerrain}）。射程は chunk を持つ範囲の何倍も外にあるので、
 * この3つが無ければ地図の大半は方眼のままだ——実際、走ったことも無く DH も入れていない土地はそうなる。それは
 * 嘘ではなく、この兵器が「見えない場所を撃つ物」であることの正直な表示である。
 *
 * <p><b>LOD の問い合わせはワーカーへ出す。</b>ゲームスレッドから DH の地形を問うとクライアントが固まる。
 * {@link LodTerrain} 参照。だから遠方は<em>埋まっていく</em>——開いた直後は方眼で、中心から外へ地形が広がる。
 *
 * <p><b>色は標高から作る。</b>ブロックの地図色ではなく高さの階調と、北西の隣との段差から出した陰影。砲を
 * 撃つ者が地図に問うのは「そこに何が生えているか」ではなく「そこは高いか低いか、斜面はどちらを向いているか」
 * だからで、しかもこの作り方なら1マスあたり高さ1つで済む。
 *
 * <p><b>標本は視野が動いた時にだけ取り直す。</b>1マスにつき chunk を1回引くので、64×64の盤面は4096回になる。
 * 毎フレーム払う値段ではないし、払う理由も無い——地形は動かない。
 */
final class LaunchMap {
    /** 盤面を何マスに割るか。1辺のマス数。 */
    private static final int CELLS = 64;

    /**
     * 拡大率の下限と上限（1ピクセルあたりブロック）。
     *
     * <p>上限は射程で決まる。60000ブロック届く発射機の射程環を盤面に収めるには、192ピクセルの盤で片道
     * 96ピクセル——1ピクセル 625 ブロック——が要る。余裕を見て 768 まで引ける。
     */
    private static final double FINEST = 1.0;
    private static final double COARSEST = 768.0;

    /** 方眼の間隔を選ぶときに狙う、線と線の画面上の間隔（ピクセル）。 */
    private static final int GRID_PIXELS = 48;

    /** 地形が読めなかったマスの色。方眼の地。 */
    private static final int UNKNOWN = 0xFF0E1614;
    private static final int GRID = 0x2240C0A0;
    private static final int EDGE = 0xFF3BE86A;
    /** 飛んでいる自分の弾。据えた点の赤とも地の緑とも別の色。 */
    private static final int MISSILE = 0xFFFFD24A;

    /** 盤面の左上（画面ピクセル）と一辺。 */
    private int left;
    private int top;
    private int side;

    /** 盤面の中心が指しているワールド座標と、1ピクセルあたりのブロック数。 */
    private double centreX;
    private double centreZ;
    private double scale = 4.0;

    /** 標本と、それを取った時の視野。視野が同じなら取り直さない。 */
    private final int[] shade = new int[CELLS * CELLS];
    private double sampledX = Double.NaN;
    private double sampledZ = Double.NaN;
    private double sampledScale;
    private long sampledAt = Long.MIN_VALUE;

    /**
     * 遠方の高さと水面。クライアントが chunk を持たなかったマスだけを、ワーカーが届いた順に埋める。
     *
     * <p>視野が変わるたびに作り直し、走っていた依頼は降ろす。要素ごとの競合は起こりうるが害にならない——
     * 1フレーム古い高さが1マスに出るだけだ。{@link LodTerrain} 参照。
     */
    private double[] far = new double[CELLS * CELLS];
    private boolean[] farLiquid = new boolean[CELLS * CELLS];
    @Nullable
    private LodTerrain.Fill filling;

    LaunchMap(Vec3 centre) {
        this.centreX = centre.x;
        this.centreZ = centre.z;
    }

    /** 盤面を画面のどこへ、どれだけの大きさで置くか。 */
    void place(int left, int top, int side) {
        this.left = left;
        this.top = top;
        this.side = side;
    }

    /** 地図の中心をここへ運ぶ。座標を打ち込んだ時に、その点を見せるため。 */
    void look(double x, double z) {
        this.centreX = x;
        this.centreZ = z;
    }

    /** 押された画面位置が盤面の上か。 */
    boolean holds(double mouseX, double mouseY) {
        return mouseX >= this.left && mouseX < this.left + this.side
                && mouseY >= this.top && mouseY < this.top + this.side;
    }

    /** 画面位置が指すワールド座標。盤面の外なら null。 */
    @Nullable
    Vec3 at(double mouseX, double mouseY, double y) {
        if (!this.holds(mouseX, mouseY)) {
            return null;
        }

        return new Vec3(this.worldX(mouseX), y, this.worldZ(mouseY));
    }

    private double worldX(double mouseX) {
        return this.centreX + (mouseX - this.left - this.side / 2.0) * this.scale;
    }

    private double worldZ(double mouseY) {
        return this.centreZ + (mouseY - this.top - this.side / 2.0) * this.scale;
    }

    /** ワールド座標が乗る画面位置。盤面の外へ出ることもある。 */
    private double screenX(double x) {
        return this.left + this.side / 2.0 + (x - this.centreX) / this.scale;
    }

    private double screenY(double z) {
        return this.top + this.side / 2.0 + (z - this.centreZ) / this.scale;
    }

    /** 掴んだまま動かした分だけ地図を送る。 */
    void drag(double dragX, double dragY) {
        this.centreX -= dragX * this.scale;
        this.centreZ -= dragY * this.scale;
    }

    /**
     * 拡大・縮小。カーソルの下にある地面をその場に留めたまま倍率を変える。地図を動かすのではなく、地図に
     * 顔を近づける操作であるべきだから。
     */
    void zoom(double mouseX, double mouseY, double amount) {
        double heldX = this.worldX(mouseX);
        double heldZ = this.worldZ(mouseY);

        this.scale = Mth.clamp(this.scale * Math.pow(0.8, amount), FINEST, COARSEST);
        this.centreX += heldX - this.worldX(mouseX);
        this.centreZ += heldZ - this.worldZ(mouseY);
    }

    /** 1ピクセルあたりのブロック数。目盛りの表示に使う。 */
    double scale() {
        return this.scale;
    }

    /**
     * 盤面を描く。地形、方眼、射程の環、車両、そして据えた点。
     *
     * @param from 車両の位置。中心の印と射程の環の元
     * @param reach 発射機が届く水平距離。0なら環は描かない
     * @param pin 据えようとしている点。無ければ null
     */
    void draw(GuiGraphics graphics, Level level, Vec3 from, double reach, @Nullable Vec3 pin) {
        this.sample(level);

        graphics.enableScissor(this.left, this.top, this.left + this.side, this.top + this.side);
        this.terrain(graphics);
        this.grid(graphics);
        this.ring(graphics, from, reach);
        this.here(graphics, from);

        this.missiles(graphics);

        if (pin != null) {
            this.mark(graphics, pin);
        }

        graphics.disableScissor();

        // 枠。地形の上に描くので、拡大して端まで地面が届いていても盤面の縁が見える。
        graphics.fill(this.left, this.top, this.left + this.side, this.top + 1, EDGE);
        graphics.fill(this.left, this.top + this.side - 1, this.left + this.side, this.top + this.side, EDGE);
        graphics.fill(this.left, this.top, this.left + 1, this.top + this.side, EDGE);
        graphics.fill(this.left + this.side - 1, this.top, this.left + this.side, this.top + this.side, EDGE);

        this.north(graphics);
        this.bar(graphics);
    }

    /**
     * 北。地図は常に北が上だが、そう<em>書いてある</em>方がいい——真上から見た絵は、言われるまでどちらが上か
     * 分からない。
     */
    private void north(GuiGraphics graphics) {
        int x = this.left + this.side - 10;
        int y = this.top + 6;

        graphics.fill(x, y, x + 1, y + 9, EDGE);
        graphics.fill(x - 3, y + 3, x + 4, y + 4, EDGE);
        graphics.fill(x - 2, y, x + 3, y + 1, EDGE);
    }

    /**
     * 縮尺の物差し。1ピクセル何ブロックという数より、盤面の上に置かれた長さの方が読める——地図に対して
     * 実際に問うのは「あの丘からあの谷までどれくらいか」だからだ。
     *
     * <p>長さは切りのよいブロック数から選び、盤の1/3前後に収まる物を採る。
     */
    private void bar(GuiGraphics graphics) {
        double want = this.side * this.scale / 3.0;
        double blocks = Math.pow(10.0, Math.floor(Math.log10(want)));

        if (blocks * 5.0 <= want) {
            blocks *= 5.0;
        } else if (blocks * 2.0 <= want) {
            blocks *= 2.0;
        }

        int length = (int) Math.round(blocks / this.scale);

        if (length < 8) {
            return;
        }

        int x = this.left + 6;
        int y = this.top + this.side - 8;

        graphics.fill(x, y, x + length + 1, y + 1, EDGE);
        graphics.fill(x, y - 3, x + 1, y + 1, EDGE);
        graphics.fill(x + length, y - 3, x + length + 1, y + 1, EDGE);
        graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                (blocks >= 1000.0 ? Math.round(blocks / 1000.0) + " km" : Math.round(blocks) + " m"),
                x + 2, y - 12, 0xC0E8ECEA, true);
    }

    /** カーソルの下のワールド座標。盤面の外なら null。盤が読み上げるのに使う。 */
    @Nullable
    String under(double mouseX, double mouseY) {
        if (!this.holds(mouseX, mouseY)) {
            return null;
        }

        return String.format(java.util.Locale.ROOT, "%d  %d",
                Math.round(this.worldX(mouseX)), Math.round(this.worldZ(mouseY)));
    }

    /** マス目を敷き詰める。 */
    private void terrain(GuiGraphics graphics) {
        double step = (double) this.side / CELLS;

        for (int row = 0; row < CELLS; row++) {
            for (int column = 0; column < CELLS; column++) {
                int colour = this.shade[row * CELLS + column];

                if (colour == 0) {
                    continue;
                }

                int x = this.left + (int) Math.floor(column * step);
                int y = this.top + (int) Math.floor(row * step);

                graphics.fill(x, y, this.left + (int) Math.ceil((column + 1) * step),
                        this.top + (int) Math.ceil((row + 1) * step), colour);
            }
        }
    }

    /**
     * 標高を色にする。低い所は暗く、高い所は明るく、そして北西の隣との段差で陰影を付ける。
     *
     * <p>近くはクライアントのブロックから即座に、遠くは Distant Horizons からワーカー越しに。前者は毎tick
     * 取り直すのでチャンクが届いた分だけ島が広がり、後者は視野が変わった時に依頼し直して、届いた順に埋まる。
     *
     * <p>取り直すのは視野が動いた時と tick が変わった時だけ。1マスにつき chunk を1回引くので、64×64の盤面は
     * 4096回になる——毎フレーム払う値段ではないし、地形は動かない。
     */
    private void sample(Level level) {
        long now = level.getGameTime();
        boolean moved = this.centreX != this.sampledX || this.centreZ != this.sampledZ
                || this.scale != this.sampledScale;

        if (!moved && now == this.sampledAt) {
            return;
        }

        this.sampledX = this.centreX;
        this.sampledZ = this.centreZ;
        this.sampledScale = this.scale;
        this.sampledAt = now;

        double blocks = this.side * this.scale / CELLS;
        double originX = this.centreX - this.side * this.scale / 2.0;
        double originZ = this.centreZ - this.side * this.scale / 2.0;
        double[] height = new double[CELLS * CELLS];
        boolean[] wet = new boolean[CELLS * CELLS];
        boolean anyFar = false;

        for (int row = 0; row < CELLS; row++) {
            for (int column = 0; column < CELLS; column++) {
                int at = row * CELLS + column;
                double y = Terrain.surface(level,
                        new Vec3(originX + (column + 0.5) * blocks, 0.0, originZ + (row + 0.5) * blocks));

                if (!Double.isNaN(y)) {
                    height[at] = y;

                    continue;
                }

                // 手元に無い列。まず一度でも通った土地として覚えていないか——覚えているならそれは
                // 本物のブロックから読んだ高さで、DH の粗い地形より確かだ。SeenTerrain 参照。
                double remembered = SeenTerrain.height(originX + (column + 0.5) * blocks,
                        originZ + (row + 0.5) * blocks);

                if (!Double.isNaN(remembered)) {
                    height[at] = remembered;

                    continue;
                }

                // 通ったことも無い。前の依頼が既に埋めていればそれを使い、まだなら遠方の依頼へ回す。
                if (!moved && !Double.isNaN(this.far[at])) {
                    height[at] = this.far[at];
                    wet[at] = this.farLiquid[at];
                } else {
                    height[at] = Double.NaN;
                    anyFar = true;
                }
            }
        }

        // 視野が動いたら、走っていた依頼は別の土地を埋めている。降ろして頼み直す。
        if (moved) {
            if (this.filling != null) {
                this.filling.cancel();
            }

            this.far = height;
            this.farLiquid = wet;
            this.filling = anyFar
                    ? LodTerrain.request((net.minecraft.client.multiplayer.ClientLevel) level,
                            this.far, this.farLiquid, CELLS, originX, originZ, blocks)
                    : null;
        }

        this.paint(moved ? this.far : height, moved ? this.farLiquid : wet);
    }

    /** 高さの格子1枚を色に変える。 */
    private void paint(double[] height, boolean[] wet) {
        for (int row = 0; row < CELLS; row++) {
            for (int column = 0; column < CELLS; column++) {
                int at = row * CELLS + column;
                double y = height[at];

                if (Double.isNaN(y)) {
                    this.shade[at] = UNKNOWN;

                    continue;
                }

                // 海面から上をひと続きの階調に。山頂で飽和しない程度に緩く。
                float lift = Mth.clamp((float) (y - 40.0) / 140.0F, 0.0F, 1.0F);
                // 北西の隣との段差。斜面が北西を向いていれば明るく、南東を向いていれば暗く。
                double against = column > 0 && row > 0 ? height[at - CELLS - 1] : y;
                float relief = Double.isNaN(against) ? 0.0F
                        : Mth.clamp((float) (y - against) / 12.0F, -0.35F, 0.35F);
                float tone = Mth.clamp(0.18F + lift * 0.62F + relief, 0.05F, 1.0F);

                // 水面は標高で語れない。どれだけ低くても同じ面なので、階調から外して1色で置く。
                this.shade[at] = wet[at]
                        ? 0xFF000000
                                | (Math.round(tone * 0.22F * 255.0F) << 16)
                                | (Math.round(tone * 0.44F * 255.0F) << 8)
                                | Math.round(tone * 0.86F * 255.0F)
                        : 0xFF000000
                                | (Math.round(tone * 0.62F * 255.0F) << 16)
                                | (Math.round(tone * 255.0F) << 8)
                                | Math.round(tone * 0.72F * 255.0F);
            }
        }
    }

    /** 方眼。線と線の間が読める幅になる、切りのよいブロック数を選ぶ。 */
    private void grid(GuiGraphics graphics) {
        double want = GRID_PIXELS * this.scale;
        double step = Math.pow(10.0, Math.ceil(Math.log10(want)));

        if (step / 5.0 >= want) {
            step /= 5.0;
        } else if (step / 2.0 >= want) {
            step /= 2.0;
        }

        double fromX = Math.ceil((this.centreX - this.side * this.scale / 2.0) / step) * step;
        double fromZ = Math.ceil((this.centreZ - this.side * this.scale / 2.0) / step) * step;

        for (double x = fromX; x < this.centreX + this.side * this.scale / 2.0; x += step) {
            int at = (int) Math.round(this.screenX(x));

            graphics.fill(at, this.top, at + 1, this.top + this.side, GRID);
        }

        for (double z = fromZ; z < this.centreZ + this.side * this.scale / 2.0; z += step) {
            int at = (int) Math.round(this.screenY(z));

            graphics.fill(this.left, at, this.left + this.side, at + 1, GRID);
        }
    }

    /** 射程の環。この外は押しても受け付けない、という線。 */
    private void ring(GuiGraphics graphics, Vec3 from, double reach) {
        if (reach <= 0.0) {
            return;
        }

        int radius = (int) Math.round(reach / this.scale);

        if (radius < 2 || radius > this.side * 4) {
            return;
        }

        int x = (int) Math.round(this.screenX(from.x));
        int y = (int) Math.round(this.screenY(from.z));

        // 破線の環。実線は地形の等高線と紛れるし、これは地面の上に在る物ではない。
        for (int step = 0; step < 360; step += 6) {
            double angle = Math.toRadians(step);

            graphics.fill(x + (int) Math.round(Math.cos(angle) * radius),
                    y + (int) Math.round(Math.sin(angle) * radius),
                    x + (int) Math.round(Math.cos(angle) * radius) + 1,
                    y + (int) Math.round(Math.sin(angle) * radius) + 1, EDGE);
        }
    }

    /**
     * 自分の発射機が撃った弾。飛んでいる間だけ、地図の上を進んでいく点として。
     *
     * <p>クライアントの追跡距離のはるか外を飛ぶので、位置はサーバーから届いた点をそのまま使う。
     * {@link MissileTrack} 参照。
     */
    private void missiles(GuiGraphics graphics) {
        for (MissileTrackPayload.Shot shot : MissileTrack.shots()) {
            int x = (int) Math.round(this.screenX(shot.x()));
            int y = (int) Math.round(this.screenY(shot.z()));

            // 小さな菱形。据えた点の四角とも車両の十字とも紛れない形にする。
            graphics.fill(x - 1, y - 3, x + 2, y + 4, MISSILE);
            graphics.fill(x - 3, y - 1, x + 4, y + 2, MISSILE);
        }
    }

    /** 車両。地図の上でどこに立っているか。 */
    private void here(GuiGraphics graphics, Vec3 from) {
        int x = (int) Math.round(this.screenX(from.x));
        int y = (int) Math.round(this.screenY(from.z));

        graphics.fill(x - 4, y, x + 5, y + 1, EDGE);
        graphics.fill(x, y - 4, x + 1, y + 5, EDGE);
    }

    /** 据えようとしている点。十字と、その周りの開いた四角。 */
    private void mark(GuiGraphics graphics, Vec3 pin) {
        int x = (int) Math.round(this.screenX(pin.x));
        int y = (int) Math.round(this.screenY(pin.z));
        int arm = 3;
        int box = 6;

        graphics.fill(x - arm, y, x + arm + 1, y + 1, 0xFFFF5A3B);
        graphics.fill(x, y - arm, x + 1, y + arm + 1, 0xFFFF5A3B);
        graphics.fill(x - box, y - box, x + box + 1, y - box + 1, 0xFFFF5A3B);
        graphics.fill(x - box, y + box, x + box + 1, y + box + 1, 0xFFFF5A3B);
        graphics.fill(x - box, y - box, x - box + 1, y + box + 1, 0xFFFF5A3B);
        graphics.fill(x + box, y - box, x + box + 1, y + box + 1, 0xFFFF5A3B);
    }
}
