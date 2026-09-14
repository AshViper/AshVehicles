package com.ashvehicles.ai.combat;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.ai.control.FireCommand;
import com.ashvehicles.ai.control.GroundVehicleController;
import com.ashvehicles.ai.core.AiBudget;
import com.ashvehicles.ai.perception.AllyObservation;
import com.ashvehicles.ai.perception.LineOfSight;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 砲を据え、引き金を引くかを決める。毎 tick。
 *
 * <p>元の {@code GroundPilot.lay} をそのまま移した。<b>撃つ前に弾道を解き</b>（{@link Ballistics}）、引き金は
 * <b>4つ揃って</b>初めて引く——砲が据わっている（前 tick の動きが {@value #SETTLED} 度以下）、角が
 * {@value #ALIGNED} 度以内、線の上に味方がいない、相手が本当に見えている。理由は
 * [[bots-are-a-pilot-object-on-the-vehicle]] の射撃の項。
 *
 * <p>元と変えたのは2つ。最後の視線は1 tick の予算（{@link AiBudget.Kind#RAY}）の内で引き、同じ相手には
 * {@value #CLEAR_TICKS} tick 覚える——据わって角が合っている tick は続くので、毎 tick 引き直す理由が無い。
 * そして据わったかは車両に訊かず自分で測る（{@link #settled}）。
 *
 * <p><b>角を測る起点は砲口、砲を据える起点は砲尾</b>（{@code GroundVehicleEntity.getBreech}）。どちらも同じ砲身の
 * 線の上にあるので、据え終われば砲口から狙い点がまっすぐ見える。車両の原点から据えていた間は、砲が耳軸の
 * 高さぶん平行にずれた線を向き、この門を一度も通らなかった（2026-09-13）。
 */
public final class FireControl {
    /**
     * 砲がこの角度まで合っていれば引き金を引く（度）。0.5度は 200 ブロック先で1.7ブロック——戦車1両の幅の半分。
     */
    private static final float ALIGNED = 0.5F;

    /** 空の相手に撃ってよい角（度）。追っている砲は据わらないので、見越しの誤差を散布と弾数で埋める。 */
    private static final float AIR_ALIGNED = 1.5F;

    /** 砲で空の相手を撃ってよい距離（ブロック）。誘導弾はシーカーが届く距離を決めるので掛けない。 */
    static final double AIR_GUN_REACH = 800.0;

    /**
     * 砲が据わったと見なす、前 tick の動き（度）。<b>割合ではなく角で測る。</b> 据える命令は1 tick 前の角度に
     * 対して出ているので、その後に砲が大きく動く tick に撃てば、弾はその分ずれる。
     */
    private static final float SETTLED = 0.35F;

    private static final double SETTLED_COS = Math.cos(Math.toRadians(SETTLED));

    /** 炸薬を持つ弾を撃ってよい最短距離。爆風半径の何倍か。 */
    private static final double SELF_BLAST = 2.5;

    /** 最後の視線を覚える長さ（tick）。 */
    private static final int CLEAR_TICKS = 5;

    /** 予算切れのとき、この齢までの視線なら使う（tick）。 */
    private static final int CLEAR_STALE = 20;

    private final GroundVehicleEntity ground;
    private final GroundVehicleController control;

    private int clearTick = Integer.MIN_VALUE;
    private int clearTarget = -1;
    private boolean clearResult;

    /** 1 tick 前に見た砲身の指向と、見た tick。 */
    @Nullable
    private Vec3 lastBore;
    private int lastBoreTick = Integer.MIN_VALUE;

    public FireControl(GroundVehicleEntity ground, GroundVehicleController control) {
        this.ground = ground;
        this.control = control;
    }

    /**
     * 砲を据え、この tick の引き金を返す。
     *
     * @param quarry 撃つ相手。null なら砲から手を離す
     * @param range  相手までの距離
     * @param allies 線の上にいてはいけない味方
     * @param now    サーバーの tick（予算と覚えのため）
     */
    public FireCommand lay(@Nullable Entity quarry, double range, GroundVehicleEntity.Armament choice,
            List<AllyObservation> allies, int now) {
        // 目標の無い tick にも測る。測っておかないと、目標が現れた最初の tick に比べる物が無い。
        boolean settled = this.settled(now);

        if (quarry == null) {
            this.control.aim(null);

            return FireCommand.NONE;
        }

        this.control.select(choice);

        ResourceLocation weaponId = WeaponSelector.weaponOf(this.ground, choice);
        // 弾が生まれるのは砲口だ。車体の中心から解いた弾道は、砲身1本ぶん短い距離の答えになる。
        Vec3 muzzle = this.ground.getMuzzle(1.0F);
        Vec3 aim = aimPoint(quarry, muzzle, weaponId);

        if (aim == null) {
            // 届かない。砲は相手の方へ向けたまま待つ——狙い続けている砲は、相手が寄れば即座に撃てる。
            this.control.aim(quarry.getBoundingBox().getCenter());

            return FireCommand.NONE;
        }

        this.control.aim(aim);

        // シーカーは押している間だけ新しい目標を取る（{@code TargetLock.tick}）。誘導弾を選んでいて、
        // まだ何も掴んでいない間だけ押す。無誘導ロケットには掴む物が無い。
        boolean lock = choice == GroundVehicleEntity.Armament.MISSILE && !this.ground.isSeekerLocked()
                && weaponId != null && Definitions.weapon(weaponId).isGuided() && !laid(weaponId);

        // <b>据え終わるまで撃たない。</b> ただし空の相手は別——動く機体を追う砲塔は毎 tick 回り続けるので、据わるのを
        // 待てば1発も撃てない。角の許しも {@value #AIR_ALIGNED} 度に広げる。
        boolean flying = quarry instanceof AircraftEntity aircraft && !aircraft.onGround();

        if (!(settled || flying) || !this.onTarget(muzzle, aim, flying ? AIR_ALIGNED : ALIGNED)) {
            return new FireCommand(false, false, lock);
        }

        // 砲で空を撃つのは近い相手だけ。遠い機体へ撒いた弾は散布で1発も当たらず、弾倉だけが空になる。
        if (flying && choice != GroundVehicleEntity.Armament.MISSILE && range > AIR_GUN_REACH) {
            return new FireCommand(false, false, lock);
        }

        if (!this.clear(muzzle, aim, quarry, weaponId, range, allies, now)) {
            return new FireCommand(false, false, lock);
        }

        return switch (choice) {
            // 発射筒は「装填済みで、誘導弾ならロックしている」。<b>無誘導ロケットにロックを求めてはいけない</b>
            // ——BM-21 や TOS の発射機はシーカーを持たないので、求めれば一発も出ない。
            // 線で導く弾（TOW の視線誘導）はシーカーで掴まない——砲塔が相手を向いていること自体が誘導になる
            // （{@code TurretLauncher.aimBeam}）。ロックを求めれば一発も出ない。
            case MISSILE -> new FireCommand(this.ground.getMissiles() > 0
                    && this.ground.getMissileReload() <= 0
                    && (weaponId == null || !Definitions.weapon(weaponId).isGuided() || laid(weaponId)
                            || this.ground.isSeekerLocked()), false, lock);
            case COAX -> new FireCommand(false, this.ground.isCoaxLoaded(), lock);
            default -> new FireCommand(this.ground.isLoaded() && this.ground.getRounds() > 0, false, lock);
        };
    }

    /**
     * 砲を置く1点。相手の動きと弾の落ちを織り込んだ、今撃てば当たる場所。届かない相手には null。
     *
     * <p>誘導弾には見越しも落ちも足さない。あれは撃った後に自分で曲がる物であり、置いた点へ撃つと初期
     * 角度がずれるだけになる。
     */
    @Nullable
    private static Vec3 aimPoint(Entity quarry, Vec3 muzzle, @Nullable ResourceLocation weaponId) {
        Vec3 at = quarry.getBoundingBox().getCenter();

        if (weaponId == null) {
            return at;
        }

        WeaponDefinition weapon = Definitions.weapon(weaponId);

        if (weapon.isGuided()) {
            return at;
        }

        Vec3 drift = quarry instanceof VehicleEntityBase machine ? machine.getVelocity() : quarry.getDeltaMovement();

        return Ballistics.aim(muzzle, at, drift, weapon.projectile());
    }

    /**
     * 撃ってよい線か。味方が線の上にいないこと、相手が本当に見えていること、炸薬持ちの弾を自分の足元で
     * 炸裂させないこと。
     */
    private boolean clear(Vec3 muzzle, Vec3 aim, Entity quarry, @Nullable ResourceLocation weaponId, double range,
            List<AllyObservation> allies, int now) {
        if (weaponId != null) {
            float blast = Definitions.weapon(weaponId).projectile().explosion();

            // 榴弾の爆風は撃った本人にも届く。味方撃ちの門は自分自身には掛からない。
            if (blast > 0.0F && range < blast * SELF_BLAST) {
                return false;
            }
        }

        double reach = muzzle.distanceTo(aim);

        for (AllyObservation ally : allies) {
            Entity mate = ally.entity();

            if (mate == this.ground || mate == quarry || mate.isRemoved()) {
                continue;
            }

            Vec3 at = mate.getBoundingBox().getCenter();

            // 相手より手前にいる味方だけが邪魔になる。後ろにいる味方の向こうへ撃つのは自由だ。
            if (at.distanceTo(muzzle) < reach && offLine(muzzle, aim, at) < mate.getBbWidth()) {
                return false;
            }
        }

        return this.sees(muzzle, quarry, now);
    }

    /** 砲口から相手が見えているか。同じ相手には数 tick 覚え、引く本数は予算の内。 */
    private boolean sees(Vec3 muzzle, Entity quarry, int now) {
        if (quarry.getId() == this.clearTarget && now - this.clearTick <= CLEAR_TICKS) {
            return this.clearResult;
        }

        MinecraftServer server = this.ground.getServer();

        if (server == null || !AiBudget.spend(server, AiBudget.Kind.RAY)) {
            return quarry.getId() == this.clearTarget && now - this.clearTick <= CLEAR_STALE && this.clearResult;
        }

        LineOfSight.Result result = LineOfSight.trace(this.ground.level(), muzzle,
                quarry.getBoundingBox().getCenter(), this.ground);

        this.clearTarget = quarry.getId();
        this.clearTick = now;
        this.clearResult = result.clear();

        return this.clearResult;
    }

    /** その点が、砲口から狙い点へ引いた線からどれだけ離れているか。線の外側にあれば無限遠。 */
    private static double offLine(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 line = to.subtract(from);
        double length = line.lengthSqr();

        if (length < 1.0E-6) {
            return Double.MAX_VALUE;
        }

        double along = point.subtract(from).dot(line) / length;

        if (along <= 0.0 || along >= 1.0) {
            return Double.MAX_VALUE;
        }

        return point.distanceTo(from.add(line.scale(along)));
    }

    /**
     * 砲身が前の tick からほとんど動いていないか（{@value #SETTLED} 度以下）。
     *
     * <p><b>車両が持つ前 tick の角（{@code turretYawO}）とは比べられない。</b> あれは AI が考えるより前
     * （{@code GroundVehicleEntity.tick} の冒頭）に今の角で上書きされ、砲塔を据えるのは AI の後なので、AI が
     * 読む瞬間の差は常に0——それを読んでいた間は、回っている最中の砲でも据わったと答えていた。だから1 tick 前に
     * 自分で見た指向と比べる。
     *
     * <p>比べるのはワールドでの向き。車体が曲がって砲塔が巻き戻している間は向きが変わらないので据わっていると
     * 数え、凸凹で車体ごと揺れている間は据わっていないと数える——弾がずれるのは後者だけだ。
     */
    private boolean settled(int now) {
        Vec3 bore = this.ground.getAimDirection(1.0F);
        // 印（MIN_VALUE）は差を取る前に見る。桁あふれした差を「1 tick 前」と読まないように。
        boolean still = this.lastBore != null && this.lastBoreTick != Integer.MIN_VALUE
                && now - this.lastBoreTick == 1 && bore.dot(this.lastBore) >= SETTLED_COS;

        this.lastBore = bore;
        this.lastBoreTick = now;

        return still;
    }

    /** 線か点で導く弾か。シーカーを持たず、ロックを取らない（{@code WeaponDefinition.Guidance.Seeker.laid}）。 */
    private static boolean laid(ResourceLocation weaponId) {
        return Definitions.weapon(weaponId).guidance().map(guidance -> guidance.seeker().laid()).orElse(false);
    }

    /** 砲が狙い点に {@code tolerance} 度以内で乗っているか。 */
    private boolean onTarget(Vec3 muzzle, Vec3 aim, float tolerance) {
        Vec3 want = aim.subtract(muzzle);

        if (want.lengthSqr() < 1.0E-6) {
            return false;
        }

        return this.ground.getAimDirection(1.0F).dot(want.normalize()) > Math.cos(Math.toRadians(tolerance));
    }
}
