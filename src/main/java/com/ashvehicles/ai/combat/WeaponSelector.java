package com.ashvehicles.ai.combat;

import javax.annotation.Nullable;

import com.ashvehicles.ai.role.Roles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * どの兵装で撃つか。主砲・同軸機銃・ミサイルの3択（[[ground-vehicle-weapon-selection-is-three-way]]）。
 *
 * <p>元の {@code GroundPilot.choose} をそのまま移した。<b>積んでいない物は選ばない</b>——{@code selected()} が
 * どのみち落とす。
 */
public final class WeaponSelector {
    /** ここより近い歩兵には同軸機銃を向ける（ブロック）。主砲を人に向けるのは弾の無駄だ。 */
    public static final double COAX_RANGE = 70.0;

    /** 発射筒の誘導弾を向ける装甲の厚さ（装甲の値）。戦車・BMPT・TOS 以上。 */
    private static final float HEAVY_ARMOUR = 3.0F;

    /** 発射筒を向けてよい最短の距離（ブロック）。弾が向き直る前に着く距離では撃たない。 */
    private static final double MISSILE_CLOSE = 30.0;

    private WeaponSelector() {
    }

    /**
     * 飛んでいる物には空を追う誘導弾（無いか尽きていれば主砲の機関砲）、近くの歩兵と弾の尽きた砲には同軸機銃、それ
     * 以外は主砲。対戦車ミサイルとロケットは空へ向けない——空の相手を狙うのは空を撃てる車両だけ
     * （{@code role/Roles.defendsAir}）。
     */
    public static GroundVehicleEntity.Armament choose(GroundVehicleEntity vehicle, Entity quarry, double range) {
        boolean flying = quarry instanceof AircraftEntity aircraft && !aircraft.onGround();

        if (flying) {
            return Roles.airMissile(vehicle.getStats()) && vehicle.getMissiles() > 0
                    ? GroundVehicleEntity.Armament.MISSILE : GroundVehicleEntity.Armament.MAIN;
        }

        boolean soft = !(quarry instanceof VehicleEntityBase);
        // 主砲を積んでいない車両——装甲車と対空車両の一部——では、機銃が主兵装だ。射程で切ると、それらは
        // 一生引き金を引かない。
        boolean hasMain = vehicle.getStats().armament().main().isPresent();

        // 厚い装甲には発射筒の弾（ブラッドレーと BMPT の TOW）。機関砲は戦車の正面にほとんど入らない。以前は主砲の弾が
        // 尽きるまで発射筒を選ばず、主砲を持つ車両の TOW は飾りだった（2026-09-13）。弾が飛んでいる間も選んだままに
        // する——視線誘導の弾は、砲手が発射筒を選んで照準を覗いている間だけ線を伝う（{@code TurretLauncher.aimBeam}）。
        // 空を追う誘導弾（57E6）は地上へ向けない。
        if (hasMain && quarry instanceof VehicleEntityBase machine && machine.isArmoured()
                && machine.armour() >= HEAVY_ARMOUR && vehicle.getMissiles() > 0
                && !Roles.airMissile(vehicle.getStats()) && range >= MISSILE_CLOSE) {
            return GroundVehicleEntity.Armament.MISSILE;
        }

        if (vehicle.hasCoaxial()
                && (!hasMain || ((soft || vehicle.getRounds() <= 0) && range <= COAX_RANGE))) {
            return GroundVehicleEntity.Armament.COAX;
        }

        // 砲を積んでいない発射機車両——多連装ロケット——では、発射筒が主兵装。撃つ物が他に無い。
        if ((!hasMain || vehicle.getRounds() <= 0) && vehicle.hasMissiles()) {
            return GroundVehicleEntity.Armament.MISSILE;
        }

        return GroundVehicleEntity.Armament.MAIN;
    }

    /** その架台に載っている兵装。無ければ null。 */
    @Nullable
    public static ResourceLocation weaponOf(GroundVehicleEntity vehicle, GroundVehicleEntity.Armament choice) {
        return switch (choice) {
            case COAX -> vehicle.getStats().coaxial().gun().orElse(null);
            case MISSILE -> vehicle.getStats().launcher().missile().orElse(null);
            default -> vehicle.getStats().armament().main().orElse(null);
        };
    }
}
