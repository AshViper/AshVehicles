package com.ashvehicles.client.model;

import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 胴体に沿って畳まれ、発射の後に開く翼。JASSM のような巡航ミサイルが持つ。
 *
 * <p>兵装の描画にアニメーションファイルは無い（{@link WeaponModel} は既定の空の物しか渡さない）ので、ここでボーンを
 * 直に回す。対象は名前で決まる——{@code wing_fold_left} と {@code wing_fold_right} を持つ geo だけが開き、それを
 * 持たない兵装には何も起きない。開き角は Blockbench の画面で Y に ∓50 度、キーフレームと同じく X/Y は符号が反転する。
 *
 * <p>パイロン上でも毎フレーム閉じ直す。ベイクされたモデルはパイロン上の物と飛んでいる物で共有されるので、
 * 開いた角が残ると吊られている方まで開いて見える。
 */
public final class FoldingWings {
    public static final String LEFT = "wing_fold_left";
    public static final String RIGHT = "wing_fold_right";

    /** 開ききった角（ラジアン）。 */
    private static final float OPEN = (float) Math.toRadians(50.0);
    /** 発射から開き始めるまで（tick）。ラックを離れてから開く。 */
    private static final float DELAY = 4.0F;
    /** 開くのにかかる時間（tick）。 */
    private static final float SWING = 6.0F;

    private FoldingWings() {
    }

    /** 発射からの経過で開き具合を決めて置く。 */
    public static void afterLaunch(GeoModel<?> model, float age) {
        pose(model, Mth.clamp((age - DELAY) / SWING, 0.0F, 1.0F));
    }

    /** 0 で畳んだまま、1 で開ききり。 */
    public static void pose(GeoModel<?> model, float openness) {
        model.getBone(RIGHT).ifPresent(bone -> swing(bone, OPEN * openness));
        model.getBone(LEFT).ifPresent(bone -> swing(bone, -OPEN * openness));
    }

    private static void swing(GeoBone bone, float angle) {
        bone.setRotY(bone.getInitialSnapshot().getRotY() + angle);
    }
}
