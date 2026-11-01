package com.ashvehicles.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 転輪1つ1つの地面への追従。描画側だけ。
 *
 * <p>車体の高さと傾きは履帯の下の地面から1枚の平面として決まり（{@code GroundVehicleEntity.settleOnGround}）、
 * 車体はその平面のいちばん高い所に乗る。だから窪みや段の上では、平面の下に地面の無い転輪が出る。実車ではその
 * 転輪はトーションバーに押されて窪みへ伸び、出っ張りでは押し縮められる。ここはそれを転輪ごとに描く。
 *
 * <p>転輪の真下の地面を tick ごとに読み、車体に対して上下できる範囲（{@link #reachOf}）の中で:
 * 地面が高ければ即座に押し上げられ（地面は転輪を突き抜けさせない）、低ければバネに押されて落ちていく
 * （一瞬で窪みの底へ移ると、段を越えるたびに転輪がぱたぱた跳ねる）。
 *
 * <p>{@link Ride} と同じく<b>絵だけ</b>。車体の位置も当たり判定も砲の照準も、これを見ない。どのクライアントも同じ
 * 地面を見ているので通信は要らない。
 */
public final class WheelGround {
    public static final WheelGround NONE = new WheelGround();

    /** 転輪が車体に対して上下できる距離の下限（ブロック）。{@code suspension.travel} がこれより小さければこちら。 */
    private static final float MIN_REACH = 0.3F;
    /** 地面が離れたとき転輪が伸びる加速度（ブロック/tick²）。 */
    private static final float DROOP = 0.06F;
    /** 一度に追い付く tick の上限。画面外にいた車両の転輪を何百 tick も回さない。 */
    private static final int MAX_CATCH_UP = 3;

    private static final Map<GroundVehicleEntity, WheelGround> STATES = new WeakHashMap<>();

    /**
     * 転輪1つ。モデル軸・モデルのブロック単位（縮尺前）。
     *
     * @param x モデルの X。+ は車両の左（描画が半回転させるので）
     * @param z モデルの Z。+ は車尾
     */
    private record Wheel(String bone, float x, float y, float z, float radius) {
    }

    private final List<Wheel> wheels = new ArrayList<>();
    /** 車体に対する転輪の持ち上がり（ワールドのブロック）。+ が押し縮められた側。 */
    private float[] offset = new float[0];
    private float[] previous = new float[0];
    private float[] rate = new float[0];
    private long tick = Long.MIN_VALUE;
    private float scale = 1.0F;

    private WheelGround() {
    }

    /**
     * この車両の転輪を今の tick まで進めて返す。モデルのポーズ付けから毎フレーム呼ぶ。
     */
    public static WheelGround update(GeoModel<?> model, GroundVehicleEntity vehicle) {
        List<String> names = vehicle.getStats().model().roadWheels();

        if (names.isEmpty()) {
            return NONE;
        }

        WheelGround state = STATES.computeIfAbsent(vehicle, ignored -> new WheelGround());

        if (state.wheels.isEmpty()) {
            state.learn(model, names, vehicle.getStats().model().scale());
        }

        state.advance(vehicle);

        return state;
    }

    /** 既に進めた状態。履帯を敷く側から読む。持たなければ {@link #NONE}。 */
    public static WheelGround of(GroundVehicleEntity vehicle) {
        WheelGround state = STATES.get(vehicle);

        return state == null ? NONE : state;
    }

    /** 転輪が車体に対して上下できる距離（ブロック）。 */
    public static float reachOf(float travel) {
        return Math.max(travel, MIN_REACH);
    }


    /** その転輪の持ち上がり（モデルのブロック単位）。知らない転輪なら0。 */
    public float liftOf(String bone, float partialTick) {
        for (int i = 0; i < this.wheels.size(); i++) {
            if (this.wheels.get(i).bone().equals(bone)) {
                return this.at(i, partialTick) / this.scale;
            }
        }

        return 0.0F;
    }

    /**
     * 履帯上の1点の持ち上がり（モデルのブロック単位）。同じ側の転輪の持ち上がりを前後位置で直線に補間する。
     *
     * @param modelX モデルの X。側を決めるのに符号だけを見る
     * @param modelZ モデルの Z
     */
    public float beltLift(float modelX, float modelZ, float partialTick) {
        int below = -1;
        int above = -1;

        for (int i = 0; i < this.wheels.size(); i++) {
            Wheel wheel = this.wheels.get(i);

            if (Math.signum(wheel.x()) != Math.signum(modelX)) {
                continue;
            }

            if (wheel.z() <= modelZ && (below < 0 || wheel.z() > this.wheels.get(below).z())) {
                below = i;
            }

            if (wheel.z() >= modelZ && (above < 0 || wheel.z() < this.wheels.get(above).z())) {
                above = i;
            }
        }

        if (below < 0 && above < 0) {
            return 0.0F;
        }

        if (below < 0 || above < 0 || below == above) {
            return this.at(below < 0 ? above : below, partialTick) / this.scale;
        }

        float z0 = this.wheels.get(below).z();
        float z1 = this.wheels.get(above).z();
        float t = z1 - z0 < 1.0E-4F ? 0.0F : (modelZ - z0) / (z1 - z0);

        return Mth.lerp(t, this.at(below, partialTick), this.at(above, partialTick)) / this.scale;
    }

    private float at(int i, float partialTick) {
        return Mth.lerp(partialTick, this.previous[i], this.offset[i]);
    }

    private void learn(GeoModel<?> model, List<String> names, float scale) {
        this.scale = Math.max(scale, 0.01F);

        for (String name : names) {
            model.getBone(name).ifPresent(bone -> this.wheels.add(wheelOf(name, bone)));
        }

        // roadWheels には上部転輪や起動輪・誘導輪も入っている。地面に届かないそれらを読むと伸び切り（−reach）になり、
        // 履帯の下側が接地転輪と交互に補間されて鋸の歯になった。地面を踏む高さの物だけを残す。
        float floor = Float.MAX_VALUE;
        float size = 0.0F;

        for (Wheel wheel : this.wheels) {
            floor = Math.min(floor, wheel.y() - wheel.radius());
            size = Math.max(size, wheel.radius());
        }

        float tolerance = Math.max(size * 0.5F, 0.1F / this.scale);
        float lowest = floor;

        this.wheels.removeIf(wheel -> wheel.y() - wheel.radius() > lowest + tolerance);

        this.offset = new float[this.wheels.size()];
        this.previous = new float[this.wheels.size()];
        this.rate = new float[this.wheels.size()];
    }

    private static Wheel wheelOf(String name, GeoBone bone) {
        return BakedGeometry.reachOf(bone)
                .map(bounds -> new Wheel(name, bounds.centre().x(), bounds.centre().y(), bounds.centre().z(),
                        (bounds.max().y() - bounds.min().y()) * 0.5F))
                .orElseGet(() -> {
                    var pivot = BakedGeometry.pivotOf(bone);

                    return new Wheel(name, pivot.x(), pivot.y(), pivot.z(), 0.0F);
                });
    }

    private void advance(GroundVehicleEntity vehicle) {
        long now = vehicle.level().getGameTime();

        if (now == this.tick) {
            return;
        }

        boolean fresh = this.tick == Long.MIN_VALUE || now - this.tick > MAX_CATCH_UP * 4L;
        int steps = fresh ? 1 : (int) Math.min(now - this.tick, MAX_CATCH_UP);
        this.tick = now;

        float reach = reachOf(vehicle.getStats().suspension().travel());
        Quaternionf attitude = vehicle.getAttitude(1.0F);
        Vec3 origin = vehicle.position();

        for (int i = 0; i < this.wheels.size(); i++) {
            float target = this.groundUnder(vehicle, this.wheels.get(i), attitude, origin, reach);

            for (int step = 0; step < steps; step++) {
                this.previous[i] = this.offset[i];

                if (fresh || target >= this.offset[i]) {
                    // 地面が転輪を押し上げる。地面は転輪を突き抜けさせないので、遅れずにその場へ。
                    this.offset[i] = target;
                    this.rate[i] = 0.0F;
                } else {
                    // 地面が離れた。転輪はバネに押されて伸び、地面に着いた所で止まる。
                    this.rate[i] -= DROOP;
                    this.offset[i] = Math.max(this.offset[i] + this.rate[i], target);

                    if (this.offset[i] <= target) {
                        this.rate[i] = 0.0F;
                    }
                }
            }

            if (fresh) {
                this.previous[i] = this.offset[i];
            }
        }
    }

    /**
     * 転輪の下の地面が、車体の上で転輪をどれだけ持ち上げる（＋）か落とす（−）か。可動域で切る。届く範囲に
     * 地面が無ければ伸び切り。
     */
    private float groundUnder(GroundVehicleEntity vehicle, Wheel wheel, Quaternionf attitude, Vec3 origin,
            float reach) {
        // モデルの +X は車両の左、+Z は車尾（描画の半回転）。車両の軸は x が右、z が車首。
        // 高さは模型の転輪の下端ではなく車体の接地面（原点の高さ）から測る。模型の転輪が原点の高さに揃っている
        // とは限らず、平地でも転輪が伸び切って履帯の下へ垂れていた。
        Vec3 body = new Vec3(-wheel.x() * this.scale, 0.0, -wheel.z() * this.scale);
        Vec3 bottom = origin.add(Attitude.toWorld(attitude, body));
        BlockHitResult hit = vehicle.level().clip(new ClipContext(bottom.add(0.0, reach + 0.5, 0.0),
                bottom.subtract(0.0, reach + 0.05, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));

        if (hit.getType() == HitResult.Type.MISS) {
            return -reach;
        }

        return Mth.clamp((float) (hit.getLocation().y - bottom.y), -reach, reach);
    }
}
