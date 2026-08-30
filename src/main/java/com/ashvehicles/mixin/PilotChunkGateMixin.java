package com.ashvehicles.mixin;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * クライアントに届いている chunk の外へ出てもパイロットを動かし続ける。
 *
 * <p>{@code LocalPlayer.tick()} は全体が1つの条件の後ろにある。「プレイヤーのいる chunk をクライアント
 * は持っているか？」。徒歩なら妥当（届いていない地面に対して物理を回してはいけない）だが、高速機の
 * 操縦桿を握っている場合は致命的だ。機体がサーバーの送信速度を追い越した瞬間に条件は偽になり、その後ろ
 * の全部が止まる。肝心の {@code ServerboundMoveVehiclePacket}——機体がどこまで来たかというパイロットの
 * 報告で、クライアント操縦の乗り物をサーバー上で動かす唯一の手段——も含めて。
 *
 * <p>このファイルが存在する理由のバグは、そこから自分で自分を育てた。報告が止まるのでサーバー側の機体は
 * 速度ゼロで空中に固まる。他の全プレイヤーにとっても、搭載兵装にとっても、狙っているシーカーにとっても。
 * サーバーはパイロットが動いたことも知らされないので、機体が<em>いた</em>場所の周りに chunk を送り続け
 * る。一方クライアントは、もう決して送られてこない土地へ独りで飛んでいくので、条件は二度と真に戻らない。
 * パイロットは届かなくなった世界を巡り、ようやく通った最初の報告（ロード済みの地面へ引き返した時）は
 * 500m の瞬間移動を告げ、サーバーはそれを拒否して機体を凍った場所へ引き戻す。
 *
 * <p>そこで、この MOD の機体に乗っている間だけ条件を素通しにする。徒歩では危険でもここでは安全だ。搭乗者
 * は自前の移動を一切行わない——運ぶのは機体であり、見えない地面をどう扱うかは機体自身の問題で、まさに
 * この状況用の規則を持つ機体自身の tick で決まる。クライアントが持たない chunk は空気として読まれ、
 * クライアント側で地形を生成することもなく、機体の飛行モデルは既にそれを正直に飛んでいる。徒歩の者は
 * バニラのままの答えを受け取る。
 */
@Mixin(LocalPlayer.class)
public abstract class PilotChunkGateMixin {
    @Redirect(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;hasChunkAt(II)Z"))
    private boolean ashvehicles$keepReportingPastTheChunks(Level level, int x, int z) {
        LocalPlayer player = (LocalPlayer) (Object) this;

        // getRootVehicle は何にも乗っていなければプレイヤー自身を返し、プレイヤーがこの MOD の機体で
        // あることは無いので、この1つの判定で両方を兼ねられる。
        if (player.getRootVehicle() instanceof VehicleEntityBase) {
            return true;
        }

        return level.hasChunkAt(x, z);
    }
}
