package com.ashvehicles.entity;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * How an aircraft nobody here is flying gets drawn.
 *
 * <p>Every client except the one at the controls sees this aircraft second-hand, from a stream of
 * positions and attitudes the server sends. The obvious way to use them — move a fraction of the way
 * towards the newest one each tick, which is what vanilla's own vehicle interpolation does — is
 * wrong in two separate ways, and both of them get worse the faster the aircraft is going. This
 * class exists to do neither.
 *
 * <h2>The standing lag</h2>
 *
 * <p>Vanilla closes a fixed fraction of the gap to the target each tick. Aim a tenth of the way at a
 * target that is itself moving {@code v} blocks a tick and the gap does not close to nothing: it
 * settles wherever the closing rate matches the target's speed, which is ten times {@code v} behind.
 * That is a <em>permanent</em> error proportional to speed, not a decaying one. It is invisible on a
 * boat doing a tenth of a block a tick. On an aircraft at three blocks a tick it is thirty blocks;
 * at this pack's top speeds it is nearer eighty. Everyone except the pilot was watching an aeroplane
 * most of a chunk behind where it really was — shooting at it, being hit by it, and looking at empty
 * sky where it appeared to be.
 *
 * <p>The cure is to stop chasing and start predicting. This keeps its own dead-reckoned pose that
 * advances at the aircraft's estimated velocity every tick, which by construction has no lag at all
 * for anything flying a straight line, and only ever has to absorb the difference between that guess
 * and the truth.
 *
 * <h2>The sawtooth</h2>
 *
 * <p>The second way is subtler and survives the first fix if the correction is applied naively.
 * Blending from "where it was drawn when the correction arrived" towards the prediction over a fixed
 * window means the prediction keeps moving during the blend, so the drawn position is quadratic in
 * elapsed time and the drawn <em>speed</em> therefore ramps across each window — then the next
 * correction restarts the window and drops it back. With a correction every tick that is a twenty
 * hertz sawtooth in velocity: the aircraft visibly surges and stalls even though not one packet was
 * lost. Closing a fixed fraction of whatever error remains has no window to restart and no elapsed
 * time in it at all, so a correction changes only where the aircraft is heading, never how fast it
 * is currently drawn as moving.
 *
 * <h2>Why the error is captured before the prediction moves</h2>
 *
 * <p>The order in {@link #advance} is load-bearing. Taking the error as "prediction minus drawn"
 * <em>after</em> advancing the prediction folds a slice of the prediction's own travel into what is
 * supposed to be leftover error, every tick, for ever — and that recurrence has the same non-zero
 * fixed point the vanilla lerp does, so it quietly reintroduces a standing offset proportional to
 * speed. The error is therefore snapshotted against the old prediction, decayed entirely on its own,
 * and added back onto the new one.
 */
public final class AircraftInterpolation {

    /** Ticks over which all but a sliver of a correction is absorbed, if the file names none. */
    public static final int DEFAULT_CORRECTION_TICKS = 3;

    /** Blocks of error beyond which the aircraft is simply put where it belongs. */
    public static final double DEFAULT_SNAP_DISTANCE = 8.0;

    /**
     * Ticks of dead reckoning trusted after the last correction before this stops driving.
     *
     * <p>Without a bound, an aircraft whose server stops talking about it — the pilot disconnects,
     * the entity stops being tracked, the server hitches — would coast on its last known velocity
     * for ever while the real one sits still, and nothing could pull it back. Comfortably longer
     * than the one-tick interval these are broadcast at, so it never trips in normal flight.
     */
    public static final int DEFAULT_MAX_PREDICTION_TICKS = 10;

    /**
     * Fraction of a correction still outstanding once the correction window has elapsed. Turning
     * this into a per-tick factor is what makes the window mean what it says.
     */
    private static final double RESIDUAL_AFTER_WINDOW = 0.02;

    /**
     * Most the estimated velocity is believed, in blocks per tick. A single late or reordered
     * position packet can otherwise imply an enormous speed for one tick, and dead reckoning would
     * fling the aircraft across the sky before the next correction could argue.
     */
    private static final double MAX_ESTIMATED_SPEED = 40.0;

    private static final Quaternionfc NO_ROTATION = new Quaternionf();

    private double simX;
    private double simY;
    private double simZ;
    private double velX;
    private double velY;
    private double velZ;

    private double renderX;
    private double renderY;
    private double renderZ;

    private double lastTargetX;
    private double lastTargetY;
    private double lastTargetZ;

    private boolean seeded;
    private boolean hasLastTarget;
    private boolean snapRequested;
    private int sinceCorrection;

    private int correctionTicks = DEFAULT_CORRECTION_TICKS;
    private double snapDistance = DEFAULT_SNAP_DISTANCE;
    private int maxPredictionTicks = DEFAULT_MAX_PREDICTION_TICKS;

    /** The attitude this is extrapolating, and the per-tick rotation it is extrapolating it by. */
    private final Quaternionf simAttitude = new Quaternionf();
    private final Quaternionf lastAttitude = new Quaternionf();
    private final Quaternionf spin = new Quaternionf();
    private boolean hasAttitude;
    private int sinceAttitude;

    /** Fed from the aircraft's file every tick; a craft learns its own tuning after it exists. */
    public void tune(int correctionTicks, double snapDistance, int maxPredictionTicks) {
        this.correctionTicks = Math.max(1, correctionTicks);
        this.snapDistance = Math.max(0.5, snapDistance);
        this.maxPredictionTicks = Math.max(1, maxPredictionTicks);
    }

    public boolean isSeeded() {
        return seeded;
    }

    public double renderX() {
        return renderX;
    }

    public double renderY() {
        return renderY;
    }

    public double renderZ() {
        return renderZ;
    }

    /** Where the aircraft is really believed to be, for anything that wants the target not the draw. */
    public double targetX() {
        return seeded ? simX : renderX;
    }

    public double targetY() {
        return seeded ? simY : renderY;
    }

    public double targetZ() {
        return seeded ? simZ : renderZ;
    }

    /** Stops driving. The next position seeds again from scratch rather than decaying from a stale pose. */
    public void release() {
        seeded = false;
        hasLastTarget = false;
        hasAttitude = false;
        sinceCorrection = 0;
        sinceAttitude = 0;
        velX = 0.0;
        velY = 0.0;
        velZ = 0.0;
        spin.identity();
    }

    /**
     * Takes a fresh authoritative position.
     *
     * <p>Note what this does not do: it does not move the drawn pose. A correction re-aims the
     * prediction and nothing else, which is precisely what stops a correction from ever producing a
     * visible step of its own. The one exception is an error too large to be flying error at all — a
     * teleport, a respawn, an aircraft that was out of range and is back — where there is nothing to
     * smooth and pretending otherwise would send it sliding across the world.
     *
     * @param currentX where the aircraft is drawn right now, so a first correction can seed from it
     */
    public void receivePosition(double x, double y, double z,
            double currentX, double currentY, double currentZ) {
        if (!seeded) {
            simX = x;
            simY = y;
            simZ = z;
            renderX = currentX;
            renderY = currentY;
            renderZ = currentZ;
            lastTargetX = x;
            lastTargetY = y;
            lastTargetZ = z;
            hasLastTarget = true;
            seeded = true;
            snapRequested = true;
            sinceCorrection = 0;
            return;
        }

        // Velocity from the two most recent authoritative positions, over the ticks that actually
        // passed between them. Measured in ticks rather than wall-clock: these arrive on the tick
        // thread one server tick apart, so the tick count is the honest interval and is immune to a
        // burst of packets landing together after a hitch, which a wall clock would read as an
        // enormous speed.
        if (hasLastTarget) {
            int gap = Math.max(1, sinceCorrection);
            double estX = (x - lastTargetX) / gap;
            double estY = (y - lastTargetY) / gap;
            double estZ = (z - lastTargetZ) / gap;
            double speed = Math.sqrt(estX * estX + estY * estY + estZ * estZ);

            if (speed > MAX_ESTIMATED_SPEED) {
                double scale = MAX_ESTIMATED_SPEED / speed;
                estX *= scale;
                estY *= scale;
                estZ *= scale;
            }
            velX = estX;
            velY = estY;
            velZ = estZ;
        }

        lastTargetX = x;
        lastTargetY = y;
        lastTargetZ = z;
        hasLastTarget = true;
        sinceCorrection = 0;

        double dx = x - renderX;
        double dy = y - renderY;
        double dz = z - renderZ;

        simX = x;
        simY = y;
        simZ = z;

        if (dx * dx + dy * dy + dz * dz > snapDistance * snapDistance) {
            renderX = x;
            renderY = y;
            renderZ = z;
            snapRequested = true;
        }
    }

    /**
     * Advances one tick of dead reckoning and decays whatever error the drawn pose still carries.
     *
     * @return whether the drawn pose should be applied to the aircraft this tick; false once the
     *         prediction budget has run out with no correction, which hands the aircraft back rather
     *         than letting it coast on a stale velocity for ever
     */
    public boolean advance() {
        if (!seeded) {
            return false;
        }

        sinceCorrection++;

        if (sinceCorrection > maxPredictionTicks) {
            release();
            return false;
        }

        // Against the OLD prediction, before it moves. See this class's own note on why.
        double errX = renderX - simX;
        double errY = renderY - simY;
        double errZ = renderZ - simZ;

        simX += velX;
        simY += velY;
        simZ += velZ;

        double residual = Math.pow(RESIDUAL_AFTER_WINDOW, 1.0 / correctionTicks);

        renderX = simX + errX * residual;
        renderY = simY + errY * residual;
        renderZ = simZ + errZ * residual;
        return true;
    }

    /** True once, after a seed or a snap, to tell the caller to place the aircraft outright. */
    public boolean consumeSnap() {
        boolean snap = snapRequested;
        snapRequested = false;
        return snap;
    }

    /**
     * Takes a fresh authoritative attitude, and works out how fast the aircraft is turning from the
     * two most recent ones so {@link #advanceAttitude} can keep turning it between them.
     *
     * <p>Held as a rotation rather than as three rates on purpose: the aircraft this belongs to is
     * built on a quaternion precisely so that it can fly through the vertical and roll inverted
     * without an angle folding back on itself, and finite-differencing yaw, pitch and roll
     * separately would put that seam straight back in.
     */
    public void receiveAttitude(Quaternionfc authoritative) {
        if (!hasAttitude) {
            simAttitude.set(authoritative);
            lastAttitude.set(authoritative);
            spin.identity();
            hasAttitude = true;
            sinceAttitude = 0;
            return;
        }

        int gap = Math.max(1, sinceAttitude);

        // The whole rotation since the last authoritative attitude, in the aircraft's own frame,
        // divided down to one tick's worth by slerping identity that far towards it.
        Quaternionf delta = new Quaternionf(lastAttitude).conjugate().mul(authoritative).normalize();

        spin.identity().slerp(delta, 1.0F / gap).normalize();
        lastAttitude.set(authoritative);
        simAttitude.set(authoritative);
        sinceAttitude = 0;
    }

    /**
     * Keeps the attitude turning at the rate the last two corrections implied.
     *
     * <p>Without this the attitude simply sits still between corrections and then has to cover the
     * whole accumulated turn the instant the next one lands. At one correction a tick nobody would
     * notice; the moment the interval stretches — a busy server, a dropped packet — an aircraft in a
     * roll at fifty degrees a second snaps through several degrees at once, over and over.
     *
     * <p>The rate itself decays towards no rotation at the same pace a position error does. A rate
     * measured across a single interval may be a sustained turn or may be one sharp tick of stick,
     * and there is no telling which from one sample: a real turn keeps being reconfirmed by fresh
     * corrections before it can fade, while a one-off spike dies out instead of spinning the
     * aircraft past its true heading for as long as the corrections happen to be sparse.
     */
    public void advanceAttitude(Quaternionf out) {
        if (!hasAttitude) {
            return;
        }

        sinceAttitude++;
        simAttitude.mul(spin).normalize();
        spin.slerp(NO_ROTATION, (float) (1.0 - Math.pow(RESIDUAL_AFTER_WINDOW, 1.0 / correctionTicks))).normalize();
        out.set(simAttitude);
    }

    public boolean hasAttitude() {
        return hasAttitude;
    }

    /** Whether this attitude is news, or the same one the aircraft was already extrapolating from. */
    public boolean isNewAttitude(Quaternionfc candidate) {
        return !hasAttitude
                || candidate.x() != lastAttitude.x
                || candidate.y() != lastAttitude.y
                || candidate.z() != lastAttitude.z
                || candidate.w() != lastAttitude.w;
    }
}
