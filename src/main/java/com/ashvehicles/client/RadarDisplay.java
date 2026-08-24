package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The two instruments that say what else is in the sky: the radar scope and the warning receiver.
 *
 * <p><b>The scope is a B-scope</b>, which is the picture a radar can honestly draw. Across is
 * bearing, off the nose, out to the edge of the sweep on either side; up the screen is range, from
 * the aeroplane at the bottom out to the radar's reach at the top. It is not a map — a contact
 * dead ahead at two hundred blocks and one dead ahead at six hundred sit one above the other,
 * however the world is arranged — but it is exactly what the aerial knows, and a straight-ahead
 * contact is straight up the middle whichever way the aeroplane is turning.
 *
 * <p><b>The receiver is a circle</b>, because the one thing it has to answer is "which way", and it
 * answers it for the whole sky at once rather than for the cone in front. What is drawn is a bearing
 * and a severity and nothing else, which is all a receiver can honestly tell you: how close in the
 * mark sits stands for how far along the attention has got, not for how far away it is coming from.
 *
 * <p>Both draw whatever the last sweep found; see {@link RadarReadout}. Neither is drawn for an
 * aircraft whose file gives it no radar, which is how an aeroplane goes without one.
 */
public final class RadarDisplay {
    /** The scope, in screen pixels. Wide enough to tell two contacts apart and no wider. */
    private static final int SCOPE_WIDTH = 104;
    private static final int SCOPE_HEIGHT = 68;
    /** Clear of the screen edge the instrument is pinned to. */
    private static final int EDGE = 8;
    /** Range rings across the scope, as fractions of the radar's reach. */
    private static final float[] RINGS = {0.05F, 0.25F, 0.5F};

    /** The receiver's ring, and the three rings threats sit on as they get worse. */
    private static final int RWR_RADIUS = 34;
    private static final float SEARCH_RING = 0.86F;
    private static final float LOCK_RING = 0.6F;
    private static final float MISSILE_RING = 0.34F;

    /** Ticks a warning is lit for, and dark for, while it blinks. */
    private static final int BLINK_TICKS = 4;
    /** How much higher or lower a contact has to be before the scope says so, in blocks. */
    private static final float NOTABLE_CLIMB = 20.0F;

    public static void draw(GuiGraphics graphics, Font font, VehicleEntityBase vehicle) {
        VehicleChassis.Radar radar = vehicle.radar();

        // Two boxes, not one. An aeroplane with no radar at all still hears somebody else's, which
        // is exactly the aeroplane that most needs to.
        if (radar.fitted()) {
            drawScope(graphics, font, radar);
        }

        if (radar.warningRange() > 0.0F) {
            drawReceiver(graphics, font);
        }
    }

    /** Bearing across, range up: what the aerial found in front of the aeroplane. */
    private static void drawScope(GuiGraphics graphics, Font font, VehicleChassis.Radar radar) {
        int left = EDGE;
        int right = left + SCOPE_WIDTH;
        // Halfway up the left-hand side. The scope is read with a glance sideways from the
        // boresight rather than a look down into the corner, and the status column keeps the
        // bottom of this edge to itself.
        int top = (graphics.guiHeight() - SCOPE_HEIGHT) / 2;
        int bottom = top + SCOPE_HEIGHT;
        int middle = (left + right) / 2;

        graphics.fill(left, top, right, bottom, AircraftHud.SHADOW);

        // The frame, the boresight up the middle, and a ring or two to read range against.
        graphics.fill(left, top, right, top + 1, AircraftHud.DIM);
        graphics.fill(left, bottom - 1, right, bottom, AircraftHud.DIM);
        graphics.fill(left, top, left + 1, bottom, AircraftHud.DIM);
        graphics.fill(right - 1, top, right, bottom, AircraftHud.DIM);
        graphics.fill(middle, top + 1, middle + 1, bottom - 1, AircraftHud.DIM);

        // Rings at a quarter and a half of the reach, placed by the same squashed scale the contacts
        // are, and labelled with the range they stand for -- which on a scale like this cannot be
        // guessed from where they sit.
        for (float ring : RINGS) {
            int y = bottom - 3 - Math.round(up(ring) * (SCOPE_HEIGHT - 6));

            graphics.fill(left + 1, y, right - 1, y + 1, AircraftHud.DIM);
            graphics.drawString(font, distance(radar.range() * ring), left + 3, y - 9, AircraftHud.DIM, false);
        }

        AircraftHud.label(graphics, font, "RDR", left + 3, top - 10);

        String reach = distance(radar.range());
        AircraftHud.label(graphics, font, reach, right - font.width(reach) - 3, top - 10);

        List<Contact> contacts = RadarReadout.contacts();

        if (contacts.isEmpty()) {
            String empty = "NO CONTACT";
            graphics.drawString(font, empty, middle - font.width(empty) / 2, top + SCOPE_HEIGHT / 2 - 4,
                    AircraftHud.DIM, false);

            return;
        }

        for (Contact contact : contacts) {
            plot(graphics, font, contact, radar, left, right, top, bottom);
        }
    }

    /** One contact, at its bearing across the scope and its range up it. */
    private static void plot(GuiGraphics graphics, Font font, Contact contact,
            VehicleChassis.Radar radar, int left, int right, int top, int bottom) {
        float across = Mth.clamp(contact.bearing() / Math.max(radar.arc(), 1.0F), -1.0F, 1.0F);
        float out = up(Mth.clamp(contact.range() / Math.max(radar.range(), 1.0F), 0.0F, 1.0F));

        int middle = (left + right) / 2;
        int x = middle + Math.round(across * (SCOPE_WIDTH / 2.0F - 4.0F));
        int y = bottom - 3 - Math.round(out * (SCOPE_HEIGHT - 6));
        int colour = contact.locked() ? AircraftHud.WARNING : AircraftHud.GREEN;
        // An aeroplane is worth more of the pilot's attention than somebody standing in a field, so
        // it is drawn as more of a mark.
        int half = contact.aircraft() ? 2 : 1;

        graphics.fill(x - half, y - half, x + half, y + half, colour);

        if (contact.locked()) {
            graphics.fill(x - 4, y - 4, x + 4, y - 3, colour);
            graphics.fill(x - 4, y + 3, x + 4, y + 4, colour);
        }

        // Above or below, for the things it matters for. A number would not fit and would not be
        // read; which side of you it is on is the part that changes what you do about it.
        if (contact.aircraft() && Math.abs(contact.altitude()) > NOTABLE_CLIMB) {
            String mark = contact.altitude() > 0.0F ? "+" : "-";

            graphics.drawString(font, mark, x + half + 1, y - 4, colour, false);
        }
    }

    /** Who is looking at you, and from where. */
    private static void drawReceiver(GuiGraphics graphics, Font font) {
        // Halfway up the right-hand side, opposite the scope: the two instruments that say what
        // else is in the sky sit either side of the boresight at the same height.
        int centreX = graphics.guiWidth() - EDGE - RWR_RADIUS;
        int centreY = graphics.guiHeight() / 2;

        AircraftHud.circle(graphics, centreX, centreY, RWR_RADIUS, AircraftHud.DIM);
        AircraftHud.circle(graphics, centreX, centreY, Math.round(RWR_RADIUS * LOCK_RING), AircraftHud.DIM);

        // The nose, so the ring can be read without wondering which way up it is.
        graphics.fill(centreX, centreY - RWR_RADIUS - 3, centreX + 1, centreY - RWR_RADIUS + 3, AircraftHud.DIM);
        graphics.fill(centreX - 1, centreY - 1, centreX + 1, centreY + 1, AircraftHud.DIM);

        List<Threat> threats = RadarReadout.threats();

        if (threats.isEmpty()) {
            return;
        }

        boolean lit = lit();

        for (Threat threat : threats) {
            float ring = switch (threat.kind()) {
                case SEARCH -> SEARCH_RING;
                case LOCK -> LOCK_RING;
                case MISSILE -> MISSILE_RING;
            };
            double angle = Math.toRadians(threat.bearing());
            int x = centreX + (int) Math.round(Math.sin(angle) * RWR_RADIUS * ring);
            int y = centreY - (int) Math.round(Math.cos(angle) * RWR_RADIUS * ring);

            // A search is a fact about the afternoon and sits there quietly; a lock or a missile is
            // a fact about the next few seconds and will not be ignored.
            if (threat.kind() == Threat.Kind.SEARCH) {
                graphics.fill(x - 1, y - 1, x + 2, y + 2, AircraftHud.DIM);
            } else if (lit) {
                graphics.fill(x - 2, y - 2, x + 3, y + 3, AircraftHud.WARNING);
            }
        }

        Threat.Kind worst = RadarReadout.worst();

        if (worst == Threat.Kind.SEARCH || !lit) {
            return;
        }

        String warning = worst == Threat.Kind.MISSILE ? "MISSILE" : "LOCKED";
        graphics.drawString(font, warning, centreX - font.width(warning) / 2, centreY + RWR_RADIUS + 4,
                AircraftHud.WARNING, true);
    }

    /**
     * Where a range sits up the scope, as a fraction of its height.
     *
     * <p>Not simply the range over the reach. A radar that sees several kilometres would otherwise
     * pile everything worth knowing about into the bottom few pixels — at five kilometres of reach, a
     * contact five hundred blocks away, which is close enough to be a problem in the next twenty
     * seconds, would sit one tenth of the way up and be indistinguishable from one at four hundred.
     *
     * <p>So the scale is squashed: the near half of the screen covers the near quarter of the reach,
     * and the far end is compressed to match. It is what the range rings are labelled for.
     */
    private static float up(float fraction) {
        return (float) Math.sqrt(fraction);
    }

    /** A range as a pilot would say it: metres up to a kilometre, and kilometres after that. */
    private static String distance(float blocks) {
        return blocks < 1000.0F
                ? Math.round(blocks) + "m"
                : String.format("%.1fk", blocks / 1000.0F);
    }

    /** Whether a blinking warning is on this instant. */
    private static boolean lit() {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level == null || (minecraft.level.getGameTime() / BLINK_TICKS) % 2 == 0;
    }

    private RadarDisplay() {
    }
}
