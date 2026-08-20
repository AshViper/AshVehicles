package com.ashvehicles.client;

import java.util.List;

import com.ashvehicles.sensor.Contact;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;

/**
 * The last thing the radar said, held until it says something else.
 *
 * <p>A radar sweeps rather than watches, so what is on the scope between passes is where things were
 * when the aerial last went by — which is why this is a held picture rather than something worked
 * out afresh each frame, and why it is allowed to be a little out of date.
 *
 * <p>It is also allowed to be stale, but not indefinitely. Nothing tells a client that the radar has
 * stopped: a pilot who climbs out, or an aircraft that is destroyed, simply stops sending, so the
 * picture is thrown away once it is older than a sweep or two rather than being left frozen on the
 * screen for the rest of the session.
 */
public final class RadarReadout {
    /** How long a picture is worth drawing after it arrived, in ticks. */
    private static final int KEEPS_FOR = 40;

    private static List<Contact> contacts = List.of();
    private static List<Threat> threats = List.of();
    private static long arrived = Long.MIN_VALUE;

    /** A fresh sweep off the wire. */
    public static void accept(List<Contact> found, List<Threat> warnings) {
        contacts = found;
        threats = warnings;
        arrived = age();
    }

    public static List<Contact> contacts() {
        return fresh() ? contacts : List.of();
    }

    public static List<Threat> threats() {
        return fresh() ? threats : List.of();
    }

    /** The worst thing being done to this aircraft, or null if nobody is doing anything. */
    public static Threat.Kind worst() {
        List<Threat> current = threats();

        return current.isEmpty() ? null : current.get(0).kind();
    }

    /** Whether the last sweep is recent enough to mean anything. */
    private static boolean fresh() {
        return age() - arrived < KEEPS_FOR;
    }

    private static long age() {
        return Minecraft.getInstance().level == null ? Long.MIN_VALUE : Minecraft.getInstance().level.getGameTime();
    }

    private RadarReadout() {
    }
}
