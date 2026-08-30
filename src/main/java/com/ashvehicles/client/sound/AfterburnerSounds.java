package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * アフターバーナー点火時、リソースパックがその音を定義していない場合に鳴らす物を探す。
 *
 * <p>{@link WeaponSounds} と同じ仕組み・同じ理由。バーナーが点いたことを知っているのはサーバー側——操縦クライ
 * アントが報告する相手——であり、サーバーにできるのはサウンドイベント名を指すことだけだ。リソースパックは
 * サーバーが見たことのない物だからだ。よってイベントはこの機体自身の名前
 * {@code ashvehicles:engine.<aircraft>.afterburner} で発行され、それに応える物があるかを問えるのはこちら側だけ
 * になる。
 *
 * <p>フォールバックは順に、パックが提供していれば MOD 自身の {@link ModSounds#AFTERBURNER}、無ければゲームの
 * ファイアチャージ。大量の燃料が一気に着火する音にバニラで最も近い物だ。エンジン音と違い、ここで無音を選ぶ理由は
 * 無い。これはループではなく短い一発の破裂音であり、代役の破裂音は「聞こえる理由も無く前へ飛び出す機体」より
 * はるかにましだ。
 *
 * <p>したがって機体に専用の音を与えるにはファイルだけで足りる。{@code sounds.json} に
 * {@code engine.<aircraft>.afterburner} と {@code .ogg} を追加するか、全機体を一度に賄うなら
 * {@code engine.afterburner} を追加する。
 *
 * <p>音量とピッチは、置き換えられる音ではなく {@link AircraftEntity} から取る。そうするほかない。このイベントは
 * サウンドエンジンが録音を引く前に発火するので、インスタンスはまだ音量を答えられず、問えば例外になる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AfterburnerSounds {
    /**
     * バーナー点火のフォールバック。ゲーム自身のファイアチャージで、大量の燃料が一気に燃える音であり、既にほぼ
     * 正しい音だ。
     */
    private static final ResourceLocation FALLBACK =
            ResourceLocation.withDefaultNamespace("item.firecharge.use");

    /** この種のイベント名に共通する末尾。{@link ModSounds#AFTERBURNER_ROLE} 参照。 */
    private static final String SUFFIX = "." + ModSounds.AFTERBURNER_ROLE;

    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    /** この不具合の報告が既にログに1件あるか。 */
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /**
     * ここで起きることに、ワールドを失う価値のある物は無い。
     *
     * <p>このイベントは音を要求したパケットの処理内部から発火するので、ここで投げた例外は音を失うだけでは済ま
     * ない。パケットが失敗しプレイヤーがゲームから切断される。どの録音を使うかにその価値は無いので、想定外の事態
     * では音をサーバーの要求通りに残し、1度だけその旨を記録する。
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        try {
            substituteDefault(event);
        } catch (Exception exception) {
            if (FAILED.compareAndSet(false, true)) {
                AshVehicles.LOGGER.error("Cannot choose an afterburner sound; leaving it to the server's choice",
                        exception);
            }
        }
    }

    private static void substituteDefault(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null) {
            return;
        }

        ResourceLocation id = sound.getLocation();

        if (!isAfterburner(id)) {
            return;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();

        // パックが持っている。ここで決めることは無い。サーバーの数値は機体自身の物だ。
        if (ModSounds.exists(sounds, id)) {
            return;
        }

        ResourceLocation shipped = ModSounds.firstPresent(sounds, ModSounds.AFTERBURNER);
        ResourceLocation recording = shipped == null ? FALLBACK : shipped;

        if (WARNED.add(id)) {
            AshVehicles.LOGGER.info("No resource pack provides {}; falling back on {}", id, recording);
        }

        // 位置も数値も同じ。変わるのは録音だけ。
        event.setSound(new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(recording),
                sound.getSource(), AircraftEntity.AFTERBURNER_VOLUME, AircraftEntity.AFTERBURNER_LIGHT_PITCH,
                SoundInstance.createUnseededRandom(), sound.getX(), sound.getY(), sound.getZ()));
    }

    /** MOD のバーナーイベントか。{@code engine.afterburner} か、機体名を冠した物。 */
    private static boolean isAfterburner(@Nullable ResourceLocation id) {
        return id != null && AshVehicles.MODID.equals(id.getNamespace())
                && id.getPath().startsWith(ModSounds.ENGINE_PREFIX) && id.getPath().endsWith(SUFFIX);
    }

    private AfterburnerSounds() {
    }
}
