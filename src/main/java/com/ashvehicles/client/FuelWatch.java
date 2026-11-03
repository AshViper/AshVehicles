package com.ashvehicles.client;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * 燃料の減り方と BINGO（帰投燃料）。MFD の ENG 頁と MAP 頁が読む、このクライアントだけの状態。
 *
 * <p>消費率は式から求めず、同期されてくる残量の差分を tick ごとに均して測る。後燃器・アイドル・燃料系の
 * どの式が変わっても、計器は実際に減った量を言う。
 *
 * <p>BINGO は ENG 頁の BNG+/BNG- で 5% ずつ置く。残量がそれを上から下へ切った瞬間に1回鳴り、しばらく全頁の
 * 上に帯を出す。切ったままの間は ENG 頁が出し続ける。
 */
final class FuelWatch {
    static final float BINGO_STEP = 0.05F;
    /** 切った後、全頁に帯を出す時間（tick）。 */
    private static final int BANNER = 100;
    /** 消費率を均す重み。1 tick あたり。約 2 秒で追い付く。 */
    private static final float SMOOTH = 0.025F;
    /** 残量がこれだけ変わらなければ、消費が止まったと読む（tick）。刻みの間隔はアイドルでも数秒。 */
    private static final long STILL = 400L;

    private static float bingo = 0.25F;
    private static int aircraftId = -1;
    private static long lastTick = Long.MIN_VALUE;
    private static float lastFuel;
    /** 1 tick あたりに減る割合。 */
    private static float rate;
    private static boolean below;
    private static long crossedAt = Long.MIN_VALUE;

    private FuelWatch() {
    }

    /** 描画のたびに呼んでよい。1 tick に1回だけ進む。 */
    static void update(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        long now = minecraft.level.getGameTime();
        float fuel = aircraft.getFuelFraction();

        if (aircraft.getId() != aircraftId) {
            aircraftId = aircraft.getId();
            lastTick = now;
            lastFuel = fuel;
            rate = 0.0F;
            below = fuel < bingo;
            crossedAt = Long.MIN_VALUE;

            return;
        }

        if (now == lastTick) {
            return;
        }

        long ticks = now - lastTick;

        // 残量は刻みでしか届かない（VehicleEntityBase.getFuel）。届いていない tick を「減っていない」と
        // 読むと、消費率は刻みの間に0へ落ちて次の刻みで跳ね、航続時間が脈打つ。変わるまで待ち、変わった
        // 時にその間の tick 数で割れば刻みごとの平均になる。長く変わらなければエンジンが止まっている。
        if (fuel == lastFuel && ticks < STILL) {
            return;
        }

        float used = (lastFuel - fuel) / ticks;

        // 給油で増えた分は消費として均さない。
        if (used >= 0.0F) {
            rate += (used - rate) * Math.min(1.0F, SMOOTH * ticks);
        }

        lastTick = now;
        lastFuel = fuel;

        boolean nowBelow = fuel < bingo;

        if (nowBelow && !below) {
            crossedAt = now;
            if (!VoiceWarnings.say(VoiceWarnings.Callout.BINGO)) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.7F, 1.0F));
            }
        }

        below = nowBelow;
    }

    static float bingo() {
        return bingo;
    }

    static void adjustBingo(int steps) {
        bingo = Math.max(0.0F, Math.min(0.9F, Math.round((bingo + steps * BINGO_STEP) * 20.0F) / 20.0F));
        below = lastFuel < bingo;
    }

    static boolean belowBingo() {
        return below;
    }

    /** 切った直後の、全頁に帯を出す間か。 */
    static boolean justCrossed() {
        Minecraft minecraft = Minecraft.getInstance();

        return below && minecraft.level != null && minecraft.level.getGameTime() - crossedAt < BANNER;
    }

    /** 1 秒あたりに減る割合。 */
    static float perSecond() {
        return rate * 20.0F;
    }

    /** 今の減り方であと何秒飛べるか。減っていなければ負。 */
    static float secondsLeft(float fuel) {
        return rate > 1.0E-7F ? fuel / perSecond() : -1.0F;
    }
}
