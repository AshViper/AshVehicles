package com.ashvehicles.ai.battlefield;

import net.minecraft.world.phys.Vec3;

/**
 * 脅威マップを塗る元になる敵1つ。陣営が知っている敵情（{@link TeamIntel}）から作る。
 *
 * @param entityId   その敵のエンティティ ID
 * @param sight      最後に知っている視点の位置
 * @param capability 壊す力（0〜1）。{@code perception/ThreatModel#capability}
 * @param reach      脅威を塗る半径（ブロック）。兵装の射程を設定の上限で切った物
 * @param seenTick   最後に誰かが見た tick
 * @param current    今も誰かが見ている。見失った敵は露出の層に塗らず、危険の層にだけ薄れながら残す
 */
public record ThreatSource(int entityId, Vec3 sight, double capability, double reach, long seenTick,
        boolean current) {
}
