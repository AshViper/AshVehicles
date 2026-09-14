package com.ashvehicles.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 目標が終わったことを告げる短い音。
 *
 * <p>撃った本人にだけ、1機につき1度。撃破を伝えるのが文だけでは足りないのは、撃破が起きるのが照準を覗いて
 * いる最中だからだ。読むには目を上げねばならず、目を上げた砲手は次の目標を見失う。音なら覗いたまま届く。
 *
 * <p>どこにも定位させずコックピットへ平坦に流す。これは目標から出ている音ではない——800m 先で装甲が抜ける
 * 音は届かないし、届いたとしても撃破の合図にはならない——ので、乗員の計器が出す音として扱う。
 * {@link WarningSounds} が警報についてやっているのと同じ扱いだ。
 */
public final class KillSounds {
    /**
     * 計器の音。0.7 では他の音に埋もれたので 1.5 倍した。
     *
     * <p><b>ここが実質の上限だ。</b>サウンドエンジンは {@code 要求値 × カテゴリの音量スライダー} を
     * 1.0 で頭打ちにする（{@code SoundEngine.calculateVolume}）ので、スライダーを上げている人には
     * 1.05 も 1.0 も同じ音量になる。これ以上大きくしたければ動かすのは録音そのもの——
     * {@code tool/make_effect_sounds.py} の書き出し峰——であって、この値ではない。
     */
    private static final float VOLUME = 1.05F;

    /** パックがより良い物を持つまでの間、確認音に最も近いゲーム内の音。 */
    private static final ResourceLocation FALLBACK = SoundEvents.NOTE_BLOCK_BELL.value().getLocation();

    private KillSounds() {
    }

    /** 1発鳴らして忘れる。 */
    public static void confirm() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();

        sounds.play(SimpleSoundInstance.forUI(recording(sounds), 1.0F, VOLUME));
    }

    /** この音に使う録音。パックが用意していればそれ、無ければゲーム自身の物。 */
    private static SoundEvent recording(SoundManager sounds) {
        ResourceLocation playing = ModSounds.firstPresent(sounds, ModSounds.KILL);

        return SoundEvent.createVariableRangeEvent(playing == null ? FALLBACK : playing);
    }
}
