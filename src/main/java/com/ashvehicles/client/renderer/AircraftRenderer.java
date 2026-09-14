package com.ashvehicles.client.renderer;

import java.util.List;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.GunStations;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * あらゆる機体を描く。スケールと姿勢は機体自身から取る。
 *
 * <p>このレンダラーはゴースト開始距離まで機体を完全に描く。それを超えると降板し——{@link #shouldRender} が no を
 * 返す——代わりにゴーストパス（{@link GhostRenderDispatcher}）が {@code AircraftGhostAdapter} を通じてスナップ
 * ショットから描く。例外的にこのレンダラーが使われるのは、ゲーム自身のエンティティループが描画を拒否した機体
 * （下に構築済みチャンクセクションが無い）だ。その場合ゴーストパスが直接呼び、{@link GhostRenderContext} がその旨
 * を示す。
 *
 * <p>背後に何も無い機体は<em>ゴースト</em>として描く。同じ形状を半透明にし、実態通りに読ませる——遠方に見える
 * 接触点であって、目の前の世界の一部ではない。プレイヤーにまだ見えている地形の上——Distant Horizons を動かして
 * いる人にとってはそこにある物の大半——では、機体は機体のままだ。
 */
public class AircraftRenderer extends VehicleRenderer<AircraftEntity> {
    /** ゴーストの不透明度。空を背に読める程度で、近くの物と間違えるにははるかに足りない。 */
    private static final float GHOST_ALPHA = GhostGeoRenderer.GHOST_ALPHA;

    /** 1機の描画中だけ設定する。モデルのフックが描き方を知るため。 */
    private boolean drawingGhost;

    public AircraftRenderer(EntityRendererProvider.Context context) {
        super(context, new AircraftModel());
    }

    /**
     * 機体を描き、続いて主翼の下に吊られている物——ラック、その上の兵装、特殊ステーションのポッド——を描く。
     *
     * <p>描くのはプレイヤーが搭載したステーションだけ。機体内蔵の機銃は既にモデルの一部であり、その銃口に何かを
     * 描けば機首に浮いたポッドが付いてしまう。
     *
     * <p>遠距離に関する事柄——そもそもそこで描かれるか、投影に収まる近さへ引き寄せるか、霧から外すか——は全て
     * ゴーストパスの管轄で、この呼び出しの周りで起きる。ここで決めるのは「機体がどう見えるべきか」だけだ。
     */
    @Override
    public void render(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        // 半透明にするのは、ゴーストパスがこれを描いていて、かつ背後に本当に何も無いと言っている場合だけ。
        // 誰か——大抵は Distant Horizons——がまだ描いている地面の上では、機体は機体でありそう見えるべきだ。
        // ただ、そこには光の状態を語るチャンクが無いので、機体らしく照らしたり霧を掛けたりできないだけだ。
        this.drawingGhost = GhostRenderContext.isTranslucent();
        this.renderSolid(aircraft, yaw, partialTick, poseStack, bufferSource, packedLight);
    }

    private void renderSolid(AircraftEntity aircraft, float yaw, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        super.render(aircraft, yaw, partialTick, poseStack, bufferSource, packedLight);

        List<AircraftDefinition.Hardpoint> hardpoints = aircraft.getStats().hardpoints();
        List<WeaponMounts.Mount> mounts = aircraft.getWeapons().mounts();

        if (hardpoints.isEmpty()) {
            return;
        }

        WeaponMounts weapons = aircraft.getWeapons();
        GunStations stations = aircraft.getStations();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);
            WeaponMounts.Mount mount = mounts.get(slot);

            if (hardpoint.isFixed()) {
                continue;
            }

            // 特殊ステーションのポッドだけで他は無い。そこにラックは無いし、吊り下がる物も無い。
            if (mount.equipment() != null) {
                this.draw(MountedStore.equipment(mount.equipment()), hardpoint.pos(), aircraft,
                        partialTick, poseStack, bufferSource, packedLight);

                continue;
            }

            if (mount.rack() == null) {
                continue;
            }

            // 倉の中の搭載物は、扉が閉じきっている間は描かない。
            //
            // 動かして扉に追従させようとしたが、模型のどの姿勢が開でどの姿勢が閉かをファイルの数値から
            // 読む方法が無く、向きを3度外した。描かないことには向きが無い。閉じた倉の中身は誰にも見えない
            // ——それが倉である理由の全部だ——ので、見えない物を正しい位置へ動かす必要はそもそも無い。
            //
            // 開いている間は何も変えない。ハードポイントの座標は搭載物が見える状態に合わせて書かれており、
            // そこはこの機能が入る前とまったく同じ描き方になる。
            if (this.stowed(aircraft, hardpoint)) {
                continue;
            }

            // まずパイロン位置にラック、続いて各兵装をラック上の自分の位置へ。ラックは空になっても描き続ける。
            // 固定されている物であり、空の投下ラックを4本ぶら下げた主翼こそ、帰投中の機体の姿だ。
            this.draw(MountedStore.rack(mount.rack()), hardpoint.pos(), aircraft, partialTick,
                    poseStack, bufferSource, packedLight);

            List<WeaponMounts.Load> loads = mount.loads();

            for (int place = 0; place < loads.size(); place++) {
                WeaponMounts.Load load = loads.get(place);

                if (load.isEmpty() || isExpended(load)) {
                    continue;
                }

                // 兵器自身のジオメトリから描く。発射後にミサイルを描くのと同じファイルなので、吊られている兵装
                // と飛んでいる兵装が同じ見た目になる。
                //
                // 砲座が振っているパイロンの砲だけは、機体ではなく砲座の向きへ描く。弾はそちらへ出ていくので、
                // 真っ直ぐ前を向いたまま斜めへ撃つガンポッドは、照準そのものへの2つ目の食い違った答えになる。
                int station = stations.stationForSlot(slot);
                boolean laid = station != GunStations.NONE
                        && Definitions.weapon(load.weapon()).type() == WeaponDefinition.Type.GUN;

                this.draw(MountedStore.of(load.weapon()), weapons.placeOf(slot, place), aircraft,
                        partialTick, poseStack, bufferSource, packedLight,
                        laid ? stations.yawOf(station) : 0.0F, laid ? stations.pitchOf(station) : 0.0F);
            }
        }
    }

    /** 機体座標系の一点に物を1つ描く。機体に対して真っ直ぐ、つまり吊られている物のほとんど。 */
    private void draw(MountedStore store, Vec3 at, AircraftEntity aircraft, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        this.draw(store, at, aircraft, partialTick, poseStack, bufferSource, packedLight, 0.0F, 0.0F);
    }

    /**
     * その搭載物が、閉じた兵装倉の中にしまわれているか。倉の外の物と、扉を持たない機体では常に偽。
     *
     * <p>扉が動いている間は描く。閉じきるまで見えていて、扉がその上に閉まる——実際にそう見えるべきだし、
     * 開ける途中に中身が遅れて現れることも無くなる。
     */
    private boolean stowed(AircraftEntity aircraft, AircraftDefinition.Hardpoint hardpoint) {
        return hardpoint.internal() && aircraft.hasBay()
                && !aircraft.isBayOpen() && aircraft.isBaySettled();
    }

    /**
     * 同じ物を、取り付け点の周りに振って描く。
     *
     * <p>角度は機体に対する物で、正が右・正が上。この座標系は機体の姿勢と半回転を通った後なので、+X が右、
     * +Z が機尾方向になる——だから右へ振るのは Y 軸周りの<em>負</em>の回転であり、上へ向けるのは X 軸周りの
     * 正の回転になる。
     */
    private void draw(MountedStore store, Vec3 at, AircraftEntity aircraft, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            float yawDegrees, float pitchDegrees) {
        poseStack.pushPose();
        // ハードポイントを測った機体座標系へ入る。半回転はモデル由来だ。ジオメトリは北を向き、機体は機首を +Z
        // 方向として記述される。
        poseStack.mulPose(aircraft.getAttitude(partialTick));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate(at.x, at.y, -at.z);

        if (yawDegrees != 0.0F || pitchDegrees != 0.0F) {
            poseStack.mulPose(Axis.YP.rotationDegrees(-yawDegrees));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitchDegrees));
        }


        GeoObjectRenderer<MountedStore> renderer = MountedStore.renderer();
        ResourceLocation texture = renderer.getTextureLocation(store);
        // ゴーストの兵装もゴーストの一部だ。不透明のまま残せば、半透明の機体で唯一の不透明物になり、ミサイル
        // ではなくバグに見える。
        RenderType type = this.drawingGhost
                ? RenderType.entityTranslucentCull(texture)
                : RenderType.entityCutoutNoCull(texture);

        renderer.render(poseStack, store, bufferSource, type, bufferSource.getBuffer(type),
                packedLight, partialTick);
        poseStack.popPose();
    }

    /**
     * ゴーストは半透明で、しかも自ら光って描く。自ら光らせるのは何にも照らされないようにするためで、
     * クライアントが一度もロードしておらず光量値も持たない地面の上に立つ物には、それが唯一まともな答えだ。
     *
     * <p>裏面を捨てる型を使う理由は {@code GhostGeoRenderer.renderType} と同じ。閉じた立体を裏表なし・
     * 深度書き込みなしで半透明に描けば、向こう側の外板が手前の外板の上に乗る。
     */
    @Override
    public RenderType getRenderType(AircraftEntity animatable, ResourceLocation texture,
            MultiBufferSource bufferSource, float partialTick) {
        return this.drawingGhost
                ? RenderType.entityTranslucentCull(texture)
                : super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    @Override
    public Color getRenderColor(AircraftEntity animatable, float partialTick, int packedLight) {
        Color colour = super.getRenderColor(animatable, partialTick, packedLight);

        return this.drawingGhost
                ? Color.ofRGBA(colour.getRed(), colour.getGreen(), colour.getBlue(),
                        (int) (GHOST_ALPHA * 255.0F))
                : colour;
    }

    /**
     * ラック上のある位置に描く物が残っていないか。発射済みのミサイルは今や別の場所にあり、レールに写しを吊った
     * ままにすれば同じミサイルが2か所に存在することになる。
     *
     * <p>空のポッドはこの意味では消費済みではない。ロケットポッドやガンポッドはラックに固定された容器であり、
     * 中身が残っていようといまいとそこに留まる。
     */
    private static boolean isExpended(WeaponMounts.Load load) {
        return load.ammo() <= 0 && Definitions.weapon(load.weapon()).leavesRail();
    }

    @Override
    protected float scaleOf(AircraftEntity animatable) {
        return animatable.getStats().model().scale();
    }

    /**
     * 尾輪式の機体を、車輪が地面に着く高さまで下げてから姿勢を掛ける。
     *
     * <p>姿勢は機体の原点周りに回る。尾輪式が立っているだけで持っている機首上げ
     * （{@link AircraftDefinition.Undercarriage#groundStance}）をそこへ掛けると、支点にあるはずの主輪が
     * {@code sin(角) × 支点までの距離} だけ持ち上がる——機体全体が地面から浮いて見える。実機が尻を下ろすとき
     * に回るのは原点ではなく主輪の接地点なので、その差だけ機体を下げれば同じ絵になる。
     *
     * <p><b>姿勢を掛ける<em>前</em>に平行移動する。</b>ここでの平行移動はワールドの鉛直そのものであり、
     * {@link AircraftEntity#bodyOrigin} が当たり判定箱・座席・カメラ・銃口に対して行う下げ方と1文字も違わ
     * ない。姿勢の後（{@code applyBodyMotion}）に置くと機体軸で効いてしまい、傾いた分だけ描かれる模型と
     * 箱がずれる。
     *
     * <p><b>脚の出方に比例させる。</b>「今この瞬間に接地しているか」を訊く必要は無い——脚を上げた機体には
     * 車輪の高さを合わせるべき地面がそもそも無いし、空中で機体が 0.3 ブロック下にあることは誰にも見えない。
     * 脚の作動量は描画のために既に同期・補間されているので、新しい状態を1つも増やさずに、格納と同じ時間を
     * かけて滑らかに抜ける。
     *
     * <p>{@code ground_stance} を書かない機体（同梱の27機すべて）ではここは丸ごと素通りする。
     */
    @Override
    protected void applyRotations(AircraftEntity animatable, PoseStack poseStack, float ageInTicks,
            float rotationYaw, float partialTick, float nativeScale) {
        float sink = animatable.stanceSink(partialTick);

        if (sink != 0.0F) {
            poseStack.translate(0.0F, -sink, 0.0F);
        }

        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
    }

}
