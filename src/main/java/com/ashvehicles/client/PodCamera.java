package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.network.DesignatePayload;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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

    /**
     * 十字線からこの角度までに入っている物を「乗っている」と数える（度）。
     *
     * <p><b>箱への交差ではなく角度で問う理由。</b>この MOD の機体は素の直方体で当たり判定を持たない——箱は
     * パイロンや機体構造の {@code VehiclePart} 側にあり、本体の {@code isPickable()} はそのために false を
     * 返す。だから交差判定は本体を素通りするし、部品の方はゴーストの距離までクライアントへ送られない
     * （{@code EntityTrackingMixin} が距離制限を外しているのは機体・弾・マーカー・標的ドローンだけ）。
     * 交差で探す限り、遠方の機体は「そこに描かれているのに掴めない」ままになる。
     *
     * <p>角度で問えばどちらも要らない。1度は1500ブロック先で半径26ブロックの籠であり、ポッドの倍率で覗いて
     * いる乗員が十字線を機体に乗せれば確実に入る一方、隣を飛ぶ別機まで飲み込むほどは広くない。
     * {@code TargetLock} が照準線に最も近い物を採るのと同じ考え方で、あちらと同じく<em>最も近い物</em>では
     * なく<em>最も十字線に近い物</em>を採る。狙っている物こそ指したい物だからだ。
     */
    private static final double BASKET_ANGLE = 1.0;

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

        // 次に、その手前に何か立っていたか。地面が見つかっていればそこまで、見つかっていなければポッドの
        // 到達距離いっぱいまで探す。
        //
        // <p><b>地面が無いことは「指示する物が無い」ことではない。</b>以前はここで先に打ち切っていたので、
        // 空を背にした物——つまり飛んでいる機体すべて——は十字線に乗せても指示できなかった。地平線の上に
        // 見えている物には背後に地面が無いのだから、地面を先に要求する順序がそのまま「空の目標は指せない」
        // という規則になっていた。ゴーストとして描かれている遠方の機体が指せなかったのはこれで、描画とは
        // 関係が無い——実体はクライアントにちゃんと届いている（{@code EntityTrackingMixin}）。
        //
        // <p>地面までで打ち切る意味自体は残す。斜面の向こうに立っている車両はパイロットの意図ではないし、
        // 走査の代金も実際に指している距離で済む。変わったのは、地面が無い時に代金を最大距離まで払うことだけだ。
        // 実体は常にポッドの到達距離いっぱいまで探す。<em>地面で打ち切ってよいのは、その地面が実際に見えた
        // ブロックだった場合だけ</em>だからだ。
        //
        // <p>ロード範囲の外で {@link Terrain} が返す地面は、見た物ではなく仮定した平らな床との交点である
        // （{@code surface} は chunk を持たない列に NaN を返し、そこから先は最後に見た高さが床になる）。
        // Distant Horizons を入れていると、乗員には本物の山や谷がそこに描かれているのに、ポッドはその平面と
        // 交わった距離——しばしば数百ブロック——を「地面」と呼ぶ。その値で実体の捜索を打ち切っていたので、
        // 1500ブロック先の機体は角度を見る前に落とされ、指示はいつも目標の手前か向こうの床に着いた。
        // 推測が実物を隠してはならない。
        Entity struck = aimedAt(from, along, REACH);

        // 実体が乗っていれば、それが指示先。サーバーはこの場合マークを置かず対象そのものを保持するので
        // （{@code AircraftEntity#designate}）、渡す点はその位置でよく、推定でもない。
        if (struck != null && !behindGround(from, struck, ground)) {
            PacketDistributor.sendToServer(new DesignatePayload(false, centreOf(struck),
                    struck.getId(), false));

            return;
        }

        // 実体も地面も無い。本当に何も乗っていないので、指示する物が無いと言う方が正しい。
        if (ground == null) {
            return;
        }

        PacketDistributor.sendToServer(new DesignatePayload(false, ground.point(), -1, ground.estimated()));
    }

    /**
     * 十字線が乗っている物。無ければ null。
     *
     * <p>採るのは籠に入っている物のうち<em>最も十字線に近い</em>物で、最も近い物ではない。乗員が狙いを付けて
     * いる先こそ指したい物だから。{@link #BASKET_ANGLE} 参照。
     *
     * @param reach ここまでの物だけ見る。地面が見つかっていればそこまで——斜面の向こうに立っている車両は
     *              乗員の意図ではない——見つかっていなければポッドの到達距離いっぱい
     */
    @Nullable
    private static Entity aimedAt(Vec3 from, Vec3 along, double reach) {
        // 箱は籠を包める太さにする。線分の AABB をそのまま使うと、真北へ向いた視線では箱が X 方向に薄い
        // ままになり、1度外れた——2000ブロック先では35ブロック横の——目標が箱に入らない。角度で選ぶ前に
        // 箱で落としてしまえば、籠は名ばかりになる。
        double spread = reach * Math.tan(Math.toRadians(BASKET_ANGLE));
        AABB box = new AABB(from, from.add(along.scale(reach))).inflate(Math.max(ENTITY_MARGIN, spread));
        double basket = Math.cos(Math.toRadians(BASKET_ANGLE));
        Entity best = null;
        double closest = basket;

        for (Entity candidate : aircraft.level().getEntities(aircraft, box, PodCamera::designatable)) {
            Vec3 gap = centreOf(candidate).subtract(from);
            double distance = gap.length();

            if (distance < 1.0E-3 || distance > reach) {
                continue;
            }

            double alignment = gap.scale(1.0 / distance).dot(along);

            if (alignment > closest) {
                closest = alignment;
                best = candidate;
            }
        }

        return best;
    }

    /** その物の中心。足元ではなく。遠方では機体の高さの半分だけでも十字線の乗り方が変わる。 */
    private static Vec3 centreOf(Entity entity) {
        return entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }

    /**
     * その実体が、乗員に見えている地面の向こうに立っているか。
     *
     * <p>斜面の裏に隠れている車両は十字線が乗っていても指示の対象ではない——それは前からの規則で、そのまま
     * 残す。ただし遮る資格があるのは<em>実際に見えたブロック</em>だけだ。ロード範囲の外で推定された床は、
     * 乗員が見ている物ではなく計算の産物であり、しかも Distant Horizons を入れていれば乗員はそこに本物の
     * 地形を見ている。推定に実物を隠す権利は無い。
     */
    private static boolean behindGround(Vec3 from, Entity struck, @Nullable Terrain.Ground ground) {
        return ground != null && !ground.estimated()
                && centreOf(struck).distanceTo(from) > ground.point().distanceTo(from);
    }

    /**
     * マークを置く価値のある物。爆弾に値するだけの実体があり、かつ指示している機体自身やその搭乗者でないこと。
     *
     * <p><b>{@code isPickable()} は訊かない。</b>自前の箱を持つ機体と車両はそこで false を返す——素の直方体が
     * 脇へ退き、当たり判定を {@code VehiclePart} に譲るからだ。プレイヤーの手にとってはそれが正しいが、ポッドに
     * とっては「この MOD の全てのまともな目標が指示できない」という意味になっていた。部品は部品で、ゴーストの
     * 距離ではクライアントへ送られてすらいない。だからここは種類で問う。
     */
    private static boolean designatable(Entity candidate) {
        if (!candidate.isAlive() || WeaponMounts.isPartOf(aircraft, candidate)) {
            return false;
        }

        // 部品に十字線が乗っていても指すのは機体本体だ。部品はゴーストの距離まで届かないし、受け取る側に
        // とっても「主翼の一部」ではなく機体そのものを追う方が正しい。本体は籠の中心にいるので取りこぼさない。
        if (candidate instanceof VehiclePart) {
            return false;
        }

        // 生き物なら何でも、ではない。籠は十字線の周りに広がりを持つので、地面を指したつもりの押下が、
        // 狙った点の脇を歩いていた牛を掴みうる。爆弾を落とす理由がある物だけを載せる——{@code TargetLock}
        // の名簿と同じ判断で、同じ理由だ。
        return candidate instanceof VehicleEntityBase || candidate instanceof TargetDroneEntity
                || candidate instanceof Player || candidate instanceof Enemy;
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
