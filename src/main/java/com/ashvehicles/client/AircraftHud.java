package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Quaternionf;

/**
 * The instruments, drawn over the world while the player is aboard an aircraft.
 *
 * <p>What is here is what a pilot actually reads: how fast, how high, which way up, which way round,
 * and what the engine and the moving parts are doing. Two marks sit in the middle of the screen and
 * are worth knowing apart. The boresight is where the nose is pointing. The flight path marker is
 * where the aircraft is actually going, which in a climb or a hard turn is not the same place, and
 * the gap between the two is the angle of attack made visible.
 *
 * <p>Everything is read from state that reaches every client, so a passenger sees the same
 * instruments as the pilot rather than a panel of zeroes.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class AircraftHud implements LayeredDraw.Layer {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft_hud");

    private static final int GREEN = 0xFF3BE86A;
    private static final int DIM = 0xB03BE86A;
    private static final int WARNING = 0xFFFF5A3B;
    private static final int SHADOW = 0x90000000;

    /** Fraction of the airframe left below which the readout goes amber. */
    private static final float LOW_HEALTH = 0.3F;

    /** Screen pixels the horizon slides for every degree of pitch. */
    private static final float PIXELS_PER_DEGREE = 3.0F;
    /** Rungs of the pitch ladder, in degrees. */
    private static final int[] LADDER = {10, 20, 30, 45, 60};
    private static final int LADDER_WIDTH = 34;
    private static final int HORIZON_WIDTH = 60;
    /**
     * How wide the bomb impact ring is on the ground, in blocks. Drawn at a size in the world rather
     * than a size on the screen, so it shrinks with distance like the ground it is lying on, and so
     * it says something: roughly what the bomb will take out when it gets there.
     */
    private static final double BOMB_RING_BLOCKS = 6.0;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, new AircraftHud());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)) {
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        Quaternionf attitude = aircraft.getAttitude(partialTick);
        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        drawAttitude(graphics, attitude, centreX, centreY);
        drawMarkers(graphics, minecraft, aircraft, attitude, velocity, speed, centreX, centreY);
        drawNumbers(graphics, minecraft.font, aircraft, attitude, velocity, speed);
        drawStatus(graphics, minecraft.font, aircraft, attitude, velocity, speed);
        drawStores(graphics, minecraft.font, aircraft, centreX, centreY);
        drawLock(graphics, minecraft, aircraft, centreX, centreY);
        drawBombSight(graphics, minecraft, aircraft, centreX, centreY);
        drawCrew(graphics, minecraft.font, aircraft);
    }

    /**
     * The seeker: a box round whatever the selected missile is looking at, closing as the lock takes
     * and turning solid once it has. Nothing is drawn for a weapon with no seeker, which is every
     * gun and every rocket.
     *
     * <p>The box is drawn where the target actually is on screen rather than at a fixed place, so it
     * is also how the pilot finds a target they have not spotted yet.
     */
    private static void drawLock(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            int centreX, int centreY) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();

        if (weapon == null || weapon.guidance().isEmpty()) {
            return;
        }

        Entity target = aircraft.getWeapons().lock().target();

        if (target == null) {
            String seeking = "SEEK";
            graphics.drawString(minecraft.font, seeking, centreX - minecraft.font.width(seeking) / 2,
                    centreY + 54, DIM, true);

            return;
        }

        boolean locked = aircraft.getWeapons().lock().isLocked();
        Vec3 middle = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int[] at = project(minecraft, middle.subtract(camera).normalize(), focalLength(minecraft, graphics),
                centreX, centreY);

        if (at != null) {
            // The box tightens as the lock closes, so how far along it is can be read without
            // looking away from the target.
            float progress = aircraft.getWeapons().lock().progress(weapon.guidance().get());
            int half = Math.round(Mth.lerp(progress, 26.0F, 11.0F));
            int colour = locked ? WARNING : GREEN;

            corner(graphics, at[0] - half, at[1] - half, 1, 1, colour);
            corner(graphics, at[0] + half, at[1] - half, -1, 1, colour);
            corner(graphics, at[0] - half, at[1] + half, 1, -1, colour);
            corner(graphics, at[0] + half, at[1] + half, -1, -1, colour);
        }

        String status = locked ? "LOCK" : "SEEK";
        int colour = locked ? WARNING : GREEN;
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        int range = (int) Math.round(aircraft.position().distanceTo(target.position()));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, centreX - minecraft.font.width(reach) / 2,
                centreY + 64, DIM, true);
    }

    /**
     * The sight over the nose, which is a different instrument depending on what is selected.
     *
     * <p>Every weapon is aimed differently and so deserves to be shown differently. A gun is aimed
     * by pointing the aeroplane, so it gets a pipper on the boresight. A rocket is aimed the same
     * way but arrives lower, so its mark is drawn open, as a reminder that it is not a promise. A
     * missile is not aimed at all — it is <em>given</em> something — so what matters is where its
     * seeker can see, and the ring drawn is exactly the cone it can lock inside. A bomb is aimed by
     * flying, and its mark is on the ground where it would land rather than up here at all.
     *
     * @param focal how many screen pixels one radian off the line of sight comes to, which is what
     *              turns the seeker's cone into a ring of the right size
     */
    private static void drawSight(GuiGraphics graphics, AircraftEntity aircraft, int x, int y, float focal) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();
        WeaponDefinition.Type type = weapon == null ? null : weapon.type();

        if (type == WeaponDefinition.Type.BOMB) {
            // Nothing over the nose: a bomb is not aimed there, and the impact ring says everything.
            return;
        }

        if (type == WeaponDefinition.Type.MISSILE && weapon.guidance().isPresent()) {
            // The seeker's cone, drawn at the size it really is. Put a target inside this ring and
            // the lock will take; outside it, nothing will happen however long the pilot waits.
            int radius = Math.round((float) Math.tan(Math.toRadians(weapon.guidance().get().lockAngle())) * focal);
            boolean locked = aircraft.getWeapons().lock().isLocked();

            circle(graphics, x, y, Mth.clamp(radius, 10, 220), locked ? WARNING : DIM);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, locked ? WARNING : DIM);

            return;
        }

        if (type == WeaponDefinition.Type.ROCKET) {
            // Open at the top and bottom: rockets leave along the nose and sag from there, so the
            // mark is deliberately not a closed promise of where they will go.
            graphics.fill(x - 7, y, x - 2, y + 1, GREEN);
            graphics.fill(x + 2, y, x + 7, y + 1, GREEN);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, GREEN);

            return;
        }

        // A gun, or nothing selected at all: the plain boresight the instruments always had.
        int colour = type == WeaponDefinition.Type.GUN ? GREEN : DIM;
        graphics.fill(x - 5, y, x + 5, y + 1, colour);
        graphics.fill(x, y - 5, x + 1, y + 5, colour);

        if (type == WeaponDefinition.Type.GUN) {
            // A pipper around it, so a gunsight reads as a gunsight rather than as a stray cross.
            circle(graphics, x, y, 9, DIM);
        }
    }

    /**
     * A circle, one pixel thick, by the midpoint algorithm.
     *
     * <p>Written out because there is nothing to draw one with: everything the GUI offers is an
     * upright rectangle, and a ring built out of a handful of those is a lozenge. This walks an
     * eighth of the circle and mirrors it into the other seven, which is exact and needs no
     * trigonometry per pixel.
     */
    private static void circle(GuiGraphics graphics, int centreX, int centreY, int radius, int colour) {
        if (radius < 1) {
            return;
        }

        int x = radius;
        int y = 0;
        int error = 1 - radius;

        while (x >= y) {
            plotOctants(graphics, centreX, centreY, x, y, colour);
            y++;

            if (error < 0) {
                error += 2 * y + 1;
            } else {
                x--;
                error += 2 * (y - x) + 1;
            }
        }
    }

    /** One point of a circle, reflected into all eight octants. */
    private static void plotOctants(GuiGraphics graphics, int centreX, int centreY, int x, int y, int colour) {
        pixel(graphics, centreX + x, centreY + y, colour);
        pixel(graphics, centreX + y, centreY + x, colour);
        pixel(graphics, centreX - y, centreY + x, colour);
        pixel(graphics, centreX - x, centreY + y, colour);
        pixel(graphics, centreX - x, centreY - y, colour);
        pixel(graphics, centreX - y, centreY - x, colour);
        pixel(graphics, centreX + y, centreY - x, colour);
        pixel(graphics, centreX + x, centreY - y, colour);
    }

    private static void pixel(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x, y, x + 1, y + 1, colour);
    }

    /**
     * Where a bomb released now would land, drawn on the ground it would land on.
     *
     * <p>Only for a bomb, because only a bomb needs it: everything else is pointed at what it is
     * meant to hit, and a bomb is let go and then watched. Without this, bombing is guesswork the
     * pilot cannot even learn from, since the answer arrives seconds after the decision.
     *
     * <p>The mark falls off the bottom of the screen when the aircraft is climbing away and the
     * bomb would land somewhere behind the viewer, which is the honest answer: from here, it would
     * not land anywhere you can see.
     */
    private static void drawBombSight(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            int centreX, int centreY) {
        WeaponDefinition weapon = aircraft.getWeapons().selectedWeapon();

        if (weapon == null || !weapon.isDropped() || aircraft.getWeapons().selectedAmmo() <= 0) {
            return;
        }

        Vec3 impact = BombSight.impact(aircraft, weapon);

        if (impact == null) {
            return;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int[] at = project(minecraft, impact.subtract(camera).normalize(),
                focalLength(minecraft, graphics), centreX, centreY);

        if (at == null) {
            return;
        }

        // A ring drawn on the ground it would land on, with a dot in the middle of it. The ring is
        // sized by how far away the impact is, so it sits over the ground the way a real mark would
        // rather than staying the same size on the screen whether the target is near or a mile off.
        double away = impact.distanceTo(camera);
        int radius = Mth.clamp((int) Math.round(BOMB_RING_BLOCKS / Math.max(away, 1.0)
                * focalLength(minecraft, graphics)), 4, 60);

        circle(graphics, at[0], at[1], radius, GREEN);
        graphics.fill(at[0] - 1, at[1] - 1, at[0] + 1, at[1] + 1, GREEN);

        // How far ahead it would fall, which is the number a pilot actually flies to.
        int range = (int) Math.round(aircraft.position().distanceTo(impact));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, at[0] - minecraft.font.width(reach) / 2, at[1] + 10, DIM, true);
    }

    /** One corner of the target box: two short strokes meeting at a right angle. */
    private static void corner(GuiGraphics graphics, int x, int y, int alongX, int alongY, int colour) {
        int arm = 6;
        graphics.fill(Math.min(x, x + alongX * arm), y, Math.max(x, x + alongX * arm), y + 1, colour);
        graphics.fill(x, Math.min(y, y + alongY * arm), x + 1, Math.max(y, y + alongY * arm), colour);
    }

    /** The artificial horizon: a line that rolls with the wings and slides with the nose. */
    private static void drawAttitude(GuiGraphics graphics, Quaternionf attitude, int centreX, int centreY) {
        float bank = Attitude.bank(attitude);
        float elevation = -Attitude.elevation(attitude);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, centreY, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(-bank));
        pose.translate(0.0F, elevation * PIXELS_PER_DEGREE, 0.0F);

        // The horizon itself, with a gap in the middle so it never hides the aircraft symbol.
        graphics.fill(-HORIZON_WIDTH, -1, -14, 0, GREEN);
        graphics.fill(14, -1, HORIZON_WIDTH, 0, GREEN);

        for (int rung : LADDER) {
            drawRung(graphics, rung);
            drawRung(graphics, -rung);
        }

        pose.popPose();

        // The aircraft itself, fixed to the screen: the horizon moves, this never does.
        graphics.fill(centreX - 13, centreY - 1, centreX - 4, centreY, GREEN);
        graphics.fill(centreX + 4, centreY - 1, centreX + 13, centreY, GREEN);
        graphics.fill(centreX - 1, centreY - 1, centreX + 1, centreY + 1, GREEN);
    }

    private static void drawRung(GuiGraphics graphics, int degrees) {
        int y = Math.round(-degrees * PIXELS_PER_DEGREE);
        int half = LADDER_WIDTH / 2;

        if (degrees > 0) {
            graphics.fill(-half, y, half, y + 1, DIM);
        } else {
            // Below the horizon the ladder is broken, the way it is on a real one.
            graphics.fill(-half, y, -half + 10, y + 1, DIM);
            graphics.fill(half - 10, y, half, y + 1, DIM);
        }
    }

    /** Where the nose points, and where the aircraft is actually going. */
    private static void drawMarkers(GuiGraphics graphics, Minecraft minecraft, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed, int centreX, int centreY) {
        float focal = focalLength(minecraft, graphics);
        Vec3 nose = Attitude.nose(attitude);

        int[] boresight = project(minecraft, nose, focal, centreX, centreY);

        if (boresight != null) {
            drawSight(graphics, aircraft, boresight[0], boresight[1], focal);
        }

        if (speed < 0.05) {
            return;
        }

        int[] path = project(minecraft, velocity.scale(1.0 / speed), focal, centreX, centreY);

        if (path != null) {
            // The classic circle and wings, near enough at this size.
            graphics.fill(path[0] - 3, path[1] - 3, path[0] + 4, path[1] - 2, GREEN);
            graphics.fill(path[0] - 3, path[1] + 3, path[0] + 4, path[1] + 4, GREEN);
            graphics.fill(path[0] - 4, path[1] - 3, path[0] - 3, path[1] + 4, GREEN);
            graphics.fill(path[0] + 3, path[1] - 3, path[0] + 4, path[1] + 4, GREEN);
            graphics.fill(path[0] - 9, path[1], path[0] - 4, path[1] + 1, GREEN);
            graphics.fill(path[0] + 4, path[1], path[0] + 9, path[1] + 1, GREEN);
        }
    }

    /** Airspeed on the left, altitude on the right, heading across the top: the usual places. */
    private static void drawNumbers(GuiGraphics graphics, Font font, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed) {
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // Blocks are metres and there are twenty ticks in a second.
        int kmh = (int) Math.round(speed * 20.0 * 3.6);
        int altitude = (int) Math.round(aircraft.getY());
        int climb = (int) Math.round(velocity.y * 20.0);
        int heading = Math.floorMod(Math.round(Attitude.heading(attitude)) + 180, 360);

        label(graphics, font, "SPD", centreX - 118, centreY - 14);
        value(graphics, font, kmh + " km/h", centreX - 118, centreY - 4);

        label(graphics, font, "ALT", centreX + 74, centreY - 14);
        value(graphics, font, altitude + " m", centreX + 74, centreY - 4);
        value(graphics, font, String.format("%+d m/s", climb), centreX + 74, centreY + 8);

        String compass = heading + "  " + cardinal(heading);
        graphics.drawString(font, compass, centreX - font.width(compass) / 2, centreY - 78, GREEN, true);
        graphics.fill(centreX - 1, centreY - 66, centreX + 1, centreY - 62, GREEN);
    }

    /** Engine, moving parts, and the two things that will bite: the angle of attack and the stall. */
    private static void drawStatus(GuiGraphics graphics, Font font, AircraftEntity aircraft,
            Quaternionf attitude, Vec3 velocity, double speed) {
        int left = 8;
        int bottom = graphics.guiHeight() - 8;

        // What is left of the airframe, and a warning colour once there is little enough of it that
        // the pilot should be thinking about home rather than about the fight.
        float health = aircraft.getHealth();
        int colour = aircraft.getHealthFraction() <= LOW_HEALTH ? WARNING : GREEN;
        value(graphics, font, String.format("HP %d/%d", Math.round(health), Math.round(aircraft.getMaxHealth())),
                left, bottom - 52, colour);

        int throttle = Math.round(aircraft.getThrottle() * 100.0F);
        value(graphics, font, "THR " + throttle + "%", left, bottom - 42);
        value(graphics, font, "GEAR " + (aircraft.isGearDown() ? "DOWN" : "UP"), left, bottom - 32);
        value(graphics, font, "FLAP " + (aircraft.isFlapsDown() ? "DOWN" : "UP"), left, bottom - 22);

        float stallAngle = aircraft.getStats().wing().stallAngle();
        float angleOfAttack = angleOfAttack(attitude, velocity, speed);
        boolean stalled = speed > 0.05 && !aircraft.onGround() && Math.abs(angleOfAttack) > stallAngle;

        value(graphics, font, String.format("AOA %+.0f", angleOfAttack), left, bottom - 12);
        value(graphics, font, String.format("%.1f G", aircraft.getLoadFactor(velocity)), left, bottom - 2);

        if (stalled) {
            String warning = "STALL";
            int centreX = graphics.guiWidth() / 2;
            graphics.drawString(font, warning, centreX - font.width(warning) / 2,
                    graphics.guiHeight() / 2 + 42, WARNING, true);
        }
    }

    /**
     * What is on the pylons: the selected weapon and its rounds, with everything else aboard listed
     * dimly beneath it, so the pilot can see what cycling would bring up next.
     *
     * <p>Nothing is drawn for an aircraft carrying nothing, which keeps the instruments of an
     * unarmed one exactly as they were.
     */
    private static void drawStores(GuiGraphics graphics, Font font, AircraftEntity aircraft, int centreX, int centreY) {
        List<ResourceLocation> carried = aircraft.getWeapons().carried();

        if (carried.isEmpty()) {
            return;
        }

        ResourceLocation selected = aircraft.getWeapons().selected();
        int right = graphics.guiWidth() - 8;
        int y = centreY + 30;

        label(graphics, font, "STORES", right - font.width("STORES"), y);
        y += 11;

        for (ResourceLocation weapon : carried) {
            boolean armed = weapon.equals(selected);
            int rounds = armed ? aircraft.getWeapons().selectedAmmo() : ammoOf(aircraft, weapon);
            String line = (armed ? "> " : "  ") + name(weapon) + "  " + rounds;
            int colour = rounds > 0 ? (armed ? GREEN : DIM) : WARNING;

            graphics.drawString(font, line, right - font.width(line), y, colour, true);
            y += 10;
        }
    }

    /** Rounds left across every mount carrying one particular weapon. */
    private static int ammoOf(AircraftEntity aircraft, ResourceLocation weapon) {
        int total = 0;

        for (WeaponMounts.Mount mount : aircraft.getWeapons().mounts()) {
            if (weapon.equals(mount.weapon())) {
                total += mount.ammo();
            }
        }

        return total;
    }

    /** A weapon's name as the instruments show it: its path, upper case, without the namespace. */
    private static String name(ResourceLocation weapon) {
        return weapon.getPath().replace('_', '-').toUpperCase(java.util.Locale.ROOT);
    }

    /** Who is aboard, pilot first. */
    private static void drawCrew(GuiGraphics graphics, Font font, AircraftEntity aircraft) {
        List<Entity> aboard = aircraft.getPassengers();
        Entity pilot = aircraft.getControllingPassenger();
        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() - 8 - aboard.size() * 10;

        label(graphics, font, "CREW", right - font.width("CREW"), y - 10);

        for (Entity rider : aboard) {
            String name = (rider == pilot ? "P  " : "-  ") + rider.getName().getString();
            graphics.drawString(font, name, right - font.width(name), y, rider == pilot ? GREEN : DIM, true);
            y += 10;
        }
    }

    /**
     * The angle the wing is meeting the airflow at. Worked out here rather than read off the
     * aircraft because only the client flying it runs the aerodynamics; every other client, a
     * passenger included, has to arrive at it from the attitude and where the aircraft is moving.
     */
    private static float angleOfAttack(Quaternionf attitude, Vec3 velocity, double speed) {
        if (speed < 1.0E-4) {
            return 0.0F;
        }

        Vec3 flow = velocity.scale(1.0 / speed);

        return (float) Math.toDegrees(Math.asin(Mth.clamp(-flow.dot(Attitude.up(attitude)), -1.0, 1.0)));
    }

    /**
     * Where a direction in the world lands on the screen. The perspective is a simple one: how far
     * off the line of sight something is, divided by how far along it, scaled by the focal length
     * the field of view implies. Returns null for anything behind the viewer.
     */
    private static int[] project(Minecraft minecraft, Vec3 direction, float focal, int centreX, int centreY) {
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 look = toVec3(camera.getLookVector());
        Vec3 up = toVec3(camera.getUpVector());
        Vec3 right = toVec3(camera.getLeftVector()).scale(-1.0);

        double along = direction.dot(look);

        if (along <= 0.05) {
            return null;
        }

        int x = centreX + (int) Math.round(direction.dot(right) / along * focal);
        int y = centreY - (int) Math.round(direction.dot(up) / along * focal);

        return new int[]{x, y};
    }

    private static float focalLength(Minecraft minecraft, GuiGraphics graphics) {
        double fov = minecraft.options.fov().get();

        return (float) (graphics.guiHeight() / 2.0 / Math.tan(Math.toRadians(fov) / 2.0));
    }

    private static String cardinal(int heading) {
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        return points[Math.floorMod((int) Math.round(heading / 45.0), 8)];
    }

    private static void label(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.drawString(font, text, x, y, DIM, true);
    }

    private static void value(GuiGraphics graphics, Font font, String text, int x, int y) {
        value(graphics, font, text, x, y, GREEN);
    }

    /** The same, in a colour of its own, for a reading that is worth noticing. */
    private static void value(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.fill(x - 2, y - 2, x + font.width(text) + 2, y + 10, SHADOW);
        graphics.drawString(font, text, x, y, colour, true);
    }

    private static Vec3 toVec3(org.joml.Vector3f vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}
