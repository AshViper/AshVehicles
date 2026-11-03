package com.ashvehicles.weapon;

import java.util.Optional;

import javax.annotation.Nullable;

import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.sensor.Iff;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 射線の先に味方がいる間、引き金を開かない。
 *
 * <p>見るのは砲口から砲身方向へ引いた1本の線で、最初に当たった物だけを問う。手前に敵がいてその奥に
 * 味方がいるなら撃てるし、手前の味方の奥に敵がいるなら撃てない——弾は最初に当たった物に当たるからだ。
 * 誘導弾の側はシーカーが味方を掴まない（{@code TargetLock.couldTarget}）ので、ここで止まるのは主に砲と
 * 無誘導の物になる。
 *
 * <p><b>地形は見ない。</b> 丘の向こうの味方でも射線上にいれば止まる。線をブロックに通すとロードされて
 * いないチャンクを読み、サーバーでは読むことが生成になる（{@code explosions-generate-chunks} と同じ
 * 罠）。止まり過ぎる側に倒しておく。
 *
 * <p>チームの無いワールドでは {@link Iff#friendly} が常に偽なので、ここは何も止めない。
 */
public final class HoldFire {
    /** 射線を問う距離（ブロック）。機関砲の有効射程より少し長く、箱の大きさがコストになる上限でもある。 */
    private static final double REACH = 256.0;
    /** 線の太さ。機体の箱に掠っただけでも「射線上」と言う。 */
    private static final double MARGIN = 1.0;

    private HoldFire() {
    }

    /**
     * 撃つ者に、射線上の味方を理由に断ったと告げる。断ったかどうかを返す。
     *
     * @param told 告げる相手。人でなければ黙って止める
     */
    public static boolean refuses(Entity shooter, Vec3 from, Vec3 bore, @Nullable Entity told) {
        if (!friendAhead(shooter, from, bore)) {
            return false;
        }

        if (told instanceof Player player) {
            player.displayClientMessage(Component.translatable("message.ashvehicles.iff.hold_fire"), true);
        }

        return true;
    }

    /** 砲口から砲身方向へ最初に当たる物が味方か。 */
    public static boolean friendAhead(Entity shooter, Vec3 from, Vec3 bore) {
        Vec3 to = from.add(bore.normalize().scale(REACH));
        AABB box = new AABB(from, to).inflate(MARGIN);
        Entity first = null;
        double nearest = Double.MAX_VALUE;

        for (Entity candidate : shooter.level().getEntities(shooter, box, entity -> blocks(shooter, entity))) {
            Optional<Vec3> hit = candidate.getBoundingBox().inflate(MARGIN).clip(from, to);

            if (hit.isEmpty()) {
                continue;
            }

            double distance = hit.get().distanceToSqr(from);

            if (distance < nearest) {
                nearest = distance;
                first = candidate;
            }
        }

        return first != null && Iff.friendly(shooter, first);
    }

    /** 弾を受け止める物。自分自身・乗員・弾・デコイは射線を塞がない。 */
    private static boolean blocks(Entity shooter, Entity candidate) {
        return candidate.isAlive() && !candidate.isSpectator()
                && !WeaponMounts.isPartOf(shooter, candidate)
                && !(candidate instanceof VehicleProjectile)
                && !(candidate instanceof CountermeasureEntity);
    }
}
