package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 急降下サイレン。機首を下げて速度が乗っている間、機体が出す悲鳴。
 *
 * <p><b>これは翼が生む音ではなく、取り付けられた装置である。</b> Ju 87 の Jericho-Trompete は主脚に付いた
 * 小さな風車が回すサイレンで、急降下でしか回らず、積んでいない機体では急降下しても何も起きない。だから
 * 既定は無音であり、鳴るのは機体ファイルが {@code sound.dive} でサイレンを名指しした機体だけだ——
 * 降着装置（{@link GearSounds}）が全機に既定のフォールバックを持つのと、そこが正反対になる。脚は全機が
 * 持っているが、サイレンはそうではない。
 *
 * <p><b>送信する物は無い。</b> 降下角も対気速度も、どのクライアントも自分で持っている——姿勢は同期され、
 * 速度は操縦していない側でも {@code AircraftEntity.tick} が実測から入れている——ので、鳴らし始めるべき
 * 瞬間はどのクライアントも自分で見つけられる。地上に立っている者に聞こえるのはそのおかげで、そして
 * それがこの音の存在意義でもある。
 *
 * <p><b>どの録音を使うか。</b>機体ファイルの {@code sound.dive}、無ければ機体名のイベント
 * {@code <namespace>:dive.<name>}、無ければ MOD 同梱の {@code ashvehicles:dive.siren}。名指しさえ
 * していれば、どのリソースパックも応えなくても鳴る。
 */
public final class DiveSounds {
    /**
     * サイレンの鳴っていない機体を再確認する間隔。
     *
     * <p>降着装置より短い。脚は2秒かけて動くが、急降下は「入った瞬間」に鳴り始めなければ意味が無い
     * ——サイレンが鳴るのは、降ってくる物に気付かせるためだ。
     */
    private static final int RETRY_TICKS = 2;

    /** このクライアントから見える全機体と、そのサイレン（鳴っている間だけ）。 */
    public static final LiveSounds<AircraftEntity> SOUNDS =
            new LiveSounds<>(AircraftEntity.class, RETRY_TICKS, DiveSounds::start);

    /** 既に警告した要求済み録音。欠落ファイルについてのログを1行に留めるため。 */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    @Nullable
    private static DiveSoundInstance start(AircraftEntity aircraft) {
        if (!DiveSoundInstance.diving(aircraft)
                || EntitySoundInstance.falloff(aircraft, DiveSoundInstance.RANGE) <= 0.0F) {
            return null;
        }

        ResourceLocation recording = diveSound(Minecraft.getInstance().getSoundManager(), aircraft);

        return recording == null
                ? null
                : new DiveSoundInstance(aircraft, SoundEvent.createVariableRangeEvent(recording));
    }

    /**
     * その機体のサイレンの録音。<b>サイレンを積んでいない機体では null</b>——それが大半である。
     *
     * <p>鳴らすたびに解決し直すので、リソースパックの変更は再起動なしで反映される。
     */
    @Nullable
    public static ResourceLocation diveSound(SoundManager sounds, AircraftEntity aircraft) {
        Optional<ResourceLocation> requested = aircraft.getStats().sound().dive();

        // 名指ししていない機体はサイレンを積んでいない。ここが降着装置と違う唯一の行であり、
        // この機能の全部でもある。
        if (requested.isEmpty()) {
            return null;
        }

        if (ModSounds.exists(sounds, requested.get())) {
            return requested.get();
        }

        if (WARNED.add(requested.get())) {
            AshVehicles.LOGGER.warn("Aircraft {} asks for dive siren {} which no resource pack provides; looking for another",
                    aircraft.getAircraftId(), requested.get());
        }

        return ModSounds.firstPresent(sounds,
                ModSounds.named(aircraft.getAircraftId(), ModSounds.DIVE_PREFIX), ModSounds.DIVE);
    }

    private DiveSounds() {
    }
}
