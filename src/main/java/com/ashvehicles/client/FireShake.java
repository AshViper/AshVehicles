package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 自分の乗る機体・車両が撃ったとき、一人称の視界が少し揺れる。
 *
 * <p>発砲はクライアントでは起きないので、自分の機体から出た弾がレベルへ入ってきたことを発砲として読む
 * （{@link VehicleProjectile#firedFrom}）。弾は往復1回ぶん遅れて現れるが、揺れにはそれで足りる。発射元の id は
 * 生成データで届き、それは参加イベントより後に読まれることがあるので、1 tick 置いてから確かめる。
 *
 * <p>強さは弾の威力の対数で決める。機銃は細かく震え、戦車砲は一度だけ跳ねる。連射は1発ごとに足さず、
 * 一番強い1発の水準に揃える——毎秒100発の M61 が戦車砲より揺れてはいけない。落とすだけの爆弾は揺らさない。
 *
 * <p>{@link FlightShake} と同じく、揺れるのは描画だけで、照準も入力も動かない。三人称とポッド・ガンカメラも
 * 揺らさない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class FireShake {
    /** 震えが最大（1）になる威力。これ以上の砲は同じだけ揺れる。 */
    private static final float FULL_DAMAGE = 200.0F;
    /** 一番弱い機銃の強さ。威力 1 のときの値。 */
    private static final float LEAST = 0.15F;
    /** ミサイルとロケットの強さ。燃える筒が離れるだけで、砲の反動は無い。 */
    private static final float ROCKET = 0.3F;
    /** これより遅く出る弾は落としただけ（爆弾・増槽）なので揺らさない（ブロック/tick）。 */
    private static final float DROPPED_SPEED = 0.5F;

    /** 震え 1 での片側への振れ幅（度）。 */
    private static final float MOST_SWING = 0.35F;
    /** 跳ね 1 で機首側へ持ち上がる量（度）。 */
    private static final float MOST_JOLT = 0.6F;

    /** 震えと跳ねが毎 tick 残る割合。数 tick で収まる。 */
    private static final float BUZZ_KEEP = 0.7F;
    private static final float JOLT_KEEP = 0.6F;
    private static final float SPENT = 0.003F;

    private static final float RATE_YAW = 3.1F;
    private static final float RATE_PITCH = 3.9F;
    private static final float RATE_ROLL = 2.6F;
    private static final int WRAP = 20 * 60 * 10;

    /** 届いたばかりで、まだ発射元を確かめていない弾。 */
    private static final List<VehicleProjectile> ARRIVED = new ArrayList<>();

    private static float buzz;
    private static float jolt;
    private static float joltO;
    private static int since;

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof VehicleProjectile round) {
            ARRIVED.add(round);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            ARRIVED.clear();
            buzz = jolt = joltO = 0.0F;

            return;
        }

        if (minecraft.isPaused()) {
            return;
        }

        Entity ridden = minecraft.player == null ? null : minecraft.player.getVehicle();
        float kick = 0.0F;

        if (ridden instanceof VehicleEntityBase) {
            for (VehicleProjectile round : ARRIVED) {
                if (!round.isRemoved() && round.firedFrom() == ridden) {
                    kick = Math.max(kick, strength(round));
                }
            }
        }

        ARRIVED.clear();

        joltO = jolt;
        buzz = Math.max(buzz * BUZZ_KEEP, kick);
        jolt = Math.max(jolt * JOLT_KEEP, kick);

        if (buzz < SPENT) {
            buzz = 0.0F;
        }
        if (jolt < SPENT) {
            jolt = 0.0F;
        }

        since = buzz > 0.0F || jolt > 0.0F ? (since + 1) % WRAP : 0;
    }

    private static float strength(VehicleProjectile round) {
        if (round instanceof RocketEntity) {
            return ROCKET;
        }

        WeaponDefinition.Projectile shot = round.getRound();

        if (shot.speed() < DROPPED_SPEED && shot.thrust() <= 0.0F) {
            return 0.0F;
        }

        float damage = Math.max(shot.damage(), 1.0F);

        return Mth.clamp(LEAST + (1.0F - LEAST) * (float) (Math.log(damage) / Math.log(FULL_DAMAGE)), 0.0F, 1.0F);
    }

    /** 機体と車両のカメラハンドラが角度を代入した後に足す。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (buzz <= 0.0F && jolt <= 0.0F) {
            return;
        }

        Camera camera = event.getCamera();
        Entity viewer = camera.getEntity();

        if (camera.isDetached() || PodCamera.isShowing() || viewer == null
                || !(viewer.getVehicle() instanceof VehicleEntityBase vehicle)
                || vehicle instanceof AircraftEntity aircraft
                        && GunCamera.stationOf(aircraft, viewer) != com.ashvehicles.weapon.GunStations.NONE) {
            return;
        }

        float partial = (float) event.getPartialTick();
        float phase = since + partial;
        float swing = buzz * MOST_SWING;
        float lift = Mth.lerp(partial, joltO, jolt) * MOST_JOLT;

        event.setYaw(event.getYaw() + swing * Mth.sin(phase * RATE_YAW));
        // 負のピッチが上。撃つと視界が持ち上がり、跳ねの減衰と一緒に戻る。
        event.setPitch(event.getPitch() - lift + swing * Mth.sin(phase * RATE_PITCH + 1.7F));
        event.setRoll(event.getRoll() + swing * 0.5F * Mth.sin(phase * RATE_ROLL + 0.9F));
    }

    private FireShake() {
    }
}
