package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * 兵器が目標へ向かう途中の音。
 *
 * <p>取りうる形は2つで、どちらかはエンティティではなく兵器が決める。ロケットやミサイルが全飛翔中に出す音——
 * モーターが押している間が最も大きい——か、落下する物を過ぎる風の音、つまり爆弾が落ちる間ずっと出す音か。銃弾は
 * どちらも出さない。既に着いているからだ。
 *
 * <p>どちらも弾に追従するループであり、どちらも価値があるのはサウンドエンジンの64ブロックがここでは役に立たない
 * からだ。ミサイルはそれを2秒で越えるし、爆弾は目標のそれより高い位置から投下される。どちらも聞こえるべきなら
 * はるかに遠くから聞こえねばならず、だから距離は {@link EntitySoundInstance} が扱う。
 */
public class ProjectileSoundInstance extends EntitySoundInstance<RocketEntity> {
    /** チャンネルを返すまでの無音tick数。 */
    private static final int SILENT_TICKS_BEFORE_STOP = 20;

    private final Kind kind;
    /** 距離を考慮する前の音量。種別の volume に対する 0〜1。 */
    private float gain;

    public ProjectileSoundInstance(RocketEntity projectile, SoundEvent sound, Kind kind) {
        super(projectile, sound, SoundSource.NEUTRAL, SILENT_TICKS_BEFORE_STOP);
        this.kind = kind;
        // 徐々に上げるのではなく最初のtickから全音量で鳴らす。モーターはレールを離れる時点で既に燃えているし、
        // 0.25秒かけてフェードインする弾は、その0.25秒を聞き手から毎tick 8ブロック離れながら過ごす。大きくなる頃
        // には遠すぎて聞こえない。
        this.gain = kind.targetGain(projectile);
    }

    @Override
    protected void update() {
        this.gain = approach(this.gain, this.kind.targetGain(this.entity()), this.kind.rate);

        float falloff = this.falloff(this.kind.range);
        this.volume = this.kind.volume * this.gain * falloff;
        this.pitch = this.kind.pitch(this.gain);

        this.heard(falloff > 0.0F && this.gain > SILENCE);
    }

    /**
     * 兵器が飛翔中に出す音と、2種類の間で異なる要素すべて。
     *
     * @param role 兵器専用録音の名前の末尾。{@code weapon.<name>.<role>}
     * @param fallback 専用録音を持たない物が使う音
     * @param range 聞こえる距離（ブロック）
     * @param volume 距離0での音量
     * @param rate 毎tick、目標音量との差をどれだけ埋めるか
     */
    public enum Kind {
        /**
         * 燃焼中のモーターと、その後に物体を過ぎる風。
         *
         * <p>燃焼終了で止めはしない。ロケットモーターの燃焼は1秒だが、ミサイルは10〜20秒空中にいる——モーターと
         * 一緒に音を切ると、飛翔の面白い部分が丸ごと無音になり、それは誰も飛翔音とは呼ばない。だから燃焼終了時に
         * 音を落とし、押される物が何も無い状態で30ブロック/tick を進む物の音にして、着弾まで保つ。
         */
        MOTOR(ModSounds.FLIGHT_ROLE, ModSounds.FLIGHT, 480.0, 0.9F, 0.25F) {
            @Override
            float targetGain(RocketEntity projectile) {
                return projectile.isBurning() ? 1.0F : COASTING;
            }

            @Override
            float pitch(float gain) {
                return 1.0F;
            }
        },
        /**
         * 落下する物を過ぎる風。投下地点では静かで、重力に掴まれるにつれ落下の全行程で音量もピッチも上がっていく。
         * 速度ではなく落下速度で測る。爆弾は機体の速度をそのまま持って離れるが、そのどれ一つとして「落ちる音」では
         * ないからだ。
         */
        FALL(ModSounds.FALL_ROLE, ModSounds.FALL, 400.0, 1.0F, 0.15F) {
            @Override
            float targetGain(RocketEntity projectile) {
                return Mth.clamp((float) -projectile.getDeltaMovement().y / FULL_FALL, 0.0F, 1.0F);
            }

            @Override
            float pitch(float gain) {
                return Mth.lerp(gain, LOW_PITCH, HIGH_PITCH);
            }
        };

        /** 燃焼終了後、単に飛んでいる状態でモーター音がどれだけ残るか。 */
        private static final float COASTING = 0.55F;
        /** この落下速度（ブロック/tick）で風切り音は最大の音量とピッチに達する。 */
        private static final float FULL_FALL = 2.0F;
        private static final float LOW_PITCH = 0.75F;
        private static final float HIGH_PITCH = 1.15F;

        final String role;
        final ResourceLocation fallback;
        final double range;
        final float volume;
        final float rate;

        Kind(String role, ResourceLocation fallback, double range, float volume, float rate) {
            this.role = role;
            this.fallback = fallback;
            this.range = range;
            this.volume = volume;
            this.rate = rate;
        }

        /**
         * 兵器が飛翔中にどちらを出すか。どちらも出さないなら null。
         *
         * <p>押す物の無いロケットやミサイルは1つ目ではなく最後の場合に当たる。燃えないモーターに音は無く、弾は
         * レールを離れた瞬間から惰性で飛んでいる。
         */
        @Nullable
        public static Kind of(WeaponDefinition weapon) {
            if (weapon.isDropped()) {
                return FALL;
            }

            return weapon.type() != WeaponDefinition.Type.GUN && weapon.projectile().hasMotor() ? MOTOR : null;
        }

        abstract float targetGain(RocketEntity projectile);

        abstract float pitch(float gain);
    }
}
