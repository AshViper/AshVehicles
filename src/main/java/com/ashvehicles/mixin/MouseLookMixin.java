package com.ashvehicles.mixin;

import com.ashvehicles.client.AimZoom;
import com.ashvehicles.client.CockpitView;
import com.ashvehicles.client.MouseAim;
import com.ashvehicles.client.PodCamera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * パイロットのマウス入力を、世界ではなくコックピットへ送る。
 *
 * <p>Minecraft はマウスをそのまま方位と仰角に読む。機体の中ではそれが二重に間違っている。旋回中は画面が
 * 傾いてもマウスは傾かないので「横」が横でなくなるし、天頂付近では方位が意味を失い視界が振り回される。
 * {@link CockpitView} も {@link MouseAim} も機体自身の軸で扱うので、どちらの問題も起きない。
 *
 * <p>どちらが入力を受け取るか、それだけがフリールックキーの決めること。通常はマウスが「機体を向かわせる
 * 目標」を動かし、頭は目標を追う（要求している物をパイロットが見ていられるように）。押している間は目標を
 * その場に残し、マウスは頭だけを動かす。そこへ飛んでいかずに肩越しを見る方法。
 */
@Mixin(MouseHandler.class)
public abstract class MouseLookMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"), cancellable = true)
    private void ashvehicles$turnTheHead(double partialTick, CallbackInfo callback) {
        // 照準を上げると視野が狭くなるので、マウスも同じだけ遅くする。画面上の移動量がどちらでも同じに
        // なるように。バニラも望遠鏡で同じことをしている。照準を下ろしていれば1、つまり何もしない。ポッド
        // は倍率がずっと高いだけの同じ話で、2つが同時に上がることはない。
        double zoom = PodCamera.isShowing() ? PodCamera.factor() : AimZoom.factor();

        // ポッドが上がっている間はマウスを丸ごと持っていく。ジンバル上のボールを手で振る装置であり、
        // パイロットが地上を相手にしている間は、頭も、機体を向かわせる目標も動く道理が無い。
        if (PodCamera.isActive()) {
            double sensitivity = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
            double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15 / zoom;

            PodCamera.turn(this.accumulatedDX * scale, this.accumulatedDY * scale);
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            callback.cancel();

            return;
        }

        // 機体に乗っていない場合（戦車の乗員か、何にも乗っていないか）。バニラのマウス処理のまま、
        // 照準を上げている間だけ遅くする。
        if (!CockpitView.isActive()) {
            this.accumulatedDX /= zoom;
            this.accumulatedDY /= zoom;

            return;
        }

        boolean inCockpit = Minecraft.getInstance().options.getCameraType().isFirstPerson();

        // 外部視点で、かつマウスで操縦していない場合、ここでやるべきことでバニラより上手くやれる物は
        // 無い。バニラの処理は世界に対する方位と仰角で、それはまさに直立した追従カメラが欲しい物であり、
        // 一周まわる。放っておけば旋回中も視点が機体の後ろに留まる。操縦に使っていないカメラにパイロット
        // が求めるのはそれだけ。
        if (!inCockpit && !MouseAim.isActive()) {
            this.accumulatedDX /= zoom;
            this.accumulatedDY /= zoom;

            return;
        }

        // マウスの感触は Minecraft と同じ。飛ぶのに歩くのと違う手つきを要求しないため。
        double sensitivity = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
        double scale = sensitivity * sensitivity * sensitivity * 8.0 * 0.15 / zoom;
        double deltaX = this.accumulatedDX * scale;
        double deltaY = this.accumulatedDY * scale;

        if (MouseAim.isActive()) {
            MouseAim.turn(deltaX, deltaY, inCockpit);

            if (inCockpit) {
                // これは頭であり、頭の可動範囲までしか回らない。機体への要求はそれより外を向いている
                // ことがあるが、パイロットには単に見えないというだけのこと。
                CockpitView.lookAlong(MouseAim.look());
                CockpitView.applyToPlayer();
            } else {
                MouseAim.applyToPlayer();
            }
        } else {
            CockpitView.turn(deltaX, deltaY);
            CockpitView.applyToPlayer();
        }

        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
        callback.cancel();
    }
}
