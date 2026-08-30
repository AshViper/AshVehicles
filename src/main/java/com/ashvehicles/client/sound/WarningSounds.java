package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 警戒受信機が出す音。警戒受信機の実体はほぼこれだ。
 *
 * <p>{@link com.ashvehicles.client.RadarDisplay} のスコープは脅威の来る方向を示すが、窮地のパイロットはそれを
 * 見ていない。そもそも脅威があると伝えるのは音の方だ。
 *
 * <p><b>受信機が出す音は2種類なので、形も2つ。</b>誰かのレーダーに捕捉されたことは知らせだ。1度起きればあとは
 * その日の状態にすぎないので、短い一鳴りの後は無音になる。ロックされること、撃たれることは事象ではなく状態で
 * あり、その状態が続く間だけ正確に鳴り続ける連続警報になる。{@link WarningSoundInstance} 参照。
 *
 * <p>後者こそ設計の全てであり、理由を書いておく価値がある。録音から警報を作る自明な方法は一定間隔で鳴らし直す
 * ことだが、録音はビープ音ではない。この用途に録られるのは警報そのもの——既にリズムを内包し、ファイル長の間
 * 鳴り続けるトーンだ。それをタイマーで鳴らし直せばコピーが積み重なる。20秒のロックトーンを毎秒2回鳴らし直せば
 * 40本が同時に鳴ることになり、それは警報ではなく騒音の壁だ。だから1本鳴らして流し続ける。
 *
 * <p>パイロット以外には誰にも聞こえない。そもそもレーダー画は他へ送られないからだ。トーンはどこにも定位させず
 * コックピットへ平坦に流す。座席の後ろの箱から出ているのであって、脅かしてくる機体から出ているのではない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WarningSounds {
    /** その警報のために切られた録音を使う場合の再生速度。 */
    private static final float AS_RECORDED = 1.0F;
    /** 別の警報の代役として使う場合の速度。捜索は低く、事態が悪化するほど高くなる。 */
    private static final float SEARCH_PITCH = 0.8F;
    private static final float LOCK_PITCH = 1.0F;
    private static final float MISSILE_PITCH = 1.45F;
    private static final float VOLUME = 0.6F;

    /** パックがより良い物を持つまでの間、計器警報に最も近いゲーム内の音。 */
    private static final ResourceLocation FALLBACK = SoundEvents.NOTE_BLOCK_BIT.value().getLocation();

    /** 受信機が最後に伝えていた内容。変化は鳴らし、繰り返しは鳴らさないため。 */
    @Nullable
    private static Threat.Kind sounding;
    /** 現在鳴っている警報（脅威がそれに値する種類なら）。 */
    @Nullable
    private static WarningSoundInstance alarm;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        // 受信機が目の前にあるのはパイロットだけ。搭乗者には画が一切送られないし、降りた者はもう何も警告されて
        // いない。既に鳴っている警報も自分で同じことに気付いて止まる。
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()
                || !(minecraft.player.getVehicle() instanceof AircraftEntity)) {
            sounding = null;

            return;
        }

        Threat.Kind worst = RadarReadout.worst();

        if (worst == null) {
            sounding = null;

            return;
        }

        boolean changed = sounding != worst;
        sounding = worst;

        // 知らせは1度きり。相手のスコープに載ったことは、始まった時点では知る価値がある。同じ空にいる間ずっと
        // 言われ続ける価値は無い。
        if (worst == Threat.Kind.SEARCH) {
            if (changed) {
                chirp(minecraft, worst);
            }

            return;
        }

        // 状態は保持する。変化時に開始し、サウンドエンジンが落とした場合は再開する——チャンネル満杯、リソース
        // リロード、あるいはその後に戻された音量スライダーのため。
        SoundManager sounds = minecraft.getSoundManager();

        if (changed || alarm == null || alarm.isStopped() || !sounds.isActive(alarm)) {
            alarm = new WarningSoundInstance(recording(sounds, worst), worst, pitch(sounds, worst), VOLUME);
            sounds.play(alarm);
        }
    }

    /** 短い音を1つ鳴らして忘れる。 */
    private static void chirp(Minecraft minecraft, Threat.Kind kind) {
        SoundManager sounds = minecraft.getSoundManager();

        sounds.play(SimpleSoundInstance.forUI(recording(sounds, kind), pitch(sounds, kind), VOLUME));
    }

    /** この警報に使う録音。専用の物があればそれ、無ければ最も近い物。 */
    private static SoundEvent recording(SoundManager sounds, Threat.Kind kind) {
        ResourceLocation playing = ModSounds.firstPresent(sounds, borrowing(kind));

        return SoundEvent.createVariableRangeEvent(playing == null ? FALLBACK : playing);
    }

    /**
     * この警報のために作られた録音は既に正しい音なので、切られたまま再生する。他の警報から借りた物やゲーム自身の
     * 物は、3つのどれの代役かを示すためピッチをずらす。
     */
    private static float pitch(SoundManager sounds, Threat.Kind kind) {
        if (recordingFor(kind).equals(ModSounds.firstPresent(sounds, borrowing(kind)))) {
            return AS_RECORDED;
        }

        return switch (kind) {
            case SEARCH -> SEARCH_PITCH;
            case LOCK -> LOCK_PITCH;
            case MISSILE -> MISSILE_PITCH;
        };
    }

    /** この警報専用に切られた録音。 */
    private static ResourceLocation recordingFor(Threat.Kind kind) {
        return switch (kind) {
            case SEARCH -> ModSounds.RWR_CONTACT;
            case LOCK -> ModSounds.RWR_LOCK;
            case MISSILE -> ModSounds.RWR_MISSILE;
        };
    }

    /**
     * 探す順序。まずこの警報専用の録音、次に他の警報の物を意味の近い順に。1つでも名前を提供したパックは、3種
     * すべてで動く受信機を手に入れる。
     */
    private static ResourceLocation[] borrowing(Threat.Kind kind) {
        return switch (kind) {
            case SEARCH -> new ResourceLocation[] {ModSounds.RWR_CONTACT, ModSounds.RWR_LOCK};
            case LOCK -> new ResourceLocation[] {ModSounds.RWR_LOCK, ModSounds.RWR_CONTACT};
            case MISSILE -> new ResourceLocation[] {
                    ModSounds.RWR_MISSILE, ModSounds.RWR_LOCK, ModSounds.RWR_CONTACT};
        };
    }

    private WarningSounds() {
    }
}
