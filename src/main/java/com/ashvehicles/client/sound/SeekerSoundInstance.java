package com.ashvehicles.client.sound;

import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 乗員自身のシーカーが、何かを捉えている間ずっと鳴らす音。
 *
 * <p>{@link WarningSoundInstance} の双子で、向きが逆だ。あちらは「誰かが<em>あなた</em>を捉えた」と告げる座席後ろ
 * の箱、こちらは「<em>自分</em>が何を見ているか」を告げるレール上のミサイル。どちらもワールド上の物ではなく計器
 * なので、どちらもコックピットへ平坦に流す——定位も減衰も距離低下も無し。
 *
 * <p><b>ロックが閉じるにつれてピッチが上がる</b>。それが捜索トーンの要点の全てだ。計器面の枠はシーカーの進行度を
 * 示して締まっていくが、計器ではなく目標を見ているパイロットにはそれは無価値だ——だからトーンが同じことを耳へ伝え
 * る。シーカーが何かを捉えた瞬間に低く始まり、ロック成立とちょうど同時に最高音へ達し、そこでこのインスタンスは
 * 本来のロックトーンへ道を譲る。
 *
 * <p>指示を待たず自ら終わるのは、警戒受信機と同じ理由だ。目標が消えた後も鳴り続けるうなりは、うなりが無いより悪い。
 * 毎tickシーカーへ、開始理由と同じことをまだ言っているか——同じ機体、同じ兵装、同じ段階か——を問い、3つのどれかが
 * 変わった瞬間にチャンネルを返す。
 */
public class SeekerSoundInstance extends AbstractTickableSoundInstance {
    /** このシーカーを持つ機体のエンティティ番号。別の機体へ乗り換えれば別の音になる。 */
    private final int vehicle;
    /** このシーカーを持つ兵装。別の兵装を選べばそちらの録音になる。 */
    @Nullable
    private final ResourceLocation weapon;
    private final SeekerSounds.Stage stage;
    /** シーカーが捉えた瞬間の音程と、ロックまでにどれだけ上がるか。 */
    private final float base;
    private final float climb;

    public SeekerSoundInstance(SoundEvent sound, SeekerSounds.Readout readout, float base, float climb,
            float volume) {
        super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.vehicle = readout.vehicle();
        this.weapon = readout.weapon();
        this.stage = readout.stage();
        this.base = base;
        this.climb = climb;
        // シーカーが捉え続けている間に録音が尽きたら頭から回す。
        this.looping = true;
        this.delay = 0;
        this.volume = volume;
        this.pitch = base + climb * readout.progress();
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /** シーカーの2つの声のどちらか。もう一方が引き継げるようにするため。 */
    public SeekerSounds.Stage stage() {
        return this.stage;
    }

    /** この読み値が、音を開始した理由と今も同じなら true。 */
    public boolean matches(@Nullable SeekerSounds.Readout readout) {
        return readout != null && readout.vehicle() == this.vehicle && readout.stage() == this.stage
                && Objects.equals(readout.weapon(), this.weapon);
    }

    @Override
    public void tick() {
        SeekerSounds.Readout now = SeekerSounds.readout();

        if (!this.matches(now)) {
            this.stop();

            return;
        }

        this.pitch = this.base + this.climb * now.progress();
    }
}
