package com.ashvehicles.client.sound;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 1機の降着装置。作動している間だけ鳴る。
 *
 * <p>脚は機体で唯一、動いている間だけ音を出す物であり、動く時間も既知だ——機体ファイルの
 * {@code landing_gear.cycle_ticks}——なので、ここで判断すべきは作動中かどうかだけ。1サイクル分の録音1本ではなく
 * ループにしてあるのは、サイクル長が機体ごとに違うのと、途中で気が変わったパイロットが完了を待たず脚を反転
 * させるからだ。
 *
 * <p>レバーが動いた瞬間に全音量で始まる。脚のモーターはスプールしない。急停止せずフェードアウトするので、脚が
 * ロックしたときループが途中で切られない。
 */
public class GearSoundInstance extends EntitySoundInstance<AircraftEntity> {
    /** 脚の音が届く距離。短い。ホイールウェル内のモーターであってエンジンではない。 */
    static final double RANGE = 48.0;

    /** 距離減衰前の音量。 */
    private static final float VOLUME = 0.8F;
    /** 脚が上げ／下げ完了した後、毎tick残り音量から削る割合。 */
    private static final float FADE_RATE = 0.3F;
    /** チャンネルを返すまでの無音tick数。 */
    private static final int SILENT_TICKS_BEFORE_STOP = 10;

    private float gain = 1.0F;

    public GearSoundInstance(AircraftEntity aircraft, SoundEvent sound) {
        super(aircraft, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
    }

    @Override
    protected void update() {
        boolean travelling = !this.entity().isGearSettled();
        this.gain = travelling ? 1.0F : approach(this.gain, 0.0F, FADE_RATE);

        float falloff = this.falloff(RANGE);
        this.volume = VOLUME * this.gain * falloff;

        this.heard(travelling ? falloff > 0.0F : this.gain > SILENCE);
    }
}
