package com.ashvehicles.ai.control;

import javax.annotation.Nullable;

import net.minecraft.world.phys.Vec3;

/**
 * AI から車両への唯一の出口。
 *
 * <p><b>これより上の層は車両の操作 API を1つも呼ばない。</b> 意思決定も経路も射撃も、出す物は
 * {@link DriveCommand} と {@link FireCommand} と狙い点だけで、それを入力に変えるのはここだけだ。車両の
 * 種類が増えたとき（今は地上車両だけ——[[bots-are-a-pilot-object-on-the-vehicle]]）に書き足すのは
 * この実装1つで、判断の層は触らない。
 *
 * <p>ここから先は人が運転しているときとまったく同じ経路を通る。AI のために変えた挙動は無い。
 */
public interface VehicleController {
    /** この tick の運転と引き金を置く。毎 tick 呼ぶこと——置かなければ前の tick の入力が残る。 */
    void apply(DriveCommand drive, FireCommand fire);

    /** 砲を向ける一点。null で手を離す。 */
    void aim(@Nullable Vec3 point);

    /** 止めて、引き金から手を離し、砲も手放す。残骸と、乗っ取られた車両のために。 */
    void park();
}
