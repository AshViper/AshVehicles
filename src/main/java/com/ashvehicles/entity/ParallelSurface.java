package com.ashvehicles.entity;

/**
 * 地表生成を並列で走らせるかどうかの1つのスイッチ。{@code SurfaceParallelMixin} 参照。
 *
 * <p>独立した定数にしてあるのは、疑わしくなった時に真っ先に触る場所だからだ。ワールドのデータが壊れて
 * いるように見えたら——地面に無い筈のブロック、宙に浮いた木、屋根を抜ける雨——まずここを false にして、
 * 同じ地形を新しいシード無しで作り直して再現するか確かめること。再現するなら原因は別にある。
 *
 * <p>バニラは地表生成を1本のスレッドで直列に走らせる。ノイズ生成は既に並列で、両者は同じ層の関門の
 * 内側にいる。ここが true の間は、地表もノイズと同じようにその層の全 chunk を背景プールへ散らす。
 */
public final class ParallelSurface {
    /** 地表生成を背景プールへ散らすか。false でバニラの直列動作。 */
    public static final boolean ENABLED = true;

    private ParallelSurface() {
    }
}
