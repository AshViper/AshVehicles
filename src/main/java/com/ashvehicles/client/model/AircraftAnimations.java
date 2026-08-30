package com.ashvehicles.client.model;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.loading.object.BakedAnimations;

/**
 * 降着装置の作動サイクル。機体でポーズ付けではなくアニメーション再生される唯一の部位。
 *
 * <p>他の可動部は全て飛行状態に連続追従し、{@link AircraftModel} のコードで設定する。補助翼はロール角速度が示す
 * 位置にあるだけで、手順は存在しない。脚は違う。脚は手順であり、機体の降着装置らしく見せている物の全ては
 * <i>順序</i>だ——ベイの扉が先に開き、脚がそれに続いて出る。戻るときは脚が先で、扉がその上を閉じる。それは
 * Blockbench で開けるファイルに属する物であって、Java で補間される2つの数値に属する物ではない。
 *
 * <p>ファイルに脚のサイクルが無い機体はエラーではない。{@link AircraftModel} が従来通りコードで脚を動かすので、
 * 新しい機体は描かれたその日に飛べるし、アニメーションは誰かがそれも描く気になったときに手に入る。
 */
public final class AircraftAnimations {
    public static final String GEAR_DOWN = "gear_down";
    public static final String GEAR_UP = "gear_up";

    /** 脚サイクルへのブレンド時間。作動途中で気が変わったパイロット用。 */
    public static final int TRANSITION_TICKS = 4;

    private static final RawAnimation LOWERING = RawAnimation.begin().thenPlayAndHold(GEAR_DOWN);
    private static final RawAnimation RAISING = RawAnimation.begin().thenPlayAndHold(GEAR_UP);

    /**
     * コントローラが再生すべき物。
     *
     * <p>名前を持つのは両端だけ。脚が今どちらへ動いているかは一切問わない。機体は下げたいか上げたいかのどちらか
     * であり、その状態で終わるアニメーションを再生すればよい。
     */
    public static PlayState gearCycle(AnimationState<AircraftEntity> state) {
        AircraftEntity aircraft = state.getAnimatable();

        if (!hasGearCycle(aircraft)) {
            return PlayState.STOP;
        }

        return state.setAndContinue(cycleFor(aircraft.isGearDown()));
    }

    /**
     * 脚を目的の状態で終えるサイクルの半分。問い合わせる機体を持たない呼び出し元——スナップショットから描かれる
     * ゴースト——のため。
     */
    public static RawAnimation cycleFor(boolean gearDown) {
        return gearDown ? LOWERING : RAISING;
    }

    /**
     * 再生速度。
     *
     * <p>役目は2つ。通常はアニメーションを、機体ファイルが示す脚サイクル時間に合わせて伸縮させる。おかげで
     * 「脚が出るまでの時間」と「出したことによる抗力が発生する時刻」を1つの値が決められる。手作業で同期させる
     * べき2つの値にならずに済むわけだ。
     *
     * <p>もう1つの役目は、脚が既に目的の状態にある場合だ。脚を出した状態で視界に入った機体は、脚を出したまま
     * そこにいるべきで、今見た人のために改めて脚を下ろすべきではない。ばかげた速度でアニメーションを進めれば
     * 1フレームで最終キーフレームを越え、あとは {@code hold_on_last_frame} が引き受ける。描かれるのはサイクルの
     * 終端であり、それがまさに欲しいポーズだ。パイロットがレバーを動かした瞬間、脚は「安定」でなくなり、
     * サイクルは本来の速度で再生される。
     */
    public static double gearSpeed(AircraftEntity aircraft) {
        return gearSpeed(AircraftModel.animationFile(aircraft), aircraft.isGearDown(),
                aircraft.getGearCycleTicks(), aircraft.isGearSettled());
    }

    /** 同じ値を、依存する4つの要素から求める版。機体を伴わずに描かれるゴースト用。 */
    public static double gearSpeed(@Nullable ResourceLocation animationFile, boolean gearDown, int cycleTicks,
            boolean settled) {
        if (settled) {
            return SETTLED;
        }

        Animation animation = gearAnimation(animationFile, gearDown ? GEAR_DOWN : GEAR_UP);

        return animation == null ? 1.0 : animation.length() / Math.max(cycleTicks, 1);
    }

    /** この機体のアニメーションファイルがサイクルの両半分を持っているか。 */
    public static boolean hasGearCycle(AircraftEntity aircraft) {
        return hasGearCycle(AircraftModel.animationFile(aircraft));
    }

    /** 同じ判定をファイル単位で。ゴーストが代役先の機体について知っている情報から。 */
    public static boolean hasGearCycle(@Nullable ResourceLocation animationFile) {
        return gearAnimation(animationFile, GEAR_DOWN) != null
                && gearAnimation(animationFile, GEAR_UP) != null;
    }

    /**
     * 1フレームでどのアニメーションの最終キーフレームも越える速さ。この速度で何かを再生しているのではなく、
     * サイクルを終端で保持するための手段だ。
     */
    private static final double SETTLED = 1.0E4;

    @Nullable
    private static Animation gearAnimation(@Nullable ResourceLocation animationFile, String name) {
        if (animationFile == null) {
            return null;
        }

        BakedAnimations file = GeckoLibCache.getBakedAnimations().get(animationFile);

        return file == null ? null : file.getAnimation(name);
    }

    private AircraftAnimations() {
    }
}
