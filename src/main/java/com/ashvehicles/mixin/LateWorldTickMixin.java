package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.LateWorld;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 機体の tick の間、{@link LateWorld} の窓を開ける。
 *
 * <p>{@code tickNonPassenger} を包むのは、機体自身の {@code tick()} だけでは足りないからだ。乗員の tick
 * （{@code tickPassenger} → {@code rideTick}）はその後に走り、乗員の位置更新も流体の判定も同じ地面を
 * 訊く。窓が閉じた後に乗員が訊けば、パイロットの足元が同期生成になる。
 *
 * <p>入れ子は無い。機体を tick する物は {@code ServerLevel} のループだけで、機体の tick が別の機体を
 * tick することは無い。{@code WeaponTicker} も同じメソッドで弾を tick するが、型で外れる。
 */
@Mixin(ServerLevel.class)
public abstract class LateWorldTickMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void ashvehicles$skyOpens(Entity entity, CallbackInfo callback) {
        if (entity instanceof AircraftEntity) {
            LateWorld.enter();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void ashvehicles$skyCloses(Entity entity, CallbackInfo callback) {
        if (entity instanceof AircraftEntity) {
            LateWorld.leave();
        }
    }
}
