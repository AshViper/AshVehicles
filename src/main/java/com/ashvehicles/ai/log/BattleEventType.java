package com.ashvehicles.ai.log;

import com.ashvehicles.ai.AiConfig;

/**
 * 戦闘記録に残す出来事の種類。
 *
 * <p><b>毎 tick の状態は記録しない。</b> 残すのは判断が変わった瞬間と、試合の中で何かが起きた瞬間だけだ。
 * 毎 tick 書けば 40両で毎秒800行になり、ファイルもディスクもサーバーも誰の役にも立たない量で埋まる。
 * 学習に要るのは「どの状態でどう判断し、その後に何が起きたか」で、判断と判断の間の tick は報酬の合計として
 * 1行に畳める（{@link BattleDecisionLog#reward}）。
 *
 * <p>名前（{@link #id}）は記録ファイルに出る綴り。後から読む道具（{@code tool/ai}）がこれで引くので、
 * 変えないこと。
 */
public enum BattleEventType {
    BATTLE_STARTED("BattleStarted"),
    BATTLE_ENDED("BattleEnded"),

    OBJECTIVE_SELECTED("ObjectiveSelected"),
    ENEMY_DETECTED("EnemyDetected"),
    TARGET_SELECTED("TargetSelected"),
    ATTACK_STARTED("AttackStarted"),
    ATTACK_ENDED("AttackEnded"),
    ROUTE_SELECTED("RouteSelected"),
    FLANK_STARTED("FlankStarted"),
    RETREAT_STARTED("RetreatStarted"),
    ACTION_CHANGED("ActionChanged"),
    SUPPORT_REQUESTED("SupportRequested"),
    SUPPORT_ANSWERED("SupportAnswered"),

    OBJECTIVE_CAPTURED("ObjectiveCaptured"),
    OBJECTIVE_RECAPTURED("ObjectiveRecaptured"),
    OBJECTIVE_DEFENDED("ObjectiveDefended"),
    OBJECTIVE_LOST("ObjectiveLost"),

    ENEMY_DESTROYED("EnemyDestroyed"),
    VEHICLE_DESTROYED("VehicleDestroyed"),
    ALLY_SUPPORTED("AllySupported"),
    GOOD_FLANK("GoodFlank"),
    UNNECESSARY_DEATH("UnnecessaryDeath"),
    LONG_EXPOSURE("LongExposure"),
    WATER_ENTERED("WaterEntered"),
    UNDERWATER("Underwater"),
    WEAPON_RELEASED("WeaponReleased");

    private final String id;

    BattleEventType(String id) {
        this.id = id;
    }

    /** 記録ファイルに出る名前。 */
    public String id() {
        return this.id;
    }

    /** この出来事に付く報酬。報酬を持たない出来事は0。値は設定が持つ（{@code [rewards]}）。 */
    public double reward(AiConfig.Rewards rewards) {
        return switch (this) {
            case ENEMY_DESTROYED -> rewards.enemyDestroyed();
            case OBJECTIVE_CAPTURED -> rewards.objectiveCaptured();
            case OBJECTIVE_RECAPTURED -> rewards.objectiveRecaptured();
            case OBJECTIVE_DEFENDED -> rewards.objectiveDefended();
            case ALLY_SUPPORTED -> rewards.allySupported();
            case GOOD_FLANK -> rewards.goodFlank();
            case VEHICLE_DESTROYED -> rewards.vehicleDestroyed();
            case OBJECTIVE_LOST -> rewards.objectiveLost();
            case UNNECESSARY_DEATH -> rewards.unnecessaryDeath();
            case LONG_EXPOSURE -> rewards.longExposure();
            case WATER_ENTERED -> rewards.waterEntered();
            case UNDERWATER -> rewards.underwater();
            default -> 0.0;
        };
    }
}
