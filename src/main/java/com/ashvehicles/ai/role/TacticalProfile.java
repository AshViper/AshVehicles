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
            case TANK -> new TacticalProfile(0.90, 0.80, 0.50, 0.25, 0.50, 0.45, 0.50, 0.20, 0.00, 0.25, 1.00);
            case IFV -> new TacticalProfile(0.45, 0.50, 0.45, 0.80, 0.60, 0.60, 0.60, 0.35, 0.80, 0.35, 1.00);
            case APC -> new TacticalProfile(0.30, 0.40, 0.40, 0.40, 0.70, 0.80, 0.60, 0.30, 0.10, 0.40, 1.00);
            case SCOUT -> new TacticalProfile(0.30, 0.35, 0.60, 0.60, 0.70, 0.50, 0.30, 0.90, 0.10, 0.45, 1.00);
            case AA -> new TacticalProfile(0.35, 0.40, 0.80, 0.10, 0.60, 0.60, 0.80, 0.20, 1.00, 0.40, 0.50);
            case ARTILLERY -> new TacticalProfile(0.25, 0.30, 0.90, 0.05, 0.70, 0.20, 0.90, 0.20, 0.00, 0.50, 0.20);
            case SUPPORT -> new TacticalProfile(0.40, 0.45, 0.50, 0.30, 0.60, 0.50, 0.90, 0.30, 0.20, 0.40, 0.60);
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
