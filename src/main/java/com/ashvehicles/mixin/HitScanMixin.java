package com.ashvehicles.mixin;

import java.util.List;
import java.util.function.Predicate;

import com.ashvehicles.entity.Hitboxes;
import com.ashvehicles.entity.VehiclePart;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 世界を貫いて狙う物すべてが、この MOD の機体を「本当にある場所」で捉えるようにする。
 *
 * <p>ゲームの照準は全部ここを通る。矢、ファイアボール、プレイヤーの十字線、そしてこの MOD の弾。中身は
 * 線分に沿ってエンティティを歩き、それぞれの直立した箱で切ること。機体の箱に対してそれは「傾いた箱を
 * 囲む箱」になるので、45度に寝た砲身の脇を通した射撃が命中したり、主翼と水平尾翼の隙間を狙ったクリック
 * で搭乗できたりする。
 *
 * <p>そこで機体はその走査から完全に外し、同じ線分を別途、本物の形に対して当てる。2つの答えのうち視点に
 * 近い方を採り、この MOD の機体でない物への命中はゲームが出した通りに残す。
 *
 * <p>呼び出し側の「何に当ててよいか」の判断はそのまま通す。箱を試す前に必ず呼び出し側のフィルタに訊く
 * ので、発射した機体に当たってはいけない弾は今も当たらないし、何も吊っていないパイロンは今も除外される。
 */
@Mixin(ProjectileUtil.class)
public abstract class HitScanMixin {
    /**
     * この MOD の箱を、ゲーム側の線分走査から完全に外す。
     *
     * <p>間違った形で命中させないためだけではなく、命中させることで「本当は後ろにあった物」を隠さない
     * ため。ゲームは見つけた最も近い1つしか返さないので、線分が直立した箱を横切っただけで実際の翼面には
     * 当たっていない主翼が、その奥の相手に向けた射撃を飲み込んでしまう。探索から外しておけば、機体の箱は
     * 誤って当たることも、何かの前に立ちはだかることもできず、下の走査が本来の位置へ戻す。
     */
    @Redirect(method = {
            "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)"
                    + "Lnet/minecraft/world/phys/EntityHitResult;"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<Entity> ashvehicles$leaveTheBoxesToUs(Level level, Entity shooter, AABB area,
            Predicate<? super Entity> filter) {
        return level.getEntities(shooter, area, filter.and(found -> !(found instanceof VehiclePart)));
    }

    /**
     * 十字線や、届く距離に上限がある物が使うオーバーロード。上限は距離の二乗で、この MOD の箱にも他と
     * まったく同じように適用される。
     */
    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)"
            + "Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$aimAtTheRealShape(Entity shooter, Vec3 from, Vec3 to, AABB area,
            Predicate<Entity> filter, double furthest, CallbackInfoReturnable<EntityHitResult> callback) {
        EntityHitResult found = callback.getReturnValue();
        EntityHitResult ours = Hitboxes.pick(shooter.level(), shooter, from, to, 0.0, filter);

        callback.setReturnValue(nearer(from, found, ours, furthest, 0.0f));
    }

    /**
     * 発射物が使うオーバーロード。距離上限ではなく、各エンティティの周囲に余裕を持たせる方式。この MOD
     * の箱にも同じ余裕を与える。そうしないと、他所でなら掠りと数えられる当たりがここでだけ弾かれる。
     */
    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
            + "Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("RETURN"), cancellable = true)
    private static void ashvehicles$strikeTheRealShape(Level level, Entity shooter, Vec3 from, Vec3 to,
            AABB area, Predicate<Entity> filter, float margin,
            CallbackInfoReturnable<EntityHitResult> callback) {
        EntityHitResult found = callback.getReturnValue();
        EntityHitResult ours = Hitboxes.pick(level, shooter, from, to, margin, filter);

        callback.setReturnValue(nearer(from, found, ours, Double.MAX_VALUE, margin));
    }

    /**
     * 2つの命中のうち視点に近い方。ただしゲームが機体の箱に対して出した答えは先に捨てる。
     *
     * <p>比較せず捨てるのは、それが同じ問いへの劣った答えではなく別の問いへの答えだから。パーツを収める
     * 直立した箱と線分の交点であって、その箱はパーツではない。
     */
    private static EntityHitResult nearer(Vec3 from, EntityHitResult found, EntityHitResult ours,
            double furthest, float margin) {
        if (found != null && found.getEntity() instanceof VehiclePart) {
            found = null;
        }

        if (ours == null) {
            return found;
        }

        double mine = from.distanceToSqr(ours.getLocation());

        if (mine > furthest) {
            return found;
        }

        return found == null || mine < reach(from, found, margin) ? ours : found;
    }

    /**
     * ゲーム側の命中までの距離。
     *
     * <p>結果から読まず計算し直すのは、2つのオーバーロードの片方が「エンティティのどこに当たったか」を
     * 言わずに命中を返すため。代わりに中心が入るが、細長いエンティティの中心は、機首を掠めた線分の進入点
     * から遠く離れている。
     */
    private static double reach(Vec3 from, EntityHitResult found, float margin) {
        return found.getEntity().getBoundingBox().inflate(margin).clip(from, found.getLocation())
                .map(from::distanceToSqr)
                .orElseGet(() -> from.distanceToSqr(found.getLocation()));
    }
}
