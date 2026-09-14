package com.ashvehicles.ai.control;

/**
 * 1 tick 分の引き金。射撃を決める側（{@code combat/CombatController}）が決め、車両への出口
 * （{@link VehicleController}）が運転と一緒に入力へ載せる。
 *
 * <p>形は人の操作と同じ3つ（{@code GroundVehicleInput}）: 選択中の兵装、同軸機銃、シーカーの捕捉。
 *
 * @param main 選択中の兵装の引き金
 * @param coax 同軸機銃の専用の引き金
 * @param lock シーカーに新しい目標を取らせる
 */
public record FireCommand(boolean main, boolean coax, boolean lock) {
    /** 何も押さない。 */
    public static final FireCommand NONE = new FireCommand(false, false, false);

    /** どれかの弾が出る引き金を引いているか。捕捉だけの tick は数えない。 */
    public boolean shooting() {
        return this.main || this.coax;
    }
}
