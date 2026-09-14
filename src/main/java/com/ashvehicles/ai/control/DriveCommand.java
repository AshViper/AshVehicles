package com.ashvehicles.ai.control;

import net.minecraft.util.Mth;

/**
 * 1 tick 分の運転。経路を追う側（{@code navigation/Navigator}）が決め、車両への出口（{@link VehicleController}）
 * が入力に載せる。
 *
 * <p>引き金を持たない。撃つかどうかは移動と無関係に決まる（{@link FireCommand}）——砲塔は車体と別に回るので、
 * 「走る」と「撃つ」を1つの判断にまとめる理由が無い。
 *
 * @param throttle 前後進。正で前、負で後ろ、[-1, 1]
 * @param steer    右が正、[-1, 1]
 * @param brake    止まって保持する
 */
public record DriveCommand(float throttle, float steer, boolean brake) {
    /** 止まって保持する。 */
    public static final DriveCommand PARKED = new DriveCommand(0.0F, 0.0F, true);

    public DriveCommand {
        throttle = Mth.clamp(throttle, -1.0F, 1.0F);
        steer = Mth.clamp(steer, -1.0F, 1.0F);
    }
}
