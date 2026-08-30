package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 機体の箱が機体座標系のどこに寝ているか。平面図として描く計器のため。
 *
 * <p>MOD にはこの計算が既に2つある——{@code GroundVehicleEntity} は実箱をワールドへ置くため毎tick行い、
 * {@code VehicleShapeRenderer} は輪郭を描くためもう一度行う——が、どちらも欲しいのは車体姿勢まで掛けた<em>ワールド
 * 座標の</em>答えだ。画面に描く戦車の絵が欲しいのはその1歩手前、つまり車体自体を静止させたまま砲塔が箱をどこへ
 * 運んだかである。よってここに1つ置き、機体を描く2つの計器——隅の平面図と着弾位置の表示——が符号のコピーを各自
 * 持つ代わりに共有する。
 *
 * <p><b>軸はファイル自身の物</b>。x が右、y が上、z が車首方向で、{@code hitbox} ブロックの書き方そのままだ。
 * 回転が働く座標系はそれではない——クォータニオンの内側では +X が<em>左</em>を指す。だから
 * {@link com.ashvehicles.vehicle.Attitude#toWorld} は符号を反転する——ので、両者を跨ぐのは {@link #turn} 1か所
 * だけで、ここの他は全てファイルの軸のままに留まる。
 */
final class Silhouette {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /**
     * 機体を見下ろせる最大角（ラジアン）。
     *
     * <p>これが無いと、ほぼ垂直に落ちてきた弾は「上」が計算の転び方次第で決まる平面図に対して描かれてしまう。
     * 丸め誤差の符号で回転する絵は、少し違う角度から描いた絵より悪い。
     */
    private static final double STEEPEST = Math.toRadians(55.0);

    private Silhouette() {
    }

    /**
     * 現在の砲塔位置を反映した、機体座標系での箱の位置。
     *
     * <p>{@code GroundVehicleEntity.mountOffset} と同じ3つの場合分けで、理由も同じ。車体上の箱はファイル通りの
     * 位置、砲塔上の箱は旋回輪の周りに回した位置、砲上の箱はまず耳軸周りに揺らしてから砲塔と共に回す。機体では
     * {@code stats} が null になる。砲塔も砲も持たず、全ての箱が車体上にあるからだ。
     */
    static Vec3 centre(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            float traverse, float pitch) {
        if (stats == null || box.mount() == VehicleShape.Mount.HULL) {
            return box.offset();
        }

        Vec3 at = box.mount() == VehicleShape.Mount.GUN
                ? onGun(box.offset(), stats.armament().trunnion(), pitch)
                : box.offset();

        return onTurret(at, stats.turret().ring(), traverse);
    }

    /** 砲塔の旋回量だけ旋回輪の周りに回した点。 */
    private static Vec3 onTurret(Vec3 offset, Vec3 ring, float traverse) {
        Vec3 local = offset.subtract(ring);
        float radians = traverse * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return ring.add(new Vec3(
                local.x * cos + local.z * sin,
                local.y,
                -local.x * sin + local.z * cos));
    }

    /** 砲の俯仰量だけ耳軸の周りに揺らした点。 */
    private static Vec3 onGun(Vec3 offset, Vec3 trunnion, float pitch) {
        Vec3 local = offset.subtract(trunnion);
        float radians = -pitch * DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);

        return trunnion.add(new Vec3(
                local.x,
                local.y * cos - local.z * sin,
                local.y * sin + local.z * cos));
    }

    /**
     * 機体内で箱がどう寝ているか。砲塔の旋回、砲身に乗るなら砲の俯仰、そして担い手の中での箱自身の角度。
     *
     * <p>戻り値はクォータニオン自身の座標系でベクトルを回す。{@code transform} ではなく {@link #turn} へ渡せば、
     * 座標系の橋渡しは代わりにやってくれる。
     */
    static Quaternionf rotation(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            float traverse, float pitch) {
        Quaternionf rotation = new Quaternionf();

        if (stats != null && box.mount() != VehicleShape.Mount.HULL) {
            rotation.rotateY(-traverse * DEG_TO_RAD);
        }

        if (stats != null && box.mount() == VehicleShape.Mount.GUN) {
            rotation.rotateX(-pitch * DEG_TO_RAD);
        }

        return rotation.mul(box.orientation());
    }

    /**
     * 上記の回転でベクトルを回す。入口も出口もファイルの軸で扱う。
     *
     * <p>x を入口と出口で反転しているのは、クォータニオンが +X を左側とする座標系で働く一方、ここの他の部分は
     * x を右として数えるからだ。
     */
    static Vec3 turn(Quaternionf rotation, Vec3 offset) {
        Vector3f turned = rotation.transform(
                new Vector3f((float) -offset.x, (float) offset.y, (float) offset.z));

        return new Vec3(-turned.x, turned.y, turned.z);
    }

    /**
     * ある線に沿って見ている者にとっての右方向と上方向。
     *
     * <p>どちらも機体座標系なので、機体上の点は「各方向にどれだけ沿っているか」を問うだけで画面に置ける。投影は
     * それが全てだ。透視投影は使わない。切手大の計器では何も得られないし、平面に描いたシルエットこそ誰もが読み方
     * を知っている物だからだ。
     */
    record View(Vec3 right, Vec3 up) {
        /**
         * 線の後ろに立ってそれに沿って見る者の視点——弾が飛来した線について言えば、砲手が見た機体の姿だ。
         */
        static View along(Vec3 line) {
            double flat = Math.sqrt(line.x * line.x + line.z * line.z);
            Vec3 look;

            if (flat < 1.0E-4) {
                // 真上か真下。取るべき方位が無いので、丸め誤差から拾った角度ではなく真後ろから描く。
                look = new Vec3(0.0, 0.0, 1.0);
            } else {
                double climb = Mth.clamp(Math.atan2(line.y, flat), -STEEPEST, STEEPEST);
                double along = Math.cos(climb);

                look = new Vec3(line.x / flat * along, Math.sin(climb), line.z / flat * along);
            }

            Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(look).normalize();

            return new View(right, look.cross(right).normalize());
        }

        /**
         * 見ている線を、そこから作った2軸から復元した物。
         *
         * <p>3つ目のフィールドとして持たずこの形にしてあるのは、重要なのがクランプ後の線——絵が実際に組まれた線
         * ——だからだ。2軸から読み戻せば、その2軸と食い違うことはありえない。
         */
        Vec3 look() {
            return this.right.cross(this.up);
        }

        /** 点が絵の中心からどれだけ右にあるか。 */
        double across(Vec3 point) {
            return point.dot(this.right);
        }

        /** 同じく、どれだけ上にあるか。 */
        double aloft(Vec3 point) {
            return point.dot(this.up);
        }
    }
}
