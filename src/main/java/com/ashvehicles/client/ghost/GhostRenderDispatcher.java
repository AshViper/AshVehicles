package com.ashvehicles.client.ghost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.ghost.dh.DHIntegration;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The ghost render pass: one pass of our own, run once a frame straight after the game's entity
 * loop, that draws every ghost the tick decided should be drawn.
 *
 * <h2>Where it runs</h2>
 *
 * <p>{@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}, and the choice matters. By then the game
 * has drawn its terrain and its entities, so the depth buffer holds everything a ghost should be
 * hidden behind that the game knows about; Distant Horizons drew its terrain long before (when the
 * solid layer came round), so a ghost is composited over that terrain, with {@link GhostOcclusion}
 * standing in for the depth it did not leave. And — this is why not {@code AFTER_ENTITIES} —
 * Distant Horizons' <em>vanilla fade</em> has also run: at the head of the translucent and
 * tripwire layers it repaints every pixel the game drew beyond its fade distance with its own
 * terrain wherever it has any ({@code fade/gl/vanilla_fade.frag}). A ghost drawn before that
 * pass is drawn and then painted over, and only survives where there is sky behind it; drawn after
 * it, it stays. The pose stack the event hands over is the entity loop's own — identity, with the
 * camera's rotation still on the model-view stack — so world-axis translations work exactly as
 * they do in the game's loop.
 *
 * <p>One caveat comes with that stage: on <em>Fabulous</em> graphics the game dispatches it with
 * the particle framebuffer bound, so ghosts are composited with the particle layer rather than
 * drawn straight into the main target. That layer carries a copy of the main depth buffer, so
 * they are still hidden by the same things and still land in the right place; only the blending
 * differs. On Fast and Fancy — where there is no transparency chain — the main target is bound
 * and the ghosts go straight into it.
 *
 * <h2>Who draws what</h2>
 *
 * <p>Ghosts are drawn by their adapters, from snapshots, nearest first, up to
 * {@link GhostConfig#maxGhosts()}. The game's own renderer is used in one case only, through
 * {@link EntityRenderDispatcher}: a ghost still in the {@link GhostLOD#FULL} tier that the game's
 * own loop declined to draw by its own test — standing where the client has no built chunk
 * section, or beyond the projection's far plane. That test is the game's own, so nothing is ever
 * drawn twice; and beyond {@code ghostStartDistance} the game's renderers stand down by asking
 * {@link #claims}.
 *
 * <h2>The far plane</h2>
 *
 * <p>The projection clips at {@code render distance × 64} blocks and nothing here moves it.
 * Instead a ghost beyond it is drawn nearer and smaller in the same measure: slid along the line
 * from the eye and shrunk by exactly as much as it was moved, it covers the same pixels in the
 * same place. The mapping keeps the order of ghosts — a further ghost is still drawn further — and
 * every pulled-in ghost stays beyond the loaded world, so depth against the game's terrain is
 * unaffected. See {@link #pull}.
 *
 * <h2>Fog and light</h2>
 *
 * <p>The pass draws in two phases, because a ghost near enough to stand in the world the client
 * has built is a different proposition from one out past the edge of it.
 *
 * <p><b>Inside the built world</b> — which, with the hand-over at {@code ghostStartDistance}, is
 * most of the {@link GhostLOD#GHOST} tier whenever the player's render distance reaches past it
 * — the client knows the light and the fog applies, so the ghost is drawn with the real light
 * value at its position and the fog left exactly as it is. Nothing about it then differs from what
 * the game's own loop would have drawn, which is the point: moving the hand-over inward must not
 * make an aeroplane at a hundred and fifty blocks glow in the dark.
 *
 * <p><b>Beyond it</b> the light level reads zero rather than unknown, so a ghost lit by it is a
 * black smear; and fog is a property of the shader rather than of the model, so the only way to
 * keep something out of it is to move the fog. Those ghosts are drawn full bright with the fog
 * planes pushed out, and the batch is flushed while they are still moved, or the change would land
 * on whatever is drawn next instead. The first phase is flushed before the fog moves at all, for
 * the same reason.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GhostRenderDispatcher {
    /** How far inside the far plane the nearest pulled-in ghost is put. */
    private static final double PULL_MARGIN = 0.85;
    /** How much further, as a fraction of that, the furthest possible ghost is put. Keeps the order. */
    private static final double PULL_SPREAD = 0.15;

    /** Nearest first. Held rather than built, since it is wanted once a frame and never changes. */
    private static final Comparator<EntityGhost> NEAREST_FIRST =
            Comparator.comparingDouble(EntityGhost::distanceSq);

    /**
     * The two working lists, kept between frames rather than made fresh each time.
     *
     * <p>The pass runs once a frame and only ever on the render thread, so there is no one to share
     * them with; and at a few hundred ghosts a frame the two lists were the only rubbish this whole
     * system made. Both are emptied at the head of the pass and hold nothing between frames.
     */
    private static final List<EntityGhost> GATHERED = new ArrayList<>();
    private static final List<Draw> DRAWS = new ArrayList<>();

    private static int drawnLastFrame;
    private static int culledLastFrame;
    private static double farPlaneLastFrame;

    private GhostRenderDispatcher() {
    }

    // ------------------------------------------------------------------
    // The handover
    // ------------------------------------------------------------------

    /**
     * Whether the ghost pass, rather than the game's entity loop, draws this entity this frame.
     * Asked by the game's own renderers from {@code shouldRender}.
     *
     * <p>Measured from the entity's tick position, which is also what its snapshot holds, so the
     * renderer and the pass reach the same answer for the same frame.
     *
     * <p>What is asked for is a <em>ghost</em>, not a type that has ghosts. The two are the same
     * thing very nearly always, and the case where they are not is the one failure this hand-over
     * must never have: a machine standing in plain sight with nothing drawing it at all. The
     * manager learns about entities from the join event and never scans the level, so a machine it
     * has no ghost of has none for the rest of its life, and standing the game's renderer down for
     * it would leave it invisible at a hundred and thirty blocks. Asking after the ghost itself
     * costs one lookup by UUID and cannot get that wrong.
     */
    public static boolean claims(Entity entity, double camX, double camY, double camZ) {
        if (!GhostConfig.enabled()) {
            return false;
        }

        if (EntityGhostManager.ghostOf(entity) == null) {
            return false;
        }

        return entity.position().distanceToSqr(camX, camY, camZ) >= GhostConfig.startSq();
    }

    // ------------------------------------------------------------------
    // The pass
    // ------------------------------------------------------------------

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level == null || minecraft.isPaused() || !GhostConfig.enabled()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        double farPlane = minecraft.gameRenderer.getDepthFar();
        double ghostBeyond = ghostStyleRadius();

        // Gathered first, so the fog is only moved when there is something to draw with it.
        List<EntityGhost> ghosts = GATHERED;
        ghosts.clear();
        ghosts.addAll(EntityGhostManager.ghosts());
        ghosts.sort(NEAREST_FIRST);

        if (ghosts.isEmpty()) {
            drawnLastFrame = culledLastFrame = 0;
            return;
        }

        int budget = GhostConfig.maxGhosts();
        int culled = 0;

        // Worked out first and drawn afterwards, because the two phases want the fog set
        // differently and nothing can be sorted into its phase until its position is known.
        List<Draw> draws = DRAWS;
        draws.clear();

        for (EntityGhost ghost : ghosts) {
            if (draws.size() >= budget) {
                ghost.record(ghost.lod(), ghost.distanceSq(), GhostVerdict.BUDGET);
                continue;
            }

            // The tier is decided from the tick position, which is what the game's renderers
            // measure in claims(); the draw itself is at the interpolated one.
            double distanceSq = ghost.current().position().distanceToSqr(eye);
            GhostLOD lod = GhostLOD.of(distanceSq);
            Vec3 position = ghost.position(partialTick);
            GhostVerdict verdict;

            if (lod == GhostLOD.FULL) {
                // The game's tier. It draws the entity itself unless its own loop declined to, in
                // which case the entity is drawn here with the game's own renderer.
                Entity entity = ghost.entity();

                if (entity == null || gameDraws(entity, minecraft, eye, farPlane)) {
                    verdict = GhostVerdict.GAME;
                } else if (ghost.isOccluded()) {
                    verdict = GhostVerdict.OCCLUDED;
                } else {
                    draws.add(Draw.ofEntity(entity, minecraft, distanceSq, ghostBeyond, partialTick, dispatcher));
                    verdict = GhostVerdict.DRAWN;
                }
            } else if (lod.isGhost()) {
                GhostSnapshot snapshot = ghost.current();
                double pull = pull(Math.sqrt(distanceSq), farPlane);

                // Inside the built world the depth buffer settles what is hidden — per pixel, by
                // the ground actually in the way — and no line is traced at all (see
                // GhostOcclusion). The flag can still be holding the answer given while the machine
                // was out past the world, and it is only re-asked every few ticks: taken as read
                // here, a machine coming in over the edge of the loaded world blinks out for as
                // long as that takes.
                if (ghost.isOccluded() && !isBuilt(BlockPos.containing(position))) {
                    verdict = GhostVerdict.OCCLUDED;
                } else if (!inView(frustum, snapshot, position, eye, pull)) {
                    culled++;
                    verdict = GhostVerdict.CULLED;
                } else {
                    draws.add(Draw.ofGhost(ghost, lod, position, minecraft, distanceSq, ghostBeyond, pull));
                    verdict = GhostVerdict.DRAWN;
                }
            } else {
                verdict = GhostVerdict.HIDDEN;
            }

            ghost.record(lod, distanceSq, verdict);
        }

        int drawn = draws.size();

        // Phase one: everything standing in the world the client has built, with that world's own
        // light and its own fog, so that it is drawn exactly as the game would have drawn it.
        // Flushed before the fog is touched.
        for (Draw draw : draws) {
            if (draw.inWorld()) {
                draw.render(eye, partialTick, poseStack, buffers, camera, dispatcher, farPlane);
            }
        }

        buffers.endBatch();

        // Phase two: everything past it, lit by nothing and kept out of the fog.
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        try {
            for (Draw draw : draws) {
                if (!draw.inWorld()) {
                    draw.render(eye, partialTick, poseStack, buffers, camera, dispatcher, farPlane);
                }
            }

            // While the fog is still moved. Batched geometry drawn after this point would otherwise
            // be the geometry that came out of the fog, and it would be the wrong geometry.
            buffers.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
        }

        drawnLastFrame = drawn;
        culledLastFrame = culled;
        farPlaneLastFrame = farPlane;

        if (GhostConfig.debugBoxes()) {
            GhostDebug.drawBoxes(ghosts, eye, partialTick, poseStack, buffers, farPlane);
        }

        // Nothing is held on to between frames: a ghost kept alive here would outlive its entity.
        draws.clear();
        ghosts.clear();
    }

    // ------------------------------------------------------------------
    // One thing to draw
    // ------------------------------------------------------------------

    /**
     * One ghost, or one entity, worked out and waiting its turn: which phase it belongs to, what
     * light it takes, and how far it must be pulled in.
     *
     * @param ghost the ghost, when its adapter is drawing it from a snapshot
     * @param entity the entity, when the game's own renderer is drawing it
     * @param inWorld whether it stands where the client has a built chunk section, and so whether
     *        the world's light and the world's fog mean anything for it
     */
    private record Draw(@Nullable EntityGhost ghost, @Nullable Entity entity, GhostLOD lod,
            @Nullable Vec3 position, double distanceSq, double pull, int light, boolean ghostStyle,
            boolean inWorld) {

        static Draw ofGhost(EntityGhost ghost, GhostLOD lod, Vec3 position, Minecraft minecraft,
                double distanceSq, double ghostBeyond, double pull) {
            boolean inWorld = isBuilt(BlockPos.containing(position));
            // Lit where it stands, but only where there is somewhere for it to stand: outside the
            // built world the level answers "no light" rather than "I do not know".
            int light = inWorld
                    ? LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(ghost.current()
                            .centre().subtract(ghost.current().position()).add(position)))
                    : LightTexture.FULL_BRIGHT;

            ghost.recordLight(light, inWorld);

            return new Draw(ghost, null, lod, position, distanceSq, pull, light,
                    distanceSq >= ghostBeyond * ghostBeyond, inWorld);
        }

        static Draw ofEntity(Entity entity, Minecraft minecraft, double distanceSq, double ghostBeyond,
                float partialTick, EntityRenderDispatcher dispatcher) {
            boolean inWorld = isBuilt(entity.blockPosition());
            // The renderer's own answer, which is the one the game's loop would have used.
            int light = inWorld
                    ? dispatcher.getPackedLightCoords(entity, partialTick)
                    : LightTexture.FULL_BRIGHT;

            return new Draw(null, entity, GhostLOD.FULL, null, distanceSq, 0.0, light,
                    distanceSq >= ghostBeyond * ghostBeyond, inWorld);
        }

        void render(Vec3 eye, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                Camera camera, EntityRenderDispatcher dispatcher, double farPlane) {
            if (this.entity != null) {
                drawWithGameRenderer(this.entity, eye, partialTick, poseStack, buffers, dispatcher,
                        farPlane, this.ghostStyle, this.light);
                return;
            }

            EntityGhost drawing = this.ghost;
            Vec3 to = this.position.subtract(eye);
            GhostRenderContext context = new GhostRenderContext(poseStack, buffers, camera, partialTick,
                    this.light, this.ghostStyle, this.distanceSq);

            poseStack.pushPose();
            poseStack.scale((float) this.pull, (float) this.pull, (float) this.pull);
            poseStack.translate(to.x, to.y, to.z);
            GhostRenderContext.enter(this.ghostStyle);

            try {
                drawing.adapter().render(drawing, this.lod, context);
            } finally {
                GhostRenderContext.exit();
                poseStack.popPose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Drawing with the game's own renderer
    // ------------------------------------------------------------------

    /**
     * Draws a live entity with its registered renderer, pulled in towards the far plane if need
     * be. The scale goes on before the offset, which is what makes the pull work: everything the
     * renderer does — the offset to the entity included — happens inside it.
     */
    private static void drawWithGameRenderer(Entity entity, Vec3 eye, float partialTick, PoseStack poseStack,
            MultiBufferSource buffers, EntityRenderDispatcher dispatcher, double farPlane, boolean ghostStyle,
            int light) {
        Vec3 to = new Vec3(
                Mth.lerp(partialTick, entity.xOld, entity.getX()),
                Mth.lerp(partialTick, entity.yOld, entity.getY()),
                Mth.lerp(partialTick, entity.zOld, entity.getZ())).subtract(eye);
        double away = to.length();

        if (away < 1.0E-4) {
            return;
        }

        float pull = (float) pull(away, farPlane);
        float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());

        poseStack.pushPose();
        poseStack.scale(pull, pull, pull);
        GhostRenderContext.enter(ghostStyle);

        try {
            dispatcher.render(entity, to.x, to.y, to.z, yaw, partialTick, poseStack, buffers, light);
        } finally {
            GhostRenderContext.exit();
            poseStack.popPose();
        }
    }

    /**
     * Whether the client has built the world at a point, and so whether the game has drawn terrain
     * around it this frame.
     *
     * <p>Asked for two things that sound different and are one question. What light something
     * standing there takes: inside the built world the level knows, outside it answers zero rather
     * than "I do not know". And whether the depth buffer can be trusted to hide it, which is
     * {@link GhostOcclusion}'s whole reason for existing or not.
     */
    static boolean isBuilt(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level != null && !minecraft.level.isOutsideBuildHeight(pos.getY())
                && minecraft.levelRenderer.isSectionCompiled(pos);
    }

    /**
     * Whether the game's own entity loop drew, or will draw, this entity this frame — by the
     * game's own test, so that nothing is drawn twice and nothing is missed.
     *
     * <p>The loop draws an entity only where the client has a built chunk section, or above the
     * build height; and the projection then clips it if it lies beyond the far plane.
     */
    static boolean gameDraws(Entity entity, Minecraft minecraft, Vec3 eye, double farPlane) {
        BlockPos pos = entity.blockPosition();
        boolean loopDraws = minecraft.level.isOutsideBuildHeight(pos.getY())
                || minecraft.levelRenderer.isSectionCompiled(pos);

        return loopDraws && entity.position().distanceToSqr(eye) < farPlane * farPlane;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The factor something this far away is scaled by, and moved by, to fit inside the far plane.
     *
     * <p>One for anything nearer than {@code PULL_MARGIN × far plane}. Beyond that, the drawn
     * distance rises from there towards {@code (PULL_MARGIN + PULL_SPREAD) × far plane} as the
     * true distance goes to infinity, so that two ghosts at different distances are still drawn
     * at different depths, in the right order, and neither reaches the plane itself.
     */
    static double pull(double away, double farPlane) {
        double reach = farPlane * PULL_MARGIN;

        if (away <= reach) {
            return 1.0;
        }

        double drawnAt = reach * (1.0 + PULL_SPREAD * (1.0 - reach / away));

        return drawnAt / away;
    }

    /** Whether the ghost's box, as it will be drawn, is in the frustum. */
    private static boolean inView(Frustum frustum, GhostSnapshot snapshot, Vec3 position, Vec3 eye, double pull) {
        AABB bounds = snapshot.bounds();

        if (pull >= 1.0) {
            return frustum.isVisible(bounds.move(position));
        }

        // Scaled about the eye, as the draw will be: the same part of the sky, nearer.
        Vec3 drawnAt = eye.add(position.subtract(eye).scale(pull));
        AABB drawn = new AABB(
                bounds.minX * pull, bounds.minY * pull, bounds.minZ * pull,
                bounds.maxX * pull, bounds.maxY * pull, bounds.maxZ * pull).move(drawnAt);

        return frustum.isVisible(drawn);
    }

    /**
     * How far out there is ground being drawn by somebody, in blocks. Beyond nine tenths of this a
     * ghost is drawn as a ghost — see-through, a contact against the sky — rather than as the thing
     * itself; inside it the thing has ground behind it and is drawn as what it is.
     */
    public static double ghostStyleRadius() {
        double vanilla = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;

        return Math.max(vanilla, DHIntegration.drawnRadius()) * 0.9;
    }

    // ------------------------------------------------------------------
    // Debug figures
    // ------------------------------------------------------------------

    public static int drawnLastFrame() {
        return drawnLastFrame;
    }

    public static int culledLastFrame() {
        return culledLastFrame;
    }

    /** Where the projection stopped last frame, which is what the pull is measured against. */
    public static double farPlaneLastFrame() {
        return farPlaneLastFrame;
    }
}
