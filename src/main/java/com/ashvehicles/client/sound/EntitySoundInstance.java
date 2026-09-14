package com.ashvehicles.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 動く物に属し、その物が音の元になる動作をやめたら終わる音。
 *
 * <p>MOD がループさせる物——エンジン、ロケットモーター、落下する爆弾を過ぎる風、作動中の降着装置——はこの点で全て
 * 同じ音だ。エンティティに追従し、そのエンティティの動作次第で大きくも小さくもなり、そして自ら終わらねばならない。
 * いつ終わるべきか他に知る者がいないからだ。
 *
 * <p>距離はサウンドエンジンに任せずここで計算する。エンジンは {@code max(volume, 1) * 16} ブロック——せいぜい64
 * ブロック——で音量を0まで落とすが、機体はそれよりはるかに遠くまで聞こえるし、ミサイルは64ブロックを2秒で横切る。
 * よってこれらは減衰を切って再生し、位置はエンティティの実位置にして方向を正しく保ち、減衰はこちら側が選んだ到達
 * 距離に対して音量へ織り込む。
 *
 * <p>自ら終わることも同じくらい重要だ。音はチャンネルでありチャンネルは少ないので、言うことの無くなった音——可聴
 * 範囲外、あるいはフェードアウト済み——はチャンネルを返す。エンティティの残りの生涯を無音でループし続けたりしない。
 * エンティティが可聴範囲へ戻れば {@link LiveSounds} が別の音を開始する。
 */
public abstract class EntitySoundInstance<T extends Entity> extends AbstractTickableSoundInstance {
    /** これを下回ったフェードアウトは完了と見なす。 */
    protected static final float SILENCE = 0.004F;

    /**
     * 過去の位置を何 tick 分覚えておくか。
     *
     * <p>120 tick ＝ 6 秒 ＝ 音速で約 2000 ブロック。どの機体の {@code sound.range} よりも遠いので、
     * 記録が足りずに遅延が頭打ちになることは無い。
     */
    private static final int TRAIL_TICKS = 120;

    /** ループ音が距離で失う鋭さ。破裂音より控えめだ。{@link #air} 参照。 */
    private static final float DULLING = 0.16F;

    /** ドップラーの追従。1tickで全部追わない。{@link #air} 参照。 */
    private static final float DOPPLER_RATE = 0.25F;

    private final T entity;
    /** チャンネルを手放すまでに、言うことが無い状態が続くべき長さ。 */
    private final int quietTicksBeforeStop;
    private int quietTicks;

    /**
     * 録音本来の速さ。空気の効果はこの上に掛かる。
     *
     * <p>{@link #pitch} を直接書かないこと。あちらは毎tick「素の速さ×空気」で置き直される場所で、
     * ピッチを設定しない音（脚のモーターなど）でも掛け算が積み重ならないのはそのためだ。
     */
    protected float note = 1.0F;

    /**
     * エンティティが過去 {@link #TRAIL_TICKS} tick に居た場所。新しい順ではなく、書き込み位置を回す輪。
     *
     * <p><b>これが「遠距離の飛行音」の本体だ。</b>2 km 先を飛ぶジェットの音は、今その機体が居る場所
     * からは届かない。6 秒前に居た場所から出た音が今ちょうど耳に着いている。だから遠くの機体は音が
     * 後ろに残り、見上げると機影は音より先へ行っている——高高度の機体を見上げたときに誰もが知っている
     * あの体験は、この 1 つのずれから出ている。
     *
     * <p>近くでは差が消える。100 ブロックなら 6 tick、0.3 秒であり、聞き分けられない。だから同じ式が
     * 近距離では今まで通りに振る舞う。
     */
    private final Vec3[] trail = new Vec3[TRAIL_TICKS];
    /** 次に書き込む輪の位置。 */
    private int mark;
    /** 輪に実際に入っている数。エンティティが現れた直後は満ちていない。 */
    private int filled;

    /** 前の tick に、音が出た場所がどれだけ離れていたか。ドップラーはこの差から出る。 */
    private double lastRange = -1.0;
    /** 今掛かっているドップラー。目標へ追従させるので、遅延の刻みで跳ねない。 */
    private float doppler = 1.0F;

    protected EntitySoundInstance(T entity, SoundEvent sound, SoundSource source, int quietTicksBeforeStop) {
        super(sound, source, SoundInstance.createUnseededRandom());
        this.entity = entity;
        this.quietTicksBeforeStop = quietTicksBeforeStop;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.0F;
        this.remember(entity.position());
        this.follow();
    }

    /** 聞き手からこの距離で全音量のうちどれだけ残るか（0〜1）。 */
    public static float falloff(Entity entity, double range) {
        return falloffAt(listener().distanceTo(entity.position()), range);
    }

    /** 同じ曲線を、距離だけから。音が出た場所が今の位置でない物のために分けてある。 */
    public static float falloffAt(double distance, double range) {
        return Air.carried(distance, range);
    }

    /** 聞き手の耳の位置。 */
    protected static Vec3 listener() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /** 現在値と目標値の差を一度だけ部分的に埋める。 */
    protected static float approach(float current, float target, float rate) {
        return current + (target - current) * rate;
    }

    protected T entity() {
        return this.entity;
    }

    protected float falloff(double range) {
        return falloff(this.entity, range);
    }

    /**
     * 同じ減衰を、<em>音が出た場所</em>から測って。
     *
     * <p>遠くの物ではこちらでなければならない。音は後ろに残っているのに大きさだけが今の位置に追従すると、
     * 聞こえてくる方向と大きさが別々の物を根拠にすることになる——真上を過ぎたジェットの音が、まだ後ろから
     * 聞こえているのに既に小さくなり始める。
     */
    protected float carried(double range) {
        return Air.carried(listener().distanceTo(new Vec3(this.x, this.y, this.z)), range);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return !this.entity.isSilent();
    }

    @Override
    public void tick() {
        // どちらも確認する価値がある。消えたエンティティには追従する物が無いし、別レベルにいるエンティティは
        // どの尺度でもこのクライアントの可聴範囲外だ。
        if (this.entity.isRemoved() || this.entity.level() != Minecraft.getInstance().level) {
            this.stop();

            return;
        }

        this.follow();
        this.update();
        // 素の速さに空気を掛けるのは毎tickここだけ。update() は note を書き、pitch は書かない。
        this.pitch = this.note * this.air();
    }

    /**
     * 音が空気を渡ってくる間に音程へ起きること。ドップラーと、高い成分の吸収。
     *
     * <p>ドップラーは<em>音が出た場所</em>までの距離の変化から取る。速度からではない——こちらで飛ばして
     * いない機体の速度はクライアントでは補間の副産物であり、位置ほど信用できない。距離の差なら常に正しく、
     * 聞き手が自分で動いた分も同じ1つの数に入る。
     *
     * <p>それでも追従させるのは、位置そのものが 1 tick 刻みで届くからだ。生の差をそのままピッチに出すと、
     * 補正パケットのたびに音程が跳ねる。{@link #was} が刻みを均し、ここが残りを均す。
     *
     * <p>そして遠い音は鈍い。空気は高い周波数から先に吸うので、同じジェットでも真上と2km先では音の
     * 中身が違う。{@link BlastSounds} と発砲音が同じことを、もっと強く掛けている——あちらは破裂音で、
     * 失う物が最初から高い側に偏っているからだ。
     */
    private float air() {
        Vec3 from = new Vec3(this.x, this.y, this.z);
        double range = listener().distanceTo(from);

        if (this.lastRange >= 0.0) {
            this.doppler = approach(this.doppler, Air.doppler(range - this.lastRange), DOPPLER_RATE);
        }

        this.lastRange = range;

        return this.doppler * Air.dulled(range, DULLING);
    }

    /** この音の1tick分。{@link #volume} と {@link #pitch} を設定し、{@link #heard} を答える。 */
    protected abstract void update();

    /** このtickに聞くべき物があったか。無い状態が続けば音は終わる。 */
    protected void heard(boolean audible) {
        this.quietTicks = audible ? 0 : this.quietTicks + 1;

        if (this.quietTicks > this.quietTicksBeforeStop) {
            this.stop();
        }
    }

    private void follow() {
        Vec3 from = this.source();

        this.x = from.x;
        this.y = from.y;
        this.z = from.z;
        // 今の位置は、次の tick 以降に届く音の出所として控える。follow の後なので、この tick の音は
        // 1 つ前までの記録から出ている。
        this.remember(this.entity.position());
    }

    /**
     * 今このクライアントへ届いている音が出た場所。
     *
     * <p>遅れは「聞き手までの距離 ÷ 音速」で、その距離は<em>音が出た場所</em>までのものだから、本当は
     * 解くべき方程式がある。だが 1 回だけ近似すれば十分だ——今の位置までの距離で遅れを見積もり、その
     * 分だけ遡った位置で測り直す。機体の速度は音速の 1/10 以下なので、2 回目の誤差は 1 tick に満たない。
     */
    protected Vec3 source() {
        Vec3 now = this.entity.position();

        if (this.filled <= 1) {
            return now;
        }

        Vec3 ear = listener();
        Vec3 guess = this.was(ear.distanceTo(now));

        return this.was(ear.distanceTo(guess));
    }

    /**
     * 聞き手からその距離だけ離れた音が出た、と見なせる過去の位置。
     *
     * <p><b>tick の刻みでは取らない。</b>遅れは連続量で、距離が音速の倍数を跨ぐたびに1 tick 分——ジェット
     * なら数ブロック——飛ぶ。その飛びは位置としては見えないが、<em>距離の変化</em>としては見える。そして
     * ドップラーは距離の変化から出るので、刻みのままだと遠くの機体が数 tick おきに音程を揺らす。2つの記録の
     * 間を取れば、音の出所も、その速さも滑らかに動く。
     */
    private Vec3 was(double distance) {
        double back = Mth.clamp(distance / Air.SPEED, 0.0, this.filled - 1.0);
        int older = (int) back;

        return this.at(older).lerp(this.at(older + 1), back - older);
    }

    /** 直前に書いた位置から {@code back} tick だけ遡った記録。 */
    private Vec3 at(int back) {
        // mark は次に書く場所なので、直前に書いたのは mark-1。そこから back だけ遡る。
        int index = Math.floorMod(this.mark - 1 - Math.min(back, this.filled - 1), TRAIL_TICKS);
        Vec3 there = this.trail[index];

        return there == null ? this.entity.position() : there;
    }

    /** 今の位置を輪へ書き込む。 */
    private void remember(Vec3 at) {
        this.trail[this.mark] = at;
        this.mark = (this.mark + 1) % TRAIL_TICKS;
        this.filled = Math.min(this.filled + 1, TRAIL_TICKS);
    }
}
