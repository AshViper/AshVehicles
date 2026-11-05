package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.renderer.SmokeCloud;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 銃砲が撃つたびに砲口から出る硝煙と発射炎。機体・車両・牽引砲のどれの銃でも同じ。
 *
 * <p><b>クライアントで撒く。</b> サーバーから送ると、粒はサーバーの機体位置——描かれている砲口の数 tick
 * 後ろ——に立ち、飛んでいる機体では砲口ではなく空中の離れた所から煙が出る。弾は生成データを読んだ瞬間に
 * 描かれた砲口へ戻される（{@code VehicleProjectile.anchorToMuzzle}）ので、その位置
 * （{@link com.ashvehicles.entity.VehicleProjectile#leftMuzzleAt}）がそのまま煙を置く場所になる。
 *
 * <p>煙はパーティクルではなく {@link SmokeCloud} の玉で、撃った機体の速度の大半を持って出て、空気に
 * 止められて後ろへ流れる。発射炎だけは一瞬なのでパーティクルのまま。
 *
 * <p>連射は1発ごとに撒かず、発射元ごとに 1 tick 1回にまとめる。M61 の毎秒100発ぶん撒けば画面が煙で埋まる。
 * 大きさは威力の対数で、機銃は薄く、戦車砲は大きい。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GunSmoke {
    /** 煙が最大になる威力。{@link FireShake} と同じ尺度。 */
    private static final float FULL_DAMAGE = 200.0F;
    /** これより遠い発砲は撒かない（ブロック）。 */
    private static final double FARTHEST = 192.0;

    private static final int SMOKE = 0xB8B2A8;
    private static final int FLASH = 0xFFC060;

    /** 連射の雲が出始める、毎秒の威力の総量（威力×発射速度）。 */
    private static final float CLOUD_FROM = 200.0F;
    /** その何倍で雲が最大になるか。 */
    private static final float CLOUD_SPAN = 5.0F;
    /** 雲の粒が撃った機体の速度をどれだけ持って出るか。 */
    private static final double CLOUD_CARRY = 0.85;
    private static final int CLOUD = 0xCFCBC4;

    private static final List<BulletEntity> ARRIVED = new ArrayList<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof BulletEntity round) {
            ARRIVED.add(round);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level == null || minecraft.isPaused() || ARRIVED.isEmpty()) {
            if (level == null) {
                ARRIVED.clear();
            }

            return;
        }

        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        Set<Integer> puffed = new HashSet<>();

        for (BulletEntity round : ARRIVED) {
            Vec3 muzzle = round.leftMuzzleAt();

            if (muzzle == null || round.level() != level || muzzle.distanceToSqr(eye) > FARTHEST * FARTHEST) {
                continue;
            }

            Entity from = round.firedFrom();

            if (!puffed.add(from == null ? round.getId() : from.getId())) {
                continue;
            }

            Vec3 velocity = round.getDeltaMovement();

            if (velocity.lengthSqr() < 1.0E-6) {
                continue;
            }

            Vec3 bore = velocity.normalize();
            WeaponDefinition.Projectile shot = round.getRound();

            Vec3 carried = from == null ? Vec3.ZERO
                    : new Vec3(from.getX() - from.xo, from.getY() - from.yo, from.getZ() - from.zo);

            puff(level, muzzle, bore, carried, size(shot));

            float cloud = cloud(shot, round.getWeapon().firing().roundsPerSecond());

            if (cloud > 0.0F) {
                cloud(level, muzzle, bore, carried, cloud);
            }
        }

        ARRIVED.clear();
    }

    /** 0.2（小銃弾）〜1（戦車砲）。 */
    private static float size(WeaponDefinition.Projectile round) {
        float damage = Math.max(round.damage(), 1.0F);

        return Mth.clamp(0.2F + 0.8F * (float) (Math.log(damage) / Math.log(FULL_DAMAGE)), 0.2F, 1.0F);
    }

    /**
     * 連射の硝煙の雲の濃さ。0〜1。
     *
     * <p>雲を作るのは1発の大きさではなく、毎秒どれだけの装薬が砲口から出るかだ。威力×発射速度で量り、
     * GAU-8（13×65）は機首を包むほど、M61 はその半分ほど、単発の砲と機銃では出ない。
     */
    private static float cloud(WeaponDefinition.Projectile round, float roundsPerSecond) {
        float flow = Math.max(round.damage(), 0.0F) * roundsPerSecond;

        if (flow <= CLOUD_FROM) {
            return 0.0F;
        }

        return Mth.clamp((float) (Math.log(flow / CLOUD_FROM) / Math.log(CLOUD_SPAN)), 0.0F, 1.0F);
    }

    /**
     * 機首を包む雲。A-10 の射撃が煙の塊に見えるのはこれだ。
     *
     * <p>砲口のガスは撃った機体と同じ速さで前へ出るので、出た直後の煙は機首の前と周りに留まり、空気に
     * 止められてから機体の後ろへ流れる。だから粒に機体の速度の大半を持たせ、抗力で落ちるのに任せる。
     * 何も持たせなければ雲は砲口から一直線の筋になり、機首は包まれない。
     */
    private static void cloud(ClientLevel level, Vec3 muzzle, Vec3 bore, Vec3 carried, float cloud) {
        RandomSource random = level.random;
        int puffs = 2 + (int) (cloud * 4.0F);

        for (int i = 0; i < puffs; i++) {
            double out = random.nextDouble() * (1.5 + cloud * 2.5);
            Vec3 at = muzzle.add(bore.scale(out)).add(
                    random.nextGaussian() * 0.5 * cloud, random.nextGaussian() * 0.4 * cloud,
                    random.nextGaussian() * 0.5 * cloud);
            Vec3 drift = carried.scale(CLOUD_CARRY).add(bore.scale(0.08 + random.nextDouble() * 0.06));

            SmokeCloud.smoke(at, drift.add(random.nextGaussian() * 0.03, random.nextGaussian() * 0.02,
                            random.nextGaussian() * 0.03),
                    0.6F + cloud * 0.8F + random.nextFloat() * 0.3F, 4.0F, 0.88F, -0.075F,
                    50 + (int) (cloud * 50.0F) + random.nextInt(20), 0.25F + cloud * 0.2F, CLOUD);
        }
    }

    private static void puff(ClientLevel level, Vec3 muzzle, Vec3 bore, Vec3 carried, float size) {
        RandomSource random = level.random;
        Vec3 ahead = muzzle.add(bore.scale(0.3 + size * 0.6));

        // 発射炎。一瞬だけの小さな火。
        level.addParticle(ModParticles.BLAST.get().of(FLASH, 0.15F + size * 0.5F),
                ahead.x, ahead.y, ahead.z, 0.0, 0.0, 0.0);

        // 砲身の先へ押し出される煙。砲口に近いほど速い。
        int puffs = 1 + (int) (size * 5.0F);

        for (int i = 0; i < puffs; i++) {
            double out = (i + random.nextDouble()) / puffs;
            Vec3 at = ahead.add(bore.scale(out * size * 3.0));
            double push = (0.05 + size * 0.2) * (1.0 - out * 0.6);

            Vec3 drift = carried.scale(CLOUD_CARRY).add(bore.x * push + random.nextGaussian() * 0.01,
                    bore.y * push + 0.01 + random.nextDouble() * 0.01, bore.z * push + random.nextGaussian() * 0.01);

            SmokeCloud.smoke(at, drift, 0.15F + size * (0.3F + 0.3F * (float) out), 3.0F + size * 2.0F, 0.85F,
                    -0.1F, 30 + (int) (size * 60.0F) + random.nextInt(10), 0.35F + size * 0.3F, SMOKE);
        }

        // 大きな砲は砲口の周りに横へ吹く輪も出す（砲口制退器の煙）。
        if (size > 0.6F) {
            Vec3 side = new Vec3(-bore.z, 0.0, bore.x);

            if (side.lengthSqr() > 1.0E-6) {
                side = side.normalize();

                for (int sign = -1; sign <= 1; sign += 2) {
                    SmokeCloud.smoke(muzzle, carried.scale(CLOUD_CARRY).add(side.scale(sign * 0.18 * size)),
                            size * 0.6F, 4.0F, 0.85F, -0.1F, 40 + (int) (size * 50.0F), 0.5F, SMOKE);
                }
            }
        }
    }

    private GunSmoke() {
    }
}
