package com.ashvehicles.client.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ashvehicles.client.model.BakedGeometry;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.client.renderer.MountedStore;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.WeaponItem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

/**
 * パイロンに吊る物——ミサイル、爆弾、ロケットポッド、それを吊るラック、特殊ステーションのポッド——を、翼下に
 * 吊られているのとまったく同じジオメトリで描く。
 *
 * <p>機体アイテムとは意図的に違う道を通る。機体は{@link VehicleIcons 一度撮った写真}を貼った1枚のクアッドで、
 * 理由もそこに書いてある——スロット1つにつき数万個のクアッドを毎フレーム払う価値は無い。搭載物はその逆側に居る。
 * ミサイル1本は20個ほどの立方体で、機体の写真を1枚撮る費用よりも、それを毎フレーム描く費用の方が安い。しかも
 * 立体で描けば、平坦な絵には決して出せない物が付いてくる——手の中で回り、額縁の中で向きを変え、地面に落ちれば
 * 転がる。翼から外した物が手の中で同じ物に見える。
 *
 * <p>絵ではなく実物なので、テクスチャを誰かが描き直す必要も無い。{@link WeaponModel} が兵装・ラック・ポッドを
 * 名前で見つけるのと同じ経路なので、新しいミサイルに要るのは相変わらず定義ファイル・ジオメトリ・テクスチャだけ
 * で、アイテム用の絵は要らない。
 */
public final class StoreItemRenderer extends BlockEntityWithoutLevelRenderer {
    /**
     * スロットの中で搭載物をどう見せるか。
     *
     * <p>まず機首（モデル軸の -Z）を右へ振り、少しだけこちら側へ寄せる。次に画面内で持ち上げて対角線に乗せる。
     * ミサイルは全長の10倍細い物なので、水平に置けばスロットの高さの1割しか使わない——バニラが剣を対角線に描く
     * のと同じ理由で、対角線こそ細長い物が16×16で一番大きく読める向きだ。
     *
     * <p>15度ぶんこちらへ振ってあるのは、真横から見ると立体である事が分からないからだ。翼と尾翼が前後にずれて
     * 見える程度で足り、それ以上振れば全長が縮む。
     */
    private static final Quaternionf VIEW = new Quaternionf()
            .rotateZ(Mth.DEG_TO_RAD * 35.0F)
            .rotateY(Mth.DEG_TO_RAD * -105.0F);

    /**
     * 向けた後の搭載物がスロットのどれだけを埋めるか。1.0 でちょうど端に触れる。
     *
     * <p>少し残してあるのは、この寸法が個数表示や耐久バーではなく<em>ジオメトリ</em>の寸法だからだ。端まで使い
     * 切ると、翼の先が隣のスロットの物と地続きに見える。
     */
    private static final float FILL = 0.95F;

    /**
     * 各搭載物をどう置けばスロットに収まるか。求めるにはモデルの全頂点を歩く必要があり、答えはリソースリロード
     * でしか変わらないので、物1つにつき一度だけ求める。{@link WeaponModel} がファイル名を覚えているのと同じ
     * 理由だ。
     *
     * <p>並行コレクションなのも同じ理由による。書くのは描画スレッドだが、捨てるのはリロードの側だ。
     */
    private static final Map<Store, Fit> FITS = new ConcurrentHashMap<>();

    @Nullable
    private static StoreItemRenderer instance;

    /** 描く物1つ。{@link WeaponModel} の3ディレクトリのどれの、どのファイルか。 */
    private record Store(String folder, ResourceLocation id) {
    }

    /**
     * その物をスロットに収める置き方。{@link #VIEW} を掛けた後の中心と、そこから掛ける倍率。
     *
     * <p>倍率は実寸ではない。1本のスロットに入るのは1つだけなので、スロットの中では 3m のサイドワインダーも
     * 6m のフェニックスも同じ大きさで、どちらも同じだけ読める。実寸の比較が要る場面は
     * {@code vehicles-are-drawn-at-real-size} の通り機体の側にある。
     */
    private record Fit(Vector3f centre, float scale) {
    }

    private StoreItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    /**
     * 唯一のインスタンス。{@link VehicleItemRenderer#instance()} と同じ理由で、アイテム拡張の登録時ではなく
     * 最初にアイテムから要求されたときに構築する。
     */
    public static StoreItemRenderer instance() {
        StoreItemRenderer built = instance;

        if (built == null) {
            built = new StoreItemRenderer();
            instance = built;
        }

        return built;
    }

    /**
     * 各搭載物の置き方を忘れる。リソースリロード時に呼ばれる。ジオメトリが読み直された以上、そこから測った寸法
     * は全て古い。
     */
    public static void forget() {
        FITS.clear();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Store store = storeOf(stack.getItem());

        if (store == null) {
            return;
        }

        Fit fit = fitOf(store);

        // ジオメトリがまだ読み込まれていない。描く物は無く、次の1〜2フレームで決着する。
        if (fit == null) {
            return;
        }

        MountedStore drawn = MountedStore.of(store.folder(), store.id());
        GeoObjectRenderer<MountedStore> renderer = MountedStore.renderer();
        RenderType type = RenderType.entityCutoutNoCull(renderer.getTextureLocation(drawn));

        poseStack.pushPose();
        // アイテムのモデル空間は角を原点とする1ブロック。搭載物はその中心に、向けた後の箱の中心を合わせて置く。
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.scale(fit.scale(), fit.scale(), fit.scale());
        poseStack.translate(-fit.centre().x(), -fit.centre().y(), -fit.centre().z());
        poseStack.mulPose(VIEW);
        renderer.render(poseStack, drawn, buffers, type, buffers.getBuffer(type), packedLight, 0.0F);
        poseStack.popPose();
    }

    /** そのアイテムが吊る物なら、どのディレクトリのどのファイルか。違うなら null。 */
    @Nullable
    private static Store storeOf(Item item) {
        if (item instanceof WeaponItem weapon) {
            return new Store(WeaponModel.WEAPONS, weapon.getWeaponId());
        }

        if (item instanceof RackItem rack) {
            return new Store(WeaponModel.RACKS, rack.getRackId());
        }

        if (item instanceof EquipmentItem equipment) {
            return new Store(WeaponModel.EQUIPMENT, equipment.getEquipmentId());
        }

        return null;
    }

    /**
     * その物の置き方。まだジオメトリが読み込まれていなければ null で、覚えもしない——次のフレームには読み込まれて
     * いるかもしれないからだ。
     */
    @Nullable
    private static Fit fitOf(Store store) {
        Fit known = FITS.get(store);

        if (known != null) {
            return known;
        }

        BakedGeoModel geometry = GeckoLibCache.getBakedModels()
                .get(WeaponModel.geometryFile(store.folder(), store.id()));

        if (geometry == null) {
            return null;
        }

        Matrix4f view = new Matrix4f().rotate(VIEW);
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean any = false;

        for (GeoBone bone : geometry.topLevelBones()) {
            any |= measure(bone, view, min, max);
        }

        if (!any) {
            return null;
        }

        Fit fit = new Fit(new Vector3f(min).add(max).mul(0.5F), FILL / across(min, max));
        FITS.put(store, fit);

        return fit;
    }

    /**
     * ボーン1つ分と、その子。向けた後にジオメトリがどこまで届くかを積み上げる。
     *
     * <p>直立の箱の8隅を後から回すのではなく全頂点を測る。{@code VehicleIconGeo.measure} と同じ理由だ——細長い
     * 物を斜めに置いた後の箱は、回した箱を囲む箱より一回り小さい。ミサイルはまさにその細長い物であり、一回りは
     * そのままスロットの中での大きさになる。
     *
     * @return この枝に測れる物があったか
     */
    private static boolean measure(GeoBone bone, Matrix4f view, Vector3f min, Vector3f max) {
        BakedGeometry.Bounds bounds =
                BakedGeometry.bounds(bone, new Matrix4f(view).mul(BakedGeometry.toRoot(bone)));
        boolean any = bounds != null;

        if (bounds != null) {
            min.min(bounds.min());
            max.max(bounds.max());
        }

        for (GeoBone child : bone.getChildBones()) {
            any |= measure(child, view, min, max);
        }

        return any;
    }

    /** 3軸のうち一番長い辺。倍率はこれに合わせる。 */
    private static float across(Vector3f min, Vector3f max) {
        return Math.max(max.x() - min.x(), Math.max(max.y() - min.y(), max.z() - min.z()));
    }
}
