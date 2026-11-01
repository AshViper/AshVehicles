package com.ashvehicles.ai.role;

import com.ashvehicles.ai.decision.ParameterSet;

/**
 * 役割ごとの戦い方の目盛り。どれも 0〜1。
 *
 * <p><b>判断を分岐させず、目盛りだけを変える。</b> 戦車も偵察車も同じ {@code RuleBasedPolicy} を通り、違うのは
 * 「どれだけの危険なら撃ち合うか」「どの体力で下がるか」の数字だけだ。こうしておけば、学習で変えてよい物
 * （この数字）と変えてはならない物（判断の骨組み・物理・当たり判定）の境界がそのままクラスの境界になる。
 *
 * <p>既定値は {@link #of} が役割ごとに持ち、版ファイルの {@code profile.<役割>.<名前>} で上書きできる
 * （{@link #tuned}）。
 *
 * @param aggression     危険を承知で撃ち合いに行く度合い
 * @param riskTolerance  経路と持ち場で受け入れる危険。低いほど遮蔽を探し、遠回りする
 * @param preferredRange 撃ち合う距離。自分の有効射程に対する割合
 * @param flankBias      側面へ回ることを選ぶ度合い
 * @param coverBias      遮蔽へ入ることを選ぶ度合い
 * @param holdBias       拠点に居座り、守る度合い
 * @param supportBias    撃たれている味方を助けに行く度合い
 * @param scoutBias      敵を探しに出る度合い
 * @param antiAirBias    空の相手を優先する度合い
 * @param retreatHealth  この体力（割合）を切ると下がることを考え始める
 * @param objectiveBias  撃ち合いより拠点を優先する度合い
 */
public record TacticalProfile(double aggression, double riskTolerance, double preferredRange, double flankBias,
        double coverBias, double holdBias, double supportBias, double scoutBias, double antiAirBias,
        double retreatHealth, double objectiveBias) {

    /**
     * その役割の既定。
     *
     * <p><b>役割で仕事を分ける</b>（2026-09-13 の指示）: 戦車は拠点を取りに行き、そこで率先して撃ち合う（攻撃性と危険の許容が
     * 高く、拠点の重みも最大。2026-09-14 の指示「戦車とIFVは拠点を取りに行くように」で 0.5 から上げた）、軽装甲（歩兵戦闘車・
     * 装甲兵員輸送車・偵察）は拠点を取る（拠点の重みが最大）、ロケットと榴弾砲は味方を
     * 支える（援護が高く、拠点も攻撃も低い）、防空は空を撃つ。空の相手を先に撃つかは役割ではなく兵装で決まる
     * （{@link Roles#defendsAir}）——主砲が機関砲の歩兵戦闘車も対空の度合いを高くしてある。
     */
    public static TacticalProfile of(VehicleRole role) {
        return switch (role) {
            case TANK -> new TacticalProfile(0.907642, 0.815309, 0.497954, 0.289467, 0.507950, 0.516845, 0.391226, 0.195743, 0.021418, 0.257228, 0.974193);
            case IFV -> new TacticalProfile(0.516428, 0.584188, 0.564972, 0.713363, 0.641940, 0.585146, 0.555418, 0.359667, 0.649848, 0.302153, 1.00);
            case APC -> new TacticalProfile(0.327526, 0.384315, 0.303157, 0.361527, 0.692795, 1.000000, 0.677966, 0.390026, 0.118305, 0.381595, 1.00);
            case SCOUT -> new TacticalProfile(0.353565, 0.438573, 0.617452, 0.633468, 0.659989, 0.593472, 0.296332, 1.000000, 0.089777, 0.466705, 0.997490);
            case AA -> new TacticalProfile(0.367579, 0.415232, 0.680776, 0.078927, 0.548938, 0.570112, 0.678202, 0.198661, 0.758582, 0.272988, 0.550609);
            case ARTILLERY -> new TacticalProfile(0.240539, 0.307826, 0.828740, 0.049377, 0.583531, 0.175366, 0.960592, 0.202782, 0.049471, 0.485976, 0.207066);
            case SUPPORT -> new TacticalProfile(0.449090, 0.439861, 0.554079, 0.305744, 0.607050, 0.438927, 0.727824, 0.292558, 0.241130, 0.446751, 0.698136);
        };
    }

    /** その役割の既定を、版のパラメータで上書きした物。 */
    public static TacticalProfile tuned(VehicleRole role, ParameterSet parameters) {
        TacticalProfile base = of(role);
        String prefix = "profile." + role.id() + ".";

        return new TacticalProfile(
                unit(parameters.get(prefix + "aggression", base.aggression)),
                unit(parameters.get(prefix + "risk_tolerance", base.riskTolerance)),
                unit(parameters.get(prefix + "preferred_range", base.preferredRange)),
                unit(parameters.get(prefix + "flank_bias", base.flankBias)),
                unit(parameters.get(prefix + "cover_bias", base.coverBias)),
                unit(parameters.get(prefix + "hold_bias", base.holdBias)),
                unit(parameters.get(prefix + "support_bias", base.supportBias)),
                unit(parameters.get(prefix + "scout_bias", base.scoutBias)),
                unit(parameters.get(prefix + "anti_air_bias", base.antiAirBias)),
                unit(parameters.get(prefix + "retreat_health", base.retreatHealth)),
                unit(parameters.get(prefix + "objective_bias", base.objectiveBias)));
    }

    private static double unit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
