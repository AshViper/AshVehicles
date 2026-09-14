package com.ashvehicles.ai.log;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * 終わった戦闘1回の結果。統計（{@code learning/BattleStatistics}）と自己対戦が読む。
 *
 * @param battleId     戦闘の名前
 * @param aborted      試合が決着する前にサーバーが止まった。統計には入れない
 * @param winner       勝った陣営。引き分けなら null
 * @param teamVersions 陣営ごとの AI の版（戦闘が始まった時点）
 * @param lives        AI の車両の一生
 * @param endTick      戦闘が終わったゲーム時刻
 */
public record BattleSummary(String battleId, boolean aborted, @Nullable String winner,
        Map<String, String> teamVersions, List<LifeRecord> lives, long endTick) {
}
