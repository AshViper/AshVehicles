package com.ashvehicles.client.ghost.dh;

import javax.annotation.Nullable;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogDrawMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFarFogConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiFogConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Distant Horizons の型を名指しする唯一のクラス。{@link DHIntegration} 経由でのみ、しかも当該 MOD が存在する場合
 * のみロードされる。
 *
 * <h2>Distant Horizons 3.2.0-b で判明したことと、使っている物</h2>
 *
 * <p><b>エンティティ API は存在しない。</b>Distant Horizons にエンティティ LOD の概念は無く、モデルをその
 * パイプラインへ流す手段も無い。公開 API（{@code com.seibel.distanthorizons.api}）が提供し、このクラスが使う物は
 * 以下の通り。
 * <ul>
 *   <li>{@link DhApi.Delayed#configs} → {@code graphics().renderingEnabled()} と
 *       {@code chunkRenderDistance()}。描画するか、どこまで描くか。{@code configs} は MOD 初期化まで null なので、
 *       読むたび null チェックする。</li>
 *   <li>{@link DhApi.Delayed#worldProxy} → {@link IDhApiWorldProxy#getAllLoadedLevelWrappers()}。
 *       クライアントレベルに対応する {@link IDhApiLevelWrapper} を {@code getWrappedMcObject()} で照合する。
 *       他の呼び出しは全てこれを要求する。</li>
 *   <li>{@link DhApi.Delayed#terrainRepo} → {@link IDhApiTerrainDataRepo#getColumnDataAtBlockPos}。
 *       ブロック座標の LOD 列を、絶対値の {@code bottomYBlockPos}/{@code topYBlockPos} を持つ
 *       {@link DhApiTerrainDataPoint} として得る（リポジトリ自身の {@code raycast} もワールドYを直接それらと比較
 *       する）。DH の山の背後にあるゴーストを見つける手段はこれだ。Distant Horizons は自前のフレームバッファへ地形
 *       を描き、ゲームのバッファへは色だけを合成する——{@code shared/gl/apply.frag} は {@code gl_FragDepth} を書か
 *       ないし、他のシェーダも書かない——ので、ゲームの深度バッファはその地形を知らず、深度テストだけではゴースト
 *       が丘を突き抜けて描かれる。DH の {@code raycast()} はそれらの列を1ブロックずつ歩き、データのロード中は
 *       ブロックするので、ワーカースレッドから呼び、マネージャが配給する。</li>
 * </ul>
 *
 * <p><b>DH がどこで描き、こちらがなぜそこで描くか。</b>DH の NeoForge {@code MixinLevelRenderer} は
 * {@code LevelRenderer.renderSectionLayer} の先頭へ注入し、solid レイヤーの番で自身の地形を描く——ゲームの地形より
 * 前、どのエンティティより前だ。そして translucent と tripwire レイヤーの先頭で<em>バニラフェード</em>
 * （{@code renderFadeOpaque}/{@code renderFadeTransparent}）を走らせ、自身のフェード距離より遠くでゲームが描いた物
 * を、自前の地形がある所では自前の地形で塗り直す。よってゴーストパスは両方の後、
 * {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES} で走る。その時点でゲームの深度バッファには自前の地形と
 * エンティティが入っており（ゴーストはそれに対して深度テストする）、色バッファには DH の完成した地形が入っており
 * （ゴーストはその上に合成され、DH が残さなかった深度の代役は上記のレイキャストが務める）、そして DH の処理はもう
 * 後から塗り重ねてこない。DH の {@code MixinGameRenderer} は空だ。投影の遠方面を動かさないので、DH を入れていても
 * ゴーストレンダラーの遠方面引き寄せは依然として必要になる。
 *
 * <p>ここに mixin もリフレクションも無い。公開 API で足りる。バージョン依存は {@code DhApi.Delayed} のフィールド
 * と上記のインターフェースに対してのみで、いずれもこの MOD がコンパイル対象にしている公開 API jar の一部だ。
 */
final class DHRendererBridge {
    /** 遮蔽レイの終端で差し引くブロック数。目標の下の地面を目標の「手前」と数えないため。 */
    private static final double TARGET_MARGIN = 2.5;

    /**
     * 同じ余白をレイ長に対する割合で。長いレイではこちらが効く。
     *
     * <p>Distant Horizons は距離とともに落ちる詳細度で地形を描く。1km 先の列は実地形のかなりのブロック数を代表し、
     * その頂点はそれらの平均だ。したがって1km 先の機体の2.5ブロック手前で止めると、レイは機体が飛んでいる列の内側
     * で終わり、その列が報告する平均地面だけで機体は「隠れている」と判定されてしまう。1/50 手前——1km で20ブロック、
     * 2km で40ブロック——で終えれば、目標直下の地面は外れつつ、途中の丘は依然として捉えられる。
     */
    private static final double TARGET_MARGIN_FRACTION = 0.02;

    /** レベルラッパーが無いとき、再解決を試みる最短間隔（tick）。 */
    private static final long WRAPPER_RETRY_TICKS = 20L;

    @Nullable
    private static ClientLevel wrappedLevel;
    @Nullable
    private static IDhApiLevelWrapper wrapper;
    private static long wrapperAskedAt = Long.MIN_VALUE / 2;
    @Nullable
    private static IDhApiTerrainDataCache cache;

    private DHRendererBridge() {
    }

    // ------------------------------------------------------------------
    // 状態
    // ------------------------------------------------------------------

    static boolean isActive() {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static double drawnRadius() {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null) {
            return 0.0;
        }

        try {
            if (!Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())) {
                return 0.0;
            }

            Integer chunks = configs.graphics().chunkRenderDistance().getValue();

            return chunks == null ? 0.0 : chunks * 16.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /**
     * DH の遠方霧の現在の設定。霧を描いていなければ {@code null}。
     *
     * <p>読むのは DH が自分のシェーダーへ渡すのと同じ値だ（{@code FogRenderParamFactory} と
     * {@code GlDhFogShader} が組む uniform: 距離スケールは {@code 1 / (chunkRenderDistance × 16)}、
     * length は end−start、range は max−min）。falloff は API 列挙の {@code value} をそのまま持ち出す。
     * シェーダーが比較するのもその数字なので。
     *
     * <p>{@code enableDhFog} が偽か、描画モードが {@code FOG_DISABLED} なら霧は無い。設定値のどれかが
     * まだ null（DH 初期化中）でも同じ答えにする。半端な霧より無い霧の方がまし。
     */
    @Nullable
    static DHFog fog() {
        IDhApiConfig configs = DhApi.Delayed.configs;

        if (configs == null) {
            return null;
        }

        try {
            IDhApiGraphicsConfig graphics = configs.graphics();

            if (!Boolean.TRUE.equals(graphics.renderingEnabled().getValue())) {
                return null;
            }

            Integer chunks = graphics.chunkRenderDistance().getValue();

            if (chunks == null || chunks <= 0) {
                return null;
            }

            IDhApiFogConfig fog = graphics.fog();

            if (Boolean.FALSE.equals(fog.enableDhFog().getValue())
                    || fog.drawMode().getValue() == EDhApiFogDrawMode.FOG_DISABLED) {
                return null;
            }

            IDhApiFarFogConfig far = fog.farFog();
            Float start = far.farFogStartDistance().getValue();
            Float end = far.farFogEndDistance().getValue();
            Float least = far.farFogMinThickness().getValue();
            Float most = far.farFogMaxThickness().getValue();
            Float density = far.farFogDensity().getValue();
            EDhApiFogFalloff falloff = far.farFogFalloff().getValue();

            if (start == null || end == null || least == null || most == null
                    || density == null || falloff == null) {
                return null;
            }

            return new DHFog(chunks * 16.0, falloff.value, start, end - start,
                    least, most - least, density);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void reset() {
        wrappedLevel = null;
        wrapper = null;
        cache = null;
        wrapperAskedAt = Long.MIN_VALUE / 2;
    }

    /** クライアントレベルに対応する DH レベル。（まだ）無ければ {@code null}。 */
    @Nullable
    private static IDhApiLevelWrapper wrapperFor(ClientLevel level) {
        if (wrapper != null && wrappedLevel == level) {
            return wrapper;
        }

        long now = level.getGameTime();

        if (wrappedLevel == level && now - wrapperAskedAt < WRAPPER_RETRY_TICKS) {
            return null;
        }

        wrappedLevel = level;
        wrapperAskedAt = now;
        wrapper = null;
        cache = null;

        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;

        if (world == null) {
            return null;
        }

        try {
            if (!world.worldLoaded()) {
                return null;
            }

            // マルチプレイではラッパーがクライアントレベル自体を包む。シングルプレイでは Distant Horizons が
            // 両側で1つのレベルを回し、*サーバー*側レベルのラッパーを渡すので、照合はディメンションへフォール
            // バックする。どちらのラッパーも同じ DH レベルに仕えるし、地形データも描画レジスタもそのレベルの物だ。
            IDhApiLevelWrapper sameDimension = null;

            for (IDhApiLevelWrapper candidate : world.getAllLoadedLevelWrappers()) {
                Object wrapped = candidate.getWrappedMcObject();

                if (wrapped == level) {
                    wrapper = candidate;
                    break;
                }

                if (sameDimension == null && wrapped instanceof Level mcLevel
                        && mcLevel.dimension().equals(level.dimension())) {
                    sameDimension = candidate;
                }
            }

            if (wrapper == null) {
                wrapper = sameDimension;
            }
        } catch (IllegalStateException e) {
            // 「ワールド未ロード」。ワールドの切り替わりの合間に問われた。少し後に再試行する。
            return null;
        }

        return wrapper;
    }

    // ------------------------------------------------------------------
    // 遮蔽
    // ------------------------------------------------------------------

    /**
     * 2点間の線を Distant Horizons の地形に対して、DH 自身の {@link IDhApiTerrainDataRepo#raycast} で撃つ。
     *
     * <p>1ブロックずつ進むので信頼できる——1ブロックの壁も山と同じ確実さで見つかる——が、それゆえゲームスレッドには
     * 不向きでもある。列検索が数千回あり、各列の初回は Distant Horizons が自前のスレッドでロードする間、呼び出し元
     * がブロックする。{@link com.ashvehicles.client.ghost.GhostOcclusion} がこれをワーカーから呼ぶのはまさにその
     * ためだ。線のうちロード範囲内の部分はゲーム自身のブロックで既に判定済みなので飛ばす。末尾の数ブロックも外し、
     * 目標が立っている地面が目標を隠すと数えられないようにする。
     */
    static boolean isOccluded(ClientLevel level, Vec3 from, Vec3 to, double skip) {
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;

        if (repo == null) {
            return false;
        }

        IDhApiLevelWrapper dhLevel = wrapperFor(level);

        if (dhLevel == null) {
            return false;
        }

        Vec3 gap = to.subtract(from);
        double away = gap.length();
        double length = away - skip - Math.max(TARGET_MARGIN, away * TARGET_MARGIN_FRACTION);

        if (length <= 1.0) {
            return false;
        }

        IDhApiTerrainDataCache dataCache = cache;

        if (dataCache == null) {
            dataCache = repo.createSoftCache();
            cache = dataCache;
        }

        Vec3 direction = gap.scale(1.0 / away);
        Vec3 start = from.add(direction.scale(skip));

        try {
            DhApiResult<DhApiRaycastResult> result = repo.raycast(dhLevel,
                    start.x, start.y, start.z,
                    (float) direction.x, (float) direction.y, (float) direction.z,
                    (int) Math.ceil(length), dataCache);

            return result != null && result.success && result.payload != null;
        } catch (RuntimeException e) {
            // レベルのロード合間にも問い合わせが来うる。失敗時の答えは「隠れていない」。
            return false;
        }
    }

    // ------------------------------------------------------------------
    // デバッグ
    // ------------------------------------------------------------------

    /** デバッグオーバーレイ用に「有効」より少し詳しい情報。何かが欠けているならそれが何か。 */
    static String detail(ClientLevel level) {
        if (!isActive()) {
            return "inactive";
        }

        return wrapperFor(level) == null ? "no level wrapper" : "level";
    }
}

