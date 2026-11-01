package com.ashvehicles.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ashvehicles.client.ghost.GhostOutline;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;

/**
 * エンティティの輪郭の処理を、ゴーストパスの後まで待たせる。遠くの味方の模型も縁取るため。
 * 理由と流れは {@link GhostOutline} 参照。
 *
 * <p>{@code renderLevel} の中で {@code PostChain.process} を呼ぶのは輪郭（{@code entityEffect}）と
 * Fabulous の透過合成（{@code transparencyChain}）の2か所。止めるのは前者だけで、後者はそのまま通す。
 */
@Mixin(LevelRenderer.class)
public class GhostOutlineMixin implements GhostOutline.GhostOutlineAccess {
    @Shadow
    private PostChain entityEffect;

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(F)V"))
    private void ashvehicles$deferOutline(PostChain chain, float ticks) {
        if (chain == this.entityEffect) {
            GhostOutline.deferVanilla(ticks);
            return;
        }

        chain.process(ticks);
    }

    @Override
    public PostChain ashvehicles$entityEffect() {
        return this.entityEffect;
    }
}
