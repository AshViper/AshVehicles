package com.ashvehicles.client;

import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;

/**
 * The vehicle from directly above, drawn in the corner as the machine itself: its own model, its own
 * turret where the turret really is, and its own barrel over whichever side the gunner has taken it.
 *
 * <p><b>Why a picture and not a number.</b> A tank crew's standing question is which way the hull is
 * pointing, and it is a question because the answer is nowhere else on the screen: the view goes
 * where the gunner looks, the turret follows it, and the hull stays wherever the driver last left
 * it. Driving off in the direction of the gun is the classic way into a ditch. A bearing in degrees
 * answers it only after a moment's arithmetic — and a moment is exactly what is not to hand when the
 * tracks are already moving.
 *
 * <p><b>The screen is what is held still, not the hull.</b> Up the panel is where the crew are
 * looking, so the turret sits near the top and it is the <em>hull</em> that swings about underneath
 * it. That is the way round the question is actually asked: not "what bearing is the turret on" but
 * "if I let the tracks out now, where do I end up relative to what I am looking at". The gap between
 * the barrel and the top of the panel is the traverse still to run, so a turret that has not caught
 * up with the view says so.
 *
 * <p><b>It is the real model.</b> Not an outline built out of the collision boxes — the machine as it
 * is drawn in the world, put through the same renderer, so the turret, the barrel, the recoil and the
 * running gear are all where the model has them and a vehicle added to a pack needs nothing written
 * for it here. It costs a second draw of one model a frame, which is the machine the player is
 * sitting in and no other.
 *
 * <p>The collision boxes are still read, but only to decide how far out the drawing has to reach: the
 * scale holds the whole circle the turret sweeps, so that a gun laid abeam is still inside the panel.
 */
final class PlanView {
    /** How big the panel is, in pixels. Square, because the turret's sweep is. */
    static final int SIZE = 62;
    /** How far in from the corner it sits. */
    static final int INSET = 8;
    /** The margin inside the frame that the machine is kept clear of. */
    private static final int MARGIN = 4;
    /**
     * How far into the screen the model is drawn.
     *
     * <p>In front of the panel it stands on, which is drawn at nothing, and far enough back that half
     * a tank's length of depth on either side of it still has somewhere to go.
     */
    private static final float DEPTH = 60.0F;

    private PlanView() {
    }

    /** Draws it in the bottom left-hand corner. */
    static void draw(GuiGraphics graphics, GroundVehicleEntity vehicle, float partialTick) {
        int left = INSET;
        int top = graphics.guiHeight() - INSET - SIZE;

        panel(graphics, left, top);

        float scale = (SIZE / 2.0F - MARGIN) / reach(vehicle);

        // Cut off at the frame. The scale is taken from the collision boxes and the model is what is
        // actually drawn, and nothing promises the second is no bigger than the first.
        graphics.enableScissor(left + 1, top + 1, left + SIZE - 1, top + SIZE - 1);
        model(graphics, vehicle, scale, left + SIZE / 2, top + SIZE / 2, partialTick);
        graphics.disableScissor();
    }

    /** The frame, and the mark at the top that says the top is where the crew are looking. */
    private static void panel(GuiGraphics graphics, int left, int top) {
        int right = left + SIZE;
        int bottom = top + SIZE;

        graphics.fill(left, top, right, bottom, AircraftHud.SHADOW);
        graphics.fill(left, top, right, top + 1, AircraftHud.DIM);
        graphics.fill(left, bottom - 1, right, bottom, AircraftHud.DIM);
        graphics.fill(left, top, left + 1, bottom, AircraftHud.DIM);
        graphics.fill(right - 1, top, right, bottom, AircraftHud.DIM);

        int middleX = left + SIZE / 2;

        for (int row = 0; row < 3; row++) {
            graphics.fill(middleX - row, top + 2 + row, middleX + row + 1, top + 3 + row, AircraftHud.DIM);
        }
    }

    /**
     * The machine, drawn from above with the line of sight up the screen.
     *
     * <p>Put through the entity renderer rather than through anything of this instrument's own, so
     * what is in the corner is the same model, at the same attitude and with the same turret angle,
     * as the thing out of the window. Two rotations do the whole of it: one tips the world over so
     * that looking down is looking into the screen, and the second spins it so that the bearing the
     * crew are looking along comes out at the top.
     *
     * <p>Lit as an inventory model and drawn at full brightness on purpose. A tank at the bottom of a
     * mine shaft at night is still a tank the driver has to be able to read, and an instrument that
     * goes dark with the sky is no instrument.
     */
    private static void model(GuiGraphics graphics, GroundVehicleEntity vehicle, float scale,
            int centreX, int centreY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        PoseStack pose = graphics.pose();
        // Where the crew are looking rather than where the hull is pointing, which is the whole point
        // of the instrument. Off the camera and not off the player, so the sight and the chase view
        // agree with each other.
        float bearing = minecraft.gameRenderer.getMainCamera().getYRot();

        pose.pushPose();
        pose.translate(centreX, centreY, DEPTH);
        // Blocks to pixels, and the z flipped because the screen counts depth the other way from the
        // world. The two turns are read right to left: the machine is spun about its own vertical
        // first, and the whole world is then tipped forward on to its face.
        pose.scale(scale, scale, -scale);
        pose.mulPose(Axis.XN.rotationDegrees(90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(bearing + 180.0F));

        Lighting.setupForEntityInInventory();
        // No shadow: there is no ground under it, and a shadow drawn on nothing is a black smear
        // across the panel.
        dispatcher.setRenderShadow(false);
        dispatcher.render(vehicle, 0.0, 0.0, 0.0, 0.0F, partialTick, pose, graphics.bufferSource(),
                LightTexture.FULL_BRIGHT);
        // Drawn now rather than left in the batch: everything after this on the screen is flat, and a
        // model still sitting in the buffer would come out over the top of it.
        graphics.flush();
        dispatcher.setRenderShadow(true);
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    /**
     * How far out from the middle the drawing has to hold, in blocks.
     *
     * <p>The hull is measured where it is, since it never moves. Anything on the turret is measured
     * as the circle it sweeps: a barrel that fits the panel over the bow and runs off the side of it
     * the moment the gun is laid abeam is an instrument that fails at exactly the angle it is being
     * consulted about.
     */
    private static float reach(GroundVehicleEntity vehicle) {
        GroundVehicleDefinition stats = vehicle.getStats();
        Vec3 ring = stats.turret().ring();
        double furthest = stats.hitbox().width() * 0.5;

        for (VehicleShape.Box box : vehicle.getShape().boxes()) {
            double halfWidth = box.size().x * 0.5;
            double halfLength = box.size().z * 0.5;

            if (box.mount() == VehicleShape.Mount.HULL) {
                furthest = Math.max(furthest, Math.abs(box.offset().x) + halfWidth);
                furthest = Math.max(furthest, Math.abs(box.offset().z) + halfLength);
            } else {
                double fromRing = Math.hypot(box.offset().x - ring.x, box.offset().z - ring.z);

                furthest = Math.max(furthest, Math.hypot(ring.x, ring.z) + fromRing
                        + Math.hypot(halfWidth, halfLength));
            }
        }

        return (float) Math.max(furthest, 0.5);
    }
}
