package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * このクライアントから見える全機体にエンジン音を与え、どの録音を使うかを選ぶ。
 *
 * <p><b>どの録音を使うか。</b>音は普通のリソースパックの音だ。{@code sounds.json} のエントリが {@code .ogg} を
 * 指す。車両には優先順に、ファイルが {@code sound.engine} で指定するイベント、車両名のイベント
 * {@code <namespace>:engine.<name>}、MOD の既定 {@code ashvehicles:engine.default} が与えられる。イベントが有効
 * なのはリソースパックが実際に持ちファイルも存在する場合だけなので、欠落や綴り間違いは無音ではなく既定へ落ちる。
 *
 * <p><b>いつ鳴るか。</b>各機体へ音を生涯結び付けるのではなく、{@link LiveSounds} がリストを保持し、音を持たない
 * 機体があるたびここへ音を求める。音は言うことが無くなれば自ら止まる。おかげで駐機中の機体はコスト0で済み、
 * リソースリロードや音量ミュートで音を失った機体も自ずと取り戻す。
 */
public final class EngineSounds {
    /** 音が鳴っていない機体を再確認する間隔。 */
    private static final int RETRY_TICKS = 10;

    /** このクライアントから見える全機体と、それぞれのエンジン音。 */
    public static final LiveSounds<VehicleEntityBase> SOUNDS =
            new LiveSounds<>(VehicleEntityBase.class, RETRY_TICKS, EngineSounds::start);

    /** 既に警告した要求済みの音。欠落ファイルのログを毎秒1行ではなく計1行に留めるため。 */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    /** 稼働中かつ可聴距離にある機体の音。該当しなければ null。 */
    @Nullable
    private static EngineSoundInstance start(VehicleEntityBase vehicle) {
        if (!EngineSoundInstance.isEngineRunning(vehicle)
                || EngineSoundInstance.falloff(vehicle, vehicle.soundSetup()) <= 0.0F) {
            return null;
        }

        return new EngineSoundInstance(vehicle, engineSound(Minecraft.getInstance().getSoundManager(), vehicle));
    }

    /**
     * 機体のエンジン録音。ファイルが要求する物、無ければ機体名の物、無ければ既定。音を鳴らすたびに解決し直す
     * ので、リソースパックの変更は再起動なしで反映される。
     */
    public static SoundEvent engineSound(SoundManager sounds, VehicleEntityBase vehicle) {
        VehicleChassis.Sound setup = vehicle.soundSetup();
        Optional<ResourceLocation> requested = setup.engine();

        if (requested.isPresent()) {
            if (ModSounds.exists(sounds, requested.get())) {
                return SoundEvent.createVariableRangeEvent(requested.get());
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Machine {} asks for engine sound {} which no resource pack provides; using the default",
                        vehicle.getVehicleId(), requested.get());
            }
        }

        ResourceLocation byName = ModSounds.named(vehicle.getVehicleId(), ModSounds.ENGINE_PREFIX);

        return SoundEvent.createVariableRangeEvent(
                ModSounds.exists(sounds, byName) ? byName : ModSounds.ENGINE);
    }

    private EngineSounds() {
    }
}
