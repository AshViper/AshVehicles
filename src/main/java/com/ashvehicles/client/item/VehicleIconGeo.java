package com.ashvehicles.client.item;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ashvehicles.client.model.TrackBelt;
import com.ashvehicles.client.model.VehicleGeoModel;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.RenderUtil;

/**
 * MOD の機体を、名前だけを頼りに作られたままの姿で描く。
 *
 * <p>アイテムの絵の背後にあるのがこれだ。エンティティも姿勢もアニメーションも時計も無い——機体IDが指すジオメトリ
 * とテクスチャを、ジオメトリファイルが残したポーズのまま描く。使うのは機体1つにつきちょうど2回、寸法を測るのに
 * 1回と写真を撮るのに1回で、クライアント稼働中それ以降は二度と使わない。{@link VehicleIcons} 参照。
 *
 * <p>ボーンをそのまま描く以外に唯一やるのが履帯を1周敷くことだ。リンク1つ分のボーンしか持たないモデルの戦車は、
 * 誰かが車輪の周りを歩かせるまで履帯の無い戦車であり、その写真は転輪剥き出しの車体の写真になってしまう。車両自身
 * のレンダラーが行うのと同じ呼び出しを、車輪を止めた状態で行う。
 */
public final class VehicleIconGeo extends GeoObjectRenderer<VehicleIconGeo.Machine> {
    private static final VehicleIconGeo INSTANCE = new VehicleIconGeo();

    /**
     * 描画中のモデル。描画開始時から保持する。履帯敷設が転輪をここから引く必要がある一方、ボーンループには渡されて
     * いないからだ。{@code GroundVehicleRenderer} と同様。
     */
    @Nullable
    private BakedGeoModel drawing;

    private VehicleIconGeo() {
        super(new Model());
    }

    /**
     * 機体を指定バッファへ、pose stack の原点と、呼び出し元が向けた座標系で描く。
     *
     * <p>最大輝度。絵はどこにも立っておらず、そこに光は無いからだ。陰影を付けるのは呼び出し元が設定した拡散光で
     * あって、ライトマップではない。
     */
    public static void draw(PoseStack poseStack, ResourceLocation vehicle, MultiBufferSource buffers) {
        Machine machine = Machine.of(vehicle);
        RenderType type = RenderType.entityCutoutNoCull(VehicleGeoModel.textureFile(vehicle));

        INSTANCE.render(poseStack, machine, buffers, type, buffers.getBuffer(type),
                LightTexture.FULL_BRIGHT, 0.0F);
    }

    /**
     * 描画時の向きに回した後、機体が占める空間の大きさ。
     *
     * <p>直立の箱の8隅を後から回すのではなく全頂点を測る。機体は角から見た細長い物であり、回した箱を囲む箱は半分が
     * 空気だからだ。これのおかげで、戦車とその4倍の長さの機体に同じ構図が使え、機体ごとの数値をどこにも置かずに
     * 済む。
     *
     * <p>GeckoLib が描画時に行うのと同じ走査——{@link RenderUtil} から同じボーン変換を同じ順序で——なので、測った物
     * が描かれる物になる。モデルが隠しているボーンを飛ばすのも同じ理由だ。
     */
    public static Bounds measure(ResourceLocation vehicle, Quaternionf view) {
        BakedGeoModel geometry = geometry(vehicle);
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(view);
        Bounds bounds = new Bounds();

        for (GeoBone bone : geometry.topLevelBones()) {
            measure(poseStack, bone, bounds);
        }

        return bounds;
    }

    /** 機体のベイク済みジオメトリ。モデル未ロードなら例外。それは呼び出し元の責任。 */
    public static BakedGeoModel geometry(ResourceLocation vehicle) {
        return INSTANCE.getGeoModel().getBakedModel(VehicleGeoModel.geometryFile(vehicle));
    }

    private static void measure(PoseStack poseStack, GeoBone bone, Bounds bounds) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, bone);

        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                poseStack.pushPose();
                RenderUtil.translateToPivotPoint(poseStack, cube);
                RenderUtil.rotateMatrixAroundCube(poseStack, cube);
                RenderUtil.translateAwayFromPivotPoint(poseStack, cube);
                measure(poseStack.last().pose(), cube, bounds);
                poseStack.popPose();
            }
        }

        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones()) {
                measure(poseStack, child, bounds);
            }
        }

        poseStack.popPose();
    }

    private static void measure(Matrix4f pose, GeoCube cube, Bounds bounds) {
        for (GeoQuad quad : cube.quads()) {
            // 立方体が奥行きを持たない面は、ベイク済みモデルに null として残る。
            if (quad == null) {
                continue;
            }

            for (GeoVertex vertex : quad.vertices()) {
                bounds.add(pose.transformPosition(new Vector3f(vertex.position())));
            }
        }
    }

    @Override
    public long getInstanceId(Machine animatable) {
        return animatable.id().hashCode();
    }

    /**
     * GeckoLib のオブジェクトレンダラーが描画対象をずらす半ブロック分の打ち消し。あちらは原点を角に持つブロック内
     * に収まる物を描く。機体は点に立つし、構図もその点を中心に決める。
     */
    @Override
    public void preRender(PoseStack poseStack, Machine animatable, BakedGeoModel model,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        this.drawing = model;

        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);
        poseStack.translate(-0.5F, -0.51F, -0.5F);
    }

    /** モデルが構成元のリンク1つを持つ場所に、履帯を1周分すべて敷く。 */
    @Override
    public void renderRecursively(PoseStack poseStack, Machine animatable, GeoBone bone, RenderType renderType,
            MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
            int packedLight, int packedOverlay, int colour) {
        BakedGeoModel model = this.drawing;

        if (model != null && TrackBelt.isLink(animatable.chassis(), bone)
                && TrackBelt.draw(model, animatable.chassis(), bone, 0.0F, Ride.LEVEL, 0.0F,
                        link -> super.renderRecursively(poseStack, animatable, link, renderType, bufferSource,
                                buffer, isReRender, partialTick, packedLight, packedOverlay, colour))) {
            return;
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
    }

    /** 物が占める空間を頂点1つずつ積み上げて求める。何か追加するまでは空。 */
    public static final class Bounds {
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        void add(Vector3f point) {
            this.minX = Math.min(this.minX, point.x());
            this.maxX = Math.max(this.maxX, point.x());
            this.minY = Math.min(this.minY, point.y());
            this.maxY = Math.max(this.maxY, point.y());
            this.minZ = Math.min(this.minZ, point.z());
            this.maxZ = Math.max(this.maxZ, point.z());
        }

        /** そもそも何か測れたか。何も無いモデルには構図を決められない。 */
        public boolean isEmpty() {
            return this.maxX < this.minX;
        }

        public float middleX() {
            return (this.minX + this.maxX) * 0.5F;
        }

        public float middleY() {
            return (this.minY + this.maxY) * 0.5F;
        }

        /** 絵の縦横のうち長い方。構図はこれに合わせて切る。 */
        public float across() {
            return Math.max(this.maxX - this.minX, this.maxY - this.minY);
        }

        public float nearest() {
            return this.maxZ;
        }

        public float furthest() {
            return this.minZ;
        }
    }

    /**
     * GeckoLib が描ける物としての機体。
     *
     * <p>ホルダー1つを、各機体の描画直前に順番にその機体へ向ける。ゴーストパスと同じやり方だ。ここには自前の状態
     * が何も無く、描画はシングルスレッドで、機体は1度描かれたら二度と描かれない。
     */
    public static final class Machine implements GeoAnimatable {
        private static final Machine INSTANCE = new Machine();

        private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

        private ResourceLocation id = null;
        private VehicleChassis.Model chassis = VehicleChassis.Model.DEFAULT;

        private Machine() {
        }

        static Machine of(ResourceLocation id) {
            INSTANCE.id = id;
            INSTANCE.chassis = chassisOf(id);

            return INSTANCE;
        }

        /**
         * 機体の種類を問わず、機体ファイルがモデルについて述べている内容。ここから読むのは履帯だけ。スケールは
         * 読まない。構図がそれを決めるからだ。
         */
        private static VehicleChassis.Model chassisOf(ResourceLocation id) {
            if (Definitions.VEHICLES.has(id)) {
                return Definitions.VEHICLES.get(id).model();
            }

            if (Definitions.AIRCRAFT.has(id)) {
                return Definitions.AIRCRAFT.get(id).model();
            }

            return VehicleChassis.Model.DEFAULT;
        }

        public ResourceLocation id() {
            return this.id;
        }

        VehicleChassis.Model chassis() {
            return this.chassis;
        }

        /** 無し。絵は1つの瞬間であり、その瞬間はモデルが作られた瞬間だ。 */
        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return this.cache;
        }

        @Override
        public double getTick(Object entity) {
            return 0.0;
        }
    }

    /** 他所と同様、機体自身の名前で見つかるジオメトリ・テクスチャ・アニメーション。 */
    private static final class Model extends GeoModel<Machine> {
        @Override
        public ResourceLocation getModelResource(Machine animatable) {
            return VehicleGeoModel.geometryFile(animatable.id());
        }

        @Override
        public ResourceLocation getTextureResource(Machine animatable) {
            return VehicleGeoModel.textureFile(animatable.id());
        }

        @Override
        public ResourceLocation getAnimationResource(Machine animatable) {
            return VehicleGeoModel.animationFile(animatable.id());
        }

        /** ここでは名前指定のボーンポーズは行わないが、アニメーションファイルの無い機体で落ちてはならない。 */
        @Override
        public boolean crashIfBoneMissing() {
            return false;
        }
    }
}
