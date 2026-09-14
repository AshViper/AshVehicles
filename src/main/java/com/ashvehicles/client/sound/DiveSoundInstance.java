package com.ashvehicles.client.sound;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 1機のサイレン。降りている間だけ鳴る。
 *
 * <p><b>回しているのは機体ではなく気流だ。</b> だから条件も音程も、スロットルでも姿勢でもなく<em>飛行経路</em>
 * から作る——サイレンが問うのは「機首がどこを向いているか」ではなく「どれだけ速く落ちているか」である。
 * 機首を下げたまま水平に流している機体は回さないし、失速して尻から落ちている機体は回す。
 *
 * <p>音程は速度そのもの。風車の回転数は対気速度に比例し、サイレンの音程は回転数に比例するので、
 * <b>間に何も置かずに対気速度をそのまま音程にできる</b>のがこの装置の性質だ。急降下の終わりに向かって
 * 音が上がっていくのはそれで、機体が引き起こせば下がる。
 *
 * <p>音量は降下の深さで開く。真下へ落ちる機体で全開、浅い降下では絞る。回転数ではなく<em>装置に当たる
 * 気流の向き</em>の話であり、そして「急降下している」と「降下している」を耳で分けるのがこの音の仕事だ。
 */
public class DiveSoundInstance extends EntitySoundInstance<AircraftEntity> {
    /**
     * サイレンが届く距離。
     *
     * <p>エンジンより広く取ってある。この音が実在した理由は、狙われている者にそれを知らせることだった
     * ——上空の機体そのものより先に届かなければ、装置として何もしていないことになる。
     */
    static final double RANGE = 768.0;

    /** ここより浅い降下では回らない（下向き成分の割合。0.5 は経路角 30 度）。 */
    private static final double SHALLOWEST = 0.5;
    /** ここまで深ければ全開（0.94 は経路角 70 度）。 */
    private static final double STEEPEST = 0.94;
    /** これ未満の速度では風車が回らない（ブロック/tick。2.0 で 144 km/h）。 */
    private static final double SLOWEST = 2.0;
    /** 音程が上限に達する速度。Ju 87 の急降下制限 600 km/h。 */
    private static final double FASTEST = 8.33;

    /** 最も遅い回転での再生速度。 */
    private static final float PITCH_LOW = 0.7F;
    /** 最も速い回転での再生速度。 */
    private static final float PITCH_HIGH = 1.8F;

    /** 距離減衰前の音量。 */
    private static final float VOLUME = 0.9F;
    /**
     * 毎tick、目標の回転へ詰める割合。
     *
     * <p>風車には慣性があり、突っ込んだ瞬間に全開では鳴らないし、引き起こした瞬間に止まりもしない。
     * 立ち上がりに半秒ほど掛かるこの値が、降下の入りで音が「立ち上がる」感じを作っている。
     */
    private static final float SPOOL_RATE = 0.12F;
    /** チャンネルを返すまでの無音tick数。 */
    private static final int SILENT_TICKS_BEFORE_STOP = 20;

    private float gain;

    public DiveSoundInstance(AircraftEntity aircraft, SoundEvent sound) {
        super(aircraft, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        this.note = PITCH_LOW;
        this.pitch = PITCH_LOW;
    }

    /**
     * その機体が今サイレンを回すだけ降りているか。
     *
     * <p>{@link DiveSounds} が鳴らし始める判定に使い、こちらが鳴らし続ける判定に使う。同じ問いなので
     * 同じ場所にある。
     */
    static boolean diving(AircraftEntity aircraft) {
        return dive(aircraft) > 0.0;
    }

    /** 降下の深さ。0 なら急降下ではなく、1 なら真下。 */
    private static double dive(AircraftEntity aircraft) {
        if (aircraft.isRemoved() || aircraft.onGround()) {
            return 0.0;
        }

        Vec3 flight = aircraft.getDeltaMovement();
        double speed = flight.length();

        if (speed < SLOWEST) {
            return 0.0;
        }

        // 経路の下向き成分。姿勢ではない——サイレンに当たるのは機首が向いている方向の空気ではなく、
        // 機体が実際に進んでいる方向の空気だ。
        double down = -flight.y / speed;

        return Mth.clamp((down - SHALLOWEST) / (STEEPEST - SHALLOWEST), 0.0, 1.0);
    }

    @Override
    protected void update() {
        AircraftEntity aircraft = this.entity();
        double dive = dive(aircraft);

        this.gain = approach(this.gain, (float) dive, SPOOL_RATE);

        // 音程は速度だけから。降下が浅くなって音量が絞られていく間も、風車は同じ速さで回っている。
        double speed = aircraft.getDeltaMovement().length();
        float spin = (float) Mth.clamp((speed - SLOWEST) / (FASTEST - SLOWEST), 0.0, 1.0);

        this.note = Mth.lerp(spin, PITCH_LOW, PITCH_HIGH);

        float falloff = this.falloff(RANGE);
        this.volume = VOLUME * this.gain * falloff;

        this.heard(dive > 0.0 ? falloff > 0.0F : this.gain > SILENCE);
    }
}
