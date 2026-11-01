package com.ashvehicles.mixin;

import com.ashvehicles.client.VehicleDismountHandler;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 乗車中は Ctrl+B で読み上げを切り替えない。
 *
 * <p>B はエアブレーキで、Ctrl は機体の操作と一緒に押さえていることが多い。バニラはその組み合わせを
 * 見るたびに読み上げの設定を1段送るので、操縦しているだけで読み上げが勝手に変わっていた。
 *
 * <p>keyPress の中の 2 つめの hasControlDown がその判定（1 つめはスクリーンショット）。画面を開いて
 * いないときに乗っていれば Ctrl は押されていないことにする。B のキー割り当てはこの後で別に処理されるので
 * エアブレーキはそのまま効き、乗っていないときや設定画面の中では今までどおり切り替わる。
 */
@Mixin(KeyboardHandler.class)
public abstract class NarratorHotkeyMixin {
    @Redirect(method = "keyPress(JIIII)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;hasControlDown()Z",
                    ordinal = 1))
    private boolean ashvehicles$noNarratorWhileAboard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null && VehicleDismountHandler.isAboard(minecraft.player)) return false;
        return Screen.hasControlDown();
    }
}
