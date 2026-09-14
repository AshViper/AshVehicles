package com.ashvehicles.ai.debug;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.role.VehicleRole;
import com.ashvehicles.ai.tactics.TacticalPosition;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * AI 1両の今の姿。可視化（{@link AiDebug}）と {@code /tdm ai inspect} が読む。
 *
 * <p>判断の途中の値をそのまま持ち出す物で、判断には使わない。
 *
 * @param entityId    車両のエンティティ ID
 * @param vehicle     車両の種類（ID の名前部分）
 * @param team        陣営
 * @param version     AI の版
 * @param role        役割
 * @param action      今の行動
 * @param objective   割り当てられた持ち場の名前
 * @param target      狙っている相手
 * @param threat      全部の敵の脅威を合わせた物
 * @param routeRisk   走っている道の危険
 * @param health      残り耐久の割合
 * @param at          今の位置
 * @param destination 行き先
 * @param tactical    探した遮蔽・側面・撤退先
 * @param route       まだ通っていない通過点
 * @param utilities   直前の判断で各行動に付いた点数
 */
public record PilotSnapshot(int entityId, String vehicle, String team, String version, VehicleRole role,
        @Nullable TacticalAction action, @Nullable String objective, @Nullable Entity target, double threat,
        double routeRisk, float health, Vec3 at, @Nullable Vec3 destination, @Nullable TacticalPosition tactical,
        List<Vec3> route, Map<TacticalAction, Double> utilities) {
}
