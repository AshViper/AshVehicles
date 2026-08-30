package com.ashvehicles.mixin;

import com.ashvehicles.entity.Hitboxes;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * この MOD の機体を、動く物すべてに対する障害物として立てる。
 *
 * <p>ゲーム標準の障害物にはなっていない。機体の箱は機体が傾いている角度のまま傾いており、ゲームの
 * 当たり判定は直立した箱しか知らない。そのため機体を構成するパーツは衝突自体を拒否しており
 * （{@code VehiclePart.canBeCollidedWith} 参照）、本来の形のままここで復活させる。
 *
 * <p>これは移動処理の一部ではなく最終決定。Minecraft がまずブロックや他の物との衝突を解決し、ここへ
 * 来るのは「どこまで動いてよいか」の結論で、ここでできるのはそれをさらに削ることだけ。世界が既に奪った
 * 分を返しはしないので、隅に嵌まったプレイヤーが横を通る戦車のおかげで抜け出せることはない。
 *
 * <p>結果は誰もが期待し、他の方法では得られないもの。傾いた甲板の上に、傾いたまま立てる。翼の周りを、
 * 翼を囲む直方体ではなく翼の形に沿って歩ける。ハーフブロックに上がるのと同じ段差処理で履帯に乗れる。
 * Minecraft は移動がどれだけ残ったかで着地を判定し、この処理はその計算の内側にあるので、船体の上に
 * 立つプレイヤーはゲームから見て地面に立っている。
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"), cancellable = true)
    private void ashvehicles$stopAtTheRealShape(Vec3 wanted, CallbackInfoReturnable<Vec3> callback) {
        Entity mover = (Entity) (Object) this;
        Vec3 allowed = callback.getReturnValue();
        Vec3 limited = Hitboxes.limit(mover, mover.getBoundingBox(), allowed);

        if (limited != allowed) {
            callback.setReturnValue(limited);
        }
    }
}
