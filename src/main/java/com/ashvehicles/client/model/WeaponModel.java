package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ashvehicles.AshVehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * あらゆる兵装を、飛翔中でも主翼に吊られていても、自身のジオメトリファイルから描く。
 *
 * <p>ここに特定の兵装専用の物は無い。ジオメトリとテクスチャは兵装自身の名前で見つかるので、{@code r60} は
 * 場所を教えられずとも {@code geo/weapon/r60.geo.json} と {@code textures/weapon/r60.png} から描かれる。機体と
 * 同じ仕組みであり、新しい兵装に Java は一切要らない——{@code data/} の JSON、モデル、テクスチャだけだ。
 *
 * <p><b>しかも兵装だけではない。</b>機体に吊られる3種——兵装、それを吊る
 * {@link com.ashvehicles.weapon.RackDefinition ラック}、特殊ステーションの
 * {@link com.ashvehicles.weapon.EquipmentDefinition ポッド}——はまさにこれによって、種類名のディレクトリから描か
 * れる。サブクラスがIDの他に述べる唯一の事柄がどのディレクトリかだ。{@link #folder} 参照。
 *
 * <p>自前のモデルを持たない兵装は、テクスチャ欠落の立方体やクラッシュではなく素のモデルへフォールバックする。
 * 発射音を持たない兵装が既定へ落ちるのと同じだ。兵装を追加した人は最初の1分から飛んで見える物を手にし、きちんと
 * 描くのは後回しにできる。
 *
 * @param <T> 描かれる対象。空中のミサイルか、パイロン上の兵装
 */
public abstract class WeaponModel<T extends GeoAnimatable> extends GeoModel<T> {
    /** 兵装の描画元ディレクトリ。別途指定しない物は全てこれを使う。 */
    public static final String WEAPONS = "weapon";
    /** レールと投下ラック。兵装の隣の専用ディレクトリにある。 */
    public static final String RACKS = "rack";
    /** ポッド。こちらも専用ディレクトリ。 */
    public static final String EQUIPMENT = "equipment";

    private static final ResourceLocation DEFAULT_ANIMATION =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "animations/weapon/default.animation.json");

    /**
     * 各兵装がどのファイルから描かれるか。一度求めて記憶する。
     *
     * <p>その両半分に対する支払い頻度が高すぎた。名前の構築は文字列連結の後に1文字ずつの検証を伴い、パックが提供
     * しているかの判定はリソースマネージャへの問い合わせ——パックスタック全体を歩き、最後はディスク上のファイル
     * 検索——を伴う。GeckoLib は描く物すべてのジオメトリとテクスチャを<em>毎フレーム</em>要求するし、満載の機体
     * ならそれが十数個ある——つまりミサイルで一杯の主翼は毎フレーム数十回のファイルシステム検索であり、しかも
     * 答えはリソースリロードでしか変わらない。
     *
     * <p>リロード時は {@link #clearCache()} が消す。レンダースレッドとクライアントtickの両方が触れるので並行
     * コレクションにしてある。半端に構築された古いマップは、検索コストより悪い。
     */
    private static final Map<Key, Files> FILES = new ConcurrentHashMap<>();

    /** 描画対象1つ。どのディレクトリの、どのファイルか。 */
    private record Key(String folder, ResourceLocation id) {
    }

    /** 1つの物を描く元の3ファイル。いずれもパックに対して解決済み。 */
    private record Files(ResourceLocation geometry, ResourceLocation texture, ResourceLocation animation) {
    }

    /** 描画対象がどの兵装か。 */
    protected abstract ResourceLocation weaponId(T animatable);

    /**
     * どのディレクトリから描くか。サブクラスが別途指定しない限り weapon。おかげでラックもポッドも、ミサイルと
     * 同じ3行で描ける。
     */
    protected String folder(T animatable) {
        return WEAPONS;
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return geometryFile(this.folder(animatable), this.weaponId(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return textureFile(this.folder(animatable), this.weaponId(animatable));
    }

    /** コントローラが名前付きアニメーションを再生する場合のみ参照される。ここにはそれを持つ物は無い。 */
    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return files(this.folder(animatable), this.weaponId(animatable)).animation();
    }

    /** ID から引く兵装の描画元ジオメトリ。{@link #getModelResource} の答え。 */
    public static ResourceLocation geometryFile(ResourceLocation weapon) {
        return geometryFile(WEAPONS, weapon);
    }

    /** ID から引く兵装の描画用テクスチャ。{@link #getTextureResource} の答え。 */
    public static ResourceLocation textureFile(ResourceLocation weapon) {
        return textureFile(WEAPONS, weapon);
    }

    /** これらのディレクトリにある物の描画元ジオメトリ。 */
    public static ResourceLocation geometryFile(String folder, ResourceLocation id) {
        return files(folder, id).geometry();
    }

    /** これらのディレクトリにある物の描画用テクスチャ。 */
    public static ResourceLocation textureFile(String folder, ResourceLocation id) {
        return files(folder, id).texture();
    }

    /**
     * 各兵装の描画元ファイルを忘れる。リソースパックのリロード時に呼ばれる。答えを変えうるのはそれだけなので、
     * 実行中に追加されたパックも、毎回問い直していた頃とまったく同じように反映される。
     */
    public static void clearCache() {
        FILES.clear();
    }

    private static Files files(String folder, ResourceLocation id) {
        return FILES.computeIfAbsent(new Key(folder, id), key -> new Files(
                found(file("geo/" + key.folder() + "/", key.id(), ".geo.json"),
                        fallback("geo/", key.folder(), ".geo.json")),
                found(file("textures/" + key.folder() + "/", key.id(), ".png"),
                        fallback("textures/", key.folder(), ".png")),
                DEFAULT_ANIMATION));
    }

    private static ResourceLocation file(String directory, ResourceLocation id, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                directory + id.getPath() + suffix);
    }

    /** ディレクトリごとのフォールバック先。同ディレクトリの {@code default} で、種類ごとに1つ。 */
    private static ResourceLocation fallback(String root, String folder, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
                root + folder + "/default" + suffix);
    }

    /**
     * どれかのリソースパックが提供していれば専用ファイル、無ければフォールバック。問い合わせは物1つにつき
     * リソースリロード1回あたり1度。{@link #FILES} 参照。
     */
    private static ResourceLocation found(ResourceLocation wanted, ResourceLocation fallback) {
        return Minecraft.getInstance().getResourceManager().getResource(wanted).isPresent()
                ? wanted
                : fallback;
    }
}
