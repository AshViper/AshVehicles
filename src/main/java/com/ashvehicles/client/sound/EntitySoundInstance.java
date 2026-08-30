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

    private final T entity;
    /** チャンネルを手放すまでに、言うことが無い状態が続くべき長さ。 */
    private final int quietTicksBeforeStop;
    private int quietTicks;

    protected EntitySoundInstance(T entity, SoundEvent sound, SoundSource source, int quietTicksBeforeStop) {
        super(sound, source, SoundInstance.createUnseededRandom());
        this.entity = entity;
        this.quietTicksBeforeStop = quietTicksBeforeStop;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0.0F;
        this.follow();
    }

    /** 聞き手からこの距離で全音量のうちどれだけ残るか（0〜1）。 */
    public static float falloff(Entity entity, double range) {
        Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        return (float) Mth.clamp(1.0 - listener.distanceTo(entity.position()) / Math.max(range, 1.0E-3), 0.0, 1.0);
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
        this.x = this.entity.getX();
        this.y = this.entity.getY();
        this.z = this.entity.getZ();
    }
}
