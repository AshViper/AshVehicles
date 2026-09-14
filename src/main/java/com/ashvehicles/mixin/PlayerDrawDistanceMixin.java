package com.ashvehicles.mixin;

import com.ashvehicles.client.ghost.GhostConfig;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ゲーム自身がプレイヤーを描くのをやめる距離を、ゴーストパスが引き継ぐ距離とちょうど一致させる。
 *
 * <p><b>これが無いと隙間が開く。</b>バニラがエンティティを描くのをやめる距離は当たり判定箱の大きさから
 * 決まる——{@code Entity.shouldRenderAtSqrDistance} は「箱の平均辺長 × 64」で、プレイヤーは 0.6×1.8×0.6
 * なので平均 1.0、つまり <b>64 ブロック</b>だ。ゴーストパスが引き継ぐのは {@code ghostStartDistance}、
 * 既定 128。その間の 64 ブロックぶん、どちらも描かない帯ができる。歩いている人が 64 で消えて 128 で
 * 戻ってくることになり、それは「遠くまで見える」より悪い。
 *
 * <p>そこで広い方でも狭い方でもなく<em>同じ</em>にする。ここが答えるのは距離判定だけで、視錐台の判定は
 * この後もそのまま走るので、背後のプレイヤーが描かれるようになることはない。
 *
 * <p>ゴースト表示自体を切っている場合は何もしない。引き継ぐ相手がいないのだから、バニラの判断こそ正しい。
 *
 * <p>プレイヤー以外には触れない。牛が 64 ブロックで消えるのは、ゴーストを持たない以上そのままが正しい。
 */
@Mixin(Entity.class)
public abstract class PlayerDrawDistanceMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$handOverToTheGhostPass(double distanceSq,
            CallbackInfoReturnable<Boolean> callback) {
        if (!((Object) this instanceof Player) || !GhostConfig.enabled()) {
            return;
        }

        double handover = GhostConfig.startDistance();

        callback.setReturnValue(distanceSq < handover * handover);
    }
}
