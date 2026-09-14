package com.ashvehicles.ai.control;

import javax.annotation.Nullable;

import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;

import net.minecraft.world.phys.Vec3;

/**
 * 地上車両への出口。{@link DriveCommand} と {@link FireCommand} を {@link GroundVehicleInput} 1つに畳んで置く。
 *
 * <p><b>AI が車両を操る API を呼ぶのはこのクラスだけ。</b> {@code setInput}・{@code aimAt}・{@code selectWeapon}・
 * {@code rearm}——人の運転手がパケットで送ってくるのと同じ入口で、ここから下は人が運転しているときと1行も変わら
 * ない（[[bots-are-a-pilot-object-on-the-vehicle]]）。
 */
public final class GroundVehicleController implements VehicleController {
    private final GroundVehicleEntity vehicle;

    public GroundVehicleController(GroundVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    @Override
    public void apply(DriveCommand drive, FireCommand fire) {
        this.vehicle.setInput(new GroundVehicleInput(drive.throttle(), drive.steer(), drive.brake(), fire.main(),
                fire.coax(), fire.lock()));
    }

    @Override
    public void aim(@Nullable Vec3 point) {
        this.vehicle.aimAt(point);
    }

    @Override
    public void park() {
        this.vehicle.setInput(GroundVehicleInput.PARKED);
        this.vehicle.aimAt(null);
    }

    /** 引き金が向く兵装を選ぶ。積んでいない物は車両の側が断る。 */
    public void select(GroundVehicleEntity.Armament armament) {
        this.vehicle.selectWeapon(armament);
    }

    /**
     * 弾と燃料を満たす。<b>AI には弾薬箱と燃料缶を運んでくる者がいない</b>ので、撃つ相手がいない間だけ自分で
     * 満たす（{@code combat/Resupply}）。
     */
    public void rearm() {
        this.vehicle.rearm();
    }
}
