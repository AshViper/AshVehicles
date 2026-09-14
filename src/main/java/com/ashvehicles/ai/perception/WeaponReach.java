package com.ashvehicles.ai.perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.weapon.GunClass;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 1種類の車両が撃つ力の見積もり。射程・毎秒の威力・装甲への効き・空を撃てるか。
 *
 * <p><b>見積もりであって弾道ではない。</b> 実際に当たるかは撃つ直前に {@code combat/Ballistics} が弾を飛ばして
 * 解く。ここで答えるのは「あの戦車の射程の中に自分はいるか」「自分の砲はあれに効くか」という、判断のための
 * 目盛りだけで、数値は兵装ファイルから引く（[[ballistic-drop-is-drag-not-gravity]]）。
 *
 * <p>車両の種類ごとに1回だけ組む。定義ファイルが読み直されれば（{@code /reload}）定義のオブジェクトが替わるので、
 * 覚えている物と同一でなければ組み直す。
 *
 * @param mainRange     主砲の届く距離（ブロック）
 * @param coaxRange     同軸機銃の届く距離
 * @param missileRange  発射筒の届く距離。誘導弾ならシーカーの捕捉距離
 * @param mainDps       主砲の毎秒の威力（直撃の点数×発射速度×一斉射数）
 * @param coaxDps       同軸機銃の毎秒の威力
 * @param missileDps    発射筒の毎秒の威力
 * @param mainRicochet  主砲の弾が弾かれずに食い込める角（度）。装甲への効きの目安
 * @param mainExplosive 主砲の弾が炸薬で効く（距離でも角度でも効きが落ちにくい）
 * @param guidedMissile 発射筒の弾が誘導する
 * @param antiAir       空を撃つ手段を持つ（レーダーか、熱・電波のシーカーか、主砲が機関砲。{@code role/Roles.defendsAir}）
 * @param indirect      山なりに遠くを撃つ物（榴弾砲・多連装ロケット）
 */
public record WeaponReach(double mainRange, double coaxRange, double missileRange, double mainDps, double coaxDps,
        double missileDps, float mainRicochet, boolean mainExplosive, boolean guidedMissile, boolean antiAir,
        boolean indirect) {

    /** 何も撃てない物。 */
    public static final WeaponReach NONE = new WeaponReach(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0F, false, false, false,
            false);

    /**
     * 歩いている人。<b>車両にとってはほとんど脅威ではない</b>——この MOD の歩兵は MOD の兵装を持たないので、
     * 装甲車両を壊す手段はほぼ無い。それでも0にしないのは、軽い車両の乗員を撃ち落とせるからだ。
     */
    public static final WeaponReach INFANTRY = new WeaponReach(64.0, 0.0, 0.0, 3.0, 0.0, 0.0, 10.0F, false, false,
            false, false);

    /**
     * 飛んでいる機体。<b>積んでいる物を1つずつ見ない</b>——吊っている兵装は出撃ごとに違い、見に行けば1機ごとに
     * 搭載架を歩くことになる。地上を撃つ機体の平均的な力として見込む。
     */
    public static final WeaponReach AIRCRAFT = new WeaponReach(1200.0, 0.0, 2500.0, 40.0, 0.0, 60.0, 30.0F, true,
            true, true, false);

    /**
     * 射程の上限（ブロック）。<b>ファイルの射程は「見捨てるまでの距離」</b>で、戦車砲では4000ある。判断の目盛り
     * としては視界（{@code perception.sightRange}）とチャンクの届く範囲を大きく超える値に意味が無いので、ここで
     * 切る。
     */
    public static final double RANGE_CAP = 1500.0;

    /** 車両の種類ごとの答え。サーバースレッドだけが引く。 */
    private static final Map<ResourceLocation, Cached> CACHE = new HashMap<>();

    private record Cached(GroundVehicleDefinition definition, WeaponReach reach) {
    }

    /** その相手の撃つ力。 */
    public static WeaponReach of(Entity entity) {
        if (entity instanceof GroundVehicleEntity ground) {
            return of(ground.getVehicleId(), ground.getStats());
        }

        if (entity instanceof AircraftEntity) {
            return AIRCRAFT;
        }

        return entity instanceof Player ? INFANTRY : NONE;
    }

    /** その種類の車両の撃つ力。 */
    public static WeaponReach of(ResourceLocation id, GroundVehicleDefinition definition) {
        Cached cached = CACHE.get(id);

        if (cached != null && cached.definition() == definition) {
            return cached.reach();
        }

        WeaponReach reach = compute(definition);

        CACHE.put(id, new Cached(definition, reach));

        return reach;
    }

    /** 覚えている答えを捨てる。サーバーが止まったとき。 */
    public static void clear() {
        CACHE.clear();
    }

    /** 一番遠くまで届く兵装の距離。 */
    public double maxRange() {
        return Math.max(this.mainRange, Math.max(this.coaxRange, this.missileRange));
    }

    /**
     * その相手に届く距離。
     *
     * <p>空の相手には、空を撃つ手段を持つ物しか届かない。戦車砲で飛行機を撃つのは AI の仕事ではない
     * （[[bots-are-a-pilot-object-on-the-vehicle]] の目標選びと同じ規則）。
     */
    public double rangeAgainst(boolean flying) {
        if (!flying) {
            return this.maxRange();
        }

        if (this.antiAir) {
            return Math.max(this.missileRange, this.mainRange);
        }

        return this.guidedMissile ? this.missileRange : 0.0;
    }

    /**
     * 相手の装甲に対する効き（0.15〜1）。
     *
     * <p>運動弾は、弾が食い込める角（{@code ricochet}）から相手の装甲の値（{@code armour}、度で書かれている）を
     * 引いた余裕で見る。45度の余裕があれば普通に食い込み、無ければ多くが弾かれる
     * （{@code weapon/Ricochet}）。炸薬で効く弾と誘導弾は角度に左右されにくい。
     */
    public double effectiveness(boolean armoured, float armour) {
        if (!armoured) {
            return 1.0;
        }

        if (this.mainExplosive) {
            return 0.85;
        }

        return Math.max(0.15, Math.min(1.0, (this.mainRicochet - armour) / 45.0));
    }

    /** 相手に対する毎秒の威力。機銃は装甲にほとんど効かない。 */
    public double dpsAgainst(boolean armoured, float armour) {
        return this.mainDps * this.effectiveness(armoured, armour) + this.missileDps
                + this.coaxDps * (armoured ? 0.05 : 1.0);
    }

    private static WeaponReach compute(GroundVehicleDefinition definition) {
        WeaponDefinition main = weapon(definition.armament().main());
        WeaponDefinition coax = weapon(definition.coaxial().gun());
        WeaponDefinition missile = weapon(definition.launcher().missile());
        boolean guided = missile != null && missile.isGuided();
        boolean airSeeker = guided && missile.guidance()
                .map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                        || guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT)
                .orElse(false);
        boolean howitzer = main != null && main.gunClass().orElse(null) == GunClass.HOWITZER;
        double missileRange = missile == null ? 0.0
                : guided ? Math.min(missile.guidance().get().lockRange(), RANGE_CAP) : range(missile);

        return new WeaponReach(
                main == null ? 0.0 : range(main),
                coax == null ? 0.0 : range(coax),
                missileRange,
                dps(main), dps(coax), dps(missile),
                main == null ? 0.0F : main.projectile().ricochet(),
                main != null && main.projectile().explosion() >= 1.5F,
                guided,
                definition.radar().fitted() || airSeeker
                        || (main != null && main.gunClass().orElse(null) == GunClass.AUTOCANNON),
                howitzer || (main == null && missile != null && !guided));
    }

    @Nullable
    private static WeaponDefinition weapon(Optional<ResourceLocation> id) {
        return id.map(Definitions::weapon).orElse(null);
    }

    /**
     * 届く距離。ファイルの射程と、抗力を無視した最大射程（{@code 初速² / 重力}）の短い方。
     *
     * <p>後者を掛けるのは榴弾砲のため——155mm は射程 3000 と書かれているが、初速 6 ブロック/tick では 45 度でも
     * 1500 ブロックに届かない（[[howitzer-range-is-set-by-the-shell-lifetime]]）。
     */
    private static double range(WeaponDefinition weapon) {
        WeaponDefinition.Projectile round = weapon.projectile();
        double range = round.range() <= 0.0F ? RANGE_CAP : round.range();

        if (!round.hasMotor() && round.gravity() > 0.0F) {
            range = Math.min(range, (double) round.speed() * round.speed() / round.gravity());
        }

        return Math.min(range, RANGE_CAP);
    }

    private static double dps(@Nullable WeaponDefinition weapon) {
        if (weapon == null) {
            return 0.0;
        }

        return weapon.projectile().damage() * weapon.firing().roundsPerSecond()
                * Math.max(weapon.firing().salvo(), 1);
    }
}
