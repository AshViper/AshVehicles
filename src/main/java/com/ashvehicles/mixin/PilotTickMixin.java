package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.LateWorld;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 機体に乗っているプレイヤー自身の tick の間、{@link LateWorld} の窓を開ける。
 *
 * <p><b>パイロットの tick は機体の tick の中に無い。</b> {@code ServerLevel.tick} は同乗者を飛ばし、
 * 乗り物の {@code tickPassenger} → {@code rideTick} → {@code tick()} が乗員を tick する——ここまでは
 * {@link LateWorldTickMixin} の窓の中だ。だが {@code ServerPlayer.tick()} は {@code super.tick()} を
 * 呼ばない。プレイヤーの本体の tick（{@code Player.tick} → {@code LivingEntity.tick} →
 * {@code Entity.baseTick}）は {@code ServerPlayer.doTick()} にあり、それを呼ぶのは
 * {@code ServerGamePacketListenerImpl.tick()}——サーバー tick の <em>connection 段</em>で、エンティティの
 * ループより後、どの窓も閉じた後だ。
 *
 * <p>その {@code baseTick} が {@code updateInWaterStateAndDoWaterCurrentPushing} →
 * {@code updateFluidHeightAndDoFluidPushing} でパイロットの箱の中の流体を読む。手前の
 * {@code touchingUnloadedChunk} は {@code hasChunksAt}——チケット水準の判定——なので、機体の周囲に
 * 約束のチケットが置かれた chunk は「在る」と答え、続く {@code getFluidState} が生成の完了を
 * サーバースレッドで待つ。2026-09-04 のログでは、この 1 回の読み取りが 1953 ms 止め、その直後に
 * 「Can't keep up! 2002ms」が出ている。機体側の窓をいくら整えても、パイロット自身がそれを踏んでいた。
 *
 * <p>だから {@code doTick} の間も窓を開ける。開いている間、まだ無い chunk は空として読まれ、流体も
 * ブロックも無い。地面は先読みとプレイヤーのチケットが別スレッドで届ける。機体の tick がそうしている
 * のと同じ扱いで、パイロットだけが待つ理由は無い。
 *
 * <p>閉じるのは「入る前の状態へ戻す」。{@code doTick} が別の窓の中から呼ばれることは無いが、機体から
 * 降りる処理が {@code doTick} の中で走ると、出口で乗り物を見直す判定は入口と食い違う。開けたかどうかを
 * 自分で覚えておけば、その食い違いで窓が開きっぱなしになることは無い。
 */
@Mixin(ServerPlayer.class)
public abstract class PilotTickMixin {
    /** この tick で自分が窓を開けたか。開けた者だけが閉じる。サーバースレッド専用。 */
    @Unique
    private boolean ashvehicles$openedTheSky;
    /** 開ける前に窓が開いていたか。閉じる時にその状態へ戻す。 */
    @Unique
    private boolean ashvehicles$skyWasOpen;

    @Inject(method = "doTick", at = @At("HEAD"))
    private void ashvehicles$skyOpens(CallbackInfo callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;

        if (self.getRootVehicle() instanceof AircraftEntity) {
            this.ashvehicles$skyWasOpen = LateWorld.enter();
            this.ashvehicles$openedTheSky = true;
        }
    }

    @Inject(method = "doTick", at = @At("RETURN"))
    private void ashvehicles$skyCloses(CallbackInfo callback) {
        if (this.ashvehicles$openedTheSky) {
            this.ashvehicles$openedTheSky = false;
            LateWorld.restore(this.ashvehicles$skyWasOpen);
        }
    }
}
