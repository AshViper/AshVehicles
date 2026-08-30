package com.ashvehicles.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ashvehicles.AshVehicles;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 何かを記述した JSON ファイルのディレクトリ1つ分。起動時に MOD 本体から、{@code /reload} のたびに
 * データパックから読み直される。
 *
 * <p>MOD 内のどの種類のファイルも欲しいのはこれだけ——機体、地上車両、その双方が撃つ兵装。以前は種類
 * ごとにローダー・マネージャー・バージョン番号・同期パケットを持っていた。同じ40行が3つ並び、次に何か
 * 増えれば4つ目が控えていた。これはそのコードを1つにまとめ、ディレクトリ・コーデック・フォールバック
 * を渡して使うもの。
 *
 * <p><b>データパックレジストリではなくリロードリスナーである理由。</b> レジストリはワールドを開く際に
 * 一度読まれ、{@code /reload} では二度と触られない。つまり値の調整のたびにタイトル画面へ戻ることに
 * なる。ここで読んだ内容はそのままプレイヤーへ送り返すので、編集したファイルはワールド内の機体に即
 * 効く。
 *
 * <p><b>MOD 本体からも読む理由。</b> エンティティ型の大きさは登録した瞬間に固定され、その登録はどの
 * データパックに触れるよりずっと前に終わる。{@link #builtIn} がその早期読み込みであり、パックが
 * ファイルを消した場合やクライアントがまだデータを受け取っていない場合の逃げ道でもある。
 */
public final class DefinitionRegistry<T> {
    private static final Gson GSON = new Gson();

    /**
     * 存在する全レジストリを宣言順に。
     *
     * <p>リロードリスナーと同期パケットを、各レジストリを二度も名指しせずリストを歩いて組めるように保持
     * する。クラス初期化時に一度書かれネットワークスレッドから読まれるので copy-on-write。
     */
    private static final List<DefinitionRegistry<?>> ALL = new CopyOnWriteArrayList<>();

    /**
     * 今どのファイル群が読まれているかを表す番号。どれか1つでも変われば変わる。
     *
     * <p>レジストリごとではなく全体で1つ。定義を毎回引き直さず保持する側は——機体は1tickに何十回も自分
     * の数値を訊くので実質全員そうする——この番号を横に持ち、値が動いたら手元の写しを捨てる。1つの
     * ディレクトリしか変わらないリロードでも無駄な読み直しが少し出るが、その代わり呼び出し側は自分の
     * 答えがどのディレクトリ由来かを知らずに済む。
     */
    private static volatile int version;

    private final String directory;
    private final Codec<T> codec;
    private final StreamCodec<ByteBuf, T> streamCodec;
    private final T fallback;
    private final String what;

    private Map<ResourceLocation, T> builtIn;
    private Map<ResourceLocation, T> active = Map.of();

    /**
     * @param directory {@code data/<namespace>/} 以下のフォルダ名
     * @param fallback 誰もファイルを持っていないときに {@link #get} が返す値。ゲームが動き続け、ログに
     *                 何が起きたか残るような無害な値
     * @param what ログ用の語。"aircraft"、"weapons" など
     */
    public static <T> DefinitionRegistry<T> of(String directory, Codec<T> codec, T fallback, String what) {
        DefinitionRegistry<T> registry = new DefinitionRegistry<>(directory, codec, fallback, what);
        ALL.add(registry);

        return registry;
    }

    private DefinitionRegistry(String directory, Codec<T> codec, T fallback, String what) {
        this.directory = directory;
        this.codec = codec;
        this.streamCodec = ByteBufCodecs.fromCodec(codec);
        this.fallback = fallback;
        this.what = what;
    }

    public static int version() {
        return version;
    }

    public static List<DefinitionRegistry<?>> registries() {
        return Collections.unmodifiableList(ALL);
    }

    /** そのディレクトリを読むレジストリ。無ければ null。 */
    public static DefinitionRegistry<?> byDirectory(String directory) {
        for (DefinitionRegistry<?> registry : ALL) {
            if (registry.directory.equals(directory)) {
                return registry;
            }
        }

        return null;
    }

    public String directory() {
        return this.directory;
    }

    public StreamCodec<ByteBuf, T> streamCodec() {
        return this.streamCodec;
    }

    /** MOD に同梱された全ファイル（ID 順）。初回使用時に一度だけ読む。 */
    public synchronized Map<ResourceLocation, T> builtIn() {
        if (this.builtIn == null) {
            this.builtIn = BuiltInFiles.read(this.directory, this.codec, this.what);
        }

        return this.builtIn;
    }

    /** 今パックが読み込んでいる内容。クライアントではサーバーが最後に送ってきたもの。 */
    public Map<ResourceLocation, T> all() {
        return this.active;
    }

    /**
     * 1件分の数値。読み込み済みの写し、無ければ MOD 同梱版、それも無ければフォールバック。
     *
     * <p>真ん中の段が見た目より重要。当たり判定の箱はエンティティのコンストラクタで組まれ、ワールド参加
     * 時にレベルへ記録されるので、形状が不明な瞬間に組まれた個体は箱を持たず、後から与えることもできない
     * ——最もたちの悪い、静かな失敗になる。
     */
    public T get(ResourceLocation id) {
        T loaded = this.active.get(id);

        if (loaded != null) {
            return loaded;
        }

        return this.builtIn().getOrDefault(id, this.fallback);
    }

    /** パックか MOD 本体のどちらかに、これを記述したファイルがあるか。 */
    public boolean has(ResourceLocation id) {
        return this.active.containsKey(id) || this.builtIn().containsKey(id);
    }

    /** サーバーではリロード、クライアントでは同期で適用される。適用後にバージョンを進めること。 */
    void accept(Map<ResourceLocation, T> values) {
        this.active = Collections.unmodifiableMap(values);
    }

    /** ディレクトリ1つ分の JSON を読む。解析できない物はログに出して飛ばす。 */
    private void load(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, T> loaded = new LinkedHashMap<>();

        files.forEach((id, json) -> this.codec.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> AshVehicles.LOGGER.error("Cannot read {} {}: {}", this.what, id, error))
                .ifPresent(value -> loaded.put(id, value)));

        AshVehicles.LOGGER.info("Loaded {} {}: {}", loaded.size(), this.what, loaded.keySet());

        this.accept(loaded);
    }

    /** レジストリの中身を丸ごと1つの値にしたもの。同期パケットの材料。 */
    public record Snapshot<T>(DefinitionRegistry<T> registry, Map<ResourceLocation, T> values) {
        public static Snapshot<?> of(DefinitionRegistry<?> registry) {
            return snapshot(registry);
        }

        private static <T> Snapshot<T> snapshot(DefinitionRegistry<T> registry) {
            return new Snapshot<>(registry, registry.all());
        }

        void apply() {
            this.registry.accept(this.values);
        }
    }

    /** 同期を丸ごと受け取り、バージョンは件数分ではなく全体で1回だけ進める。 */
    public static void acceptAll(List<Snapshot<?>> snapshots) {
        snapshots.forEach(Snapshot::apply);
        version++;
    }

    /** レジストリ1つ分のリロードリスナー。ディレクトリごとに1つ追加される。 */
    public PreparableReloadListener reloadListener() {
        return new SimpleJsonResourceReloadListener(GSON, this.directory) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources,
                    ProfilerFiller profiler) {
                DefinitionRegistry.this.load(files);
                version++;
            }
        };
    }
}
