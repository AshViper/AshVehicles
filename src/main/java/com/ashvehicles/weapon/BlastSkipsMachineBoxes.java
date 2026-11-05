package com.ashvehicles.weapon;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.entity.VehicleProjectile;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * 爆風の打撃を自前で渡す弾（{@code projectile.blast}）の爆発から、機体の当たり判定の箱を外す。
 *
 * <p><b>結果は何も変わらない。消えるのは捨てられていた計算だけ。</b>バニラの {@code Explosion.explode} は巻き込んだ
 * 相手1体ごとに {@code getSeenPercent} を2回引く（ダメージ量と押しの量）。あれは箱の大きさに比例した数の光線を
 * 撃つ——6×2×6 の箱で1回 845 本——ので、機体の横で爆弾が1発炸裂すると、箱の数だけ数千本の光線が tick スレッドで
 * 走っていた。そして箱へ渡された打撃は親の {@code VehicleEntityBase.hurt} が「この弾の爆風は blastMachines が
 * 別に渡している」として必ず断り、箱に足された速度は誰も読まない（箱は毎 tick 親が置き直す）。
 *
 * <p>外すのは箱だけで、機体本体は一覧に残す。本体へのバニラの押しはこれまで通り届く。{@code blast} を持たない
 * 弾（ロケット・榴弾など）の爆発では箱がダメージの入口なので、そちらには一切触れない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class BlastSkipsMachineBoxes {
    private BlastSkipsMachineBoxes() {
    }

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()
                || !(event.getExplosion().getDirectSourceEntity() instanceof VehicleProjectile shot)
                || shot.getRound().blast() <= 0.0F) {
            return;
        }

        event.getAffectedEntities().removeIf(entity ->
                entity instanceof VehiclePart part && part.getParent() instanceof VehicleEntityBase);
    }
}
