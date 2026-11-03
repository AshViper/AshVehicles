package com.ashvehicles.client.renderer;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.entity.AircraftEntity;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * アフターバーナーのプルーム。パーティクルではなく発光する形状で描く（AshCC のジェットエンジンの写し）。
 *
 * <p>入れ子になった加算合成の殻——外側の包絡、黄色い炎の本体、超音速の青白い芯——と、その中に並ぶマッハ
 * ダイヤモンド（衝撃波の菱形）。包絡はダイヤモンドの所で括れ、その間で膨らみ、輪ごとに揺らぐ。出力の低い間は
 * 半透明の紫がかった青、全開で明るい橙の松明になり、点火の瞬間はノズルに火の玉が出る。
 *
 * <p>以前は機体が毎 tick 炎と煙の粒を撒いていた。高速では粒が点線になり、機体と一緒に動かない炎は旋回のたびに
 * 横へ千切れて見えた。形状は機体の姿勢に載るので常にノズルから伸び、光量を持たない描画型なのでロード範囲の
 * 外でも黒くならない。
 *
 * <p>座標は機体座標系で組む。{@code Attitude.toWorld} と同じく x を反転し（ファイルの +x は機体の右）、
 * プルームは −Z へ伸びる。クライアントのみ。
 */
public final class AfterburnerPlume {
    private static final int SEGMENTS = 18;
    private static final int RINGS = 28;
    /** ノズル出口の半径（ブロック）。機体は実寸なので戦闘機のノズル径ほど。 */
    private static final double NOZZLE_RADIUS = 0.5;
    /** AshCC の寸法（ノズル半径 0.34）から実寸へ伸ばす倍率。長さもダイヤモンドの間隔もこれで伸びる。 */
    private static final double SIZE = NOZZLE_RADIUS / 0.34;
    /** これを下回る出力では描かない。{@code AircraftEntity.LIT} と同じ。 */
    private static final float LIT = 0.05F;
    /** 点火の火の玉が消えるまでの tick。 */
    private static final float FLASH_TICKS = 6.0F;

    /** 機体ごとの点火の検出。[前フレームの出力, 点火した時刻]。 */
    private static final Map<Object, float[]> LIGHT = new WeakHashMap<>();

    private static final Vec3 D = new Vec3(0, 0, -1);
    private static final Vec3 A = new Vec3(1, 0, 0);
    private static final Vec3 B = new Vec3(0, 1, 0);

    private AfterburnerPlume() {
    }

    /** 1殻の形：長さに沿った割合 s（0 がノズル、1 が先端）での半径と色。 */
    private interface Shell {
        double radius(double s, double x);

        void color(double s, float[] rgba);
    }

    /** 機体の原点に置かれた {@code poseStack} へ、全ノズルのプルームを描く。 */
    public static void draw(AircraftEntity aircraft, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        if (aircraft.isWrecked()) {
            return;
        }

        draw(aircraft, aircraft.getId(), aircraft.getAfterburnerNozzles(), aircraft.getAfterburner(),
                (aircraft.level().getGameTime() % 100000L) + partialTick, aircraft.getAttitude(partialTick),
                poseStack, buffers);
    }

    /**
     * 実体を介さずに描く。ゴーストが撮影した値で呼ぶ。{@code key} は点火の火の玉を覚えておく相手で、描く物ごとに
     * 同じ物を渡し続けること。
     */
    public static void draw(Object key, int id, List<Vec3> nozzles, double ab, float time, Quaternionf attitude,
            PoseStack poseStack, MultiBufferSource buffers) {
        if (nozzles.isEmpty()) {
            return;
        }

        double flash = flash(key, (float) ab, time);

        if (ab <= LIT && flash <= 0.02) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(attitude);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(PlumeRenderType.PLUME);
        // 可変ノズルはバーナーで開く
        double exitR = NOZZLE_RADIUS * (1 + 0.18 * ab);

        for (int n = 0; n < nozzles.size(); n++) {
            Vec3 nozzle = nozzles.get(n);
            Vec3 exit = new Vec3(-nozzle.x, nozzle.y, nozzle.z);
            long seed = id * 31L + n;

            // ノズルの奥のタービン面。排気の黒体色
            float[] c = blackBody(1000 + 500 * ab);
            disc(vc, m, exit, exitR * 0.98, c[0], c[1], c[2], (float) Math.min(0.6, ab * 0.45 + 0.15));

            if (ab > LIT) {
                afterburner(vc, m, exit, exitR, ab, time, seed);
            }

            if (flash > 0.02) {
                double fl = flash;
                tube(vc, m, exit, (0.9 + 0.6 * (1 - fl)) * SIZE, 10, 12, new Shell() {
                    public double radius(double s, double x) {
                        return exitR * (1.2 + 1.4 * fl) * Math.sqrt(Math.max(0, 1 - s * s)) + 0.02;
                    }

                    public void color(double s, float[] o) {
                        set(o, 1f, 0.75f, 0.4f, (float) (0.55 * fl * (1 - s)));
                    }
                }, time, seed);
            }
        }

        poseStack.popPose();
    }

    /**
     * ロケットモーターの炎。{@code poseStack} は弾の原点で +Z を進行方向へ向けてあること。
     *
     * <p>バーナーと同じ殻と菱形で描くが、固体モーターなので色は兵装ファイルの {@code flame.colour} から取り、
     * 菱形は薄く少なく、芯は白に近い。半径は {@code flame.size}、長さは {@code flame.length} に点火の膨らみを
     * 掛けた物。機体ではなく弾に付くので、どれだけ速く飛んでも炎は途切れない。
     */
    public static void motor(int colour, double size, double length, double flare, float time, long seed,
            PoseStack poseStack, MultiBufferSource buffers) {
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(PlumeRenderType.PLUME);
        float cr = ((colour >> 16) & 0xFF) / 255F;
        float cg = ((colour >> 8) & 0xFF) / 255F;
        float cb = (colour & 0xFF) / 255F;
        double exitR = size * 0.25 * Math.sqrt(flare);
        double len = length * flare * (1 + 0.06 * Math.sin(time * 2.9 + seed));
        Vec3 exit = Vec3.ZERO;

        // 外側：兵装の色の包絡
        tube(vc, m, exit, len, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                return exitR * (1.0 + 0.6 * s) * Math.sqrt(Math.max(0, 1 - Math.pow(s, 2.2)));
            }

            public void color(double s, float[] o) {
                set(o, cr, cg, cb, (float) (0.55 * Math.pow(1 - s, 1.2)));
            }
        }, time, seed);
        // 内側：白熱した芯
        tube(vc, m, exit, len * 0.45, RINGS / 2, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                return exitR * 0.55 * (1 - 0.6 * s);
            }

            public void color(double s, float[] o) {
                set(o, 1f, Math.min(1, cg + 0.35f), Math.min(1, cb + 0.5f), (float) (0.8 * (1 - s)));
            }
        }, time, seed);

        // 薄い菱形を2つ
        double spacing = len * 0.18;

        for (int k = 0; k < 2; k++) {
            double x = spacing * (0.8 + k);
            float alpha = (float) (0.5 * (1 - 0.4 * k) * (0.85 + 0.15 * noise(time * 1.7 + k * 3.1, seed)));
            bicone(vc, m, exit.add(D.scale(x)), spacing * 0.3, exitR * 0.4, 1f, 0.9f, 0.7f, alpha);
        }

        disc(vc, m, exit, exitR * 0.95, 1f, 0.85f, 0.6f, 0.6f);
    }

    /** 点火の火の玉の強さ（1→0）。同期された出力が LIT を越えた瞬間をフレームで拾う。 */
    private static double flash(Object key, float ab, float time) {
        float[] state = LIGHT.computeIfAbsent(key, k -> new float[] {ab, -1000F});

        if (state[0] <= LIT && ab > LIT) {
            state[1] = time;
        }

        state[0] = ab;
        float since = time - state[1];

        return since < 0 ? 0 : Math.max(0, 1 - since / FLASH_TICKS);
    }

    private static void afterburner(VertexConsumer vc, Matrix4f m, Vec3 exit, double exitR, double ab, float time,
            long seed) {
        double len = (2.6 + 4.6 * ab) * SIZE * (1 + 0.04 * Math.sin(time * 2.3 + seed));
        // マッハダイヤモンド：圧力比が高いほど数が増え間隔が開く
        double spacing = (0.42 + 0.22 * ab) * SIZE;
        int diamonds = 2 + (int) Math.round(3 * ab);
        double first = 0.32 * SIZE + spacing * 0.5;
        // 色：最小出力で半透明の紫がかった青、全開で橙の松明
        float or = (float) Mth.lerp(ab, 0.75, 1.0);
        float og = (float) Mth.lerp(ab, 0.35, 0.52);
        float ob = (float) Mth.lerp(ab, 0.85, 0.16);

        // 外側の包絡。ダイヤモンドごとに括れる
        tube(vc, m, exit, len, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                double taper = Math.sqrt(Math.max(0, 1 - Math.pow(s, 2.4)));
                return exitR * (1.05 + 0.45 * s) * taper * pinch(x, first, spacing, 0.10);
            }

            public void color(double s, float[] o) {
                set(o, or, og, ob, (float) (0.45 * Math.sqrt(ab) * Math.pow(1 - s, 1.1)));
            }
        }, time, seed);
        // 黄色い炎の本体
        tube(vc, m, exit, len * 0.78, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                double taper = Math.sqrt(Math.max(0, 1 - Math.pow(s, 2.0)));
                return exitR * 0.78 * (1 + 0.2 * s) * taper * pinch(x, first, spacing, 0.16);
            }

            public void color(double s, float[] o) {
                set(o, 1f, (float) Mth.lerp(ab, 0.55, 0.78), (float) Mth.lerp(ab, 0.75, 0.32),
                        (float) (0.5 * Math.sqrt(ab) * (1 - s)));
            }
        }, time, seed);
        // 青白い芯：空気と混ざる前の超音速の噴流
        tube(vc, m, exit, first + spacing * (diamonds - 1) + 0.2 * SIZE, RINGS, SEGMENTS, new Shell() {
            public double radius(double s, double x) {
                return exitR * 0.5 * (1 - 0.55 * s) * pinch(x, first, spacing, 0.25);
            }

            public void color(double s, float[] o) {
                set(o, 0.7f, 0.82f, 1f, (float) (0.55 * ab * (1 - s * 0.7)));
            }
        }, time, seed);

        // ダイヤモンド：軸上の明るい双円錐。下流ほど薄れる
        for (int k = 0; k < diamonds; k++) {
            double x = first + spacing * k;

            if (x > len * 0.85) {
                break;
            }

            double fadeK = 1 - (double) k / (diamonds + 1);
            double flick = 0.85 + 0.15 * noise(time * 1.7 + k * 3.1, seed);
            double half = spacing * (0.30 - 0.03 * k);
            double r = exitR * (0.42 - 0.04 * k) * (0.6 + 0.4 * ab);
            float alpha = (float) (Math.min(1, 1.1 * ab) * fadeK * flick);
            Vec3 centre = exit.add(D.scale(x));
            bicone(vc, m, centre, half, r, 1f, (float) Mth.lerp(fadeK, 0.6, 0.95),
                    (float) Mth.lerp(fadeK, 0.35, 0.85), alpha);
            // 菱形の周りの柔らかな光
            bicone(vc, m, centre, half * 1.6, r * 1.9, 1f, 0.7f, 0.35f, alpha * 0.25f);
        }
    }

    /** 衝撃波の所（ダイヤモンド）で噴流を細め、その間で太らせる半径の係数。 */
    private static double pinch(double x, double first, double spacing, double depth) {
        if (x < first - spacing) {
            return 1;
        }

        return 1 - depth * 0.5 * (1 + Math.cos(2 * Math.PI * (x - first) / spacing));
    }

    /** D に沿った rings × segments の四角の筒。形と色は殻が決め、輪ごとに揺らぐ。 */
    private static void tube(VertexConsumer vc, Matrix4f m, Vec3 exit, double len, int rings, int segments,
            Shell shell, float time, long seed) {
        float[] c0 = new float[4];
        float[] c1 = new float[4];
        double[] cos = new double[segments + 1];
        double[] sin = new double[segments + 1];

        for (int j = 0; j <= segments; j++) {
            double ang = 2 * Math.PI * j / segments;
            cos[j] = Math.cos(ang);
            sin[j] = Math.sin(ang);
        }

        double prevX = 0;
        double prevR = shell.radius(0, 0) * wobble(0, time, seed);
        shell.color(0, c0);
        float prevFlick = 1;

        for (int i = 1; i <= rings; i++) {
            double s = (double) i / rings;
            double x = s * len;
            double r = shell.radius(s, x) * wobble(i, time, seed);
            shell.color(s, c1);
            float flick = (float) (0.8 + 0.2 * noise(time * 1.3 + i * 0.77, seed));

            for (int j = 0; j < segments; j++) {
                vertex(vc, m, ring(exit, prevX, prevR, cos[j], sin[j]), c0, prevFlick);
                vertex(vc, m, ring(exit, prevX, prevR, cos[j + 1], sin[j + 1]), c0, prevFlick);
                vertex(vc, m, ring(exit, x, r, cos[j + 1], sin[j + 1]), c1, flick);
                vertex(vc, m, ring(exit, x, r, cos[j], sin[j]), c1, flick);
            }

            prevX = x;
            prevR = r;
            prevFlick = flick;
            System.arraycopy(c1, 0, c0, 0, 4);
        }
    }

    private static Vec3 ring(Vec3 exit, double x, double r, double cos, double sin) {
        return exit.add(D.scale(x)).add(A.scale(cos * r)).add(B.scale(sin * r));
    }

    private static void bicone(VertexConsumer vc, Matrix4f m, Vec3 centre, double half, double r, float red,
            float green, float blue, float alpha) {
        float[] tip = {red, green, blue, 0};
        float[] mid = {red, green, blue, alpha};
        Vec3 front = centre.subtract(D.scale(half));
        Vec3 back = centre.add(D.scale(half));
        int seg = 12;

        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg;
            double a1 = 2 * Math.PI * (j + 1) / seg;
            Vec3 q0 = ring(centre, 0, r, Math.cos(a0), Math.sin(a0));
            Vec3 q1 = ring(centre, 0, r, Math.cos(a1), Math.sin(a1));

            for (Vec3 apex : new Vec3[] {front, back}) {
                vertex(vc, m, apex, tip, 1);
                vertex(vc, m, apex, tip, 1);
                vertex(vc, m, q1, mid, 1);
                vertex(vc, m, q0, mid, 1);
            }
        }
    }

    private static void disc(VertexConsumer vc, Matrix4f m, Vec3 centre, double r, float red, float green,
            float blue, float alpha) {
        float[] mid = {Math.min(1, red * 1.2f), Math.min(1, green * 1.3f), Math.min(1, blue * 1.3f), alpha};
        float[] rim = {red, green, blue, alpha * 0.5f};
        int seg = 16;

        for (int j = 0; j < seg; j++) {
            double a0 = 2 * Math.PI * j / seg;
            double a1 = 2 * Math.PI * (j + 1) / seg;
            vertex(vc, m, centre, mid, 1);
            vertex(vc, m, centre, mid, 1);
            vertex(vc, m, ring(centre, 0, r, Math.cos(a1), Math.sin(a1)), rim, 1);
            vertex(vc, m, ring(centre, 0, r, Math.cos(a0), Math.sin(a0)), rim, 1);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float[] c, float flick) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(c[0], c[1], c[2], Mth.clamp(c[3] * flick, 0, 1));
    }

    private static void set(float[] o, float r, float g, float b, float a) {
        o[0] = r;
        o[1] = g;
        o[2] = b;
        o[3] = a;
    }

    private static double wobble(int ring, float time, long seed) {
        return 1 + 0.05 * noise(time * 0.9 + ring * 1.37, seed);
    }

    /** 互いに割り切れない数本の正弦から作る -1〜1 の滑らかな雑音。 */
    private static double noise(double t, long seed) {
        double s = (seed & 1023) * 0.37;

        return 0.5 * Math.sin(t * 3.1 + s) + 0.3 * Math.sin(t * 7.3 + s * 1.7) + 0.2 * Math.sin(t * 13.9 + s * 2.3);
    }

    /** 光る金属・ガスのおおよその色。温度は摂氏。 */
    private static float[] blackBody(double c) {
        double t = Mth.clamp((c - 400) / 1200, 0, 1);

        return new float[] {
            (float) Mth.clamp(0.35 + t * 2.2, 0, 1),
            (float) Mth.clamp(-0.1 + t * 1.3, 0, 1),
            (float) Mth.clamp(-0.6 + t * 1.2, 0, 1)
        };
    }

    /**
     * 加算合成・両面・深度は書かない。重なった殻は互いを隠さず光として足し合わさる。cull と書き込みマスクは
     * 明示する（[[render-types-do-not-set-cull-or-depth-write]]）。
     */
    private static final class PlumeRenderType extends RenderType {
        static final RenderType PLUME = create("ashvehicles_afterburner_plume", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 1 << 16, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                        .setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setOutputState(TRANSLUCENT_TARGET)
                        .createCompositeState(false));

        private PlumeRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }
    }
}
