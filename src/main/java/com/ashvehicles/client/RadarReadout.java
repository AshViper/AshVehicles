package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;

/**
 * レーダーが最後に伝えた内容。次を伝えるまで保持する。
 *
 * <p>レーダーは監視ではなく走査するので、走査の合間にスコープに出ているのは、アンテナが最後に通ったときの位置
 * だ。だからこれは毎フレーム算出し直す物ではなく保持された画であり、多少古くてよい。
 *
 * <p>古くなること自体は許すが、無期限にではない。レーダーが停止したことをクライアントへ伝える物は無い。降機した
 * パイロットや破壊された機体は単に送信をやめるので、走査1〜2回分より古くなった画は破棄する。セッションの残り
 * ずっと画面に凍り付かせておくのではなく。
 */
public final class RadarReadout {
    /** 到着後、画を描く価値がある時間（tick）。 */
    private static final int KEEPS_FOR = 40;

    private static List<Contact> contacts = List.of();
    private static List<Threat> threats = List.of();
    private static long arrived = Long.MIN_VALUE;

    /** 回線から届いた新しい走査結果。 */
    public static void accept(List<Contact> found, List<Threat> warnings) {
        contacts = found;
        threats = warnings;
        arrived = age();
    }

    public static List<Contact> contacts() {
        return fresh() ? contacts : List.of();
    }

    public static List<Threat> threats() {
        return fresh() ? threats : List.of();
    }

    /** この機体に対して行われている最悪の行為。誰も何もしていなければ null。 */
    public static Threat.Kind worst() {
        List<Threat> current = threats();

        return current.isEmpty() ? null : current.get(0).kind();
    }

    /** 最後の走査が意味を持つ程度に新しいか。 */
    private static boolean fresh() {
        return age() - arrived < KEEPS_FOR;
    }

    private static long age() {
        return Minecraft.getInstance().level == null ? Long.MIN_VALUE : Minecraft.getInstance().level.getGameTime();
    }

    private RadarReadout() {
    }
}
