package com.ashvehicles.client.sound;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 遠くで一度だけ鳴った物が、空を渡って耳に着くまで。
 *
 * <p>砲声、発射、バーナーの点火——どれもその場では一瞬の出来事だが、聞き手が数百ブロック離れていれば、
 * 起きたことと聞こえることの間に秒単位の時間がある。爆発は元からそう鳴っていた（{@link BlastSounds}）
 * のに他の一発物は即座に鳴っていたので、遠くの戦車は「撃つ音がして、少し経ってから着弾の音がする」では
 * なく「発砲と同時に音がして、着弾だけが遅れる」という、どちらとも違う聞こえ方をしていた。同じ空気を
 * 渡ってくる以上、全部が同じだけ遅れる。
 *
 * <p><b>大きさは着いた瞬間に測る。</b>音が旅している間ずっと聞き手は飛んでいた——機体では1秒が100
 * ブロックだ。送信時の距離で決めた音量は、着く頃には誰の距離でもない。
 *
 * <p>そして音速より速い物では、順序そのものが入れ替わる。超音速弾の通過音（{@link BulletSounds}）は
 * 弾が脇を通った瞬間にその場で鳴るので、遠くの砲では<b>破裂音が先に来て、砲声が後から追い付く</b>。
 * 順序を組み立てているコードはどこにも無い。同じ空気に同じ規則を掛けた結果として出てくる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class Arrivals {
    /** これ以上は保持しない。その頃には聞き手がまったく別の場所にいるかもしれないからだ。 */
    private static final int LONGEST_WAIT = 200;

    private static final List<Pending> WAITING = new ArrayList<>();

    /**
     * 一発物を、そこからここまで音が渡ってから鳴らす。
     *
     * <p>まだ着かないなら {@code null} を返して自分で預かる。呼び出し元が {@code PlaySoundEvent} の中に
     * いるので、これは「この音を今は鳴らすな」の意味になる。着いていれば、その距離にふさわしい音を返す。
     *
     * @param at 音が出た場所
     * @param gain 距離0での大きさ
     * @param pitch 同じく、空気に吸われる前の速さ
     * @param carry 聞こえなくなる距離（ブロック）
     * @param dulling 全部吸われた所で失う鋭さの割合。{@link Air#dulled} 参照
     */
    @Nullable
    public static SimpleSoundInstance send(ResourceLocation recording, SoundSource source, Vec3 at,
            float gain, float pitch, double carry, float dulling) {
        int wait = Math.min(Air.travel(earTo(at)), LONGEST_WAIT);

        if (wait > 0) {
            WAITING.add(new Pending(recording, source, at, gain, pitch, carry, dulling, wait));

            return null;
        }

        return played(recording, source, at, gain, pitch, carry, dulling);
    }

    /** 空を渡っている物を1tick進め、着いた物を鳴らす。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (WAITING.isEmpty()) {
            return;
        }

        // ワールドを離れるとき、まだ空にある音も捨てる。前のワールドの砲声が次のワールドで届くのは妙な話だ。
        if (Minecraft.getInstance().level == null) {
            WAITING.clear();

            return;
        }

        // 着いた物を先に集め、鳴らすのは輪から出てから。鳴らす行為そのものが PlaySoundEvent を起こし、
        // その先で誰かがこの列に触りうる——実際、着いた音は自分でもう一度この列へ入ろうとしていた
        // （それが 2026-09-08 のクラッシュだ。ConcurrentModificationException）。今は arrived() が
        // 二度目を止めているが、反復の最中に鳴らさない形にしておけば、次に何かが起きても列は壊れない。
        List<Pending> landed = null;
        Iterator<Pending> each = WAITING.iterator();

        while (each.hasNext()) {
            Pending sound = each.next();

            if (--sound.wait <= 0) {
                each.remove();

                if (landed == null) {
                    landed = new ArrayList<>();
                }

                landed.add(sound);
            }
        }

        if (landed == null) {
            return;
        }

        for (Pending sound : landed) {
            Minecraft.getInstance().getSoundManager().play(played(sound.recording, sound.source,
                    sound.at, sound.gain, sound.pitch, sound.carry, sound.dulling));
        }
    }

    /**
     * この音は既に空を渡り終えた物か。
     *
     * <p><b>二度預かってはならない。</b>{@code SoundManager.play} は {@code PlaySoundEvent} を起こすので、
     * ここが鳴らした音は、それを預けた張本人（{@code WeaponSounds} / {@code AfterburnerSounds}）の前へ
     * もう一度現れる。素通しにすると、着いた音がまた「まだ着いていない」と判断されて列へ戻り、二度と
     * 鳴らないまま列が育つ——そしてその追加は上の反復の最中に起きるので、ゲームが落ちる。
     */
    public static boolean arrived(@Nullable SoundInstance sound) {
        return sound instanceof Arrived;
    }

    /**
     * 音が今この耳に着いた。その瞬間の距離から大きさと鋭さを決める。
     *
     * <p>距離は既に音量とピッチへ織り込んであるので減衰は切る。位置は音が<em>出た</em>場所のままにする
     * ので、方向は正しい——{@code Attenuation.NONE} が止めるのは距離の計算だけで、左右の振り分けは残る。
     */
    private static SimpleSoundInstance played(ResourceLocation recording, SoundSource source, Vec3 at,
            float gain, float pitch, double carry, float dulling) {
        double away = earTo(at);

        return new Arrived(recording, source,
                gain * Air.carried(away, Math.max(carry, 1.0)),
                pitch * Air.dulled(away, dulling), at);
    }

    private static double earTo(Vec3 at) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(at);
    }

    /**
     * 渡り終えた音。{@link SimpleSoundInstance} と中身は同じで、型が「もう預かるな」の印になる。
     *
     * <p>印を旗（静的な真偽値など）で持たない。音響エンジンが実際に再生を始めるのは
     * {@code SoundManager.play} を呼んだその場とは限らないので、旗の寿命はこちらでは決められない。
     * 型なら音そのものに付いて回る。
     */
    private static final class Arrived extends SimpleSoundInstance {
        private Arrived(ResourceLocation recording, SoundSource source, float volume, float pitch, Vec3 at) {
            super(recording, source, volume, pitch, SoundInstance.createUnseededRandom(), false, 0,
                    // 距離は既に音量とピッチへ織り込んであるので減衰は切る。位置は音が出た場所のままな
                    // ので、左右の振り分けは残り、方向は正しい。
                    SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false);
        }
    }

    /** 空を渡っている途中の一発と、残りの飛行tick数。 */
    private static final class Pending {
        private final ResourceLocation recording;
        private final SoundSource source;
        private final Vec3 at;
        private final float gain;
        private final float pitch;
        private final double carry;
        private final float dulling;
        private int wait;

        private Pending(ResourceLocation recording, SoundSource source, Vec3 at, float gain, float pitch,
                double carry, float dulling, int wait) {
            this.recording = recording;
            this.source = source;
            this.at = at;
            this.gain = gain;
            this.pitch = pitch;
            this.carry = carry;
            this.dulling = dulling;
            this.wait = wait;
        }
    }

    private Arrivals() {
    }
}
