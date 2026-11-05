package com.ashvehicles.mixin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ashvehicles.client.model.bbmodel.BbModels;
import com.ashvehicles.client.model.obj.ObjModels;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.loading.object.BakedAnimations;

/**
 * GeckoLib がリロードで geo.json を焼き終えたら、続けて {@code obj/} の OBJ と {@code bbmodel/} の Blockbench
 * プロジェクトを焼いて同じマップへ入れる。bbmodel のアニメーションはリロードの最後に足す。
 *
 * <p>GeckoLib はリロードのたびに模型のマップを新しく作って丸ごと差し替えるので、外から後で足す物は次のリロード
 * で消える。自前のリロードリスナーから足すと、GeckoLib の差し替えより後に走る保証がリスナーの登録順にしか無い。
 * ここで GeckoLib 自身の読み込みの続きにしておけば、OBJ は差し替えの前に同じマップへ入り、同じ瞬間に見える
 * ようになる。入れる関数（{@code models::put}）は素の HashMap なので、GeckoLib が入れ終わった後に繋ぐ。
 * {@link ObjModels#load} と {@link BbModels} 参照。同じ名前なら後に入る bbmodel が勝つ。
 */
@Mixin(value = GeckoLibCache.class, remap = false)
public abstract class ObjModelLoadMixin {
    @Inject(method = "loadModels", at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$thenObj(Executor executor, ResourceManager resources,
            BiConsumer<ResourceLocation, BakedGeoModel> models, CallbackInfoReturnable<CompletableFuture<Void>> result) {
        result.setReturnValue(result.getReturnValue()
                .thenCompose(done -> ObjModels.load(executor, resources, models))
                .thenCompose(done -> BbModels.loadModels(executor, resources, models)));
    }

    /**
     * bbmodel のアニメーションは、GeckoLib がマップを差し替えた後にゲームスレッドから足す。読むのはリロードの
     * 準備と並べて今から始める。{@link BbModels#readAnimations} 参照。
     */
    @Inject(method = "reload", at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$thenBbmodelAnimations(PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resources, ProfilerFiller preparation, ProfilerFiller application, Executor background,
            Executor game, CallbackInfoReturnable<CompletableFuture<Void>> result) {
        CompletableFuture<Map<ResourceLocation, BakedAnimations>> animations =
                BbModels.readAnimations(background, resources);

        result.setReturnValue(result.getReturnValue().thenCombine(animations, (done, read) -> read)
                .thenAcceptAsync(read -> GeckoLibCache.getBakedAnimations().putAll(read), game));
    }
}
