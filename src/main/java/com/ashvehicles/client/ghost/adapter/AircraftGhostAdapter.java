package com.ashvehicles.client.ghost.adapter;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.client.ghost.EntityGhost;
import com.ashvehicles.client.ghost.EntityGhostRenderer;
import com.ashvehicles.client.ghost.GhostAdapter;
import com.ashvehicles.client.ghost.GhostConfig;
import com.ashvehicles.client.ghost.GhostLOD;
import com.ashvehicles.client.ghost.GhostRenderContext;
import com.ashvehicles.client.ghost.GhostSnapshot;
import com.ashvehicles.client.ghost.geo.GhostAnimatable;
import com.ashvehicles.client.ghost.geo.GhostGeoRenderer;
import com.ashvehicles.client.ghost.geo.PlainVertices;
import com.ashvehicles.client.item.VehicleIcons;
import com.ashvehicles.client.model.AircraftAnimations;
import com.ashvehicles.client.model.AircraftModel;
import com.ashvehicles.client.model.WeaponModel;
import com.ashvehicles.client.renderer.MountedStore;
import com.ashvehicles.client.renderer.VehicleRenderer;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import org.joml.Quaternionf;

/**
 * ゴーストとしての機体。
 *
 * <p>スナップショットが運ぶのは機体の姿勢、モデルとアニメーションのファイル、可動部のポーズ、そして主翼の下に
 * 吊られている物。ゴーストはそれ以外から描かれない。結果として得られるのは機体そのままの姿だ。武装したモデル、
 * 最後の角速度で偏向した舵面、このtickのこの瞬間まで回されたローター、そして自身のアニメーションファイルから
 * サイクルを再生する降着装置——機体が自分用に登録するのと同じコントローラに、同じ2つの値を渡した物だ。別物になる
 * のはビルボード距離（有効時）だけで、そこでは機体のアイテムアイコンが平坦にカメラを向いて描かれる。
 *
 * <p>機体はどこにいても全クライアントへ送られる（{@code EntityTrackingMixin} 参照）ので、<em>飛行中に</em>
 * 受信が止まった機体は撃墜された機体であり、ゴーストも居残らず一緒に消える。だが駐機中の機体は別だ。自分の
 * チャンクを保持するのは飛んでいる間だけなので、全員がそこを離れれば機体は地面ごとサーバーからアンロードされる
 * ——消えたのではなく、世界ごと眠っただけ。誰もロードしていない土地に駐まった機体は変わりようがないから、最後に
 * 見えた姿で立たせ続けることが、そこにある物の唯一の正直な描画になる。だから静止していた機体のゴーストは
 * {@link GhostConfig#machineTimeoutTicks()} の間（既定では誰かが戻るまでずっと）残る。
 */
public final class AircraftGhostAdapter implements GhostAdapter<AircraftEntity> {
    /** これ未満の速度の二乗なら、その機体は駐まっていた——受信が止まった理由は撃墜ではなくアンロードだ。 */
    private static final double STATIONARY = 1.0E-2;

    @Override
    public boolean keepAfterLeave(AircraftEntity entity) {
        return entity.getVelocity().lengthSqr() < STATIONARY;
    }

    @Override
    public int orphanTicks() {
        return GhostConfig.machineTimeoutTicks();
    }

    // ------------------------------------------------------------------
    // スナップショット
    // ------------------------------------------------------------------

    @Override
    public GhostSnapshot snapshot(AircraftEntity aircraft, @Nullable GhostSnapshot previous, long gameTime) {
        ResourceLocation id = aircraft.getAircraftId();
        AircraftDefinition stats = aircraft.getStats();
        VehicleChassis.Model setup = stats.model();
        Vec3 position = aircraft.position();
        // 機体が描かれる範囲の箱であって、衝突に使う箱ではない。素の直方体は胴体しか覆わないので、それでカリング
        // したゴーストは、大部分がまだ画面内にある15m の機体が消える現象になる。これは引き継ぎの手前側でゲーム自身
        // のレンダラーが機体のカリングに使う箱と同じなので、機体が画面から外れるタイミングは引き継ぎを跨いでも
        // 変わらない。
        AABB bounds = aircraft.getBoundingBoxForCulling().move(position.reverse());
        Payload payload = new Payload(id, setup, AircraftModel.Pose.of(aircraft, 1.0F), stores(aircraft, stats),
                aircraft.isGearDown(), aircraft.getGearCycleTicks());

        return new GhostSnapshot(
                aircraft.getUUID(),
                aircraft.getId(),
                aircraft.getType(),
                position,
                aircraft.getDeltaMovement(),
                aircraft.getYRot(),
                aircraft.getXRot(),
                aircraft.getYRot(),
                new Quaternionf(aircraft.getAttitude()),
                setup.scale(),
                aircraft.isWrecked() ? VehicleRenderer.CHARRED : 1.0F,
                AircraftModel.geometryFile(id),
                AircraftModel.textureFile(id),
                AircraftModel.animationFile(id),
                this.billboard(id),
                bounds,
                true,
                gameTime,
                payload);
    }

    /**
     * ステーションに吊られている物すべて。機体座標系での位置、描画元のディレクトリ、その中のファイル。兵装だけで
     * なくラックとポッドも含む——空の投下ラックを4本積んだゴーストは、そう見えるべきだ。
     */
    private static List<Store> stores(AircraftEntity aircraft, AircraftDefinition stats) {
        List<AircraftDefinition.Hardpoint> hardpoints = stats.hardpoints();
        WeaponMounts weapons = aircraft.getWeapons();
        List<WeaponMounts.Mount> mounts = weapons.mounts();

        if (hardpoints.isEmpty()) {
            return List.of();
        }

        List<Store> stores = new ArrayList<>();

        for (int slot = 0; slot < Math.min(hardpoints.size(), mounts.size()); slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);
            WeaponMounts.Mount mount = mounts.get(slot);

            if (hardpoint.isFixed()) {
                continue;
            }

            if (mount.equipment() != null) {
                stores.add(new Store(hardpoint.pos(), WeaponModel.EQUIPMENT, mount.equipment()));

                continue;
            }

            if (mount.rack() == null) {
                continue;
            }

            stores.add(new Store(hardpoint.pos(), WeaponModel.RACKS, mount.rack()));

            List<WeaponMounts.Load> loads = mount.loads();

            for (int place = 0; place < loads.size(); place++) {
                WeaponMounts.Load load = loads.get(place);

                if (load.isEmpty()) {
                    continue;
                }

                // 発射済みのミサイルは今や別の場所にある。空のポッドは固定されたまま残る。
                if (load.ammo() <= 0 && Definitions.weapon(load.weapon()).leavesRail()) {
                    continue;
                }

                stores.add(new Store(weapons.placeOf(slot, place), WeaponModel.WEAPONS, load.weapon()));
            }
        }

        return stores;
    }

    /**
     * 機体アイテムの絵として描かれる物、つまり機体の絵。モデルを描く価値の無い距離での代役として正しい物だ。
     *
     * <p>ここでは保持しない。機体自身のジオメトリから一度撮影され {@link VehicleIcons} が保持する。あちらは最初の
     * 撮影が済むまでの1〜2フレーム、何も返さない——ビルボードを持たないスナップショットは、次が撮られるまで単に
     * モデルを描く。
     */
    @Nullable
    private ResourceLocation billboard(ResourceLocation id) {
        return VehicleIcons.of(id);
    }

    // ------------------------------------------------------------------
    // 描画
    // ------------------------------------------------------------------

    @Override
    public void render(EntityGhost ghost, GhostLOD lod, GhostRenderContext context) {
        GhostSnapshot snapshot = ghost.current();

        if (lod == GhostLOD.BILLBOARD || !GhostConfig.geckoLibGhosts()) {
            if (EntityGhostRenderer.drawBillboard(snapshot, context) || !GhostConfig.geckoLibGhosts()) {
                return;
            }
            // アイコンが無い。モデル自体で用は足りる。
        }

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        // 機体座標系へ入る。半回転はモデル由来だ。ジオメトリは北を向き、機体は機首を +Z 方向として記述される。
        poseStack.mulPose(attitude(ghost, context.partialTick()));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        EntityGhostRenderer.drawModel(ghost, snapshot, context, GhostConfig.animation() ? POSER : null);
        drawStores(snapshot, context);
        poseStack.popPose();
    }

    /** 描画に使う姿勢。直近2スナップショット間を近い側の経路で補間する。 */
    private static Quaternionf attitude(EntityGhost ghost, float partialTick) {
        Quaternionf now = ghost.current().attitude();
        Quaternionf then = ghost.previous().attitude();

        if (now == null) {
            return new Quaternionf();
        }

        if (then == null || then == now) {
            return now;
        }

        return new Quaternionf(then).slerp(now, partialTick).normalize();
    }

    /** 撮影時に主翼の下に吊られていた物。 */
    private static void drawStores(GhostSnapshot snapshot, GhostRenderContext context) {
        Payload payload = payload(snapshot);

        if (payload == null || payload.stores().isEmpty()) {
            return;
        }

        PoseStack poseStack = context.poseStack();
        MultiBufferSource buffers = context.buffers();
        GeoObjectRenderer<MountedStore> renderer = MountedStore.renderer();

        for (Store store : payload.stores()) {
            poseStack.pushPose();
            poseStack.translate(store.pos().x, store.pos().y, -store.pos().z);

            MountedStore mounted = MountedStore.of(store.folder(), store.weapon());
            ResourceLocation texture = renderer.getTextureLocation(mounted);
            // ゴーストの兵装もゴーストの一部だ。バッファの覆いも本体と同じ理由で同じ物を挟むし
            // （PlainVertices 参照）、DH の霧にも機体と同じだけ沈む（MountedStore.StoreRenderer 参照）。
            RenderType type = GhostGeoRenderer.renderType(texture, context);

            renderer.render(poseStack, mounted, buffers, type,
                    new PlainVertices(buffers.getBuffer(type)),
                    context.packedLight(), context.partialTick());
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------
    // 機体の動きに合わせて動かす
    // ------------------------------------------------------------------

    /**
     * 機体のうち飛行に毎瞬追従する要素すべてを、機体自身のモデルが機体から設定するのと同じやり方で、直近2つの
     * スナップショットから設定する。角速度に応じた舵面、行き先へ向かう途中の脚・フラップ・ノズル、前tick終端から
     * 今tickのこの瞬間まで回したローター。
     *
     * <p>最新の1つではなく2つの間で求める。このゴーストの隣に立つ機体に対してゲームが描くのがそれだからだ。任意の
     * 瞬間に画面に出ているのは、前々tickを前tickへブレンドした物である。最新スナップショットだけでポーズを付けた
     * ゴーストは1tick先行し、しかも毎tick跳ねる。
     */
    private static final GhostAnimatable.GhostPoser POSER = (model, ghost, partialTick) -> {
        Payload now = payload(ghost.current());

        if (now == null) {
            return;
        }

        Payload then = payload(ghost.previous());
        AircraftModel.Pose pose = then == null
                ? now.pose()
                : AircraftModel.Pose.between(then.pose(), now.pose(), partialTick);

        AircraftModel.applyPose(model, now.setup(), pose);
    };

    // ------------------------------------------------------------------
    // 降着装置
    // ------------------------------------------------------------------

    /**
     * 脚のサイクル。{@code AircraftEntity} が自分用に登録するのとまったく同じ形でゴースト用に登録する。同じ
     * アニメーションファイルの同じ2つの半分、両者間の同じブレンド、再生速度を決める同じ値。だからゴーストの脚は、
     * 扉も含めてファイルが述べる順序で出てくる。コードから概算で振るのではなく——それはファイルにサイクルを持たない
     * 機体が上の poser から受け取る扱いだ。
     */
    @Override
    public void registerGhostControllers(AnimatableManager.ControllerRegistrar controllers,
            GhostAnimatable animatable) {
        controllers.add(new AnimationController<>(animatable, "gear", AircraftAnimations.TRANSITION_TICKS,
                AircraftGhostAdapter::gearCycle).setAnimationSpeedHandler(AircraftGhostAdapter::gearSpeed));
    }

    /** どちらの半分を再生するか。パイロットが要求した状態で終わる方。 */
    private static PlayState gearCycle(AnimationState<GhostAnimatable> state) {
        Payload payload = payload(state.getAnimatable().snapshot());

        if (payload == null || payload.pose().sweepGear() || !GhostConfig.animation()) {
            return PlayState.STOP;
        }

        return state.setAndContinue(AircraftAnimations.cycleFor(payload.gearDown()));
    }

    /**
     * 再生速度。機体自身のサイクル時間から求める。脚が既に所定の状態にあるときはサイクル終端で保持する——だから
     * 脚を出した状態で視界に入ったゴーストは、今見た人のために改めて脚を下ろすのではなく、その脚の上に座っている。
     */
    private static double gearSpeed(GhostAnimatable animatable) {
        Payload payload = payload(animatable.snapshot());

        if (payload == null) {
            return 1.0;
        }

        boolean settled = payload.pose().gear() == (payload.gearDown() ? 1.0F : 0.0F);

        return AircraftAnimations.gearSpeed(animatable.snapshot().animation(), payload.gearDown(),
                payload.gearCycleTicks(), settled);
    }

    // ------------------------------------------------------------------
    // スナップショットが運ぶ物
    // ------------------------------------------------------------------

    @Nullable
    private static Payload payload(GhostSnapshot snapshot) {
        return (Payload) snapshot.payload();
    }

    /**
     * ステーション上の物1つ。機体座標系での位置、3つのディレクトリのどれから描くか、その中のどのファイルか。
     */
    record Store(Vec3 pos, String folder, ResourceLocation weapon) {
    }

    /**
     * スナップショットが運ぶ機体固有の情報すべて。
     *
     * @param gearDown パイロットが要求している降着装置の状態。ゴーストが再生するサイクルの半分を決める
     * @param gearCycleTicks この機体が脚を上げ下げするのに要する時間
     */
    record Payload(ResourceLocation aircraftId, VehicleChassis.Model setup, AircraftModel.Pose pose,
            List<Store> stores, boolean gearDown, int gearCycleTicks) {
    }
}
