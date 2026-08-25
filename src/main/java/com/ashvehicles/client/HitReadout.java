package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the last few rounds landed on what they were fired at, drawn on a picture of it.
 *
 * <p><b>Why it is needed at all.</b> A tank gunner firing at eight hundred metres sees a puff of
 * smoke and nothing else. Whether the round went into the turret front, into the tracks, or a foot
 * over the roof is invisible from here, and without an answer there is nothing to correct: the next
 * round is aimed at the same place with the same hope. Everything else on this screen is about
 * getting a round away; this is the only thing that says what happened when one arrived.
 *
 * <p><b>The target is drawn as the gunner saw it</b> — the machine's own model, turned to the bearing
 * the round came in on, which is taken from the round's own line of flight rather than from where
 * the shooter is standing now. So a shot into the side of a hull is drawn on a side view with the
 * mark low on the hull, and a shot at something head-on is drawn on its front. Nothing has to be
 * worked out about which way anybody was facing; the round already knew.
 *
 * <p><b>Marks are held against the box they went into</b>, as a fraction of it, rather than as a
 * point in the air near the vehicle. A turret that traverses between one round and the next
 * therefore carries its own hits round with it, and a mark on the mantlet stays on the mantlet.
 *
 * <p>A filled mark means the round went in. A hollow one means the armour threw it off — which is
 * the single most useful thing this instrument says, because it is the difference between aiming
 * somewhere else and firing again at the same place.
 *
 * <p>The whole thing is a snapshot of one engagement: a hit on something else clears it and starts
 * again, and it fades out a few seconds after the last round arrives rather than sitting in the
 * corner of the screen for the rest of the battle.
 */
public final class HitReadout {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 70;
    private static final int INSET = 8;
    /** The margin inside the frame the picture is not drawn in. */
    private static final int MARGIN = 6;
    /** The room kept at the top for the target's name and at the bottom for the tally. */
    private static final int HEADER = 12;
    private static final int FOOTER = 11;

    /** How long the readout stays up after the last round arrives, in milliseconds. */
    private static final long LINGER = 6000L;
    /** How much of that is spent fading, so it goes out rather than switching off. */
    private static final long FADE = 900L;
    /** How many marks are kept. A long burst from a cannon would otherwise fill the picture in. */
    private static final int MOST = 24;
    /**
     * How far into the screen the machine is drawn, and how far in front of its own skin a mark is
     * lifted, in blocks.
     *
     * <p>The second is what stops a mark disappearing inside the plate it is on. Where a round
     * struck is worked out against the collision boxes, and the model's own skin does not lie
     * exactly on those — so a mark left where the arithmetic put it can end up a few centimetres
     * inside the armour, which on a solid model is the same as not drawing it. Lifted towards the
     * viewer instead, by far less than the thickness of anything it could wrongly show through.
     */
    private static final float DEPTH = 90.0F;
    private static final float LIFT = 0.35F;

    /** Behind every mark, so that one on a green deck is still a mark. */
    private static final int BACKING = 0xC0000000;
    /** A round that went in. */
    private static final int STRIKE = AircraftHud.WARNING;
    /** A round the armour threw off, which is a different answer and gets a different colour. */
    private static final int BOUNCE = 0xFFFFD24A;

    /**
     * One round's arrival: which box it went into and whereabouts in that box, as a fraction of each
     * of the box's half-lengths.
     *
     * <p>Kept as a fraction rather than as a distance so that the mark is placed by the same
     * arithmetic that places the box itself — see {@link Silhouette} — and so rides the turret round
     * with it. A {@code box} of -1 is a machine with no boxes at all, where {@code within} is
     * measured in blocks from the middle instead.
     */
    private record Mark(int box, Vec3 within, boolean bounced) {
    }

    private static final List<Mark> MARKS = new ArrayList<>();

    /** Which machine is being reported on, so that hitting a second one starts a fresh picture. */
    private static int target = -1;
    @Nullable
    private static ResourceLocation machine;
    private static Vec3 line = new Vec3(0.0, 0.0, 1.0);
    private static float traverse;
    private static float gunPitch;
    private static float damage;
    private static long arrived;

    /** The machine kept about to be drawn, and which kind it is. See {@link #copyOf}. */
    @Nullable
    private static VehicleEntityBase drawn;
    @Nullable
    private static ResourceLocation drawnId;

    private HitReadout() {
    }

    /**
     * A round has arrived. Called from the packet the server sends the shooter and nobody else.
     *
     * @param struck the target's entity id, which is only ever compared: a hit on something new
     *               clears whatever was being shown
     * @param id which machine it is, for its shape and its name
     * @param box which of that shape's boxes was hit, or -1 for a machine that has none
     * @param within where in that box, as a fraction of each half-length
     * @param approach the way the round was travelling, in the machine's own axes
     * @param damage what it took off, or zero if the armour threw it off
     */
    public static void report(int struck, ResourceLocation id, int box, Vec3 within, Vec3 approach,
            float traverse, float gunPitch, float damage, boolean bounced) {
        long now = Util.getMillis();

        if (struck != target || !id.equals(machine) || now - arrived > LINGER) {
            MARKS.clear();
            HitReadout.damage = 0.0F;
        }

        target = struck;
        machine = id;
        line = approach;
        HitReadout.traverse = traverse;
        HitReadout.gunPitch = gunPitch;
        HitReadout.damage += damage;
        arrived = now;

        if (MARKS.size() >= MOST) {
            MARKS.remove(0);
        }

        MARKS.add(new Mark(box, within, bounced));
    }

    /** Draws it in the top right-hand corner, or draws nothing if nothing has been hit lately. */
    static void draw(GuiGraphics graphics, Font font) {
        ResourceLocation id = machine;

        if (id == null || MARKS.isEmpty()) {
            return;
        }

        long age = Util.getMillis() - arrived;

        if (age > LINGER) {
            return;
        }

        float alpha = age > LINGER - FADE ? (float) (LINGER - age) / FADE : 1.0F;
        int left = graphics.guiWidth() - INSET - WIDTH;
        int top = INSET;

        panel(graphics, left, top, alpha);
        name(graphics, font, id, left, top, alpha);
        tally(graphics, font, left, top, alpha);
        picture(graphics, id, left, top, alpha);
    }

    private static void panel(GuiGraphics graphics, int left, int top, float alpha) {
        int right = left + WIDTH;
        int bottom = top + HEIGHT;
        int edge = fade(AircraftHud.DIM, alpha);

        graphics.fill(left, top, right, bottom, fade(AircraftHud.SHADOW, alpha));
        graphics.fill(left, top, right, top + 1, edge);
        graphics.fill(left, bottom - 1, right, bottom, edge);
        graphics.fill(left, top, left + 1, bottom, edge);
        graphics.fill(right - 1, top, right, bottom, edge);
    }

    /** What was hit, by the name the game gives it rather than by the id of its file. */
    private static void name(GuiGraphics graphics, Font font, ResourceLocation id, int left, int top,
            float alpha) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        String name = type == null ? id.getPath() : type.getDescription().getString();
        String text = font.plainSubstrByWidth(name.toUpperCase(Locale.ROOT), WIDTH - 8);

        graphics.drawString(font, text, left + 4, top + 3, fade(AircraftHud.GREEN, alpha), true);
    }

    /**
     * What the burst has come to: what it has taken off, and how many rounds it took.
     *
     * <p>The count is worth as much as the damage. A tally that keeps climbing while the damage does
     * not is a gun that cannot get through what it is shooting at, which is the moment to stop firing
     * and go somewhere else.
     */
    private static void tally(GuiGraphics graphics, Font font, int left, int top, float alpha) {
        int bounced = 0;

        for (Mark mark : MARKS) {
            if (mark.bounced()) {
                bounced++;
            }
        }

        String hurt = damage > 0.0F ? "DMG " + Math.round(damage)
                : bounced > 0 ? "RICOCHET" : "NO DAMAGE";
        String count = MARKS.size() + (bounced > 0 ? " HIT " + bounced + "R" : " HIT");
        int y = top + HEIGHT - FOOTER + 1;

        graphics.drawString(font, hurt, left + 4, y,
                fade(damage > 0.0F ? AircraftHud.GREEN : BOUNCE, alpha), true);
        graphics.drawString(font, count, left + WIDTH - 4 - font.width(count), y,
                fade(AircraftHud.DIM, alpha), true);
    }

    /**
     * The machine itself, and the marks on it.
     *
     * <p>Drawn as the model, not as an outline: the same geometry the thing has in the world, put
     * through the same renderer, so that a mark on the mantlet is on a mantlet the gunner
     * recognises. What is drawn is not the target itself, though — see {@link #copyOf}.
     *
     * <p><b>The marks are placed in the picture rather than on it.</b> Each one is worked out where
     * it belongs on the machine, run through the very matrix the model was drawn with, and laid down
     * at the depth that comes back. So a hit on the far side of a hull is hidden by the hull and one
     * on the near side is not, without anything here having to know which side of anything it is on.
     *
     * <p>The scale still comes from the collision boxes, which are the only account of the machine's
     * size that can be read without drawing it first.
     */
    private static void picture(GuiGraphics graphics, ResourceLocation id, int left, int top, float alpha) {
        Minecraft minecraft = Minecraft.getInstance();
        VehicleEntityBase machine = copyOf(id, minecraft.level);

        if (machine == null) {
            return;
        }

        VehicleShape shape = Definitions.shape(id);
        GroundVehicleDefinition stats = Definitions.VEHICLES.has(id) ? Definitions.VEHICLES.get(id) : null;
        Silhouette.View view = Silhouette.View.along(line);
        double[] extent = extent(machine, shape, stats, view);
        int width = WIDTH - 2 * MARGIN;
        int height = HEIGHT - HEADER - FOOTER;
        float scale = (float) Math.min(width / Math.max(extent[1] - extent[0], 0.5),
                height / Math.max(extent[3] - extent[2], 0.5));
        float middleAcross = (float) ((extent[0] + extent[1]) * 0.5);
        float middleAloft = (float) ((extent[2] + extent[3]) * 0.5);

        machine.poseForDrawing(new Quaternionf(), traverse, gunPitch);

        // Everything from here is cut off at the panel: the barrel runs out of it, and a long burst
        // walking off the back of a hull should stop at the frame rather than in the middle of the
        // reading underneath.
        graphics.enableScissor(left + 1, top + HEADER, left + WIDTH - 1, top + HEADER + height);

        PoseStack pose = graphics.pose();

        pose.pushPose();
        pose.translate(left + WIDTH / 2.0, top + HEADER + height / 2.0, DEPTH);
        pose.scale(scale, scale, -scale);
        // In blocks rather than pixels, since it is inside the scale: it slides the machine so that
        // the middle of what is drawn sits in the middle of the panel rather than its origin, which
        // on a tank is down between the tracks.
        pose.translate(-middleAcross, middleAloft, 0.0F);
        turn(pose, view);
        model(graphics, machine, pose);

        // Kept while it is still the matrix the model was drawn with, which is what makes a mark land
        // on the metal rather than near it.
        Matrix4f drawnWith = new Matrix4f(pose.last().pose());

        pose.popPose();

        // And then taken back out of whatever the screen's own matrix is. A layer of the HUD is not
        // handed a clean one — every layer drawn before this one has pushed it further forward — so a
        // depth read straight off the matrix above would have that offset in it twice over, and a
        // mark that should be buried in the far side of a hull would float in front of the near one.
        Matrix4f onScreen = new Matrix4f(pose.last().pose()).invert().mul(drawnWith);

        for (Mark spot : MARKS) {
            Vec3 on = where(spot, shape, stats);
            // The x is crossed over because the machine's own axes count it to the right and the
            // world counts it to the left. See Silhouette.
            Vector3f at = onScreen.transformPosition(
                    new Vector3f((float) -on.x, (float) on.y, (float) on.z));

            mark(graphics, Math.round(at.x), Math.round(at.y), Math.round(at.z + scale * LIFT),
                    spot.bounced(), alpha);
        }

        graphics.disableScissor();
    }

    /**
     * Turns the picture so that the machine is seen from wherever the round came in from.
     *
     * <p>Three turns and no thinking. The machine is spun about its own vertical until the line the
     * round came in on runs into the screen, tipped by however far above or below the horizontal
     * that line was, and then stood the right way up — which the last one is for, since the screen
     * counts its y downwards and the world counts it up.
     */
    private static void turn(PoseStack pose, Silhouette.View view) {
        Vec3 look = view.look();
        // Into the world's axes, where the machine's right-hand side lies along −x.
        float bearing = (float) Math.toDegrees(Mth.atan2(-look.x, look.z));
        float climb = (float) Math.toDegrees(Math.asin(Mth.clamp(look.y, -1.0, 1.0)));

        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.mulPose(Axis.XP.rotationDegrees(climb));
        pose.mulPose(Axis.YP.rotationDegrees(-bearing));
    }

    /**
     * Draws the machine, lit as an inventory model and at full brightness.
     *
     * <p>Bright on purpose: what was hit is usually a long way off with half of it in shadow, and an
     * instrument that reports the light on the target rather than the shape of it is no use.
     */
    private static void model(GuiGraphics graphics, VehicleEntityBase machine, PoseStack pose) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();

        Lighting.setupForEntityInInventory();
        dispatcher.setRenderShadow(false);
        dispatcher.render(machine, 0.0, 0.0, 0.0, 0.0F, 1.0F, pose, graphics.bufferSource(),
                LightTexture.FULL_BRIGHT);
        // Put down now rather than left in the batch, so that the marks go over the top of it.
        graphics.flush();
        dispatcher.setRenderShadow(true);
        Lighting.setupFor3DItems();
    }

    /**
     * One mark, at the depth it sits at on the machine.
     *
     * <p>Filled where the round went in and hollow where the armour threw it off. A shape as well as
     * a colour, because which of the two happened is the whole point of the instrument and should
     * not rest on telling red from amber.
     */
    private static void mark(GuiGraphics graphics, int x, int y, int z, boolean bounced, float alpha) {
        int colour = fade(bounced ? BOUNCE : STRIKE, alpha);

        graphics.fill(RenderType.gui(), x - 3, y - 3, x + 3, y + 3, z, fade(BACKING, alpha));

        if (!bounced) {
            graphics.fill(RenderType.gui(), x - 2, y - 2, x + 2, y + 2, z, colour);

            return;
        }

        graphics.fill(RenderType.gui(), x - 2, y - 2, x + 2, y - 1, z, colour);
        graphics.fill(RenderType.gui(), x - 2, y + 1, x + 2, y + 2, z, colour);
        graphics.fill(RenderType.gui(), x - 2, y - 1, x - 1, y + 1, z, colour);
        graphics.fill(RenderType.gui(), x + 1, y - 1, x + 2, y + 1, z, colour);
    }

    /**
     * A machine of this kind that exists only to be drawn.
     *
     * <p><b>Not the target.</b> A round fired at anything worth this instrument is fired at something
     * a long way off, and a long way off is usually outside what the client is told about at all — so
     * by the time the readout goes up there is very often no such entity here to point a renderer at.
     * What is drawn instead is a fresh one of the same kind, made from the entity type, never added
     * to any world and never ticked: a mannequin, posed from what the server said about the real one
     * and thrown away when the next report is about something else.
     *
     * <p>Kept between frames because making one is not free and the readout is up for seconds at a
     * time. Thrown away when the kind changes, or when the level does — a mannequin still holding a
     * level the player has left would hold the whole of it.
     */
    @Nullable
    private static VehicleEntityBase copyOf(ResourceLocation id, @Nullable ClientLevel level) {
        if (level == null) {
            return null;
        }

        VehicleEntityBase kept = drawn;

        if (kept != null && id.equals(drawnId) && kept.level() == level) {
            return kept;
        }

        Entity made = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .map(type -> type.create(level))
                .orElse(null);

        drawn = made instanceof VehicleEntityBase machine ? machine : null;
        drawnId = id;

        return drawn;
    }

    /**
     * How much room the machine takes up in the picture, as the stretch its boxes cover: left,
     * right, bottom and top, in blocks.
     *
     * <p>Measured off the collision boxes because they are the only account of the machine's size
     * that can be read without drawing it, and the scale has to be settled before anything is drawn.
     * A machine that lists none falls back on the plain box its file gives it.
     */
    private static double[] extent(VehicleEntityBase machine, VehicleShape shape,
            @Nullable GroundVehicleDefinition stats, Silhouette.View view) {
        double[] extent = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};

        for (VehicleShape.Box box : shape.boxes()) {
            // The barrel is left out of the fit and drawn over the edge instead. A tank gun is half
            // again as long as the tank, and seen from the beam it would otherwise decide the whole
            // scale: the machine the marks are on would be squeezed into a third of the panel so
            // that a length of empty tube could have the rest.
            if (box.mount() == VehicleShape.Mount.GUN) {
                continue;
            }

            double[] flat = flatten(box, stats, view);

            extent[0] = Math.min(extent[0], flat[0]);
            extent[1] = Math.max(extent[1], flat[1]);
            extent[2] = Math.min(extent[2], flat[2]);
            extent[3] = Math.max(extent[3], flat[3]);
        }

        if (extent[0] <= extent[1]) {
            return extent;
        }

        double half = machine.hitbox().width() * 0.5;

        return new double[]{-half, half, 0.0, machine.hitbox().height()};
    }

    /** Where a mark sits in the machine's own axes, with the turret where it was when it arrived. */
    private static Vec3 where(Mark mark, VehicleShape shape, @Nullable GroundVehicleDefinition stats) {
        if (mark.box() < 0 || mark.box() >= shape.boxes().size()) {
            return mark.within();
        }

        VehicleShape.Box box = shape.boxes().get(mark.box());
        Quaternionf rotation = Silhouette.rotation(box, stats, traverse, gunPitch);
        Vec3 inside = new Vec3(
                -mark.within().x * box.size().x * 0.5,
                mark.within().y * box.size().y * 0.5,
                mark.within().z * box.size().z * 0.5);

        return Silhouette.centre(box, stats, traverse, gunPitch).add(Silhouette.turn(rotation, inside));
    }

    /**
     * One box flattened onto the picture, as the stretch of it the corners cover: left, right, bottom
     * and top, in blocks.
     */
    private static double[] flatten(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            Silhouette.View view) {
        Vec3 centre = Silhouette.centre(box, stats, traverse, gunPitch);
        Quaternionf rotation = Silhouette.rotation(box, stats, traverse, gunPitch);
        double halfX = box.size().x * 0.5;
        double halfY = box.size().y * 0.5;
        double halfZ = box.size().z * 0.5;
        double[] flat = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};

        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = centre.add(Silhouette.turn(rotation, new Vec3(
                    (corner & 1) == 0 ? -halfX : halfX,
                    (corner & 2) == 0 ? -halfY : halfY,
                    (corner & 4) == 0 ? -halfZ : halfZ)));
            double across = view.across(at);
            double aloft = view.aloft(at);

            flat[0] = Math.min(flat[0], across);
            flat[1] = Math.max(flat[1], across);
            flat[2] = Math.min(flat[2], aloft);
            flat[3] = Math.max(flat[3], aloft);
        }

        return flat;
    }

    /** The same colour, dimmed by however much of the readout's life is left. */
    private static int fade(int colour, float alpha) {
        int opacity = Math.round(((colour >>> 24) & 0xFF) * Mth.clamp(alpha, 0.0F, 1.0F));

        return (opacity << 24) | (colour & 0x00FFFFFF);
    }
}
