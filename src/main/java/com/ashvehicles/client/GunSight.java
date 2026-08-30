package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.weapon.GunStations;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 砲が本当はどこを指しているか、そしてそれで何かに当てるには何が要るか。
 *
 * <p>砲は取り付け先を向けることで照準する——機体なら飛ばして、砲塔なら旋回させて——し、計器はかつてボアサイト上の
 * マーク1つだけでそれを伝えていた。内側からならほぼ正しいが、他のどこから見ても誤りだ。三人称カメラは十数ブロック
 * 後ろかつ数ブロック上に座っており、<em>そこから</em>引いた方向は弾が出ていく線ではない。斜面に据えれば、上からの
 * ボアサイトは弾の着弾点よりずっと先の地面を指す。カメラが銃口より上にある分だけずれるのだ。弾は落ちもするし、
 * ロケットは到達するまでに目標が動くほどゆっくり出ていく。そのどれもボアサイトには載っていない。
 *
 * <p><b>どの機体に取り付けられているかはここでは関係無い。</b>照準に必要なのは「弾がどこから出て、どちらへ出るか」
 * だけで、それが {@link Bore} の全てだ。機体はパイロンと機首で答え、砲塔は銃口と自分が据えた線で答える。以下の
 * 全て——飛翔、落下、見越し——はどちらでも同じ計算であり、それが対空砲架に「機体が既に持っていた照準」を借りさせて
 * いる。
 *
 * <p>だからこれは仮定ではなく算出した弾道だ。弾は銃口から、弾体自身が使うのと同じ計算——モーター、レールが持ち出した
 * 速度、落下——で前進させ、世界にぶつかるまで飛ばす。その結果から2つを読む。
 *
 * <ul>
 * <li><b>ピッパー</b>: 弾が落ちる位置を、落ちる対象の上のワールド上の点として。方向ではなく位置に描くので、どの
 * カメラからでも正しい。開けた空で落ちる物が無ければ、線上の基準距離に置く。</li>
 * <li><b>見越し点</b>: ボアサイトに最も近く射程内にある物について、今撃った弾がその頃の目標位置へ到達するには機首を
 * どこへ置くべきか。飛翔時間は算出した弾道から、目標自身の速度をそれで前進させ、落下分を差し引くので弾の落下は既に
 * 支払い済みになる。</li>
 * </ul>
 *
 * <p>爆撃照準と同様、毎tick 1回求める。世界の走査と目標の掃引にコストがかかるし、どちらの答えも1/60秒では変わらない
 * からだ。返すのは画面座標の対ではなく、毎フレームそれを<em>組み直す</em>のに必要な物だ。点ではなく機首方向の距離を
 * 返すので、マークは機首の動きと同じなめらかさで追従し、1tick古いのは「どれだけ先にあるか」だけになる——発砲に値する
 * どの距離でもそれは目に見えない。{@link Solution} 参照。
 */
public final class GunSight {
    /** 追跡する価値のある最長飛翔時間（tick）。これを超えると弾は地平線の向こうだ。 */
    private static final int MAX_FLIGHT = 400;
    /**
     * 弾が何に当たるかを世界へ問う距離の上限（ブロック）。
     *
     * <p>弾が横切る全ブロックを歩くのがこの処理の高価な部分であり、機関砲弾には900の射程が与えられている。描画距離
     * の外ではどのみちクライアントに当たる物は無い——未ロードのチャンクは空気だ——ので、これより遠くまで問うのは
     * 「何も無い」と告げられるために金を払うことになる。
     */
    private static final double TRACE_REACH = 512.0;
    /**
     * 弾が落ちる物が無いときピッパーを置く、線上の距離（ブロック）。実物のガンサイトもある距離で照準規正されている。
     * これがそれだ。
     */
    private static final double REFERENCE_RANGE = 300.0;
    /**
     * 見越し点を提示する価値のある距離の上限（ブロック）。
     *
     * <p>弾の飛翔可能距離より十分内側だ。数百ブロックを超えると飛翔時間が長くなりすぎ、目標が同じ動きを続ける保証は
     * どこにも無い。マークは照準器が守れない約束になってしまう。
     */
    private static final double TARGET_REACH = 600.0;
    /**
     * 機首からどれだけ外れた物を目標として捉えるか、そして捉えた後どれだけ外れるまで保持するか（度）。
     *
     * <p>意図的に広く取り、後者を前者より広くしてある。横切る目標への見越しはかなりの角度になるし、見越しマークへ
     * 向けて飛ぶパイロットにとって目標自体はボアサイトから大きく外れている——機首の真下の物しか保持できないほど狭い
     * 円錐は、パイロットが撃とうとしたまさにその瞬間に目標を手放してしまう。
     */
    private static final float SEARCH_CONE = 25.0F;
    private static final float HOLD_CONE = 40.0F;
    /**
     * 現在の目標を手放して別の物へ乗り換えるために、その別の物がボアサイトへどれだけ近い必要があるか（度）。余裕が
     * 無いと、中央付近の2つの目標が数tickごとに入れ替わり、見越しマークもそれに付き合うことになる。
     */
    private static final float SWITCH_MARGIN = 5.0F;
    /** マークが「撃て」と示すために、機首が見越し点へどれだけ近い必要があるか（度）。 */
    private static final float ON_TARGET = 1.5F;
    /**
     * 毎tick、目標速度をどれだけ新しい値で更新するか。
     *
     * <p>クライアント自身が動かしていない物の速度は、前tickにどれだけ動いて描かれたかから読むが、それはクライアント
     * 自身の補間で平滑化され階段状になっている。生で使うと見越しマークが震える。こうして馴らせば数tickで落ち着き、
     * その後は動かない。
     */
    private static final float VELOCITY_SMOOTHING = 0.5F;
    /** 見越し計算の反復回数。飛翔時間は答えに依存するので、反復で収束させる。 */
    private static final int LEAD_PASSES = 4;

    /**
     * 見越し対象を新たに探すため空を掃引する間隔（tick）。
     *
     * <p>毎tickではない。この MOD の何一つ大きな箱をレベルへ毎tick問い合わせないのと同じ理由だ。この問いのコストは
     * 中身ではなく<em>箱の大きさ</em>で決まる。レベルは箱が覆うチャンク座標を歩くので、到達距離600ブロックは差し渡し
     * 1200の箱——毎秒20回、5500回のチャンク検索を、目標を0.25秒早く見つけるためだけに払うことになる。
     *
     * <p>これが抑制<em>しない</em>のは見越し計算そのものだ。既に見越している対象は毎tick、そのtickの到達位置で測り
     * 直すので、マークは従来通り正確に目標を追い、射撃の質も落ちない。動くのは「別の物に初めて気付く瞬間」だけで、
     * 最大0.25秒——しかも相手は、誰かが撃てるようになるまでそれよりずっと長く照準に保たれねばならない目標だ。
     */
    private static final int SWEEP_TICKS = 5;

    /**
     * 弾がどこから出てどちらへ出るかを、フレームごとに問い直す。
     *
     * <p>保存したベクトル2つではなく問い合わせ2つにしてあるのは、答えが動くからだ。マークは毎フレーム、兵装が<em>今</em>
     * 据えられている線から組み直されるので、機首や砲身の動きと同じなめらかさで追従し、1tick古いのは「どれだけ先にあるか」
     * だけになる。
     */
    public interface Bore {
        /** tick間の任意の瞬間における、弾の射出位置（ワールド座標）。 */
        Vec3 muzzle(float partialTick);

        /** 同じ瞬間の射出方向。単位ベクトル。 */
        Vec3 direction(float partialTick);
    }

    /**
     * このtickに照準を描くため計器が必要とする物。
     *
     * @param bore 弾の射出位置と方向。毎フレームマークを組み直すため
     * @param pipperRange ピッパーが砲腔線上のどこにあるか（ブロック）
     * @param pipperDrop その時点で弾がその線からどれだけ外れているか（ワールド）。銃では極小、燃焼後のロケットでは
     *                   持つ価値がある
     * @param struck ピッパーが開けた空ではなく、弾が当たる物の上にあるか
     * @param target 見越しを提示する対象。射程内に何も無ければ null
     * @param leadOffset 見越しマークの位置。目標中心からのオフセット。目標はtick間に動くので、オフセットは描画位置へ
     *                   加算する
     * @param targetRange 目標までの現在距離（ブロック）
     * @param inRange 弾が目標の到達予定位置まで届くか
     * @param onTarget 機首が見越し点に乗っているか。乗っていれば今撃てば当たる
     */
    public record Solution(Bore bore, double pipperRange, Vec3 pipperDrop, boolean struck,
            @Nullable Entity target, Vec3 leadOffset, double targetRange, boolean inRange, boolean onTarget) {
    }

    /** 弾の飛翔。銃口からのtickごとの位置と、何かにぶつかったならその対象。 */
    private record Flight(List<Vec3> samples, @Nullable Vec3 impact) {
        private Vec3 last() {
            return this.samples.get(this.samples.size() - 1);
        }
    }

    private static VehicleEntityBase cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static ResourceLocation cachedWeapon;
    @Nullable
    private static Solution cached;

    /** 最後に見越しを提示した対象と、その速度（平滑化済み）。 */
    @Nullable
    private static Entity held;
    private static Vec3 heldVelocity = Vec3.ZERO;
    /** 最後に空を掃引してからのtick数。{@link #SWEEP_TICKS} 参照。 */
    private static int sinceSweep;

    private GunSight() {
    }

    /** この兵装が照準器で狙う物か。機首方向に発射され、発射後に誘導されない物。 */
    public static boolean aims(WeaponDefinition weapon) {
        return weapon.type() == WeaponDefinition.Type.GUN || weapon.type() == WeaponDefinition.Type.ROCKET;
    }

    /**
     * パイロットが選択中の物の照準。この方式で狙う兵装でなければ null。
     *
     * <p>弾はそれを積むステーションの平均位置から出るので、ポッド2つならその中間から狙うことになる。そして機首方向へ
     * 出る。固定武装を機体が向けられるのはそれしか無いからだ。
     */
    @Nullable
    public static Solution solve(AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        ResourceLocation selected = weapons.selected();
        WeaponDefinition weapon = weapons.selectedWeapon();

        if (selected == null || weapon == null || !aims(weapon)) {
            forget();

            return null;
        }

        Vec3 offset = muzzleOffset(aircraft, selected);
        Bore bore = new Bore() {
            @Override
            public Vec3 muzzle(float partialTick) {
                return aircraft.toWorld(offset, partialTick);
            }

            @Override
            public Vec3 direction(float partialTick) {
                return aircraft.getAimDirection(partialTick);
            }
        };

        return solve(aircraft, selected, weapon, bore);
    }

    /**
     * 乗員が撃つ砲座向けの同じ処理。
     *
     * <p>ここでも2つの問いの答えは砲が持っている。弾は砲口から出て、砲が向いている方向へ出る——機体がどこを
     * 向いているかは関係が無い。だから戦車の砲塔とまったく同じ形になり、以下の弾道も見越しも同じ計算を通る。
     * {@link com.ashvehicles.weapon.GunStations} 参照。
     */
    @Nullable
    public static Solution solve(AircraftEntity aircraft, int station) {
        GunStations stations = aircraft.getStations();

        if (station < 0 || station >= stations.count()) {
            forget();

            return null;
        }

        ResourceLocation selected = stations.weaponOf(station);

        if (selected == null) {
            forget();

            return null;
        }

        WeaponDefinition weapon = Definitions.weapon(selected);

        if (!aims(weapon)) {
            forget();

            return null;
        }

        Bore bore = new Bore() {
            @Override
            public Vec3 muzzle(float partialTick) {
                return stations.muzzle(station, partialTick);
            }

            @Override
            public Vec3 direction(float partialTick) {
                return stations.direction(station, partialTick);
            }
        };

        return solve(aircraft, selected, weapon, bore);
    }

    /**
     * 砲塔内蔵の砲を撃つ車両向けの同じ処理。
     *
     * <p>ここでは2つの問いに、機体より単純な答えがある。弾は銃口から出る——車両が耳軸と砲身から既に算出している——し、
     * 砲腔方向へ出る。どちらにも車体は関与しない。
     */
    @Nullable
    public static Solution solve(GroundVehicleEntity vehicle) {
        ResourceLocation selected = vehicle.getStats().armament().main().orElse(null);

        if (selected == null || vehicle.isMissileMode()) {
            forget();

            return null;
        }

        WeaponDefinition weapon = Definitions.weapon(selected);

        if (!aims(weapon)) {
            forget();

            return null;
        }

        Bore bore = new Bore() {
            @Override
            public Vec3 muzzle(float partialTick) {
                return vehicle.getMuzzle(partialTick);
            }

            @Override
            public Vec3 direction(float partialTick) {
                return vehicle.getAimDirection(partialTick);
            }
        };

        return solve(vehicle, selected, weapon, bore);
    }

    /** どの機体から要求されても、毎tick 1回求めてその間は記憶する。 */
    @Nullable
    private static Solution solve(VehicleEntityBase vehicle, ResourceLocation selected,
            WeaponDefinition weapon, Bore bore) {
        long now = vehicle.level().getGameTime();

        if (vehicle != cachedFor || now != cachedAt || !selected.equals(cachedWeapon)) {
            if (vehicle != cachedFor) {
                forget();
            }

            cachedFor = vehicle;
            cachedAt = now;
            cachedWeapon = selected;
            cached = work(vehicle, weapon, bore);
        }

        return cached;
    }

    private static void forget() {
        cached = null;
        held = null;
        heldVelocity = Vec3.ZERO;
        sinceSweep = Integer.MAX_VALUE / 2;
    }

    @Nullable
    private static Solution work(VehicleEntityBase vehicle, WeaponDefinition weapon, Bore bore) {
        Vec3 nose = bore.direction(1.0F);

        // 狙う線が無い。同じ場合、砲架も同じ理由で発砲を拒否する。
        if (nose.lengthSqr() < 1.0E-6) {
            return null;
        }

        Vec3 muzzle = bore.muzzle(1.0F);
        // 砲架が実際に行う発射。砲腔方向への兵装自身の速度に、その方向へ機体が既に持っている速度を足した物だ。レール
        // が持ち出すのはそれだけだからである。WeaponMounts.fireRound 参照。停止中の車両は何も足さず、高速の車両は
        // 相応の分を足す。同じ規則を逆から読んだだけだ。
        Vec3 launch = nose.scale(weapon.projectile().speed() + Math.max(0.0, vehicle.getVelocity().dot(nose)));
        Flight flight = fly(vehicle, weapon.projectile(), muzzle, nose, launch);

        double flown = flight.last().distanceTo(muzzle);

        Entity target = leads(vehicle) ? chooseTarget(vehicle, nose, Math.min(TARGET_REACH, flown)) : null;
        Vec3 leadOffset = Vec3.ZERO;
        double targetRange = 0.0;
        boolean inRange = false;
        boolean onTarget = false;
        int targetTick = -1;

        if (target == null) {
            held = null;
        } else {
            Vec3 centre = centre(target);
            Vec3 speed = velocityOf(target);
            Vec3 predicted = centre;

            // 飛翔時間は目標の到達予定距離に依存し、その距離は飛翔時間に依存する。数回の反復で収束する。
            for (int pass = 0; pass < LEAD_PASSES; pass++) {
                targetTick = tickAt(flight, muzzle, predicted.distanceTo(muzzle));
                predicted = centre.add(speed.scale(targetTick));
            }

            // 弾は落下分だけ線より下に到達するので、線は目標の到達予定位置より同じだけ上へ置く。
            Vec3 drop = offLine(flight.samples().get(targetTick), muzzle, nose);
            Vec3 lead = predicted.subtract(drop);
            Vec3 line = lead.subtract(muzzle);
            double away = predicted.distanceTo(muzzle);
            // 遮る地面は「撃つな」の理由であって「目標を手放せ」の理由ではない。尾根を越えた瞬間のために見越しは
            // 依然として持つ価値がある。
            boolean blocked = flight.impact() != null && flight.impact().distanceTo(muzzle) < away;

            leadOffset = lead.subtract(centre);
            targetRange = centre.distanceTo(muzzle);
            inRange = reaches(flight, muzzle, away);
            onTarget = inRange && !blocked && line.lengthSqr() > 1.0E-6
                    && degreesBetween(line.normalize(), nose) <= ON_TARGET;
        }

        // ピッパー。目標があればその距離、無ければ基準距離、そのどちらより手前で弾が地面にぶつかるなら地面の上。
        Vec3 point = target != null
                ? flight.samples().get(targetTick)
                : flight.samples().get(tickAt(flight, muzzle, REFERENCE_RANGE));
        boolean struck = false;

        if (flight.impact() != null && flight.impact().distanceToSqr(muzzle) < point.distanceToSqr(muzzle)) {
            point = flight.impact();
            struck = true;
        }

        Vec3 offset = point.subtract(muzzle);
        double pipperRange = offset.dot(nose);

        return new Solution(bore, pipperRange, offset.subtract(nose.scale(pipperRange)), struck,
                target, leadOffset, targetRange, inRange, onTarget);
    }

    /**
     * 弾をtickごとに前進させる。{@code VehicleProjectile} が飛ばすのとまったく同じ順序で、モーターを持つ物はまず
     * モーター、次に移動、次に次tickのための落下を差し引く。
     *
     * <p>何かにぶつかった後も飛ばし続ける。見越しの基準になるのはこの飛翔であり、尾根の向こうの目標も尾根を越えた
     * 瞬間には目標だ——尾根に乗るのはピッパーであって、消えるのが見越しではない。最初に当たった物だけを記録し、それ以降
     * は世界へ問わない。弾が世界の答えられる範囲を超えた後も同様だ。
     */
    private static Flight fly(VehicleEntityBase vehicle, WeaponDefinition.Projectile round, Vec3 muzzle, Vec3 nose,
            Vec3 launch) {
        int flight = Math.min(MAX_FLIGHT, round.lifetime());
        List<Vec3> samples = new ArrayList<>(flight + 1);
        Vec3 position = muzzle;
        Vec3 velocity = launch;
        Vec3 impact = null;
        double topSpeed = round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE;
        boolean tracing = true;

        samples.add(position);

        for (int age = 1; age <= flight; age++) {
            // モーターは発射軸方向へ押すし、無誘導ロケットを回す物は無いので、速度の全てが機首方向へ戻される。前tick
            // に重力がやったことは方位ではなく速度の変化になる。推力下のロケットが真っ直ぐ飛ぶ理由だ。RocketEntity.steer
            // 参照。
            if (round.hasMotor() && age <= round.burnTicks()) {
                double speed = Math.min(velocity.length() + thrustAt(round, age), topSpeed);
                velocity = nose.scale(speed);
            }

            Vec3 next = position.add(velocity);

            if (tracing) {
                if (position.distanceToSqr(muzzle) > TRACE_REACH * TRACE_REACH) {
                    tracing = false;
                } else {
                    HitResult hit = vehicle.level().clip(new ClipContext(position, next,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, vehicle));

                    if (hit.getType() != HitResult.Type.MISS) {
                        impact = hit.getLocation();
                        tracing = false;
                    }
                }
            }

            position = next;
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
            samples.add(position);
        }

        return new Flight(samples, impact);
    }

    /** この齢でモーターが出している推力。ロケット自身と同様、スプールをかけて立ち上げる。 */
    private static float thrustAt(WeaponDefinition.Projectile round, int age) {
        int spool = round.spoolTicks();

        if (spool <= 0) {
            return round.thrust();
        }

        return round.thrust() * Mth.clamp((age + 1) / (float) spool, 0.0F, 1.0F);
    }

    /**
     * 弾が銃口から {@code distance} 離れる最初のtick。到達しなければ飛翔の最終tick。
     */
    private static int tickAt(Flight flight, Vec3 muzzle, double distance) {
        List<Vec3> samples = flight.samples();
        double wanted = distance * distance;

        for (int tick = 0; tick < samples.size(); tick++) {
            if (samples.get(tick).distanceToSqr(muzzle) >= wanted) {
                return tick;
            }
        }

        return samples.size() - 1;
    }

    /** 打ち切られる前に、弾が銃口から {@code distance} まで到達するか。 */
    private static boolean reaches(Flight flight, Vec3 muzzle, double distance) {
        return flight.last().distanceToSqr(muzzle) >= distance * distance;
    }

    /** 飛翔上のある点が、銃口からの機首方向の直線からどれだけ外れているか。 */
    private static Vec3 offLine(Vec3 sample, Vec3 muzzle, Vec3 nose) {
        Vec3 offset = sample.subtract(muzzle);

        return offset.subtract(nose.scale(offset.dot(nose)));
    }

    /**
     * 機体座標系での弾の射出位置。選択中の兵装を積み、かつ残弾のある全ステーションの平均。だからポッド2つならその中間
     * から狙う。残弾を問わず積んでいるステーション、次に機体中央へフォールバックする。
     */
    private static Vec3 muzzleOffset(AircraftEntity aircraft, ResourceLocation selected) {
        WeaponMounts weapons = aircraft.getWeapons();
        List<WeaponMounts.Mount> mounts = weapons.mounts();
        Vec3 loadedSum = Vec3.ZERO;
        Vec3 carriedSum = Vec3.ZERO;
        int loaded = 0;
        int carried = 0;

        for (int slot = 0; slot < mounts.size(); slot++) {
            List<WeaponMounts.Load> loads = mounts.get(slot).loads();

            for (int place = 0; place < loads.size(); place++) {
                WeaponMounts.Load load = loads.get(place);

                if (!selected.equals(load.weapon())) {
                    continue;
                }

                Vec3 at = weapons.placeOf(slot, place);
                carriedSum = carriedSum.add(at);
                carried++;

                if (load.ammo() > 0) {
                    loadedSum = loadedSum.add(at);
                    loaded++;
                }
            }
        }

        if (loaded > 0) {
            return loadedSum.scale(1.0 / loaded);
        }

        return carried > 0 ? carriedSum.scale(1.0 / carried) : Vec3.ZERO;
    }

    /**
     * この機体にそもそも見越しを提示するか。
     *
     * <p><b>地上ではレーダーを持つ物だけ。</b>見越しマークは射撃管制だ。1秒後に何がどこにいるかを告げる物であり、それを
     * 知れるのは装置が距離と変化率を測っているからであって、砲手が望遠鏡を覗いているからではない。対空砲架はその装置を
     * 持ち、それ無しでは役に立たない——機体との交戦は「行き先を撃つ」ことが全てだ——一方、戦車は装置も、それを使う相手も
     * 持たない。よってマークはアンテナに従う。{@code pantsir_s1} と {@code zumwalt} は見越しを持ち、どの戦車も見えている
     * 物へ砲を据えるだけでそれ以上はしない。
     *
     * <p>機体はファイルがレーダーについて何と言っていようと常に見越しを持つ。戦闘機のガンサイトも同じ装置で測距するが、
     * この MOD には機関砲を持ちながら装置を持たない機体は存在しないし、機関砲があって見越しの無いパイロットには何にも
     * 当てる手段が無い——ピッパーを代わりに合わせる物が空には何も無いからで、それこそまさに戦車が持っている物だ。
     *
     * <p>掃引の節約にもなる。見越し対象を探すとは、数tickごとに差し渡し1000ブロックの箱をレベルへ問い合わせることであり、
     * そのコストは中身ではなく箱の大きさで決まる——{@link #SWEEP_TICKS} 参照。戦車はもうそれを払わない。
     */
    private static boolean leads(VehicleEntityBase vehicle) {
        return !(vehicle instanceof GroundVehicleEntity) || vehicle.radar().fitted();
    }

    /**
     * 見越しを提示する対象。捜索円錐内かつ射程内でボアサイトに最も近い物——ただし前tickの対象を優先する。中央付近の
     * 2つの目標の間でマークが跳ばないようにするためだ。
     */
    @Nullable
    private static Entity chooseTarget(VehicleEntityBase vehicle, Vec3 nose, double reach) {
        if (reach <= 1.0) {
            return null;
        }

        Vec3 from = vehicle.position();

        // 既に見越している対象は、まだ存在し照準器が使える位置にある限り、レベルへ何も問わずに保持する。掃引を待つのは
        // 「別の物を探す」ことだけだ。SWEEP_TICKS 参照。
        if (held != null && ++sinceSweep < SWEEP_TICKS && stillWorthLeading(vehicle, from, nose, reach)) {
            return held;
        }

        sinceSweep = 0;

        AABB box = vehicle.getBoundingBox().inflate(reach);
        Entity best = null;
        double bestOff = SEARCH_CONE;
        double heldOff = Double.MAX_VALUE;

        for (Entity candidate : vehicle.level().getEntities(vehicle, box, entity -> couldTarget(vehicle, entity))) {
            Vec3 gap = centre(candidate).subtract(from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                continue;
            }

            double off = degreesBetween(gap.scale(1.0 / distance), nose);

            if (candidate == held) {
                heldOff = off;
            }

            if (off < bestOff) {
                bestOff = off;
                best = candidate;
            }
        }

        if (held != null && heldOff <= HOLD_CONE
                && (best == null || best == held || bestOff > heldOff - SWITCH_MARGIN)) {
            return held;
        }

        return best;
    }

    /**
     * 既に見越している目標が今も目標であり続けているか。まだ存在し、まだ照準器が受け入れる種類で、まだ射程内で、まだ
     * 保持用の円錐内にいるか。
     *
     * <p>全て既に手元にある1つのエンティティについての判定なので、コストはベクトル数個でレベルへは何も問わない——それが
     * この処理の要点だ。掃引を行わない5tick中4tickで走るのがこれだからである。
     */
    private static boolean stillWorthLeading(VehicleEntityBase vehicle, Vec3 from, Vec3 nose, double reach) {
        Entity target = held;

        if (target == null || !couldTarget(vehicle, target)) {
            return false;
        }

        Vec3 gap = centre(target).subtract(from);
        double distance = gap.length();

        if (distance > reach || distance < 1.0E-3) {
            return false;
        }

        return degreesBetween(gap.scale(1.0 / distance), nose) <= HOLD_CONE;
    }

    /**
     * 照準器が見越しを提示する対象。生きている物か、別の機体。見ている当の機体、その搭乗者、そして他の何かに乗っている
     * 者は除く——目標は乗員ではなく彼らが乗っている機体だ——し、MOD 自身の弾やデコイも除く。
     */
    private static boolean couldTarget(VehicleEntityBase vehicle, Entity candidate) {
        if (candidate == vehicle || candidate instanceof VehicleProjectile
                || candidate instanceof CountermeasureEntity || WeaponMounts.isPartOf(vehicle, candidate)) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        if (candidate.getVehicle() instanceof VehicleEntityBase) {
            return false;
        }

        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        // 標的ドローンは撃たれるために飛んでいる。シーカー（TargetLock）と同じ1行。
        if (candidate instanceof TargetDroneEntity) {
            return true;
        }

        return candidate instanceof LivingEntity;
    }

    /**
     * 目標の速度（ブロック/tick）。
     *
     * <p>この MOD の機体は自分で申告し、しかもどの側でも正直に申告する。それ以外は前tickにどれだけ動いて描かれたかから
     * 読むが、クライアント上でのそれは真実への平滑化された推測なので、マークが震えないようここでさらに少し平滑化する。
     */
    private static Vec3 velocityOf(Entity target) {
        Vec3 now = target instanceof VehicleEntityBase vehicle
                ? vehicle.getVelocity()
                : target.position().subtract(target.xOld, target.yOld, target.zOld);

        if (target == held) {
            now = heldVelocity.lerp(now, VELOCITY_SMOOTHING);
        }

        held = target;
        heldVelocity = now;

        return now;
    }

    private static Vec3 centre(Entity entity) {
        return entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }

    /** 2つの単位方向ベクトルの成す角（度）。 */
    private static double degreesBetween(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Mth.clamp(a.dot(b), -1.0, 1.0)));
    }
}
