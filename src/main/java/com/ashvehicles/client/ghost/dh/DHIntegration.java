package com.ashvehicles.client.ghost.dh;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Distant Horizons への唯一の窓口。ゴーストシステムの残りがそれについて話す相手は、このパッケージ外ではこのクラス
 * だけだ。
 *
 * <p>ここの各メソッドはまず当該 MOD の有無を問い、無ければ「no」か「何も無い」と答える。そのチェックの奥でだけ
 * {@link DHRendererBridge} に触れる。あのクラスは {@code com.seibel} 型を名指しする唯一のクラスなので、Distant
 * Horizons の無いゲームでは解決どころかロードもされず、ゴーストシステムはそれ抜きで描画を続ける。
 *
 * <p>Distant Horizons が実際に提供する物（3.2.0-b で確認。{@link DHRendererBridge} 参照）:
 * <ul>
 *   <li>地形をどこまで描いているか。ゴーストの背後に地面があるかを決める。</li>
 *   <li>地形データ（列単位）。DH の山がカメラとゴーストの間に立っていることを知る唯一の手段だ——DH はゲームの深度
 *       バッファに深度を残さない。</li>
 * </ul>
 */
public final class DHIntegration {
    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    private DHIntegration() {
    }

    /** そもそも MOD リストに存在するか。 */
    public static boolean isLoaded() {
        return LOADED;
    }

    /** ロード済み・初期化済みで、今まさに地形を描いているか。 */
    public static boolean isActive() {
        return LOADED && DHRendererBridge.isActive();
    }

    /** 地形をどこまで描いているか（ブロック）。描いていなければ0。 */
    public static double drawnRadius() {
        return LOADED ? DHRendererBridge.drawnRadius() : 0.0;
    }

    /**
     * DH の遠方霧の現在の形。DH が無いか、あっても霧を描いていなければ {@code null}。
     *
     * <p>フレームに1度写しを取り、そのフレームの全ゴーストを同じ写しで薄める。設定読みはフレームごと
     * 数回の getValue で安く、ゴーストごとに繰り返す理由は無い。{@link DHFog} 参照。
     */
    @javax.annotation.Nullable
    public static DHFog fog() {
        return LOADED ? DHRendererBridge.fog() : null;
    }

    /**
     * DH の地形が2点の間に立っているか。
     *
     * @param level クライアントレベル。対応する DH レベルを見つけるため
     * @param from 視点位置
     * @param to 見ている点
     * @param skip {@code from} から線に沿ってどこまでが、ゲーム自身のブロックで既に判定済みで再問い合わせ不要か
     */
    public static boolean isOccluded(ClientLevel level, Vec3 from, Vec3 to, double skip) {
        return LOADED && DHRendererBridge.isOccluded(level, from, to, skip);
    }

    /**
     * ある列で Distant Horizons が知っている一番上の地面。知らなければ null。
     *
     * <p><b>ゲームスレッドから呼ばないこと。</b>DH の地形問い合わせは future を待ち、DH 側がメインスレッドを
     * 待っていればクライアントが固まる。呼んでよいのは専用ワーカーからだけで、この MOD ではそれが
     * {@link com.ashvehicles.client.LodTerrain} だ。
     *
     * @return {@code [高さ, 液体なら1]}。答えが無ければ null
     */
    @javax.annotation.Nullable
    public static double[] columnTop(ClientLevel level, int blockX, int blockZ) {
        return LOADED ? DHRendererBridge.columnTop(level, blockX, blockZ) : null;
    }

    /** 旧レベルに紐付く物を全て忘れる。 */
    public static void onLevelChanged() {
        if (LOADED) {
            DHRendererBridge.reset();
        }
    }

    /** デバッグオーバーレイ用の一語。 */
    public static String status() {
        if (!LOADED) {
            return "ABSENT";
        }

        return DHRendererBridge.isActive() ? "ACTIVE" : "INACTIVE";
    }

    /** {@link #status()} より少し詳しい情報。ブリッジが到達できた物とできなかった物。 */
    public static String detail(ClientLevel level) {
        return LOADED ? DHRendererBridge.detail(level) : "absent";
    }
}
