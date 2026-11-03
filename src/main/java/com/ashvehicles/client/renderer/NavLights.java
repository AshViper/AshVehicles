package com.ashvehicles.client.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.vehicle.VehicleShape;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 外部灯火。左翼端の赤・右翼端の緑・尾部の白の航法灯と、胴体上下の赤い衝突防止灯、翼端と尾部の白いストロボ。
 *
 * <p><b>位置は箱から読む。</b>定義ファイルに灯火の欄は無い。翼端の面の中、中心線の一番後ろ、主翼の付け根での
 * 胴体の背と腹（{@link #locate}）。箱を持たない機体は翼の {@code span} と素の当たり判定の
 * 高さに戻る。だから新しい機体を足しても灯火のために何かを書く必要は無い。
 *
 * <p><b>光は描くだけ。</b>アフターバーナーと同じ加算合成の板で、地形を照らさない
 * （[[moving-light-is-faked-in-two-places]]）。カメラに正対する板を2枚——小さな芯と柔らかな暈——重ね、
 * 遠いほど大きくして、昼でも夜でも遠くの機体の向きが灯の色で読めるようにする。
 *
 * <p>スイッチは同期されるが、明滅は世界の時刻から両側で出す。クライアントのみ。
 */
public final class NavLights {
    /** 芯の半径（ブロック）。実機の灯火の大きさより少し大きい。 */
    private static final float CORE = 0.12F;
    /** 遠くで見えるように、カメラからの距離に掛けて芯を太らせる割合。 */
    private static final float GROWTH = 0.0035F;
    /** 衝突防止灯の周期（tick）。実機の毎分 40〜100 回の真ん中。 */
    private static final int BEACON_PERIOD = 18;
    /** ストロボの周期（tick）。 */
    private static final int STROBE_PERIOD = 24;

    /** 翼端の面とみなす、一番外の角からの幅（ブロック）。 */
    private static final float TIP_BAND = 0.2F;
    /** 中心線を跨ぐとみなす半幅（ブロック）。 */
    private static final float SPINE = 0.25F;
    /** 灯を外板から浮かせる量。面の上にちょうど置くと半分が機体に埋まる。 */
    private static final float LIFT = 0.05F;

    private static final float[] RED ={1.0F, 0.12F, 0.08F};
    private static final float[] GREEN = {0.15F, 1.0F, 0.3F};
    private static final float[] WHITE = {1.0F, 0.97F, 0.9F};

    /** 定義ごとの灯火の位置。機体座標系で x が右。 */
    private static final Map<AircraftDefinition, Spots> SPOTS = new WeakHashMap<>();

    private record Spots(Vector3f left, Vector3f right, Vector3f tail, Vector3f top, Vector3f bottom) {
    }

    private NavLights() {
    }

    /** 機体の原点に置かれた {@code poseStack} へ、点いている灯火を描く。 */
    public static void draw(AircraftEntity aircraft, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers) {
        if (aircraft.isWrecked()) {
            return;
        }

        int lights = (aircraft.isLightOn(AircraftEntity.LIGHT_NAV) ? AircraftEntity.LIGHT_NAV : 0)
                | (aircraft.isLightOn(AircraftEntity.LIGHT_BEACON) ? AircraftEntity.LIGHT_BEACON : 0)
                | (aircraft.isLightOn(AircraftEntity.LIGHT_STROBE) ? AircraftEntity.LIGHT_STROBE : 0);

        draw(aircraft.getStats(), lights, aircraft.getId(), (aircraft.level().getGameTime() % 100000L) + partialTick,
                aircraft.getAttitude(partialTick), poseStack, buffers);
    }

    /**
     * 実体を介さずに描く。ゴーストが撮影した値で呼ぶ。{@code lights} は {@code AircraftEntity.LIGHT_*} の組、
     * {@code time} は明滅の位相を決めるゲームtick（部分tick込み）。
     */
    public static void draw(AircraftDefinition stats, int lights, int id, float time, Quaternionf attitude,
            PoseStack poseStack, MultiBufferSource buffers) {
        boolean nav = (lights & AircraftEntity.LIGHT_NAV) != 0;
        boolean beacon = (lights & AircraftEntity.LIGHT_BEACON) != 0;
        boolean strobe = (lights & AircraftEntity.LIGHT_STROBE) != 0;

        if (!(nav || beacon || strobe)) {
            return;
        }

        Spots spots = SPOTS.computeIfAbsent(stats, NavLights::locate);
        time += id & 15;

        poseStack.pushPose();
        // ここでの poseStack はカメラに対する機体の原点で、まだ回っていない。距離はその並進から測る。
        float distance = poseStack.last().pose().getTranslation(new Vector3f()).length();
        float core = CORE + distance * GROWTH;
        poseStack.mulPose(attitude);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = buffers.getBuffer(LightRenderType.LIGHT);
        // 機体座標系で見たカメラの向き。板はこれで正対させる。
        Quaternionf facing = new Quaternionf(attitude).conjugate()
                .mul(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());

        if (nav) {
            glow(vc, m, facing, spots.left(), RED, core, 1.0F);
            glow(vc, m, facing, spots.right(), GREEN, core, 1.0F);
            glow(vc, m, facing, spots.tail(), WHITE, core * 0.8F, 0.8F);
        }

        if (beacon) {
            // 上と下は半周期ずらす。実機の衝突防止灯も同時には光らない。
            glow(vc, m, facing, spots.top(), RED, core * 1.2F, pulse(time, BEACON_PERIOD, 0.0F));
            glow(vc, m, facing, spots.bottom(), RED, core * 1.2F, pulse(time, BEACON_PERIOD, 0.5F));
        }

        if (strobe) {
            float flash = flash(time, STROBE_PERIOD);

            if (flash > 0.0F) {
                glow(vc, m, facing, spots.left(), WHITE, core * 1.6F, flash);
                glow(vc, m, facing, spots.right(), WHITE, core * 1.6F, flash);
                glow(vc, m, facing, spots.tail(), WHITE, core * 1.3F, flash);
            }
        }

        poseStack.popPose();
    }

    /** 回転灯のように、正弦の山で強まり谷で消える。 */
    private static float pulse(float time, int period, float phase) {
        float s = (float) Math.sin(2.0 * Math.PI * (time / period + phase));

        return s <= 0.0F ? 0.0F : s * s;
    }

    /** 周期の頭で2回、短く閃く。 */
    private static float flash(float time, int period) {
        float t = time % period;

        return t < 1.5F || (t >= 3.0F && t < 4.5F) ? 1.0F : 0.0F;
    }

    /** 芯と暈の2枚。{@code at} は機体座標系（x が右）なので、描画の座標系へは x を反転する。 */
    private static void glow(VertexConsumer vc, Matrix4f m, Quaternionf facing, Vector3f at, float[] colour,
            float radius, float strength) {
        if (strength <= 0.01F) {
            return;
        }

        Vector3f centre = new Vector3f(-at.x, at.y, at.z);
        quad(vc, m, facing, centre, radius * 3.5F, colour, 0.35F * strength);
        quad(vc, m, facing, centre, radius, mix(colour), strength);
    }

    /** 芯は色を白へ寄せる。強い光は真ん中が白飛びして見える。 */
    private static float[] mix(float[] colour) {
        return new float[] {0.5F + colour[0] * 0.5F, 0.5F + colour[1] * 0.5F, 0.5F + colour[2] * 0.5F};
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Quaternionf facing, Vector3f centre, float r,
            float[] c, float alpha) {
        Vector3f right = facing.transform(new Vector3f(r, 0.0F, 0.0F));
        Vector3f up = facing.transform(new Vector3f(0.0F, r, 0.0F));
        // 縁は透明、中心だけ色を持つ扇。4つの三角を四角形の頂点の重複で描く。
        Vector3f[] rim = {
            new Vector3f(centre).add(right),
            new Vector3f(centre).add(up),
            new Vector3f(centre).sub(right),
            new Vector3f(centre).sub(up)
        };

        for (int i = 0; i < 4; i++) {
            Vector3f a = rim[i];
            Vector3f b = rim[(i + 1) % 4];
            vertex(vc, m, centre, c, alpha);
            vertex(vc, m, centre, c, alpha);
            vertex(vc, m, b, c, 0.0F);
            vertex(vc, m, a, c, 0.0F);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vector3f p, float[] c, float alpha) {
        vc.addVertex(m, p.x, p.y, p.z).setColor(c[0], c[1], c[2], Math.min(1.0F, alpha));
    }

    /**
     * 箱から灯火の位置を決める。実機の付け方に合わせる:
     *
     * <ul>
     * <li>航法灯は翼端の<b>面の中</b>。一番外の角1つではなく、一番外から少しの所にある角を全部均す。後退翼の
     * 後縁の角や、翼の上面・下面の縁に乗らないように。左右は鏡に写す——片側だけ箱がずれていても揃う。</li>
     * <li>尾灯は中心線の一番後ろ（テールコーンか垂直尾翼の後縁）。水平尾翼の先端を拾って中心へ寄せると、
     * 何も無い空中に浮いていた。</li>
     * <li>衝突防止灯は胴体の背と腹、主翼の付け根の辺り。一番高い所を取ると垂直尾翼の先へ上がっていた。
     * 翼端が機体の後ろ半分にある（ヘリの水平安定板）なら、胴体の真ん中に置く。</li>
     * </ul>
     */
    private static Spots locate(AircraftDefinition stats) {
        List<VehicleShape.Box> boxes = stats.hitbox().shape().boxes();
        List<Vector3f> corners = new ArrayList<>();
        // 箱ごとの、角を囲む軸平行の範囲。min x, y, z, max x, y, z。
        List<float[]> extents = new ArrayList<>();

        for (VehicleShape.Box box : boxes) {
            // 可動部（ランプ・扉・ノズル）の箱は外れる。灯火は動かない所に付いている。
            if (box.mount() != VehicleShape.Mount.HULL) {
                continue;
            }

            Quaternionf orientation = box.orientation();
            Vector3f half = new Vector3f((float) box.size().x, (float) box.size().y, (float) box.size().z).mul(0.5F);
            Vector3f centre = new Vector3f((float) box.offset().x, (float) box.offset().y, (float) box.offset().z);
            float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = orientation.transform(new Vector3f(
                        (corner & 1) == 0 ? -half.x : half.x,
                        (corner & 2) == 0 ? -half.y : half.y,
                        (corner & 4) == 0 ? -half.z : half.z)).add(centre);
                corners.add(p);

                for (int i = 0; i < 3; i++) {
                    e[i] = Math.min(e[i], p.get(i));
                    e[i + 3] = Math.max(e[i + 3], p.get(i));
                }
            }

            extents.add(e);
        }

        if (corners.isEmpty()) {
            return fallback(stats);
        }

        float reach = 0.0F;
        float front = -Float.MAX_VALUE;
        float back = Float.MAX_VALUE;

        for (Vector3f p : corners) {
            reach = Math.max(reach, Math.abs(p.x));
            front = Math.max(front, p.z);
            back = Math.min(back, p.z);
        }

        float tipY = 0.0F;
        float tipZ = 0.0F;
        int tips = 0;

        for (Vector3f p : corners) {
            if (Math.abs(p.x) >= reach - TIP_BAND) {
                tipY += p.y;
                tipZ += p.z;
                tips++;
            }
        }

        tipY /= tips;
        tipZ /= tips;

        // 中心線を跨ぐ箱。胴体と垂直尾翼。
        List<float[]> spine = new ArrayList<>();

        for (float[] e : extents) {
            if (e[0] <= SPINE && e[3] >= -SPINE) {
                spine.add(e);
            }
        }

        if (spine.isEmpty()) {
            spine = extents;
        }

        float[] last = spine.get(0);

        for (float[] e : spine) {
            if (e[2] < last[2]) {
                last = e;
            }
        }

        Vector3f tail = new Vector3f(0.0F, (last[1] + last[4]) * 0.5F, last[2] - LIFT);

        float beaconZ = tipZ < back + (front - back) * 0.4F ? (front + back) * 0.5F : tipZ;
        float top = -Float.MAX_VALUE;
        float bottom = Float.MAX_VALUE;

        // その位置で胴体を切る箱が無ければ（翼と胴体の間の隙間など）、前後へ範囲を広げて探す。
        for (float reachZ = 0.5F; top == -Float.MAX_VALUE; reachZ *= 2.0F) {
            for (float[] e : spine) {
                if (e[2] - reachZ <= beaconZ && beaconZ <= e[5] + reachZ) {
                    top = Math.max(top, e[4]);
                    bottom = Math.min(bottom, e[1]);
                }
            }
        }

        return new Spots(new Vector3f(-reach - LIFT, tipY, tipZ), new Vector3f(reach + LIFT, tipY, tipZ), tail,
                new Vector3f(0.0F, top + LIFT, beaconZ), new Vector3f(0.0F, bottom - LIFT, beaconZ));
    }

    /** 箱を持たない機体。翼幅と素の当たり判定から置く。 */
    private static Spots fallback(AircraftDefinition stats) {
        float half = stats.wing().span() * 0.5F;
        float height = stats.hitbox().height();
        float length = Math.max(stats.hitbox().width(), stats.wing().span()) * 0.5F;

        return new Spots(new Vector3f(-half, height * 0.4F, 0.0F), new Vector3f(half, height * 0.4F, 0.0F),
                new Vector3f(0.0F, height * 0.5F, -length), new Vector3f(0.0F, height, 0.0F),
                new Vector3f(0.0F, 0.0F, 0.0F));
    }

    /**
     * 加算合成・両面・深度は読むが書かない。アフターバーナーのプルームと同じ状態
     * （[[render-types-do-not-set-cull-or-depth-write]]）。
     */
    private static final class LightRenderType extends RenderType {
        static final RenderType LIGHT = create("ashvehicles_nav_light", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 1 << 12, false, true, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                        .setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setOutputState(TRANSLUCENT_TARGET)
                        .createCompositeState(false));

        private LightRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }
    }
}
