package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.entity.RocketEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 銃が撃った物以外で空中にある全てに、飛翔中の音を与える。
 *
 * <p>監視対象は {@link RocketEntity} のみで、それがまさに該当する。ロケット・ミサイル・爆弾は全て同じ
 * エンティティであり、どれであるかは発射元の兵器が決める。銃弾は別のエンティティで、毎秒大量に飛ぶので、
 * 1発ずつ「無音だ」と判断するより一切追跡しない方が得だ。
 *
 * <p><b>どの録音を使うか。</b>兵器名と動作名から作る {@code <namespace>:weapon.<name>.flight} または
 * {@code weapon.<name>.fall}、無ければ MOD の {@code ashvehicles:weapon.flight} か
 * {@code ashvehicles:weapon.fall}。<b>後者2つは同梱していない</b>ので、どちらの名前でも何も無い兵器は無音で飛ぶ。
 * どちらもループであり、ループ用に切っていないループは無い方がましだ。{@link ModSounds} 参照。
 */
public final class ProjectileSounds {
    /**
     * 音が鳴っていない弾を再確認する間隔。毎tick。30ブロック/tick の弾はレールを離れて5tick後には150ブロック
     * 先——何も始まらないうちに可聴範囲の端近くまで行ってしまい、「まったく音のしないロケット」として聞かれて
     * いた。空中にこれが大量にあることは無いし、確認はマップ検索1回だ。
     */
    private static final int RETRY_TICKS = 1;

    /** このクライアントから見える空中の全弾と、それが出している音。 */
    public static final LiveSounds<RocketEntity> SOUNDS =
            new LiveSounds<>(RocketEntity.class, RETRY_TICKS, ProjectileSounds::start);

    @Nullable
    private static ProjectileSoundInstance start(RocketEntity projectile) {
        ProjectileSoundInstance.Kind kind = ProjectileSoundInstance.Kind.of(projectile.getWeapon());

        // 可聴範囲外でも再確認する価値がある。空にある他の何より速く動くので、遠くで発射された1発が1秒後には
        // 頭上に来ていることがある。
        if (kind == null || EntitySoundInstance.falloff(projectile, kind.range) <= 0.0F) {
            return null;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        ResourceLocation recording = ModSounds.firstPresent(sounds,
                ModSounds.named(projectile.getWeaponId(), ModSounds.WEAPON_PREFIX, kind.role), kind.fallback);

        return recording == null
                ? null
                : new ProjectileSoundInstance(projectile, SoundEvent.createVariableRangeEvent(recording), kind);
    }

    private ProjectileSounds() {
    }
}
