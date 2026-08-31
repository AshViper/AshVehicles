package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.network.MissileTrackPayload;

import net.minecraft.client.Minecraft;

/**
 * 自分の発射機が撃った弾が今どこにいるか。サーバーから届いた点をそのまま持っておくだけ。
 *
 * <p>射撃指揮盤の地図（{@code LaunchMap}）が読む。弾はクライアントの追跡距離のはるか外を飛ぶので、地図に
 * 出す道はこれしか無い——{@link MissileTrackPayload} 参照。
 *
 * <p><b>古い点は自分で捨てる。</b>発射機を降りればサーバーは送るのをやめるが、最後に届いた点はここに残る。
 * 数秒で忘れるので、次に盤を開いた時に前の交戦の印が浮かんでいることはない。
 */
public final class MissileTrack {
    /** 更新が途切れてから点を忘れるまでの tick 数。送信間隔より十分長く、人が気付くより短く。 */
    private static final int STALE_TICKS = 40;

    private static List<MissileTrackPayload.Shot> shots = List.of();
    private static long tookAt = Long.MIN_VALUE;

    private MissileTrack() {
    }

    /** サーバーから届いた。 */
    public static void take(List<MissileTrackPayload.Shot> arrived) {
        shots = arrived;
        tookAt = now();
    }

    /** 今飛んでいる自分の弾。届いていない、あるいは古すぎれば空。 */
    public static List<MissileTrackPayload.Shot> shots() {
        return now() - tookAt > STALE_TICKS ? List.of() : shots;
    }

    private static long now() {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level == null ? Long.MIN_VALUE : minecraft.level.getGameTime();
    }
}
