package com.ashvehicles.client.sound;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * このクライアントが聞く、1台の車両のエンジン音。
 *
 * <p>録音1本をループし、スロットルが上がるにつれて大きく速く鳴らす。録音は開始時に {@link EngineSounds} が
 * その車両向けに解決した物で、自前の録音を持たない車両には MOD の既定が含まれる。
 *
 * <p>音量もピッチも目標値へ飛ばず追従するので、スロットルを開けばスイッチを入れた音ではなくエンジンがスプール
 * する音になり、閉じれば音が消えていく。
 *
 * <p>距離とチャンネル返却は {@link EntitySoundInstance} の担当だが、到達距離はサウンドエンジンが知りえない、
 * この車両のファイル由来の値を使う。
 */
public class EngineSoundInstance extends EntitySoundInstance<VehicleEntityBase> {
    /** 毎tick、現在音量と目標音量の差をどれだけ埋めるか。 */
    private static final float VOLUME_RATE = 0.12F;
    /** ピッチ用の同じ値。より遅い。タービンのスプールには時間がかかる。 */
    private static final float PITCH_RATE = 0.05F;
    /** チャンネルを返すまでの無音tick数。 */
    private static final int SILENT_TICKS_BEFORE_STOP = 60;
    /**
     * アフターバーナー全開が、ファイルのピッチ範囲の上限にさらに上乗せする分。
     *
     * <p>範囲に畳み込まず加算する。範囲はエンジンにできることを表すが、これはエンジンではないからだ。バーナーが
     * 点火する時点でレバーは既にストッパーに当たっている。タービンはそれ以上速く回らないし、録音がそう示唆しても
     * ならない。変わるのは背後に2つ目の火が加わったことで、その音は同じ音により硬い縁が付いた物になる。
     */
    private static final float AFTERBURNER_PITCH = 0.18F;
    /** そして音量への上乗せ分。外から聞こえるのはこちらの半分だ。 */
    private static final float AFTERBURNER_GAIN = 0.35F;

    /** 距離を考慮する前の音量。ファイルの volume に対する 0〜1。 */
    private float gain;
    private float currentPitch;

    public EngineSoundInstance(VehicleEntityBase vehicle, SoundEvent sound) {
        super(vehicle, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        // 正しい音から始める。全開状態で可聴範囲に入ってきた機体が、まずアイドルから上がっていくのはおかしい。
        this.currentPitch = targetPitch(vehicle, vehicle.soundSetup());
        this.pitch = this.currentPitch;
    }

    /**
     * エンジンが回っているか。地上での暖機か、空中での任意の設定か。
     *
     * <p>スロットルではなく {@code getEngineNote} から読む。ヘリのローターが回り始めた瞬間から音が出るようにする
     * ためだ。その時点でコレクティブはまだ下限にあり、数秒はそのままだが、始動の間ずっと無音の機体の隣に立つのは
     * 妙な体験だろう。
     *
     * <p>残骸はそのどちらを問うより先に処理する。残骸のエンジン音は既に0だが、落下中の全損機は動いており、
     * 動きだけで音を保持し続けるには十分だ——つまり焼けた機体が地面に着くまで聞こえ続けてしまう。
     */
    public static boolean isEngineRunning(VehicleEntityBase vehicle) {
        return !vehicle.isRemoved() && !vehicle.isWrecked()
                && (vehicle.getEngineNote() > 0.001F || vehicle.getVelocity().lengthSqr() > 0.01);
    }

    /** この距離で全音量のうちどれだけ残るか。車両ファイルが指定する到達距離を基準にする。 */
    public static float falloff(VehicleEntityBase vehicle, VehicleChassis.Sound setup) {
        return falloff(vehicle, setup.range());
    }

    private static float targetPitch(VehicleEntityBase vehicle, VehicleChassis.Sound setup) {
        return Mth.lerp(vehicle.getEngineNote(), setup.pitchMin(), setup.pitchMax())
                + vehicle.getAfterburner() * AFTERBURNER_PITCH;
    }

    @Override
    protected void update() {
        VehicleEntityBase vehicle = this.entity();
        VehicleChassis.Sound setup = vehicle.soundSetup();

        boolean running = isEngineRunning(vehicle);
        // アフターバーナー時は意図的に1を超える。この値はレベルではなくファイルの volume に対する倍率であり、
        // バーナーは取り付け元のエンジンより本当に大きい——より遠くから聞こえるほど大きく、1超の音量が買うのは
        // まさにそれだ。
        float targetGain = running
                ? Mth.lerp(vehicle.getEngineNote(), setup.idleVolume(), 1.0F)
                        * (1.0F + vehicle.getAfterburner() * AFTERBURNER_GAIN)
                : 0.0F;
        this.gain = approach(this.gain, targetGain, VOLUME_RATE);
        this.currentPitch = approach(this.currentPitch, targetPitch(vehicle, setup), PITCH_RATE);

        float falloff = this.falloff(setup.range());
        this.volume = setup.volume() * this.gain * falloff;
        this.pitch = this.currentPitch;

        // エンジンが止まりフェードアウトした後、あるいは車両が可聴範囲を出た後は聞くべき物が無い。状況が変われば
        // EngineSounds が別の音を開始する。
        this.heard(running ? falloff > 0.0F : this.gain > SILENCE);
    }
}
