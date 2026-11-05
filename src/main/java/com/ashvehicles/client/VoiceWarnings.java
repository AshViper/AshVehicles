package com.ashvehicles.client;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.sound.ModSounds;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 音声警報（Voice Warning System）。コックピットの声が「PULL UP」「STALL」などを読み上げる。
 *
 * <p>警告灯（{@link AircraftHud#caution}）と同じ事柄を、目を使わずに伝える。灯は見ていなければ届かないが、
 * 地面へ向かっているパイロットが見ているのは大抵地面だ。
 *
 * <p><b>声は1本だけ。</b>2つの警報を重ねて喋ると両方聞き取れない。だから深刻な順（{@link Callout} の宣言順）に
 * 並べ、上の物は下の物の発声を切って割り込み、下の物は上の物が喋っている間は黙る。状態の警報（PULL UP・STALL）
 * は続く限り間隔を置いて繰り返し、事象の警報（BINGO・FUEL OUT・MISSILE・LOCK・ENGINE・ENGINE OUT）は起きた瞬間に1回だけ言う。
 *
 * <p>録音は {@code ashvehicles:voice.<name>}。同梱しているのは音声合成の代役で、パックが同じ名前で録音を
 * 差し込めばそちらが鳴る。名前ごと消された声は鳴らさず、呼び出し元が代わりの音を選べる（{@link #say} の戻り値）。
 *
 * <p>パイロットにしか聞こえない。定位もさせない。RWR（{@link com.ashvehicles.client.sound.WarningSounds}）と同じく
 * 席の後ろの箱から出る音だ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class VoiceWarnings {
    /** 読み上げる事柄。宣言順が優先度で、上ほど深刻。 */
    public enum Callout {
        PULL_UP("pull_up", 30),
        STALL("stall", 40),
        MISSILE("missile", 0),
        ENGINE_FIRE("engine_fire", 60),
        ENGINE_OUT("engine_out", 0),
        ENGINE("engine", 0),
        LOCK("lock", 0),
        OVER_G("over_g", 40),
        GEAR("gear", 60),
        ALTITUDE("altitude", 0),
        BANK_ANGLE("bank_angle", 60),
        FUEL_OUT("fuel_out", 0),
        BINGO("bingo", 0);

        final ResourceLocation sound;
        /** 続く間に言い直す間隔（tick）。0 なら1回きりの事象。 */
        final int repeat;

        Callout(String name, int repeat) {
            this.sound = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "voice." + name);
            this.repeat = repeat;
        }
    }

    /** この秒数のうちに飛行経路が地面に届くなら PULL UP。 */
    private static final double PULL_UP_SECONDS = 4.0;
    /** 経路を追う最短の距離。遅い機体でも目の前の丘は見る。 */
    private static final double PULL_UP_MIN_REACH = 24.0;
    /** これより遅い（blocks/tick）と地面は脅威ではなく着陸先。 */
    private static final double PULL_UP_MIN_SPEED = 0.4;
    /** 地形の走査は重いので間引く。 */
    private static final int TERRAIN_EVERY = 2;
    private static final float VOLUME = 0.9F;
    /** HUD の G 表示が赤くなるのと同じ値。 */
    private static final float OVER_G = 7.0F;
    /** 脚を上げたままこの高さ（対地）より下で降りていれば GEAR。 */
    private static final double GEAR_HEIGHT = 25.0;
    /** 対地高度がこれを上から切ったら ALTITUDE を1回。{@link #ALTITUDE_REARM} より上へ戻るまで再び言わない。 */
    private static final double ALTITUDE_HEIGHT = 60.0;
    private static final double ALTITUDE_REARM = 80.0;
    /** これより低いところでこれより深く傾けたら BANK ANGLE。高いところのバンクは操縦であって危険ではない。 */
    private static final double BANK_HEIGHT = 100.0;
    private static final float BANK_LIMIT = 60.0F;
    /** 降下とみなす沈下（blocks/tick）。 */
    private static final double DESCENDING = -0.05;


    @Nullable
    private static Callout speaking;
    @Nullable
    private static SimpleSoundInstance voice;
    /** 状態の警報がそれぞれ次に言い直してよい tick。 */
    private static final long[] nextAt = new long[Callout.values().length];

    private static boolean terrain;
    private static boolean wasStalled;
    private static boolean wasOutOfFuel;
    /** ALTITUDE を言える状態か。高度を切ると落ち、上へ戻ると立つ。 */
    private static boolean altitudeArmed;
    private static float lastEngine;
    @Nullable
    private static Threat.Kind lastThreat;
    private static int aircraftId = -1;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()
                || !(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)
                || aircraft.getControllingPassenger() != minecraft.player) {
            silence(minecraft);
            aircraftId = -1;

            return;
        }

        // 乗り換えた直後に前の機体の状態から「新しく起きた」事象を読まない。
        if (aircraft.getId() != aircraftId) {
            aircraftId = aircraft.getId();
            silence(minecraft);
            terrain = false;
            wasStalled = true;
            wasOutOfFuel = aircraft.isOutOfFuel();
            lastThreat = RadarReadout.worst();
            altitudeArmed = false;
            lastEngine = aircraft.getEngineHealth();
            java.util.Arrays.fill(nextAt, 0L);
        }

        if (speaking != null && !minecraft.getSoundManager().isActive(voice)) {
            speaking = null;
            voice = null;
        }

        long now = minecraft.level.getGameTime();
        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        if (now % TERRAIN_EVERY == 0) {
            terrain = closingOnGround(minecraft, aircraft, velocity, speed);
        }

        Quaternionf attitude = aircraft.getAttitude(1.0F);
        boolean stalled = AircraftHud.stalled(aircraft, attitude, velocity, speed);
        boolean outOfFuel = aircraft.isOutOfFuel();
        Threat.Kind threat = RadarReadout.worst();

        // 状態は続く間繰り返す。失速は始まった瞬間にも間隔を待たずに言う。
        if (terrain) {
            repeat(Callout.PULL_UP, now);
        } else if (stalled) {
            if (!wasStalled) {
                nextAt[Callout.STALL.ordinal()] = 0L;
            }

            repeat(Callout.STALL, now);
        }

        if (Math.abs(aircraft.getLoadFactor(velocity)) > OVER_G) {
            repeat(Callout.OVER_G, now);
        }

        // 対地高度。足元のチャンクが無ければ（NaN）高度の警報は全て黙る——推測した床で鳴らす価値は無い。
        double height = aircraft.getY() - Terrain.surface(minecraft.level, aircraft.position());
        boolean airborne = !aircraft.onGround() && !Double.isNaN(height);
        boolean descending = velocity.y < DESCENDING;

        if (airborne && descending && height < GEAR_HEIGHT && !aircraft.isGearDown() && !aircraft.isHovering()
                && aircraft.getStats().landingGear().retractable()) {
            repeat(Callout.GEAR, now);
        }

        if (airborne && height > ALTITUDE_REARM) {
            altitudeArmed = true;
        } else if (airborne && altitudeArmed && descending && height < ALTITUDE_HEIGHT) {
            altitudeArmed = false;

            if (!aircraft.isGearDown()) {
                say(Callout.ALTITUDE);
            }
        }

        if (airborne && height < BANK_HEIGHT && Math.abs(aircraft.getRoll(1.0F)) > BANK_LIMIT) {
            repeat(Callout.BANK_ANGLE, now);
        }

        if (threat == Threat.Kind.MISSILE && lastThreat != Threat.Kind.MISSILE) {
            say(Callout.MISSILE);
        }

        // ロックはミサイルへ進めばそちらが言うので、ロックが新しく掛かった瞬間だけ。
        if (threat == Threat.Kind.LOCK && lastThreat != Threat.Kind.LOCK && lastThreat != Threat.Kind.MISSILE) {
            say(Callout.LOCK);
        }

        // エンジンの健全度（AircraftEntity.getEngineHealth）。傷みは1回、火は続く限り、停止は1回。
        float engine = aircraft.getEngineHealth();

        if (aircraft.isEngineDead()) {
            if (lastEngine > 0.0F) {
                say(Callout.ENGINE_OUT);
            }
        } else if (engine < AircraftEntity.ENGINE_BURNING) {
            repeat(Callout.ENGINE_FIRE, now);
        } else if (engine < AircraftEntity.ENGINE_DAMAGED && lastEngine >= AircraftEntity.ENGINE_DAMAGED) {
            say(Callout.ENGINE);
        }

        lastEngine = engine;

        if (outOfFuel && !wasOutOfFuel) {
            say(Callout.FUEL_OUT);
        }

        wasStalled = stalled;
        wasOutOfFuel = outOfFuel;
        lastThreat = threat;
    }

    /**
     * 1回読み上げる。より深刻な声が喋っていれば黙り、より軽い声なら切って割り込む。
     *
     * @return 録音があり、鳴らしたか、より深刻な声に譲ったなら true。録音が無ければ false で、呼び出し元は自分の
     *         音を鳴らしてよい
     */
    public static boolean say(Callout callout) {
        Minecraft minecraft = Minecraft.getInstance();
        SoundManager sounds = minecraft.getSoundManager();

        if (!ModSounds.exists(sounds, callout.sound)) {
            return false;
        }

        if (speaking != null && sounds.isActive(voice)) {
            if (speaking.ordinal() <= callout.ordinal()) {
                return true;
            }

            sounds.stop(voice);
        }

        voice = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(callout.sound), 1.0F, VOLUME);
        speaking = callout;
        sounds.play(voice);

        return true;
    }

    private static void repeat(Callout callout, long now) {
        int index = callout.ordinal();

        if (now < nextAt[index]) {
            return;
        }

        // 自分が喋り終わってから数える。割り込まれて黙った分は、次の tick にまた試す。
        if (say(callout) && speaking == callout) {
            nextAt[index] = now + callout.repeat;
        }
    }

    /**
     * 今の速度ベクトルのまま飛ぶと {@link #PULL_UP_SECONDS} 秒以内に地面に当たるか。
     *
     * <p>脚を下ろしていれば地面は着陸先なので黙る（実機の GPWS もモードを落とす）。走査はロード範囲の外では
     * 仮定の床に対して答える（{@link Terrain#along}）ので、遠くの地面にも警報は鳴る。
     */
    private static boolean closingOnGround(Minecraft minecraft, AircraftEntity aircraft, Vec3 velocity,
            double speed) {
        if (speed < PULL_UP_MIN_SPEED || aircraft.onGround() || aircraft.isHovering() || aircraft.isGearDown()) {
            return false;
        }

        double reach = Math.max(PULL_UP_MIN_REACH, speed * 20.0 * PULL_UP_SECONDS);

        return Terrain.along(minecraft.level, aircraft.position(), velocity.scale(1.0 / speed), reach,
                aircraft) != null;
    }

    private static void silence(Minecraft minecraft) {
        if (voice != null) {
            minecraft.getSoundManager().stop(voice);
        }

        voice = null;
        speaking = null;
    }

    private VoiceWarnings() {
    }
}
