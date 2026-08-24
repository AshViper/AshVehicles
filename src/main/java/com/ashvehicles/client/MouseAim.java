package com.ashvehicles.client;

import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Flying by pointing: the pilot puts a mark in the sky and the aeroplane goes and puts its nose on
 * it.
 *
 * <p>What the mouse moves is not the aircraft. It moves a direction, and a small autopilot underneath
 * works out what the stick would have to do to bring the nose round onto it — which for an aeroplane
 * means banking and pulling, because that is the only way an aeroplane changes where it is going, and
 * for a helicopter means the pedals, because that is how a helicopter points its nose. The keys are
 * still there and still fly the aircraft directly; this only fills in what the pilot is not asking
 * for by hand.
 *
 * <p><b>The mark is held as a world direction, not as an angle off the nose.</b> That is the whole of
 * why it works. An angle stored against the aircraft does not shrink as the aircraft turns towards
 * it — the aeroplane would roll into the turn and go on rolling for ever, since the mark it was
 * chasing came round with it. A direction stays where the pilot put it, the error against the nose
 * closes as the aircraft comes round, and the stick centres itself when it gets there.
 *
 * <p>It is also why this is not the same thing as {@link CockpitView}, which has to be two clamped
 * angles for reasons set out over there. A head is a rotation and can wind itself up; a direction has
 * no roll to wind and cannot tumble past anything.
 *
 * <p><b>Looking and asking are two different directions.</b> Where the pilot is looking is not
 * limited at all beyond the poles: in the chase view the camera has to be able to go the whole way
 * round, and being able to see behind is half of what that view is for. What the aeroplane is asked
 * for is that direction brought inside a cone about the nose, which is what keeps this a stick rather
 * than an instruction to turn round. Push the mouse hard over and the camera swings past the tail
 * while the aircraft turns after it as hard as it can — which is the right answer to both.
 *
 * <p><b>Which axes the mouse works in depends on the view, and has to.</b> In the cockpit the camera
 * is bolted to the airframe and rolls with the wings, so sideways on the screen is sideways on the
 * aircraft and the mouse is turned about the aircraft's own axes. The chase camera is deliberately
 * left upright, so on that screen sideways is sideways in the <em>world</em> — and a mouse turned
 * about the aircraft's axes there sends the view down the screen the moment the wings are not level,
 * which is precisely what made the chase view unflyable.
 */
public final class MouseAim {
    /**
     * How far off the nose the mark may be taken, in degrees.
     *
     * <p>Small on purpose. This is a stick, not a look-around: a mark that could be put anywhere
     * would let the pilot ask for a turn the aeroplane will spend ten seconds arriving at, with the
     * stick hard over the whole way and no feel for what it is doing. Within a cone it behaves like
     * a stick — how far out the mark is, is how much deflection is being asked for. Looking further
     * than this is what the free-look key is for, and that does not steer.
     */
    private static final float CONE = 35.0F;

    /**
     * Degrees of bank asked for per degree the mark is off to one side, and the most that may be
     * asked for.
     *
     * <p>An aeroplane changes heading by banking and pulling; the rudder barely comes into it. So a
     * mark out to the right is not a shove on the rudder, it is an angle of bank to hold while the
     * wing does the work — which is exactly what a pilot does with the stick, and why an aeroplane
     * flown this way looks like an aeroplane rather than like a mouse cursor.
     */
    private static final float BANK_PER_DEGREE = 3.0F;
    private static final float MAX_BANK = 75.0F;

    /**
     * How much back-stick to add for the lift a bank costs, as a fraction of full deflection for
     * each time over its own weight the wing has to pull.
     *
     * <p>A banked wing does not hold the aircraft up any less hard, it merely holds it up in the
     * wrong direction: at sixty degrees only half of the lift is still pointing at the sky, and the
     * aeroplane starts down. What keeps a turn level is somebody pulling, and a pilot supplies it
     * without thinking about it — so this supplies it too. Without it, holding the mouse over to one
     * side is not a turn at all but a spiral into the ground, which is exactly what it did.
     *
     * <p>Only a bias. A pilot who puts the mark below the horizon is asking to go down and the error
     * term says so far louder than this does.
     */
    private static final float TURN_HOLD = 0.8F;
    /**
     * How far over the wing has to be before the pull is given up on. Past this there is no useful
     * "up" to pull towards, and hauling back while inverted points the aircraft at the ground.
     */
    private static final double UPRIGHT = 0.1;

    /**
     * How near the vertical the chase camera may look, in degrees.
     *
     * <p>A shade short of straight up, which is the same limit vanilla puts on every other view in
     * the game: past it a bearing stops meaning anything and the picture slews. The bearing itself is
     * not limited at all — the camera goes the whole way round, because half of what an outside view
     * is for is seeing what is behind you.
     */
    private static final float POLE = 89.5F;

    /** Stick per degree of error, and stick per degree per tick of the rotation already under way. */
    private static final float ROLL_GAIN = 0.04F;
    private static final float ROLL_DAMPING = 0.15F;
    private static final float PITCH_GAIN = 0.08F;
    private static final float PITCH_DAMPING = 0.15F;
    /**
     * The rudder, which on an aeroplane is a trim rather than a way of turning: enough to walk the
     * nose the last degree or two onto the mark, not enough to skid the aircraft round with.
     */
    private static final float YAW_GAIN = 0.02F;
    /**
     * And on a helicopter, which is the other way round entirely. A helicopter points its nose with
     * the tail rotor and does not have to bank to do it, so there the pedals are the whole of it.
     */
    private static final float ROTOR_YAW_GAIN = 0.05F;
    private static final float YAW_DAMPING = 0.15F;

    /** What the aim is asking the stick to do, each in [-1, 1]. */
    public record Stick(float pitch, float roll, float yaw) {
        public static final Stick NONE = new Stick(0.0F, 0.0F, 0.0F);
    }

    /** The aircraft this pilot is flying, or null when they are not flying one. */
    private static AircraftEntity aircraft;
    /**
     * Where the pilot is looking, as a direction in the world. Free to go anywhere but the poles.
     */
    private static Vec3 look = Vec3.ZERO;
    /** And what the aeroplane is being asked for: that direction, brought inside the cone. */
    private static Vec3 aim = Vec3.ZERO;
    /**
     * Whether the mouse flies the aircraft at all.
     *
     * <p>On to begin with, because it is what most people reach for, and switchable because it is a
     * different way of flying rather than a better one: turned off, the mouse goes back to doing
     * nothing but look around and the keys have the aircraft entirely to themselves, which is how
     * this mod flew before there was any of this.
     */
    private static boolean enabled = true;

    private MouseAim() {
    }

    /**
     * Called every frame. Takes note of whether this player is at the controls, and starts the mark
     * on the nose whenever they climb into something.
     *
     * <p>Only for whoever is actually flying. A passenger's mouse has nothing to steer with and is
     * left to look out of the window.
     */
    public static void follow(AircraftEntity riding) {
        AircraftEntity flying = riding != null
                && riding.getControllingPassenger() == Minecraft.getInstance().player ? riding : null;

        if (flying != aircraft) {
            aircraft = flying;
            centre();
        }
    }

    /** True while there is an aircraft under this pilot's hands for the mark to steer. */
    public static boolean isActive() {
        return enabled && aircraft != null && !aircraft.isRemoved();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Switches the whole thing on or off, leaving the mark on the nose either way. */
    public static void setEnabled(boolean on) {
        enabled = on;
        centre();
    }

    /** Where the pilot is looking, as a direction in the world. Not limited to the cone. */
    public static Vec3 look() {
        return look;
    }

    /** Puts both directions back on the nose: nothing being asked for, nothing being looked away at. */
    public static void centre() {
        look = aircraft == null ? Vec3.ZERO : aircraft.getNoseVector();
        aim = look;
    }

    /**
     * Moves where the pilot is looking by a mouse movement, in degrees.
     *
     * @param inCockpit whether the camera is bolted to the airframe, which decides the axes the
     *                  movement is applied in. See the note on the class
     */
    public static void turn(double deltaX, double deltaY, boolean inCockpit) {
        if (!isActive()) {
            return;
        }

        if (inCockpit) {
            // Turned about the aircraft's own up and right, because that is what the screen is
            // lying along. Negated both times: a rotation about an axis carries a direction the
            // other way from the way the hand moved.
            Quaternionf attitude = aircraft.getAttitude(1.0F);

            look = spin(look, Attitude.up(attitude), -deltaX);
            look = spin(look, Attitude.right(attitude), -deltaY);

            return;
        }

        // And outside it, a bearing and an elevation against the world — which is what the mouse is
        // everywhere else in the game, and what an upright screen wants. The bearing runs the whole
        // way round; only the elevation stops, where looking up stops.
        float heading = (float) (Mth.atan2(-look.x, look.z) * (180.0 / Math.PI) + deltaX);
        float elevation = (float) Mth.clamp(
                -Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * (180.0 / Math.PI) + deltaY, -POLE, POLE);

        look = Vec3.directionFromRotation(elevation, heading);
    }

    /**
     * Points the player's own bearing and elevation where the pilot is looking.
     *
     * <p>For the chase view, where nothing else does it: in the cockpit the camera is placed from
     * {@link CockpitView} and the player's angles follow from there, but a detached camera is put
     * where the player is facing, so the player's facing is the camera.
     */
    public static void applyToPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || !isActive() || look.lengthSqr() < 1.0E-8) {
            return;
        }

        player.setYRot((float) (Mth.atan2(-look.x, look.z) * (180.0 / Math.PI)));
        player.setXRot((float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * (180.0 / Math.PI)));
        player.setYHeadRot(player.getYRot());
    }

    /**
     * The stick the mark is asking for, and the mark brought back within reach of the nose while we
     * are here.
     *
     * <p>Called once a tick from the pilot's input, and the one place any of this becomes flying.
     *
     * <p>Held down, the free-look key leaves the mark where it was and lets the pilot's eyes go on
     * alone: the aeroplane keeps flying at what it was last asked for while its pilot looks over
     * their shoulder, which is what a look round is for and would be worth nothing if the aircraft
     * followed the eyes.
     */
    public static Stick stick() {
        if (!isActive()) {
            return Stick.NONE;
        }

        if (!ModKeyMappings.FREE_LOOK.isDown()) {
            aim = look;
        }

        holdInCone();

        Quaternionf attitude = aircraft.getAttitude(1.0F);
        Vec3 nose = Attitude.nose(attitude);
        Vec3 up = Attitude.up(attitude);
        Vec3 right = Attitude.right(attitude);

        // How far off the nose the mark is, split into the two ways an aircraft can be wrong about
        // it: round to one side, and up or down.
        float offYaw = (float) Math.toDegrees(Mth.atan2(aim.dot(right), aim.dot(nose)));
        float offPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(aim.dot(up), -1.0, 1.0)));

        // The elevator, on both sorts of aircraft: what raises the nose is the same lever whether
        // the lift comes from a wing or from a rotor. Damped against the rotation already under way,
        // or the nose arrives at the mark still turning and sails past it.
        float pitch = Mth.clamp(offPitch * PITCH_GAIN - aircraft.getPitchDelta() * PITCH_DAMPING,
                -1.0F, 1.0F);

        if (aircraft.isRotorcraft()) {
            // A helicopter is pointed, not banked. The pedals swing the nose onto the mark and leave
            // it there, and the cyclic stays free for the keys — which is what it is wanted for,
            // since on a helicopter roll is how you go sideways rather than how you turn.
            float yaw = Mth.clamp(offYaw * ROTOR_YAW_GAIN - aircraft.getYawDelta() * YAW_DAMPING,
                    -1.0F, 1.0F);

            return new Stick(pitch, 0.0F, yaw);
        }

        // And an aeroplane is banked, not pointed. The mark decides an angle of bank; the ailerons
        // are asked for the difference between that and the bank there already is, so the aircraft
        // rolls in, holds the turn while the wing brings the nose round, and rolls out as the mark
        // comes to the middle. A rudder term goes with it, small, for the last degree or two.
        float bank = aircraft.getRoll();
        float wanted = Mth.clamp(offYaw * BANK_PER_DEGREE, -MAX_BANK, MAX_BANK);
        float roll = Mth.clamp((wanted - bank) * ROLL_GAIN
                - aircraft.getRollDelta() * ROLL_DAMPING, -1.0F, 1.0F);
        float yaw = Mth.clamp(offYaw * YAW_GAIN, -1.0F, 1.0F);

        return new Stick(Mth.clamp(pitch + holdTheTurn(bank), -1.0F, 1.0F), roll, yaw);
    }

    /**
     * The back-stick a bank costs, so that a turn stays a turn rather than becoming a descent.
     *
     * <p>What is wanted is the load the wing has to pull for the upward part of its lift to still
     * come to the weight, which is the secant of the bank angle: one level, two at sixty degrees,
     * away to nothing at ninety. Given up on past the vertical, where there is no up to pull towards
     * and pulling would only point the aircraft further at the ground.
     */
    private static float holdTheTurn(float bank) {
        double upright = Math.cos(Math.toRadians(bank));

        if (upright <= UPRIGHT) {
            return 0.0F;
        }

        return Mth.clamp((float) (1.0 / upright - 1.0) * TURN_HOLD, 0.0F, 1.0F);
    }

    /**
     * Keeps the mark inside the cone about the nose.
     *
     * <p>Wanted every tick and not only when the mouse moves, because the aircraft is turning too:
     * a mark left alone while the aeroplane rolls away from it would drift out of reach on its own
     * and leave the stick hard over with nothing the pilot did to explain it.
     */
    private static void holdInCone() {
        Vec3 nose = aircraft.getNoseVector();

        if (aim.lengthSqr() < 1.0E-8) {
            aim = nose;

            return;
        }

        double off = Math.toDegrees(Math.acos(Mth.clamp(aim.dot(nose), -1.0, 1.0)));

        if (off <= CONE) {
            return;
        }

        Vec3 axis = nose.cross(aim);

        // Straight ahead, or exactly astern, where there is no plane to swing it back through.
        aim = axis.lengthSqr() < 1.0E-9 ? nose : spin(nose, axis.normalize(), CONE);
    }


    /** A direction turned about an axis, in degrees. Both are already units, so this stays one. */
    private static Vec3 spin(Vec3 direction, Vec3 axis, double degrees) {
        Vector3f turned = new Quaternionf()
                .rotateAxis((float) Math.toRadians(degrees), (float) axis.x, (float) axis.y, (float) axis.z)
                .transform(new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));

        return new Vec3(turned.x(), turned.y(), turned.z()).normalize();
    }
}
