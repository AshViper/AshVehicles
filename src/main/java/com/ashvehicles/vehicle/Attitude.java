package com.ashvehicles.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 機体の向き。3つの角度ではなく回転（クォータニオン）として保持する。
 *
 * <p>方位・仰角・バンクは Minecraft が mob の向きを表す方法で、上下が保たれる物には十分だ。機体はそう
 * ではない。宙返りの頂点では背面かつ後ろ向きだし、天頂を越える瞬間に方位は180度飛び、仰角は折り返す。
 * 回転にはその継ぎ目が無いので、回転で組んだ機体は天頂を突き抜けて反対側へ出ても何事も起きない。
 *
 * <p>機体座標系は Minecraft がエンティティに使う物と同じ。+Z が機首方向、+Y がキャノピーを抜ける上方向、
 * したがって +X は左翼方向。保持している値の右から回転を掛けるとその座標系で回転が適用され、それが操作を
 * 世界ではなく機体に効かせている仕組み。
 */
public final class Attitude {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    private static final Vector3f NOSE = new Vector3f(0.0F, 0.0F, 1.0F);
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f LEFT = new Vector3f(1.0F, 0.0F, 0.0F);

    private Attitude() {
    }

    /** Minecraft の方位と仰角へ機体を向け、翼を水平にする回転。 */
    public static Quaternionf of(float yRot, float xRot) {
        return new Quaternionf().rotateY(-yRot * DEG_TO_RAD).rotateX(xRot * DEG_TO_RAD);
    }

    /**
     * 機体を自身の軸回りに回す。
     *
     * @param rollRate 機首軸回りの角度（度）。右が正
     * @param pitchRate 翼軸回りの角度（度）。機首上げが正
     * @param yawRate 機体の垂直軸回りの角度（度）。機首右が正
     */
    public static Quaternionf rotate(Quaternionf attitude, float rollRate, float pitchRate, float yawRate) {
        return attitude.rotateY(-yawRate * DEG_TO_RAD)
                .rotateX(-pitchRate * DEG_TO_RAD)
                .rotateZ(rollRate * DEG_TO_RAD)
                .normalize();
    }

    public static Vec3 nose(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(NOSE)));
    }

    public static Vec3 up(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(UP)));
    }

    /** 右翼方向。 */
    public static Vec3 right(Quaternionf attitude) {
        return toVec3(attitude.transform(new Vector3f(LEFT))).scale(-1.0);
    }

    /** 機首が向いている方位を Minecraft の流儀で。 */
    public static float heading(Quaternionf attitude) {
        Vec3 nose = nose(attitude);

        return (float) (Mth.atan2(-nose.x, nose.z) * (180.0 / Math.PI));
    }

    /** 機首が水平線からどれだけ下を向いているか。Minecraft の流儀で、機首下げが正。 */
    public static float elevation(Quaternionf attitude) {
        Vec3 nose = nose(attitude);

        return (float) (-Math.asin(Mth.clamp(nose.y, -1.0, 1.0)) * (180.0 / Math.PI));
    }

    /**
     * バンク角。右翼下げが正。世界の上方向を基準に、機体が機首軸回りにどれだけロールしているか。
     */
    public static float bank(Quaternionf attitude) {
        Vec3 nose = nose(attitude);
        Vec3 up = up(attitude);
        // 機体がまったくロールしていなければ翼があったはずの向き。
        Vec3 levelRight = nose.cross(new Vec3(0.0, 1.0, 0.0));

        if (levelRight.lengthSqr() < 1.0E-6) {
            // 真上か真下を向いている場合。どのバンク角でも見た目は同じなので水平と呼ぶ。
            return 0.0F;
        }

        levelRight = levelRight.normalize();
        Vec3 levelUp = levelRight.cross(nose);

        return (float) (Mth.atan2(up.dot(levelRight), up.dot(levelUp)) * (180.0 / Math.PI));
    }

    /**
     * {@code sight} の方向を見ている者から見た機体のバンク。その視界で水平線がどれだけ傾いているか。
     * コックピットカメラ用。パイロットの頭は機体に固定されているが、目はどこを向いていてもよいので。
     */
    public static float bankAlong(Quaternionf attitude, Vec3 sight) {
        Vec3 up = up(attitude);
        Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);
        Vec3 screenRight = sight.cross(worldUp);

        if (screenRight.lengthSqr() < 1.0E-6) {
            return bank(attitude);
        }

        screenRight = screenRight.normalize();
        Vec3 screenUp = screenRight.cross(sight).normalize();

        return (float) (Mth.atan2(up.dot(screenRight), up.dot(screenUp)) * (180.0 / Math.PI));
    }

    /**
     * 回転を「回転軸×回転量（ラジアン）」のベクトルとして表したもの。
     *
     * <p>回転<em>レート</em>は半分にでき、足せて、ゼロへ減衰できる必要があるが、クォータニオンはその
     * どれでもない。これは同じ回転を、それができる唯一の形で表した物——機体座標系の3つの数値で、まさに
     * パイロットが旋回計で読む機体レート（機首軸回りのロール、翼軸回りのピッチ、垂直尾翼回りのヨー）。
     * {@link #rotationOf} が逆変換で、半回転までの範囲では厳密な逆になる。
     *
     * <p>常に近い方の回り方を取るので、350度の回転は「大半周」ではなく逆向きの10度として返る。機体の
     * 1tick 分の回転を測る物は、定義からして近い方の回り方を訊いている。
     *
     * @param rotation 正規化済みの回転
     */
    public static Vector3f rotationVector(Quaternionfc rotation) {
        float w = rotation.w();
        float x = rotation.x();
        float y = rotation.y();
        float z = rotation.z();

        // クォータニオンとその符号反転は同じ回転を表す。近い方の回り方はそのうち片方だけ。
        if (w < 0.0F) {
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }

        float sine = (float) Math.sqrt(Math.max(0.0F, 1.0F - w * w));

        // これより小さいと軸が丸め誤差に埋もれる。どのみち残る桁の範囲では半角ベクトルが答えになる。
        if (sine < 1.0E-6F) {
            return new Vector3f(x * 2.0F, y * 2.0F, z * 2.0F);
        }

        float scale = 2.0F * (float) Math.acos(Mth.clamp(w, -1.0F, 1.0F)) / sine;

        return new Vector3f(x * scale, y * scale, z * scale);
    }

    /** {@link #rotationVector} が書く形式の「軸×角度」ベクトルが表す回転。 */
    public static Quaternionf rotationOf(Vector3fc rotation) {
        float angle = rotation.length();

        if (angle < 1.0E-6F) {
            return new Quaternionf();
        }

        float half = angle * 0.5F;
        float scale = (float) Math.sin(half) / angle;

        return new Quaternionf(rotation.x() * scale, rotation.y() * scale, rotation.z() * scale,
                (float) Math.cos(half));
    }

    /**
     * 機体座標系（x が右、y が上、z が機首）のオフセットをワールド座標のオフセットへ変換する。
     *
     * <p>回転は3回ではなく1回。以前組んでいた3本の軸は3つの単位ベクトルに回転を適用した物であり、回転は
     * 線形なので、オフセット全体を一度に回せば計算量1/3・ゴミ生成1/10で同じ答えになる。ここではそれが
     * 効く。全機体の全当たり判定と全パイロンが毎tick ここを通り、1回の呼び出しがクォータニオン変換3回と
     * 使い捨てベクトル十数個だった。
     *
     * <p>入口で x を反転しているのは、機体座標系の +X が<em>左</em>翼方向へ伸びるのに対し
     * （{@link #LEFT} と {@link #right} 参照）、オフセットの x は右向きに測るから。
     */
    public static Vec3 toWorld(Quaternionf attitude, Vec3 offset) {
        Vector3f world = attitude.transform(
                new Vector3f((float) -offset.x, (float) offset.y, (float) offset.z));

        return toVec3(world);
    }

    /**
     * 逆向きの変換。ワールド座標で測った物を機体座標系へ戻す。
     *
     * <p>符号反転も含め {@link #toWorld} の厳密な逆。単位回転の共役は逆回転であり、出口で x を戻すのは
     * 入口で反転するのと同じ理由。答えるのは「機体のどこか」——どちら側で、どれだけ上で、どれだけ前か。
     * 車体がどんな姿勢で寝ていても変わらない。
     */
    public static Vec3 toBody(Quaternionf attitude, Vec3 world) {
        Vector3f body = new Quaternionf(attitude).conjugate().transform(
                new Vector3f((float) world.x, (float) world.y, (float) world.z));

        return new Vec3(-body.x, body.y, body.z);
    }

    private static Vec3 toVec3(Vector3f vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
