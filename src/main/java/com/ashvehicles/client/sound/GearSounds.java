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
 * 降着装置。脚が上げ／下げ作動中に機体が出す音。
 *
 * <p>これについて送信する物は無い。脚レバーは既に同期されており、格納から展開までのカウントも両側で同じなので、
 * クライアントは脚の作動を自分で見て、その瞬間に音を鳴らし始められる。{@link AircraftEntity#isGearSettled()}
 * 参照。
 *
 * <p><b>どの録音を使うか。</b>機体ファイルが {@code sound.gear} で指定するイベント、無ければ機体名のイベント
 * {@code <namespace>:gear.<name>}、無ければ MOD の {@code ashvehicles:gear.default}。<b>3つとも同梱していない</b>
 * ので、リソースパックがどれも提供しなければ脚は無音になる。ループはループ用に切ってあるか、さもなくば無い方が
 * ましであり、ゲーム内に流用できる録音は無い。1つ用意すればそれで足りる。{@link ModSounds} 参照。
 */
public final class GearSounds {
    /**
     * 脚の音が鳴っていない機体を再確認する間隔。短い。脚の作動は2秒程度で、0.5秒遅れて鳴らし始めれば気付かれる
     * からだ。
     */
    private static final int RETRY_TICKS = 2;

    /** このクライアントから見える全機体と、その降着装置（音を出す間だけ）。 */
    public static final LiveSounds<AircraftEntity> SOUNDS =
            new LiveSounds<>(AircraftEntity.class, RETRY_TICKS, GearSounds::start);

    /** 既に警告した要求済み録音。欠落ファイルについてのログを1行に留めるため。 */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    @Nullable
    private static GearSoundInstance start(AircraftEntity aircraft) {
        if (aircraft.isGearSettled()
                || EntitySoundInstance.falloff(aircraft, GearSoundInstance.RANGE) <= 0.0F) {
            return null;
        }

        ResourceLocation recording = gearSound(Minecraft.getInstance().getSoundManager(), aircraft);

        return recording == null
                ? null
                : new GearSoundInstance(aircraft, SoundEvent.createVariableRangeEvent(recording));
    }

    /**
     * 機体の脚の録音。どのリソースパックも提供しなければ null。音を鳴らすたびに解決し直すので、リソースパックの
     * 変更は再起動なしで反映される。
     */
    @Nullable
    public static ResourceLocation gearSound(SoundManager sounds, AircraftEntity aircraft) {
        Optional<ResourceLocation> requested = aircraft.getStats().sound().gear();

        if (requested.isPresent()) {
            if (ModSounds.exists(sounds, requested.get())) {
                return requested.get();
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Aircraft {} asks for gear sound {} which no resource pack provides; looking for another",
                        aircraft.getAircraftId(), requested.get());
            }
        }

        return ModSounds.firstPresent(sounds,
                ModSounds.named(aircraft.getAircraftId(), ModSounds.GEAR_PREFIX), ModSounds.GEAR);
    }

    private GearSounds() {
    }
}
