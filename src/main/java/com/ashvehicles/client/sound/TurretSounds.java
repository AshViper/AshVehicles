package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 砲塔・砲架が動いている間だけ鳴る音。戦車が砲を振る音と、牽引砲の砲手がハンドルを回す音。
 *
 * <p><b>この2つは同じ機構ではないので、同じ音であってはならない。</b>戦車の架台は電動機か油圧で回り、
 * 聞こえるのは一定の唸りと歯車の噛み合いだ。牽引砲にはそのどちらも無く、鳴っているのは人の手が回している
 * ウォームギヤそのものであり、だから音は手の速さで鳴る。どちらへ落ちるかを決めるのは {@code hull.crewed}
 * ——牽引砲であることを表す唯一のフラグ——であり、{@link com.ashvehicles.entity.GroundVehicleEntity#isCrewed()}
 * 参照。
 *
 * <p><b>送信する物は無い。</b>降着装置（{@link GearSounds}）と同じ理由だ。砲塔角は既に同期されており
 * （{@code DATA_TURRET_YAW} / {@code DATA_GUN_PITCH}）、どのクライアントもそれを毎tick自分で寄せている
 * ので、「架台が今どれだけの速さで動いているか」は誰でも自分で見られる。
 * {@link GroundVehicleEntity#getSlewEffort()} 参照。
 *
 * <p>そして<b>それが牽引砲でこの音が成立する唯一の道でもある</b>。牽引砲を据えているのはサーバーで、
 * 操作している者は乗っていない（{@code crew-served guns} は車外から操作する）。命令を持つクライアントが
 * 存在しないので、動きから読む以外に鳴らし始める手掛かりが無い。
 *
 * <p><b>どの録音を使うか。</b>車両ファイルが {@code sound.turret} で指定するイベント、無ければ車両名の
 * イベント {@code <namespace>:turret.<name>}、無ければ MOD 同梱の {@code ashvehicles:turret.default}
 * （動力）か {@code ashvehicles:turret.crank}（ハンドル）。3段目まで落ちても鳴るので、車両ファイルに何も
 * 書く必要は無い。
 */
public final class TurretSounds {
    /**
     * 架台の音が鳴っていない車両を再確認する間隔。短い。
     *
     * <p>脚（2秒かけて動く）と違い、架台の動きは一瞬で終わることがある——牽引砲の微調整は右クリック1回
     * ぶん、数tickだ。遅れて鳴らし始めれば、既に止まっている物の音を出すことになる。
     */
    private static final int RETRY_TICKS = 2;

    /** このクライアントから見える全地上車両と、その架台（音を出す間だけ）。 */
    public static final LiveSounds<GroundVehicleEntity> SOUNDS =
            new LiveSounds<>(GroundVehicleEntity.class, RETRY_TICKS, TurretSounds::start);

    /** 既に警告した要求済み録音。欠落ファイルについてのログを1行に留めるため。 */
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    @Nullable
    private static TurretSoundInstance start(GroundVehicleEntity vehicle) {
        // 振る架台を持たない車両は、動かす物が無いので永久に無音だ。ここで落としておくと、装甲車の群れが
        // 毎秒10回この先を通らずに済む。
        if (!vehicle.getStats().turret().exists()
                || vehicle.getSlewEffort() < TurretSoundInstance.MOVING
                || EntitySoundInstance.falloff(vehicle, TurretSoundInstance.RANGE) <= 0.0F) {
            return null;
        }

        ResourceLocation recording = turretSound(Minecraft.getInstance().getSoundManager(), vehicle);

        return recording == null
                ? null
                : new TurretSoundInstance(vehicle, SoundEvent.createVariableRangeEvent(recording));
    }

    /**
     * その車両の架台の録音。どのリソースパックも提供しなければ null。音を鳴らすたびに解決し直すので、
     * リソースパックの変更は再起動なしで反映される。
     */
    @Nullable
    public static ResourceLocation turretSound(SoundManager sounds, GroundVehicleEntity vehicle) {
        Optional<ResourceLocation> requested = vehicle.getStats().sound().turret();

        if (requested.isPresent()) {
            if (ModSounds.exists(sounds, requested.get())) {
                return requested.get();
            }

            if (WARNED.add(requested.get())) {
                AshVehicles.LOGGER.warn("Vehicle {} asks for turret sound {} which no resource pack provides; looking for another",
                        vehicle.getVehicleId(), requested.get());
            }
        }

        // 最後の1つだけが車両の種類で分かれる。名指しされた録音と車両名の録音は、牽引砲でも戦車でも
        // 同じように探される——「この砲だけ別の音」を表す場所は1つでよい。
        return ModSounds.firstPresent(sounds,
                ModSounds.named(vehicle.getVehicleId(), ModSounds.TURRET_PREFIX),
                vehicle.isCrewed() ? ModSounds.TURRET_CRANK : ModSounds.TURRET);
    }

    private TurretSounds() {
    }
}
