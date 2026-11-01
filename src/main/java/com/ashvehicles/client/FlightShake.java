package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 固定翼機の一人称で、飛び方から視界が揺れる。
 *
 * <p>揺れの元は4つで、どれも機体が既に持っている数から読む。高 G の旋回で翼が気流を剥がし始める振動
 * （バフェット）、迎角が失速角に近づいたときの同じ振動、最高速度に近い高速域の細かい震え、そして滑走路を
 * 転がる車輪の突き上げ。それぞれを 0〜1 に揃え、一番強い物を主にして他を少し足す。全部を足すと、普通の
 * 急旋回が失速より揺れる。
 *
 * <p>{@link BlastShake} と同じく、揺れるのはこのフレームの描画だけで、機体も照準も入力も動かない。ヘリは
 * 揺れ方が別物（ローターの周期）なので対象外。三人称とポッド・ガンカメラも揺らさない——外から見ている物と
 * 安定化された装置の映像だからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class FlightShake {
    /** バフェットが出始める荷重倍数と、最大になる荷重倍数。 */
    private static final float BUFFET_FROM_G = 4.5F;
    private static final float BUFFET_FULL_G = 9.0F;
    /** 失速角に対する割合で、バフェットが出始める迎角。 */
    private static final float STALL_BUFFET_FROM = 0.7F;
    /** 最高速度に対する割合で、高速の震えが出始める速さ。 */
    private static final float FAST_FROM = 0.8F;
    /** 地上滑走で揺れが最大になる速さ（ブロック/tick）。 */
    private static final float ROLL_FULL_SPEED = 2.0F;

    /** 主でない揺れの元を足す割合。 */
    private static final float OTHERS_SHARE = 0.35F;

    /** 揺れ 1 での片側への振れ幅（度）。爆風の {@link BlastShake} より控えめ。 */
    private static final float MOST_SWING = 0.5F;
    private static final float ROLL_SHARE = 0.5F;
    /** 地上滑走は上下に突き上げる。上下だけ強め、左右は弱め。 */
    private static final float GROUND_PITCH_SHARE = 1.4F;

    /** 目標へ寄る割合（毎tick）。急に入って急に抜けると段差に見える。 */
    private static final float FOLLOW = 0.25F;
    private static final float SPENT = 0.003F;

    /** 揺れの速さ（ラジアン/tick）。バフェットは細かく速く、滑走は遅く大きい。 */
    private static final float RATTLE_YAW = 2.9F;
    private static final float RATTLE_PITCH = 3.7F;
    private static final float RATTLE_ROLL = 2.3F;
    private static final float BUMP_RATE = 1.1F;
    private static final float SECOND_RATE = 2.7F;
    private static final float SECOND_SHARE = 0.4F;
    /** 位相を巻き戻す周期。float の精度が落ちて揺れが階段状になる前に戻す。 */
    private static final int WRAP = 20 * 60 * 10;

    /** 空中の振動と地上の突き上げ。0で静止、1で最大。 */
    private static float air;
    private static float ground;
    private static int since;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        float airTarget = 0.0F;
        float groundTarget = 0.0F;

        if (!minecraft.isPaused() && minecraft.player != null
                && minecraft.player.getVehicle() instanceof AircraftEntity aircraft
                && !aircraft.getStats().isHelicopter() && !aircraft.isWrecked()) {
            Vec3 velocity = aircraft.getDeltaMovement();
            float speed = (float) velocity.length();

            if (aircraft.onGround()) {
                groundTarget = Mth.clamp(speed / ROLL_FULL_SPEED, 0.0F, 1.0F);
            } else {
                float g = Math.abs(aircraft.getLoadFactor(velocity));
                float load = ramp(g, BUFFET_FROM_G, BUFFET_FULL_G);

                float stallAngle = aircraft.getStats().wing().stallAngle();
                float stall = stallAngle <= 0.0F ? 0.0F
                        : ramp(Math.abs(aircraft.getAngleOfAttack()) / stallAngle, STALL_BUFFET_FROM, 1.0F);
                if (aircraft.isStalled()) {
                    stall = 1.0F;
                }

                float maxSpeed = aircraft.getMaxSpeed();
                float fast = maxSpeed <= 0.0F ? 0.0F : ramp(speed / maxSpeed, FAST_FROM, 1.05F) * 0.5F;

                float strongest = Math.max(load, Math.max(stall, fast));
                float rest = load + stall + fast - strongest;
                airTarget = Math.min(strongest + rest * OTHERS_SHARE, 1.0F);
            }
        }

        air += (airTarget - air) * FOLLOW;
        ground += (groundTarget - ground) * FOLLOW;

        if (air < SPENT && airTarget == 0.0F) {
            air = 0.0F;
        }
        if (ground < SPENT && groundTarget == 0.0F) {
            ground = 0.0F;
        }

        since = air > 0.0F || ground > 0.0F ? (since + 1) % WRAP : 0;
    }

    /**
     * このフレームのカメラ角度に揺れを足す。機体のカメラハンドラが角度を代入した後に走るよう優先度は最低。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (air <= 0.0F && ground <= 0.0F) {
            return;
        }

        Camera camera = event.getCamera();

        if (camera.isDetached() || PodCamera.isShowing()
                || !(camera.getEntity() != null && camera.getEntity().getVehicle() instanceof AircraftEntity aircraft)
                || GunCamera.stationOf(aircraft, camera.getEntity()) != com.ashvehicles.weapon.GunStations.NONE) {
            return;
        }

        float phase = since + (float) event.getPartialTick();
        float buzz = air * MOST_SWING;
        float bump = ground * MOST_SWING;

        event.setYaw(event.getYaw()
                + buzz * rattle(phase, RATTLE_YAW, 0.0F)
                + bump * 0.4F * rattle(phase, BUMP_RATE * 0.8F, 2.1F));
        event.setPitch(event.getPitch()
                + buzz * rattle(phase, RATTLE_PITCH, 1.7F)
                + bump * GROUND_PITCH_SHARE * rattle(phase, BUMP_RATE, 0.4F));
        event.setRoll(event.getRoll()
                + buzz * ROLL_SHARE * rattle(phase, RATTLE_ROLL, 0.9F)
                + bump * ROLL_SHARE * rattle(phase, BUMP_RATE * 0.7F, 1.3F));
    }

    private static float ramp(float value, float from, float full) {
        return Mth.clamp((value - from) / (full - from), 0.0F, 1.0F);
    }

    /** 速さの違う正弦波2本。{@link BlastShake} と同じ形。 */
    private static float rattle(float phase, float rate, float offset) {
        return Mth.sin(phase * rate + offset) * (1.0F - SECOND_SHARE)
                + Mth.sin(phase * rate * SECOND_RATE + offset) * SECOND_SHARE;
    }

    private FlightShake() {
    }
}
