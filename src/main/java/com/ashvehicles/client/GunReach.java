package com.ashvehicles.client;

import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.weapon.GunClass;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * この据えで、弾はどこまで飛ぶのか。
 *
 * <p><b>なぜ {@link GunSight} では足りないのか。</b>あちらは直射砲の照準器で、世界へ問い合わせるのは 512
 * ブロックまでだ——機関砲弾も戦車砲弾もその内側で当たる物に当たるので、それより先を問うのは「何も無い」と
 * 告げられるために金を払うことになる。榴弾砲は違う。155mm は仰角 45 度で 1388 ブロック先へ落ち、照準器の
 * 到達距離のほぼ 3 倍だ。そこに答えを持たない照準器は基準距離 300 ブロックにマークを置くので、砲手が見て
 * いる印は着弾点ではなくなる。<b>山なりに撃つ砲のマークは、必ずこちらが置く</b>——{@link #lobs} 参照。
 *
 * <p><b>だから遠くの地面については、見えない物についても進んで述べる。</b>{@link Terrain} と同じ 3 段の梯子だ。
 * chunk が手元にある間はブロック自体に問い、本物の答えを得る。無くなったら {@link SeenTerrain} が覚えている高さ
 * ——一度は本当に見た地面——に問う。それも無ければ、走査が最後に知っていた床を持ち越す。後ろ 2 つは推測なので
 * {@code estimated} を立てて返し、計器はチルダを付けて出す。
 *
 * <p><b>推測した地面までしか言えないのは、弾も同じだからだ。</b> サーバーの弾はロード済みの chunk のブロックに
 * しか当たらない（{@code VehicleProjectile.groundUnder}）。だから計器が「見えている地面」と言い切れる距離の外は、
 * 弾にとっても当たる物の無い距離である。チルダは推測の印であると同時に「そこはまだ誰も開けていない」の印でもある。
 *
 * <p><b>落ちない弾もある。</b>弾は {@code range} まで飛ぶと見捨てられる（{@code WeaponDefinition.Projectile
 * .lifetime()}）ので、寿命が弧より短ければ弾は降りて来る前に消える。榴弾砲の {@code range} はそうならない値に
 * してあるが、判定は残す——真上へ撃った砲と、{@code range} を切り詰めた兵装ファイルがそこを通る。その場合 {@code lands}
 * は false で、{@code range} は消える地点までの距離になる。計器は {@code >} を付けて出すので、「そこまで飛んで、
 * それきり」と読める。
 *
 * <p>返す位置は砲腔線上の距離と線からの外れに分けてある。{@link GunSight.Solution} と同じ理由で、そうすれば計器
 * はマークを毎フレーム今の砲身から組み直せる——1tick 古いのは「どれだけ先か」だけになり、マークはハンドルを回す
 * 速さでなめらかに動く。
 */
public final class GunReach {
    /**
     * 追う価値のある最長飛翔（tick）。
     *
     * <p>35 秒。榴弾が最も長く飛ぶのは仰角の上端で、155mm は 72 度で 460 tick。谷底へ撃ち下ろす分の余裕を
     * 見てもここには届かない。触れるのは {@code range} を書いていない弾——寿命 5 分の「無制限」——だけで、
     * あれを最後まで追えば毎tick 6000 回の積分になる。
     */
    private static final int MAX_FLIGHT = 700;

    /**
     * 弾の行き先。
     *
     * @param alongBore 砲腔線上のどこか（ブロック）。マークを組み直すため
     * @param drop その地点で弾が砲腔線からどれだけ外れているか（ワールド）。榴弾では砲腔線より遥か下になる
     * @param range 砲から着弾点までの<b>水平</b>距離（ブロック）。砲手が読む「射距離」であり、砲兵が言う距離は
     *              昔からこちらだ。斜距離では、谷底へ撃つのと平地へ撃つのとで同じ地図上の距離が別の数字になる
     * @param ticks 発砲から着弾までの tick 数
     * @param lands 弾が地面に届くか。false なら寿命が先に尽きる——{@code range} はそこまでの距離
     * @param estimated 着弾点が、クライアントに実際に見えるブロックではなく覚えている高さ／持ち越した床の上か
     */
    public record Shot(double alongBore, Vec3 drop, double range, int ticks, boolean lands,
            boolean estimated) {
        /**
         * 計器とワールド上のマークが共に出す 1 行。
         *
         * <p>接頭辞が、その数字が何であるかを言う。何も付かなければ見えている地面までの距離、{@code ~} なら
         * 覚えている／持ち越した地面までの推測、{@code >} なら地面に届かず、そこまで飛んで消える距離。
         */
        public String label() {
            String prefix = !this.lands ? ">" : this.estimated ? "~" : "";

            return prefix + Math.round(this.range) + " m";
        }

        /** 発砲から着弾までの秒数。届かない弾では意味を持たない。 */
        public float seconds() {
            return this.ticks / 20.0F;
        }
    }

    @Nullable
    private static GroundVehicleEntity cachedFor;
    private static long cachedAt = Long.MIN_VALUE;
    @Nullable
    private static ResourceLocation cachedAmmunition;
    @Nullable
    private static Shot cached;

    private GunReach() {
    }

    /**
     * この砲は山なりに撃つか。
     *
     * <p><b>これが「照準器ではなくこちらが答える」の判定である。</b> 弾が砲腔線からどれだけ外れて落ちるかで
     * 決めているのではなく、{@code gun_class} が言っている——榴弾砲は当てるのではなく面を制圧するために
     * 山なりに撃つ砲だと、定義ファイルが既に宣言している（{@link GunClass#HOWITZER}）。戦車砲を同じ経路へ
     * 通してはいけない。あちらのピッパーは、開けた空では基準距離 300 ブロックに立つのが正しい——直射砲の
     * 照準規正はそういう物で、2km 先の推測した地面へ飛ばすのは照準の改善ではない。
     */
    public static boolean lobs(GroundVehicleEntity gun) {
        ResourceLocation selected = gun.getStats().armament().main().orElse(null);

        return selected != null && !gun.isMissileMode() && !gun.isCoaxMode()
                && Definitions.weapon(selected).gunClass().orElse(null) == GunClass.HOWITZER;
    }

    /**
     * 今の砲の据えで解いた1発。撃つ物が無ければ null。
     *
     * <p>毎tick 1回だけ解いて、その間は記憶する。弾道は砲身の向きに依存し、向きは tick ごとにしか動かない
     * ——同じ tick の中で何フレーム描いても答えは同じだ。弾種は鍵に含める。榴弾と成形炸薬弾では初速も抗力も
     * 違い、つまり射距離が違う。
     */
    @Nullable
    public static Shot solve(GroundVehicleEntity gun) {
        ResourceLocation selected = gun.getStats().armament().main().orElse(null);

        // 解くのは主砲1門だけ。ミサイルは狙う物ではなく手渡される物なので、選択がそちらにあるなら答える弾道は
        // 無い——弾種もミサイルの物になっている。同軸機銃も同じで、あれは自分の銃口と自分の弾道を持つ。
        if (selected == null || gun.isMissileMode() || gun.isCoaxMode()) {
            return null;
        }

        WeaponDefinition weapon = Definitions.weapon(selected);

        if (!GunSight.aims(weapon)) {
            return null;
        }

        long now = gun.level().getGameTime();
        ResourceLocation ammunition = gun.getSelectedAmmunition();

        if (gun != cachedFor || now != cachedAt || !Objects.equals(ammunition, cachedAmmunition)) {
            cachedFor = gun;
            cachedAt = now;
            cachedAmmunition = ammunition;
            cached = work(gun, Definitions.round(weapon, ammunition));
        }

        return cached;
    }

    /**
     * 弾を tick ごとに前進させ、地面に出会うまで——出会わなければ寿命が尽きるまで——飛ばす。
     *
     * <p>前進のさせ方は {@code GunSight.fly} と同じでなければならない。モーターがあればまずモーター、次に移動、
     * 次に次tickのための抗力と落下。抗力の式そのものは
     * {@code WeaponDefinition.Projectile#slowedByAir} の1箇所にしか無いので、ここが写し取っているのは順序だけだ。
     * 違うのは世界への問い方だけである。
     */
    @Nullable
    private static Shot work(GroundVehicleEntity gun, WeaponDefinition.Projectile round) {
        Vec3 nose = gun.getAimDirection(1.0F);

        // 狙う線が無い。同じ場合、砲架も同じ理由で発砲を拒否する。
        if (nose.lengthSqr() < 1.0E-6) {
            return null;
        }

        Level level = gun.level();
        Vec3 muzzle = gun.getMuzzle(1.0F);
        Vec3 position = muzzle;
        Vec3 velocity = nose.scale(round.speed());
        double topSpeed = round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE;
        // 実地形の列を1つでも読むまでは海面高。砲が立っている列は最初のステップで読まれる。
        double floor = level.getSeaLevel();
        int flight = Math.min(MAX_FLIGHT, round.lifetime());

        for (int age = 1; age <= flight; age++) {
            if (round.hasMotor() && age <= round.burnTicks()) {
                velocity = nose.scale(Math.min(velocity.length() + thrustAt(round, age), topSpeed));
            }

            Vec3 next = position.add(velocity);
            double ground = Terrain.surface(level, next);

            if (!Double.isNaN(ground)) {
                // 問い合わせる chunk がある。ブロック自体に訊けば、斜面も屋根も含めて本物の答えが返る。
                HitResult hit = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.ANY, gun));

                if (hit.getType() != HitResult.Type.MISS) {
                    return shot(muzzle, nose, hit.getLocation(), age, true, false);
                }

                floor = ground;
            } else {
                // 手元に無い。一度でも見た地面は覚えているので、それがあれば使う。無ければ最後に知っていた床。
                double remembered = SeenTerrain.height(next.x, next.z);

                if (!Double.isNaN(remembered)) {
                    floor = remembered;
                }

                if (next.y <= floor) {
                    return shot(muzzle, nose, Terrain.crossing(position, next, floor), age, true, true);
                }
            }

            position = next;
            velocity = round.hasMotor() ? velocity : round.slowedByAir(velocity);
            velocity = velocity.subtract(0.0, round.gravity(), 0.0);
        }

        // 地面に出会わないまま寿命が尽きた。弾はここで消える。地面を1つも使っていないので推測ではない。
        return shot(muzzle, nose, position, flight, false, false);
    }

    /** この齢でモーターが出している推力。{@code GunSight.thrustAt} と同じ立ち上げ。 */
    private static float thrustAt(WeaponDefinition.Projectile round, int age) {
        int spool = round.spoolTicks();

        if (spool <= 0) {
            return round.thrust();
        }

        return round.thrust() * Mth.clamp((age + 1) / (float) spool, 0.0F, 1.0F);
    }

    /** 求めた着弾点を、砲腔線上の距離と線からの外れに分ける。 */
    private static Shot shot(Vec3 muzzle, Vec3 nose, Vec3 point, int ticks, boolean lands,
            boolean estimated) {
        Vec3 offset = point.subtract(muzzle);
        double alongBore = offset.dot(nose);
        double range = Math.sqrt(offset.x * offset.x + offset.z * offset.z);

        return new Shot(alongBore, offset.subtract(nose.scale(alongBore)), range, ticks, lands, estimated);
    }
}
