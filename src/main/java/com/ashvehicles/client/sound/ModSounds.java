package com.ashvehicles.client.sound;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.Ricochet;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;

/**
 * Every recording the mod asks for by name, and the one question worth asking about any of them.
 *
 * <p>Sounds are not code. Each name here is an entry in some resource pack's {@code sounds.json}
 * pointing at an {@code .ogg}, and the mod itself ships three of them: the engine note, the cannon
 * and the launch. The rest are names held open for whoever wants to record something. Nothing is
 * broken while a name goes unclaimed, because every one of them is looked up before it is asked for
 * and a name nothing answers to is either swapped for one that is or simply not played.
 *
 * <p>Which of the two depends on what sort of sound it is. A report, a clunk or a bang is a single
 * short noise, and something recognisable beats silence, so those fall through to a recording that
 * does exist — the mod's own where there is one, the game's where there is not. The loops, a motor
 * and an undercarriage, have no fallback at all: a recording that was not cut to loop sounds far
 * worse looped than absent, and there is nothing in the game that was cut to loop as either of
 * these.
 *
 * <p><b>Adding one.</b> Put the {@code .ogg} under {@code assets/ashvehicles/sounds/} and name it in
 * {@code assets/ashvehicles/sounds.json} under the event's path — {@code weapon.fall}, say. That is
 * the whole of it; nothing here needs changing, and a resource pack that is not the mod's own can do
 * it just as well.
 *
 * <p>Every one of these also has a per-thing form, which is what to record when one weapon or one
 * aircraft should not sound like the rest: {@code engine.<aircraft>} and {@code gear.<aircraft>} for
 * an airframe, {@code weapon.<weapon>} for a firing report, and {@code weapon.<weapon>.<role>} for
 * the rest of what a weapon does. Those are looked for first and need nothing written in the
 * aircraft's or the weapon's file. See {@link #named}.
 */
public final class ModSounds {
    /** {@code engine.<aircraft>}: an airframe's own engine note. */
    public static final String ENGINE_PREFIX = VehicleEntityBase.SOUND_PREFIX;
    /** {@code engine.<aircraft>.afterburner}: the burner catching, for an airframe that has one. */
    public static final String AFTERBURNER_ROLE = AircraftEntity.AFTERBURNER_ROLE;
    /** {@code gear.<aircraft>}: an airframe's own undercarriage. */
    public static final String GEAR_PREFIX = "gear.";
    /** {@code weapon.<weapon>}, and {@code weapon.<weapon>.<role>} for everything but the report. */
    public static final String WEAPON_PREFIX = WeaponMounts.SOUND_PREFIX;
    /** {@code rwr.<role>}: the warning receiver, which belongs to no particular weapon. */
    public static final String RWR_PREFIX = "rwr.";
    /** {@code seeker.<role>}: the crew's own seeker, for a weapon that has recorded nothing itself. */
    public static final String SEEKER_PREFIX = "seeker.";

    /** The role of a sound a weapon makes other than firing, as the tail of its name. */
    public static final String FLIGHT_ROLE = "flight";
    public static final String FALL_ROLE = "fall";
    public static final String RICOCHET_ROLE = Ricochet.SOUND_ROLE;
    /** What a weapon's own seeker has to say: working on something, holding it, and losing it. */
    public static final String SEEK_ROLE = "seek";
    public static final String LOCK_ROLE = "lock";
    public static final String LOST_ROLE = "lost";

    /** The engine note every aircraft falls back on. Shipped. */
    public static final ResourceLocation ENGINE = id(ENGINE_PREFIX + "default");
    /**
     * The burner lighting, for any aircraft with no recording of its own. Not shipped: unlike the
     * engine note this is one short bang rather than a loop, so there is something sensible in the
     * game to fall back to until somebody records it. See {@link AfterburnerSounds}.
     */
    public static final ResourceLocation AFTERBURNER = id(ENGINE_PREFIX + AFTERBURNER_ROLE);
    /**
     * The undercarriage every aircraft falls back on. Looped, and not shipped: an aircraft is silent
     * on the gear lever until something provides this or a note of its own.
     */
    public static final ResourceLocation GEAR = id(GEAR_PREFIX + "default");

    /** What a gun's report falls back on. Shipped. */
    public static final ResourceLocation GUN = id(WEAPON_PREFIX + "gun");
    /** What anything with a motor falls back on: a rocket does not sound like a cannon. Shipped. */
    public static final ResourceLocation LAUNCH = id(WEAPON_PREFIX + "launch");
    /**
     * A store leaving its rack. A bomb is not fired, it is let go, and what that sounds like from the
     * cockpit is the rack banging shut rather than anything going off. Falls through to
     * {@link #LAUNCH} until something is recorded for it.
     */
    public static final ResourceLocation RELEASE = id(WEAPON_PREFIX + "release");
    /** Ground crew hanging a store on a pylon, or taking one back off. Named by the server. */
    public static final ResourceLocation LOAD = WeaponMounts.LOAD_SOUND;
    /** The countermeasure dispenser letting one go. Named by the server. */
    public static final ResourceLocation DECOY = Dispenser.RELEASE_SOUND;

    /** A motor burning, heard from outside, for as long as it burns. Looped, and not shipped. */
    public static final ResourceLocation FLIGHT = id(WEAPON_PREFIX + FLIGHT_ROLE);
    /** The rising whistle of something falling under nothing but gravity. Looped, and not shipped. */
    public static final ResourceLocation FALL = id(WEAPON_PREFIX + FALL_ROLE);

    /** The mod's own bang. See {@link BlastSounds}, which times and shapes it for the distance. */
    public static final ResourceLocation BLAST = id(WEAPON_PREFIX + "blast");

    /**
     * A round skidding off armour rather than going into it, for any weapon with no clang recorded
     * of its own. Named by the server; not shipped, so until something records one it falls through
     * to the game's own metal-on-metal. See {@link Ricochet}.
     */
    public static final ResourceLocation RICOCHET = Ricochet.SOUND;

    /**
     * The warning receiver's tone: one short beep, repeated as fast as the trouble deserves.
     *
     * <p>A beep rather than a loop, deliberately. What tells a pilot how much trouble they are in is
     * the <em>rate</em> — the same note coming twice a second means something is looking at you and
     * five times a second means it is on its way — so what has to be recorded is one short sound and
     * nothing else. It also means there is something sensible to fall back on until one is: see
     * {@link com.ashvehicles.client.sound.WarningSounds}.
     *
     * <p>One name per thing the receiver has to say, so that a pack can give each its own note. Any
     * of them left unrecorded borrows from the ones that are, and a receiver with nothing recorded at
     * all still beeps — the game's own note block, pitched by how much trouble the pilot is in.
     *
     * <p>This one is somebody's radar finding you: one chirp, and then silence until it turns into
     * something worse.
     */
    public static final ResourceLocation RWR_CONTACT = id(RWR_PREFIX + "contact");
    /** Somebody's seeker has taken you. Beeped twice a second. */
    public static final ResourceLocation RWR_LOCK = id(RWR_PREFIX + "lock");
    /** Something has left their rail and is coming for you. Beeped five times a second. */
    public static final ResourceLocation RWR_MISSILE = id(RWR_PREFIX + "missile");

    /**
     * The crew's own seeker, which is the receiver pointed the other way round: not somebody taking
     * you, but your own missile saying what it can see. See {@link SeekerSounds}.
     *
     * <p>Three names, because a lock is three moments. This one is the seeker working on something
     * and not yet having it: a growl, looped for as long as it is working, and <b>the one recording
     * here that does not play at exactly the note it was cut at</b> — it climbs a little as the lock
     * closes, which is what tells a pilot watching the target rather than the instruments how far
     * along it has got. A hand's breadth either side of as-recorded, so cut it at the note it should
     * be heard at and the climb looks after itself. Shipped, as a four-second loop.
     */
    public static final ResourceLocation SEEKER_SEARCH = id(SEEKER_PREFIX + "search");
    /** The seeker has it. A steady tone, running for exactly as long as the lock does. Shipped. */
    public static final ResourceLocation SEEKER_LOCK = id(SEEKER_PREFIX + LOCK_ROLE);
    /**
     * A lock that was had and has fallen away. One short note, and then the growl again or silence.
     *
     * <p>Not shipped, and the one of the three with no fallback among the others: the two above are
     * loops, and a loop played once over the top of the growl starting again is not a short note. So
     * until somebody records this the game's own is used. See {@link SeekerSounds}.
     */
    public static final ResourceLocation SEEKER_LOST = id(SEEKER_PREFIX + LOST_ROLE);

    /** The event named after one aircraft or one weapon: {@code <namespace>:<prefix><name>}. */
    public static ResourceLocation named(ResourceLocation subject, String prefix) {
        return subject.withPath(prefix + subject.getPath());
    }

    /** The same, for one of the several sounds a weapon makes: {@code weapon.<name>.<role>}. */
    public static ResourceLocation named(ResourceLocation subject, String prefix, String role) {
        return subject.withPath(prefix + subject.getPath() + "." + role);
    }

    /**
     * The first of these a resource pack actually provides, or null if it provides none of them.
     *
     * <p>Both halves of that matter. An event that is defined but whose file was not found counts as
     * missing, which is what makes a misspelt path fall through to something audible instead of to
     * silence; and null is an answer rather than a failure, because for a loop there is nothing
     * sensible to fall back to and not playing is the right thing to do.
     */
    @Nullable
    public static ResourceLocation firstPresent(SoundManager sounds, ResourceLocation... chain) {
        for (ResourceLocation id : chain) {
            if (exists(sounds, id)) {
                return id;
            }
        }

        return null;
    }

    /** True if the resource packs define this event and at least one of its files was found. */
    public static boolean exists(SoundManager sounds, ResourceLocation id) {
        WeighedSoundEvents weighed = sounds.getSoundEvent(id);

        return weighed != null && weighed.getWeight() > 0;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, path);
    }

    private ModSounds() {
    }
}
