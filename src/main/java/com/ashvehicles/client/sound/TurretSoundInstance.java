package com.ashvehicles.client.sound;

import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * 1両の砲塔・砲架。動いている間だけ鳴る。
 *
 * <p>降着装置（{@link GearSoundInstance}）と同じ形の音だが、1つだけ違う。脚は「動いているか止まっているか」
 * の2値で、途中の速さという物が無い——油圧は全開で作動する。架台にはそれがある。砲手が最後の1度を詰めて
 * いるのか、目標を振り替えるために全速で回しているのか、牽引砲のハンドルを急いで回しているのかは、外から
 * 見れば同じ「動いている」だが、耳には別の音だ。だからここでは効き（{@link GroundVehicleEntity#getSlewEffort()}）
 * が音量と音程の両方になる。
 *
 * <p><b>音程を動かすのはごまかしではない。</b>録音の中身は歯車の噛み合いであり、噛み合う速さは架台の速さ
 * そのものだ。速く回せば噛む間隔は詰まる。再生速度を上げることが、まさにそれを起こす。牽引砲のハンドルで
 * 最も分かりやすい——手を速く回せばカチカチが詰まる——ので、動力の架台より広い幅を与えてある必要は無く、
 * 同じ幅で両方が正しく鳴る。
 *
 * <p>止まったらフェードアウトする。急停止させないのは、脚と同じくループが途中で切られないためだが、もう1つ
 * 理由がある——操縦していない側では砲塔角が報告値へ指数的に寄るので、振り終わりに短い尾が残る。フェード
 * アウトはその尾とちょうど重なり、架台が止まりきる音として聞こえる。
 */
public class TurretSoundInstance extends EntitySoundInstance<GroundVehicleEntity> {
    /**
     * 架台の音が届く距離。エンジンよりはるかに短い。
     *
     * <p>歯車の音であって排気ではない。戦車の砲塔が回る音は、隣に立っていれば嫌でも聞こえるが、
     * 100 ブロック先の丘からは聞こえない。牽引砲のハンドルはさらに小さいが、距離を分ける理由にはしない
     * ——減衰の中で自ずと差が付くほど音量が違う。
     */
    static final double RANGE = 40.0;

    /**
     * これを下回る効きは「止まっている」。全速に対する割合なので、どの架台でも同じ意味になる。
     *
     * <p>0 にしてはいけない。操縦していない側の砲塔角は報告値へ指数的に寄るので、<b>厳密には決して
     * 止まらない</b>——毎tick僅かに動き続ける。0 で判定すると、据え終わった戦車が永久に唸る。
     */
    static final float MOVING = 0.06F;

    /** 距離減衰前、全速で回している時の音量。 */
    private static final float VOLUME = 0.75F;
    /** 効きが上がるとき、毎tick差のどれだけを埋めるか。架台の立ち上がりより速くしない。 */
    private static final float RISE_RATE = 0.5F;
    /** 止まった後、毎tick残りから削る割合。 */
    private static final float FADE_RATE = 0.3F;
    /** チャンネルを返すまでの無音tick数。 */
    private static final int SILENT_TICKS_BEFORE_STOP = 10;

    /** 動き出しと止まりの音程。1.0 が録音そのままで、全速がそこより上に来る。 */
    private static final float NOTE_MIN = 0.8F;
    private static final float NOTE_MAX = 1.15F;

    /** 今鳴らしている効き。実測値をそのまま使わず追従させる。 */
    private float effort;

    public TurretSoundInstance(GroundVehicleEntity vehicle, SoundEvent sound) {
        super(vehicle, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
    }

    @Override
    protected void update() {
        float demand = this.entity().getSlewEffort();
        boolean turning = demand >= MOVING;

        // 上がるときと下がるときで速さが違う。上がるのは架台が本当に加速している間だけなので実測に
        // 付いていけばよく、下がるのは「止まった」の一言なので、こちらが尾を作る。
        this.effort = turning
                ? approach(this.effort, demand, RISE_RATE)
                : approach(this.effort, 0.0F, FADE_RATE);

        float falloff = this.falloff(RANGE);

        this.volume = VOLUME * this.effort * falloff;
        this.note = Mth.lerp(this.effort, NOTE_MIN, NOTE_MAX);

        this.heard(turning ? falloff > 0.0F : this.effort > SILENCE);
    }
}
