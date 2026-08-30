package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.network.DesignatePayload;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;

/**
 * ターゲティングポッド越しの視界。カメラをパイロットの頭から外してポッドへ載せ、機体ではなくマウスで旋回させる。
 *
 * <p>これはレーザー誘導兵器のもう半分だ。爆弾は翼とシーカーを持つが、誘導する物を何も持たない。それを与えるのは、
 * 落下中に誰かが目標へマークを据え続けることであり、これがそのための計器である。要件は
 * {@link com.ashvehicles.weapon.WeaponDefinition}、マーク自体は
 * {@link com.ashvehicles.entity.DesignationEntity} 参照。
 *
 * <p><b>キーは3つで、いずれも手が既に置かれている物だ。</b>照準キーでポッドを出し入れする——ただしポッドを必要と
 * する兵装を選択中に限るので、ポッドを積んだ機体が主翼の他の兵装のたびにガンサイトを失うことはない。スペースで
 * 十字線の下の物を指示し、また解放する。トリガーは投下で、それは従来通りのトリガーの動作だ。
 *
 * <p><b>機体は依然として操縦されている。</b>ここは操縦桿に一切触れない。この MOD は昔からキーで飛ぶので、ポッド
 * 視界のパイロットの足元にはキャノピー越しに外を見るパイロットと同じ機体があるし、飛ばしていたマウスはそもそも
 * 飛ばしていない。実際に意味するのは「パイロットが行き先を見られない」ことであり、それはストロー越しに地面を覗く
 * ことの正直な代償であって、実機で後席が操作する理由でもある。
 *
 * <p><b>指示以外は全てクライアント側だ。</b>ポッドがどこを向いているかはこのクライアントしか知らない——マウスで
 * あり、シミュレートする物が無い——し、どこを指示したかはサーバーへ送られる。そもそも保持してよいかを所有するのは
 * サーバーだ。{@link DesignatePayload} 参照。
 *
 * <p><b>ロード範囲の外でも指示する。</b>そうするほかない。ポッドは望遠鏡であり、向けられる先は日常的に、クライ
 * アントがチャンクを持つ範囲の数倍遠い。誰もロードしていない地面に置いた十字線は以前まったく何も見つけられず、
 * キーは何もせず、機体が印を付けられるのはほぼ真上にある物だけだった。その外では代わりに、仮定した床に対して地面を
 * 追い——{@link Terrain} 参照——推定値であると印を付けて地点を送る。
 * {@link com.ashvehicles.entity.DesignationEntity} が、そこがロードされ次第、実際の地表へ落ち着かせる。
 */
public final class PodCamera {
    /** ポッドが肉眼よりどれだけ物を近付けるか。ポッドは望遠鏡だ。 */
    public static final float ZOOM = 8.0F;
    /** 1tickで視界が進む量。往復の全行程に対する割合。 */
    private static final float RATE = 0.25F;

    /**
     * ポッドが左右・上下にどこまで見られるか（度）。
     *
     * <p>頭ではなくジンバルだ。パイロンの先端のボールなのでほぼ全周を回れるし、大きく下を見られて上はほとんど見ない。
     * 用途が地面だからだ。ゲーム内の他の仰角と同様、下が正。
     */
    private static final float YAW_LIMIT = 150.0F;
    private static final float PITCH_UP_LIMIT = -20.0F;
    private static final float PITCH_DOWN_LIMIT = 89.0F;

    /** ポッドを上げたときの初期指向。前下方の地面。 */
    private static final float REST_PITCH = 30.0F;

    /** 指示レイの到達距離（ブロック）。これを超えると見るべき物は無い。 */
    private static final double REACH = 2048.0;
    /** どれだけ外しても「十字線が物に乗っている」と数えるか（ブロック）。 */
    private static final double ENTITY_MARGIN = 0.6;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private static boolean active;
    /** 機体に対して測ったポッドの旋回量。右が正、下が正。 */
    private static float yaw;
    private static float pitch;
    /** 視界の遷移がどこまで進んだか。出し入れのイージング用。 */
    private static float progress;
    private static float progressO;
    @Nullable
    private static AircraftEntity aircraft;

    private PodCamera() {
    }

    /** ポッド視界が上がっており、プレイヤーがポッドを持つコックピットにいる間 true。 */
    public static boolean isActive() {
        return active && aircraft != null && !aircraft.isRemoved();
    }

    /**
     * 毎tick呼ばれる。プレイヤーが飛行中かを記録し、降機したり使用条件を失ったりしたらポッドを仕舞い、視界の遷移を
     * なめらかに進める。
     */
    public static void tick(@Nullable AircraftEntity flying) {
        if (flying != aircraft) {
            aircraft = flying;
            active = false;
            rest();
        }

        if (active && !isAvailable(flying)) {
            // ポッドが外れたか、兵装が切り替えられたか、機体がもう何かを見ていられる状態でなくなった。もう持って
            // いない望遠鏡をパイロットに覗かせ続けず、視界をコックピットへ戻す。
            active = false;
        }

        progressO = progress;
        progress = active ? Math.min(progress + RATE, 1.0F) : Math.max(progress - RATE, 0.0F);
    }

    /**
     * 今ポッド視界を上げられるか。ターゲティングポッドを搭載しており、それを必要とする兵装を選択中であること。
     *
     * <p>後半が、照準キーに従来通りの動作をさせている。ポッドと機関砲弾を満載した機体にもガンサイトはあるべきで、
     * 同じキーが別の意味を持つのは、パイロットがポッドの存在理由たる物を選択して初めてだ。{@link AimZoom} 参照。
     */
    public static boolean isAvailable(@Nullable AircraftEntity flying) {
        if (flying == null || flying.isRemoved() || flying.isWrecked()) {
            return false;
        }

        WeaponMounts weapons = flying.getWeapons();
        WeaponDefinition selected = weapons.selectedWeapon();

        return selected != null
                && selected.requires().orElse(null) == EquipmentDefinition.Kind.TARGETING
                && weapons.hasPod(EquipmentDefinition.Kind.TARGETING);
    }

    /** ポッドを出す／仕舞う。現在上がっているかを返す。 */
    public static boolean toggle() {
        if (active) {
            active = false;

            return false;
        }

        if (!isAvailable(aircraft)) {
            return false;
        }

        active = true;
        rest();

        return true;
    }

    private static void rest() {
        yaw = 0.0F;
        pitch = REST_PITCH;
    }

    /**
     * マウス移動の分だけポッドを旋回させる（度）。
     *
     * <p>何かを指示中は無視する。ポッドはマークを保持して追っており、マウスで視界をそこから引き剥がせば、それは誰も
     * 保持していないマークになる。先に解放すれば——捕捉したのと同じキーだ——ポッドはまた手持ちに戻り、向いていた位置
     * から始まる。
     */
    public static void turn(double deltaYaw, double deltaPitch) {
        if (designated() != null) {
            return;
        }

        yaw = Mth.clamp((float) (yaw + deltaYaw), -YAW_LIMIT, YAW_LIMIT);
        pitch = Mth.clamp((float) (pitch + deltaPitch), PITCH_UP_LIMIT, PITCH_DOWN_LIMIT);
    }

    /** このクライアントが最後に聞いた、ポッドの捕捉対象。何も捉えていなければ null。 */
    @Nullable
    public static Entity designated() {
        return aircraft == null ? null : aircraft.getDesignated();
    }

    /**
     * ワールドでのポッドの視線。
     *
     * <p>マークがあれば真っ直ぐそこを向く——「追う」の意味はそれが全てだ。機体は飛び続け、マークは置かれた場所に留まり、
     * ポッドはそれを画面中央に保つよう回る。マークが無ければ、マウスが残した2つの角度を、パイロットの頭と同様、機体
     * 自身の姿勢の後に適用した物になる。
     */
    public static Vec3 direction(float partialTick) {
        Entity mark = designated();

        if (mark != null && aircraft != null) {
            Vec3 toMark = mark.position().add(0.0, mark.getBbHeight() * 0.5, 0.0)
                    .subtract(eye(partialTick));

            if (toMark.lengthSqr() > 1.0E-6) {
                return toMark.normalize();
            }
        }

        return Attitude.nose(world(partialTick));
    }

    /** ワールドでのポッド自身の回転。視線はここから取る。 */
    private static Quaternionf world(float partialTick) {
        if (aircraft == null) {
            return new Quaternionf();
        }

        return new Quaternionf(aircraft.getAttitude(partialTick))
                .rotateY(-yaw * DEG_TO_RAD)
                .rotateX(pitch * DEG_TO_RAD)
                .normalize();
    }

    /**
     * カメラ用の、ポッドが向いている方位。
     *
     * <p>2つのジンバル角ではなく方向ベクトルから求める。マーク保持中は方向が真であり、角度はマウスが最後にポッドを
     * 残した位置にすぎないからだ。
     */
    public static float viewYaw(float partialTick) {
        Vec3 look = direction(partialTick);

        return (float) (Mth.atan2(-look.x, look.z) * (180.0 / Math.PI));
    }

    /** ポッドが水平線からどれだけ下を見ているか。Minecraft 流に下が正。 */
    public static float viewPitch(float partialTick) {
        Vec3 look = direction(partialTick);

        return (float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * (180.0 / Math.PI));
    }

    /**
     * 映像のロール。0だ。
     *
     * <p>ポッドは安定化されている。ボールが筐体の中で回り、線を通して送られる映像は、上の主翼が何をしていようと水平線
     * を水平線の位置に置く。代わりに機体のバンクを映像へ持ち込めば、パイロットが旋回するたび地面が傾く。それはまさに
     * マークを見失うまいとしている瞬間であり——不快であるだけでなく、答えとしても誤りだ。
     */
    public static float viewRoll(float partialTick) {
        return 0.0F;
    }

    /**
     * ワールドでの映像の取得位置。ポッドが吊られているステーション上の、ポッド自身のレンズだ。
     *
     * <p>両半分とも重要だ。ステーションは搭載状況の物——ポッドを内側へ移せば視界も内側へ移り、その外側に吊られた物の
     * 向こう側になる——で、ステーションからレンズまでのオフセットはポッドの物で、ポッド自身のモデルから読む。どちらも
     * 機体ファイルが持てる値ではない。どのステーションかは誰かが武装するまで決まらないし、ポッド前面のガラスの位置は
     * どこへ取り付けても同じだからだ。{@link EquipmentDefinition#lensAt} 参照。
     */
    public static Vec3 eye(float partialTick) {
        if (aircraft == null) {
            return Vec3.ZERO;
        }

        return aircraft.toWorld(lens(aircraft), partialTick);
    }

    /**
     * 機体座標系でのポッドのレンズ位置。搭載ステーションに、ポッド自身のガラスまでのオフセットを足した物。
     *
     * <p>機上で最初のターゲティングポッドを使う。MOD のどの機体でもそれが唯一の物だ。見つからなければ機体中央へ
     * フォールバックする。フレームとこの呼び出しの間にポッドが外れた場合だ。
     */
    private static Vec3 lens(AircraftEntity flying) {
        List<AircraftDefinition.Hardpoint> hardpoints = flying.getStats().hardpoints();
        List<WeaponMounts.Mount> mounts = flying.getWeapons().mounts();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            ResourceLocation pod = mounts.get(slot).equipment();

            if (pod == null) {
                continue;
            }

            EquipmentDefinition fitted = Definitions.equipment(pod);

            if (fitted.kind() == EquipmentDefinition.Kind.TARGETING) {
                return fitted.lensAt(hardpoints.get(slot).pos());
            }
        }

        return Vec3.ZERO;
    }

    /**
     * 捕捉と解放のキー。十字線が乗っている物を指示するか、既に保持していればマークを落とす。
     *
     * <p>どちらも指示を所有するサーバーへのメッセージだ。ここでは何も変えないし、映像がマークへ振れるのはサーバーが
     * そう言ってからになる。押下後コンマ数秒何も起きないわけだが、その価値はある——自分で指示するクライアントは、
     * 爆弾に渡されなかったマークを表示してしまう。
     */
    public static void designate() {
        if (aircraft == null) {
            return;
        }

        if (designated() != null) {
            PacketDistributor.sendToServer(DesignatePayload.CLEAR);

            return;
        }

        Vec3 from = eye(1.0F);
        Vec3 along = direction(1.0F);

        // まず地面がどうなっているか。空へ向けたポッドに指示する物は無いし、そう言う方が、上空2km にマークを置く
        // より良い。
        //
        // ロード範囲の外ではこれは見た答えではなく算出した答えになるが、それがポッドを使い物にしている全てだ。ポッドは
        // 遠くの物へ向ける望遠鏡であり、使用距離はクライアントがチャンクを持つ距離の数倍になる。ブロックだけを問うと
        // その外では何も見つからず、キーはまったく何もせず、機体はほぼ真上にある物しか指示できなかった。Terrain 参照。
        Terrain.Ground ground = Terrain.along(aircraft.level(), from, along, REACH, aircraft);

        if (ground == null) {
            return;
        }

        // 次に、その手前に何か立っていたか。地面までしか見ないので、十字線が乗っている斜面の向こうの車両はパイロット
        // の意図ではない——そしてスイープのコストが最大到達距離ではなく実際に指示している距離で済む。
        Vec3 point = ground.point();
        EntityHitResult struck = ProjectileUtil.getEntityHitResult(aircraft.level(), aircraft,
                from, point, new AABB(from, point).inflate(ENTITY_MARGIN), PodCamera::designatable);

        PacketDistributor.sendToServer(new DesignatePayload(false, point,
                struck == null ? -1 : struck.getEntity().getId(), ground.estimated()));
    }

    /**
     * マークを置く価値のある物。爆弾に値するだけの実体があり、かつ指示している機体自身やその搭乗者でないこと。
     */
    private static boolean designatable(Entity candidate) {
        return candidate.isAlive() && candidate.isPickable()
                && !WeaponMounts.isPartOf(aircraft, candidate);
    }

    /** 現時点で視界が通常よりどれだけ狭いか。1なら通常のまま。 */
    public static float factor(float partialTick) {
        return 1.0F + (ZOOM - 1.0F) * Mth.lerp(partialTick, progressO, progress);
    }

    /** 同じ値の、このフレーム用。 */
    public static float factor() {
        return factor(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
    }

    /** 視界の遷移が始まったら true。カメラがポッドにあるべき時点。 */
    public static boolean isShowing() {
        return isActive() || progress > 0.0F;
    }

}
