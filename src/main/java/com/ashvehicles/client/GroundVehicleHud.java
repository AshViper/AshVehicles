package com.ashvehicles.client;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.weapon.WeaponDefinition;

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

/**
 * The instruments, drawn over the world while the player is aboard a ground vehicle.
 *
 * <p>A tank crew read almost nothing an aircrew do. There is no altitude, no attitude worth showing
 * — the hull lies where the ground puts it and there is nothing to be done about that — and no
 * airspeed to keep above. What there is instead is the gun, and everything here is about the gun.
 *
 * <p><b>The sight is a different instrument depending on what is selected</b>, for the same reason
 * an aeroplane's is: a weapon is shown the way it is aimed. A gun is aimed by laying the turret, so
 * it gets a mark on the ground where the round will land and — on a vehicle whose file gives it a
 * radar, and only there — a second one against something moving, where the barrel has to be for the
 * round to arrive. See {@link GunSight}, which is the aeroplane's own sight asked about a turret
 * instead. Missiles are not aimed at all; they are <em>given</em> something, so what is drawn is the
 * seeker's cone and a box round whatever it has taken.
 *
 * <p><b>The gun mark is a ring, and it is the only mark on the screen.</b> Vanilla's crosshair is
 * taken off while anybody is aboard — see {@link CrewHudSuppressor} — because two marks are two
 * answers to the one question the crew are asking, and the crew will aim with whichever of them is
 * in the middle of the screen whether or not it is the one the gun is on.
 *
 * <p><b>It is still a mark on the world rather than a mark on the screen.</b> The gun is laid on the
 * middle of the view, so the ring settles there and the two agree — but only once the turret has
 * caught up: the crew look wherever they please and the turret follows at a couple of degrees a
 * tick, so for the first second of any traverse the ring is well off the middle, which is the sight
 * saying so. It also goes on the <em>point</em> the gun is laid on rather than merely along the
 * barrel, so what it reads is a range as well as a direction, and a round that will strike a ridge
 * short of the target puts the ring on the ridge.
 *
 * <p>Beside them, {@link PlanView}: the machine itself, from directly above, with the line of sight
 * up the panel and the hull swinging about underneath it. A tank driver whose turret is laid abeam
 * has no other way of knowing which way the hull is pointing, and driving off in the direction of
 * the gun is the classic way to end up in a ditch.
 *
 * <p>And, opposite the sight, {@link HitReadout}: where the last few rounds actually landed on what
 * they were fired at. Everything else here is about getting a round away; that is the only thing
 * that says what happened when one arrived, which at the range a tank gun is used at is not
 * something the crew can see for themselves.
 *
 * <p>Everything is read from state that reaches every client, so a passenger sees the same
 * instruments as the crew rather than a panel of zeroes.
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GroundVehicleHud implements LayeredDraw.Layer {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_hud");

    /** Fraction of the hull left below which the readout goes amber. */
    private static final float LOW_HEALTH = 0.3F;
    /** Rounds left below which the count goes amber. Two engagements' worth. */
    private static final int LOW_ROUNDS = 6;

    private static final int RELOAD_BAR_WIDTH = 62;
    /** The empty part of the reload bar: there, but not competing with the part that has filled. */
    private static final int TRACK = 0x40FFFFFF;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, new GroundVehicleHud());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.player.getVehicle() instanceof GroundVehicleEntity vehicle)) {
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // One or the other, never both: which weapon the trigger fires is which sight the crew are
        // looking through, and two marks on one screen would be two answers to one question.
        if (vehicle.isMissileMode()) {
            drawSeeker(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        } else {
            drawGunMark(graphics, minecraft, vehicle, partialTick, centreX, centreY);
        }

        drawCompass(graphics, minecraft.font, vehicle, partialTick, centreX, centreY);

        // The plan of the machine goes in the corner itself and the readings are moved over to make
        // room for it, so that the two share the bottom left-hand side rather than one being laid
        // over the other.
        PlanView.draw(graphics, vehicle, partialTick);
        drawStatus(graphics, minecraft.font, vehicle, partialTick, PlanView.SIZE + 6);
        drawCrew(graphics, minecraft.font, vehicle);
        // What the last few rounds did, if any of them have landed lately. Drawn from the crew's own
        // instruments rather than as a layer of its own, so that it is up while they are aboard
        // something and gone the moment they get out.
        HitReadout.draw(graphics, minecraft.font);
        // Only a machine whose file gives it a set draws either instrument, which is every launcher
        // and no tank.
        RadarDisplay.draw(graphics, minecraft.font, vehicle);
    }

    /**
     * Where the round will land, drawn out in the world rather than at the middle of the screen, and
     * against something moving, where the barrel has to be for it to get there.
     *
     * <p>Green with a round up the spout, amber without, so whether the gun can be fired is the same
     * glance as where it is pointing.
     */
    private static void drawGunMark(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        GunSight.Solution sight = GunSight.solve(vehicle);

        if (sight == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        // Rebuilt from this frame's bore rather than read off last tick's: only how far out the mark
        // sits is a tick old, and it tracks the barrel as smoothly as the barrel moves.
        Vec3 muzzle = sight.bore().muzzle(partialTick);
        Vec3 bore = sight.bore().direction(partialTick);
        Vec3 point = muzzle.add(bore.scale(sight.pipperRange())).add(sight.pipperDrop());
        int colour = vehicle.isLoaded() ? AircraftHud.GREEN : AircraftHud.WARNING;
        int[] mark = AircraftHud.project(minecraft, point.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark != null) {
            int x = mark[0];
            int y = mark[1];

            // A ring round the aiming point with a pip in the middle of it, which is the same mark
            // an aircraft's gun gets — see AircraftHud.drawGunSight. Open in the middle apart from
            // the pip, so what is being shot at is not hidden by the thing pointing at it.
            AircraftHud.circle(graphics, x, y, 9, colour);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, colour);

            // Stadia either side of the ring, which is what they are for: something of a known width
            // on the ground between them is something at a known range.
            graphics.fill(x - 15, y - 3, x - 14, y + 4, colour);
            graphics.fill(x + 15, y - 3, x + 16, y + 4, colour);

            if (sight.struck()) {
                String text = Math.round(sight.pipperRange()) + " m";

                graphics.drawString(minecraft.font, text, x - minecraft.font.width(text) / 2, y + 18,
                        AircraftHud.DIM, true);
            }
        }

        drawLead(graphics, minecraft, sight, partialTick, focal, camera, centreX, centreY);
    }

    /**
     * The lead: a diamond on the point the target will have reached by the time a round fired now
     * arrives, raised by the round's own drop. Put the gun mark on the diamond and fire.
     *
     * <p>This is what an anti-aircraft mounting is for and what a tank does not get. An aeroplane
     * crossing at a few hundred blocks is most of a second's flight away, and a gunner laying the
     * barrel on the aeroplane itself is laying it where the aeroplane <em>was</em> — but knowing
     * where it will be is a set measuring the range and the rate, so only a vehicle with a radar is
     * offered one at all. {@code GunSight.leads} is where that is decided; nothing is needed here
     * beyond drawing whatever it hands back, which for a tank is no target. Dim while the target is
     * beyond what the round can reach, green inside it, and amber — with the word — once the barrel
     * is close enough that firing now would hit.
     */
    private static void drawLead(GuiGraphics graphics, Minecraft minecraft, GunSight.Solution sight,
            float partialTick, float focal, Vec3 camera, int centreX, int centreY) {
        Entity target = sight.target();

        if (target == null || target.isRemoved()) {
            return;
        }

        // The target has moved since the tick the lead was worked out; the offset has not, so the
        // mark rides along with wherever the target is drawn this frame.
        Vec3 lead = target.getPosition(partialTick)
                .add(0.0, target.getBbHeight() * 0.5, 0.0)
                .add(sight.leadOffset());
        int[] mark = AircraftHud.project(minecraft, lead.subtract(camera).normalize(), focal, centreX, centreY);

        if (mark == null) {
            return;
        }

        int colour = !sight.inRange() ? AircraftHud.DIM
                : sight.onTarget() ? AircraftHud.WARNING : AircraftHud.GREEN;

        AircraftHud.diamond(graphics, mark[0], mark[1], 6, colour);

        String reach = Math.round(sight.targetRange()) + " m";
        graphics.drawString(minecraft.font, reach, mark[0] - minecraft.font.width(reach) / 2, mark[1] + 10,
                colour, true);

        if (sight.inRange() && sight.onTarget()) {
            String cue = "SHOOT";
            graphics.drawString(minecraft.font, cue, mark[0] - minecraft.font.width(cue) / 2, mark[1] + 20,
                    AircraftHud.WARNING, true);
        }
    }

    /**
     * The missile sight: the cone the seeker can see inside, and a box round whatever it has taken.
     *
     * <p>Nothing here is a point of aim. A missile is not laid on a target, it is handed one, so what
     * the crew are actually doing is holding the mounting still enough and long enough for the lock
     * to close — and the two things worth drawing are therefore where it can look and how far along
     * it has got. The ring is the seeker's own cone at the size it really is: put a target inside it
     * and the lock will take; outside it, nothing will happen however long the crew wait.
     */
    private static void drawSeeker(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        WeaponDefinition missile = missileOf(vehicle);

        if (missile == null) {
            return;
        }

        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 rail = vehicle.turretToWorld(vehicle.getStats().launcher().rail(), partialTick);
        Vec3 bore = vehicle.getAimDirection(partialTick);
        boolean locked = vehicle.isSeekerLocked();
        boolean loaded = vehicle.getMissiles() > 0 && vehicle.getMissileReload() <= 0;
        int[] boresight = AircraftHud.project(minecraft, rail.add(bore.scale(64.0)).subtract(camera).normalize(),
                focal, centreX, centreY);

        if (boresight != null && missile.guidance().isPresent()) {
            int radius = Math.round(
                    (float) Math.tan(Math.toRadians(missile.guidance().get().lockAngle())) * focal);
            int colour = locked ? AircraftHud.WARNING : loaded ? AircraftHud.GREEN : AircraftHud.DIM;

            AircraftHud.circle(graphics, boresight[0], boresight[1], Mth.clamp(radius, 10, 220), colour);
            graphics.fill(boresight[0] - 1, boresight[1] - 1, boresight[0] + 1, boresight[1] + 1, colour);
        }

        Entity target = vehicle.getSeekerTarget();

        if (target == null || target.isRemoved()) {
            String seeking = "SEEK";
            graphics.drawString(minecraft.font, seeking, centreX - minecraft.font.width(seeking) / 2,
                    centreY + 54, AircraftHud.DIM, true);

            return;
        }

        // The box is drawn where the target actually is on the screen rather than at a fixed place,
        // so it is also how the crew find something they have not spotted yet.
        Vec3 middle = target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0);
        int[] at = AircraftHud.project(minecraft, middle.subtract(camera).normalize(), focal, centreX, centreY);
        int colour = locked ? AircraftHud.WARNING : AircraftHud.GREEN;

        if (at != null) {
            // The box tightens as the lock closes, so how far along it is can be read without
            // looking away from the target.
            int half = Math.round(Mth.lerp(vehicle.getSeekerProgress(), 26.0F, 11.0F));

            AircraftHud.corner(graphics, at[0] - half, at[1] - half, 1, 1, colour);
            AircraftHud.corner(graphics, at[0] + half, at[1] - half, -1, 1, colour);
            AircraftHud.corner(graphics, at[0] - half, at[1] + half, 1, -1, colour);
            AircraftHud.corner(graphics, at[0] + half, at[1] + half, -1, -1, colour);
        }

        String status = locked ? "LOCK" : "SEEK";
        graphics.drawString(minecraft.font, status, centreX - minecraft.font.width(status) / 2,
                centreY + 54, colour, true);

        int range = (int) Math.round(vehicle.position().distanceTo(target.position()));
        String reach = range + " m";
        graphics.drawString(minecraft.font, reach, centreX - minecraft.font.width(reach) / 2,
                centreY + 64, AircraftHud.DIM, true);
    }

    /** The round in the tubes, or null for a vehicle that carries none. */
    @Nullable
    private static WeaponDefinition missileOf(GroundVehicleEntity vehicle) {
        return vehicle.getStats().launcher().missile().map(Definitions::weapon).orElse(null);
    }

    /** Which way the hull is pointing, on the same compass an aircraft gets. */
    private static void drawCompass(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            float partialTick, int centreX, int centreY) {
        int heading = Math.floorMod(Math.round(Attitude.heading(vehicle.getAttitude(partialTick))) + 180, 360);
        String compass = heading + "  " + AircraftHud.cardinal(heading);

        graphics.drawString(font, compass, centreX - font.width(compass) / 2, centreY - 78,
                AircraftHud.GREEN, true);
        graphics.fill(centreX - 1, centreY - 66, centreX + 1, centreY - 62, AircraftHud.GREEN);
    }

    /** What is left of the vehicle, how fast it is going, and what the armament has to say. */
    private static void drawStatus(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            float partialTick, int indent) {
        int left = 8 + indent;
        int bottom = graphics.guiHeight() - 8;

        float health = vehicle.getHealth();
        int healthColour = vehicle.getHealthFraction() <= LOW_HEALTH ? AircraftHud.WARNING : AircraftHud.GREEN;
        AircraftHud.value(graphics, font,
                String.format("HP %d/%d", Math.round(health), Math.round(vehicle.getMaxHealth())),
                left, bottom - 52, healthColour);

        // Blocks are metres and there are twenty ticks in a second. Signed, because a tank spends a
        // fair amount of its life going backwards and the driver should be told which it is.
        float speed = vehicle.getSpeed();
        int kmh = (int) Math.round(Math.abs(speed) * 20.0 * 3.6);
        String gear = speed < -0.001F ? " R" : "";
        AircraftHud.value(graphics, font, kmh + " km/h" + gear, left, bottom - 42);

        // The machine gun, which belongs to neither of the lines below. It is never selected — it is
        // simply there — so it is shown whatever the trigger is on, and it is shown in the second
        // column so that it does not read as a third thing the crew could be firing instead. Amber
        // once the belt is out, which is the only thing about it worth a second glance.
        if (vehicle.hasCoaxial()) {
            int belt = vehicle.getCoaxRounds();

            AircraftHud.value(graphics, font, String.format("MG %d/%d", belt, vehicle.getCoaxCapacity()),
                    left + 84, bottom - 20, belt > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);
        }

        boolean missiles = vehicle.isMissileMode();

        if (missiles) {
            drawTubes(graphics, font, vehicle, left, bottom);
        } else if (vehicle.getStats().armament().exists()) {
            drawGun(graphics, font, vehicle, left, bottom);
        } else {
            return;
        }

        // What the barrel is doing in the turret, which no mark out in the world can show: a mark on
        // a hillside looks the same at ten degrees of elevation as at two, and against an aeroplane
        // overhead how much is left before the mounting jams against its own stop is the difference
        // between a burst and a wasted second.
        int elevation = Math.round(vehicle.getGunPitch(partialTick));
        AircraftHud.value(graphics, font, String.format("ELV %+d°", elevation), left + 84, bottom - 32);

        // Which of the two the trigger would fire, for a vehicle carrying both. Nothing at all for
        // one carrying a single armament, where the answer is never in doubt.
        if (vehicle.hasMissiles() && vehicle.getStats().armament().exists()) {
            AircraftHud.value(graphics, font, missiles ? "SEL MSL" : "SEL GUN", left + 84, bottom - 42);
        }
    }

    /** The gun: rounds left, and the loader at work. */
    private static void drawGun(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle, int left, int bottom) {
        int rounds = vehicle.getRounds();

        AircraftHud.value(graphics, font, String.format("RDS %d/%d", rounds, vehicle.getRoundCapacity()),
                left, bottom - 32, rounds > LOW_ROUNDS ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (vehicle.getRounds() <= 0) {
            AircraftHud.value(graphics, font, "NO ROUNDS", left, bottom - 20, AircraftHud.WARNING);

            return;
        }

        if (vehicle.isLoaded()) {
            AircraftHud.value(graphics, font, "LOADED", left, bottom - 20);

            return;
        }

        drawWait(graphics, left, bottom - 20, vehicle.getReload(), vehicle.getReloadTicks());
    }

    /** The tubes: missiles left, and the wait before the next one may go. */
    private static void drawTubes(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle,
            int left, int bottom) {
        int tubes = vehicle.getMissiles();

        AircraftHud.value(graphics, font, String.format("MSL %d/%d", tubes, vehicle.getMissileCapacity()),
                left, bottom - 32, tubes > 0 ? AircraftHud.GREEN : AircraftHud.WARNING);

        if (tubes <= 0) {
            AircraftHud.value(graphics, font, "TUBES EMPTY", left, bottom - 20, AircraftHud.WARNING);

            return;
        }

        if (vehicle.getMissileReload() <= 0) {
            // Ready is not the same as able: a guided round with nothing to chase stays in the tube,
            // and the crew should be told which of the two is stopping them.
            boolean locked = vehicle.isSeekerLocked();

            AircraftHud.value(graphics, font, locked ? "READY" : "NO LOCK", left, bottom - 20,
                    locked ? AircraftHud.GREEN : AircraftHud.WARNING);

            return;
        }

        drawWait(graphics, left, bottom - 20, vehicle.getMissileReload(), vehicle.getMissileReloadTicks());
    }

    /**
     * The wait between rounds: a bar that fills as it runs down.
     *
     * <p>A bar rather than a number of seconds, because what the crew are actually deciding is
     * whether to stay where they are or pull back, and that is a question about how much of the wait
     * is left rather than about how many ticks it is.
     */
    private static void drawWait(GuiGraphics graphics, int x, int y, int left, int total) {
        float done = Mth.clamp(1.0F - (float) left / Math.max(total, 1), 0.0F, 1.0F);

        graphics.fill(x - 2, y - 2, x + RELOAD_BAR_WIDTH + 2, y + 8, AircraftHud.SHADOW);
        graphics.fill(x, y, x + RELOAD_BAR_WIDTH, y + 6, TRACK);
        graphics.fill(x, y, x + Math.round(RELOAD_BAR_WIDTH * done), y + 6, AircraftHud.WARNING);
    }

    private static void drawCrew(GuiGraphics graphics, Font font, GroundVehicleEntity vehicle) {
        List<Entity> aboard = vehicle.getPassengers();

        if (aboard.isEmpty()) {
            return;
        }

        Entity commander = vehicle.getControllingPassenger();
        int right = graphics.guiWidth() - 8;
        int y = graphics.guiHeight() - 8 - aboard.size() * 10;

        for (Entity rider : aboard) {
            String name = (rider == commander ? "C  " : "-  ") + rider.getName().getString();

            graphics.drawString(font, name, right - font.width(name), y,
                    rider == commander ? AircraftHud.GREEN : AircraftHud.DIM, true);
            y += 10;
        }
    }
}
