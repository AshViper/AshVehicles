package com.ashvehicles.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ashvehicles.client.MatchMarks;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * 試合中の味方を、バニラの光る効果と同じ輪郭で光らせる。
 *
 * <p><b>描画は1行も書かない。</b> ゲーム本体は「光って見えるか」を1つの述語で決めており
 * （{@code Minecraft.shouldEntityAppearGlowing}）、真を返せばそのエンティティはアウトラインバッファへ
 * もう一度描かれ、ポストパスが輪郭を引く。この MOD が足すのは、その述語への答えだけだ。
 *
 * <p>色は別の場所が決める。{@code MatchGlowColourMixin} 参照。
 *
 * <p><b>世界の全エンティティについて毎フレーム訊かれる。</b> 弾も破片も薬莢もここを通るので、
 * {@link MatchMarks#isFriendly} は型で落とせるものを名簿より先に落としている。
 */
@Mixin(Minecraft.class)
public class MatchGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$glowFriendlies(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (MatchMarks.isFriendly(entity)) {
            callback.setReturnValue(true);
        }
    }
}
