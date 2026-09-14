package com.ashvehicles.ai.role;

import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.ai.AiConfig;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.weapon.GunClass;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;

/**
 * どの車両がどの役割か。
 *
 * <p><b>車両ファイルに「役割」の欄を足さない。</b> 役割は車両の事実——積んでいる砲の種類、装甲、速さ、レーダー
 * ——から読める物で、欄を足せば事実と食い違う書き方ができてしまう（主砲に榴弾砲を積んだ「偵察車」）。それに
 * {@code GroundVehicleDefinition} のトップレベルは既に DFU の16フィールド上限そのものだ
 * （[[team-deathmatch-shape]] の値段の項）。
 *
 * <p>読み方が外れる車両は設定の {@code roles.overrides} で名指しで直す。
 */
public final class Roles {
    private Roles() {
    }

    /** その車両の役割。設定の上書きが先。 */
    public static VehicleRole of(GroundVehicleEntity vehicle) {
        VehicleRole forced = AiConfig.get().roleOverrides().get(vehicle.getVehicleId());

        return forced != null ? forced : classify(vehicle.getStats());
    }

    /** その種類の車両の役割。設定の上書きを見る版。 */
    public static VehicleRole of(ResourceLocation id, GroundVehicleDefinition definition) {
        VehicleRole forced = AiConfig.get().roleOverrides().get(id);

        return forced != null ? forced : classify(definition);
    }

    /**
     * 車両ファイルの事実から役割を読む。上から順に最初に当てはまった物。
     *
     * <ol>
     * <li><b>防空</b>——レーダーを持つか、熱か電波のシーカーの誘導弾を積む（パーンツィリ）
     * <li><b>砲兵</b>——主砲が榴弾砲か、主砲を持たず無誘導のロケットだけを積む（PzH 2000・BM-21・TOS）
     * <li><b>戦車</b>——主砲が戦車砲。機関砲でも装甲と耐久が戦車並みなら戦車の仕事をする（BMPT）
     * <li><b>歩兵戦闘車</b>——主砲が機関砲（ブラッドレー・CV90）
     * <li><b>偵察／輸送</b>——機銃しか持たない。速くて軽ければ偵察、そうでなければ輸送（BTR-80）
     * <li><b>歩兵戦闘車</b>——誘導弾だけを持つ
     * <li>それ以外は<b>支援</b>
     * </ol>
     */
    public static VehicleRole classify(GroundVehicleDefinition definition) {
        WeaponDefinition main = weapon(definition.armament().main());
        WeaponDefinition missile = weapon(definition.launcher().missile());
        GunClass gun = main == null ? null : main.gunClass().orElse(null);
        boolean guided = missile != null && missile.isGuided();
        boolean airSeeker = guided && missile.guidance()
                .map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                        || guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT)
                .orElse(false);

        if (definition.radar().fitted() || airSeeker) {
            return VehicleRole.AA;
        }

        if (gun == GunClass.HOWITZER || (main == null && missile != null && !guided)) {
            return VehicleRole.ARTILLERY;
        }

        if (gun == GunClass.TANK_GUN) {
            return VehicleRole.TANK;
        }

        if (gun == GunClass.AUTOCANNON) {
            return definition.hull().armour() >= 4.5F && definition.hull().health() >= 450.0F
                    ? VehicleRole.TANK : VehicleRole.IFV;
        }

        if (gun == GunClass.MACHINE_GUN || (main == null && definition.coaxial().exists())) {
            return definition.powertrain().maxSpeed() >= 1.05F && definition.hull().health() <= 260.0F
                    ? VehicleRole.SCOUT : VehicleRole.APC;
        }

        if (guided) {
            return VehicleRole.IFV;
        }

        return VehicleRole.SUPPORT;
    }

    /**
     * 空の相手を撃つ手段を持つか——レーダー、空を追うシーカー（熱・電波）の誘導弾、または主砲が機関砲。
     *
     * <p>持つ車両は空の相手を先に撃ち（{@code combat/TargetSelector}、2026-09-13 の指示「ぱんつぃーりや機関砲をメインで
     * 持つ地上車両は対空戦闘を優先」）、持たない車両は空を狙わない——戦車砲や対戦車ミサイル、ロケットで飛行機は落ちない。
     * 役割ではなく兵装で決めるのは、機関砲の車両が重装甲なら戦車（BMPT）、軽装甲なら歩兵戦闘車に分かれるから。
     */
    public static boolean defendsAir(GroundVehicleDefinition definition) {
        WeaponDefinition main = weapon(definition.armament().main());

        return definition.radar().fitted() || airMissile(definition)
                || (main != null && main.gunClass().orElse(null) == GunClass.AUTOCANNON);
    }

    /** 発射筒の弾が、空を追うシーカー（熱・電波）を持つか。 */
    public static boolean airMissile(GroundVehicleDefinition definition) {
        WeaponDefinition missile = weapon(definition.launcher().missile());

        return missile != null && missile.guidance()
                .map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                        || guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT)
                .orElse(false);
    }

    @Nullable
    private static WeaponDefinition weapon(Optional<ResourceLocation> id) {
        return id.map(Definitions::weapon).orElse(null);
    }
}
