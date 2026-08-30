package com.ashvehicles.weapon;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 対抗手段の投射機。警戒受信機が鳴き始めた時にパイロットが引く物。
 *
 * <p>2種類を別々に消費する。異なる問いへの答えだからだ——熱源追尾にはフレア、レーダー反射追尾にはチャフ。
 * どちらのレバーが正解かは受信機の言い分が決め、間違えた方は何もしない。
 * {@link com.ashvehicles.client.RadarDisplay} 参照。
 *
 * <p>残数はここではなく機体の同期データにある。計器が専用の通信なしで読めるように。それ以外——次を放出
 * できるのはいつか、どこから出るか、地上でどれだけ早く補充されるか——はサーバーの担当でここにある。
 */
public final class Dispenser {
    /** 投射機の作動音。サーバーが指定するのでこちら側に置く。 */
    public static final ResourceLocation RELEASE_SOUND =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + "decoy");
    public static final float RELEASE_VOLUME = 1.2F;
    public static final float RELEASE_PITCH = 1.0F;

    /** 機体のどれだけ後方・下方から出るか。機体自身の軸で。 */
    private static final Vec3 PORT = new Vec3(0.0, -0.6, -2.0);
    /** 機体の速度をどれだけ受け継ぐか。撃ち出すのではなく放り出す物なので。 */
    private static final double CARRIED = 0.55;
    /** そして射出時にどれだけ散らすか。連続放出が線ではなく雲になるように。 */
    private static final double SCATTER = 0.12;

    private final AircraftEntity aircraft;
    /** 次の1発を放出できるまでの tick 数。 */
    private int cooldown;
    /** 各弾倉へ1発補充するために数えている地上滞在 tick 数。 */
    private int reloading;

    public Dispenser(AircraftEntity aircraft) {
        this.aircraft = aircraft;
    }

    /**
     * 投射機の1tick分。
     *
     * @param flare この tick にパイロットがフレアを要求しているか
     * @param chaff チャフを要求しているか
     */
    public void tick(boolean flare, boolean chaff) {
        if (!(this.aircraft.level() instanceof ServerLevel level)) {
            return;
        }

        if (this.cooldown > 0) {
            this.cooldown--;
        }

        AircraftDefinition.Countermeasures setup = this.aircraft.getStats().countermeasures();

        // 一度に1発、一度に1種類。両方同時に要求した場合はまずフレア、チャフは次の放出になる。2つの
        // 弾倉を同じ1秒に空けたりしない。
        if (this.cooldown <= 0 && (flare || chaff)) {
            boolean sendFlare = flare && this.aircraft.getCountermeasures(true) > 0;
            boolean sendChaff = !sendFlare && chaff && this.aircraft.getCountermeasures(false) > 0;

            if (sendFlare || sendChaff) {
                this.release(level, setup, sendFlare);
                this.cooldown = Math.max(setup.intervalTicks(), 1);
            }
        }

        this.reload(setup);
    }

    /** 1発を機体から離れた場所へ放り出し、近くの全員にそれを知らせる。 */
    private void release(ServerLevel level, AircraftDefinition.Countermeasures setup, boolean flare) {
        RandomSource random = this.aircraft.getRandom();
        Vec3 at = this.aircraft.toWorld(PORT, 1.0F);
        // 機体自身の軸で下後方へ。デコイが常に南へ落ちるのではなく、機体から出る物らしく機体から出る
        // ように。
        Vec3 away = Attitude.toWorld(this.aircraft.getAttitude(), new Vec3(0.0, -1.0, -0.35)).normalize();

        CountermeasureEntity decoy = new CountermeasureEntity(ModEntities.COUNTERMEASURE.get(), level);

        decoy.setFlare(flare);
        decoy.setPos(at);
        decoy.setDeltaMovement(this.aircraft.getVelocity().scale(CARRIED)
                .add(away.scale(setup.speed()))
                .add(random.nextGaussian() * SCATTER, random.nextGaussian() * SCATTER,
                        random.nextGaussian() * SCATTER));

        level.addFreshEntity(decoy);
        this.aircraft.setCountermeasures(flare, this.aircraft.getCountermeasures(flare) - 1);

        level.playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(RELEASE_SOUND), SoundSource.NEUTRAL,
                RELEASE_VOLUME, RELEASE_PITCH);
    }

    /**
     * 機体が駐機している間、一定間隔で各弾倉へ1発ずつ戻す。
     *
     * <p>1発あたりではなく満載1回分の時間として数えるので、全弾使い切った機体は搭載数が10でも60でも
     * {@code reload_ticks} 後に復帰する。
     */
    private void reload(AircraftDefinition.Countermeasures setup) {
        if (!this.aircraft.isParked() || this.aircraft.getThrottle() > 0.0F) {
            this.reloading = 0;

            return;
        }

        int biggest = Math.max(Math.max(setup.flares(), setup.chaff()), 1);
        int perRound = Math.max(setup.reloadTicks() / biggest, 1);

        if (++this.reloading < perRound) {
            return;
        }

        this.reloading = 0;

        for (boolean flare : new boolean[] {true, false}) {
            int carried = this.aircraft.getCountermeasures(flare);

            if (carried < setup.capacity(flare)) {
                this.aircraft.setCountermeasures(flare, carried + 1);
            }
        }
    }
}
