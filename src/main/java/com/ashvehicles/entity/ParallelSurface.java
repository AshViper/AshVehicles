package com.ashvehicles.entity;

import net.neoforged.fml.ModList;

/**
 * 地表生成を並列で走らせるかどうかの1つのスイッチ。{@code SurfaceParallelMixin} 参照。
 *
 * <p>独立した定数にしてあるのは、疑わしくなった時に真っ先に触る場所だからだ。ワールドのデータが壊れて
 * いるように見えたら——地面に無い筈のブロック、宙に浮いた木、屋根を抜ける雨——まずここを false にして、
 * 同じ地形を新しいシード無しで作り直して再現するか確かめること。再現するなら原因は別にある。
 *
 * <p>バニラは地表生成を1本のスレッドで直列に走らせる。ノイズ生成は既に並列で、両者は同じ層の関門の
 * 内側にいる。ここが true の間は、地表もノイズと同じようにその層の全 chunk を背景プールへ散らす。
 *
 * <p><b>c2me が入っていれば切る。</b> c2me はワールド生成を自分のスケジューラ（{@code c2me-worker-N}、
 * chunk の周囲を施錠してから段階を走らせる）で丸ごと並列化していて、地表もその中で並列に走る。ここが
 * 返す future はその上でさらに地表を vanilla の背景プール（別の 15 本）へ逃がすので、c2me のワーカーは
 * 施錠を持ったまま他人のプールを待つことになり、並列度は増えずスレッドだけが増える。c2me の
 * スレッド安全性の修正はワーカー上で走ることを前提にしている物もある。差し込む理由が無い。
 */
public final class ParallelSurface {
    /** 地表生成を背景プールへ散らすか。false でバニラの直列動作。c2me が生成を持っていれば常に false。 */
    public static final boolean ENABLED = !ModList.get().isLoaded("c2me");

    private ParallelSurface() {
    }
}
