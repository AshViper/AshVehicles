package com.ashvehicles.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ashvehicles.client.MatchMarks;

import net.minecraft.world.entity.Entity;

/**
 * 味方の輪郭を陣営の色にする。
 *
 * <p>ゲーム本体はアウトラインの色を {@code Entity.getTeamColor()} から取り、それはバニラの
 * {@code scoreboard team} を見る。この MOD の陣営はそれとは別の帳簿にあるので（[[team-deathmatch-shape]]）、
 * 味方について訊かれたときだけ自陣の色を返す。
 *
 * <p><b>クライアント側だけ。</b> この mixin はクライアント設定に載せてあるが、シングルプレイでは同じ
 * クラスが論理サーバーでも動く。だから最初に世界の側を確かめ、サーバー側の問い合わせは素通りさせる
 * ——{@link MatchMarks} はクライアントの写しであって、サーバーが答えを求める場所ではない。
 */
@Mixin(Entity.class)
public class MatchGlowColourMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$friendlyColour(CallbackInfoReturnable<Integer> callback) {
        Entity self = (Entity) (Object) this;

        if (self.level().isClientSide && MatchMarks.isFriendly(self)) {
            callback.setReturnValue(MatchMarks.friendlyColour());
        }
    }
}
