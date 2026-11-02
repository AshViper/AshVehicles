package com.ashvehicles.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 放出キーが何を出すか。クライアントだけの選択で、サーバーに届くのは従来通りフレアとチャフの2本のハンドルだ。
 * 選択は機体ではなくパイロットが持つ——乗り換えても手癖は変わらない。
 */
public enum CountermeasureMode {
    FLARE(true, false, "FLR"),
    CHAFF(false, true, "CHF"),
    BOTH(true, true, "F+C");

    private static CountermeasureMode current = BOTH;

    private final boolean flare;
    private final boolean chaff;
    private final String label;

    CountermeasureMode(boolean flare, boolean chaff, String label) {
        this.flare = flare;
        this.chaff = chaff;
        this.label = label;
    }

    /** MFD の押しボタンの札。 */
    public String label() {
        return label;
    }

    public static CountermeasureMode current() {
        return current;
    }

    /** 次へ送り、どれになったかを画面に出す。今の状態を示す物が他に無いからだ。 */
    public static void cycle(LocalPlayer player) {
        current = values()[(current.ordinal() + 1) % values().length];
        player.displayClientMessage(Component.translatable(
                "message.ashvehicles.countermeasure_" + current.name().toLowerCase()), true);
    }

    public static boolean flare(boolean release) {
        return release && current.flare;
    }

    public static boolean chaff(boolean release) {
        return release && current.chaff;
    }
}
