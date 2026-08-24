package com.ashvehicles.entity;

import com.ashvehicles.vehicle.Attitude;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

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
 * advances at the aircraft's velocity every tick, which by construction has no lag at all for
 * anything flying a straight line, and only ever has to absorb the difference between that guess and
 * the truth.
 *
 * <h2>Three clocks, and why the aircraft used to stutter anyway</h2>
 *
 * <p>The prediction is only as good as the velocity driving it, and the honest velocity is not
 * something this side can measure. A piloted aircraft is flown on the pilot's client, relayed by the
 * server, and watched here: three twenty-hertz clocks, none of them in step, with a network between
 * each pair. What arrives is <em>not</em> one position update per tick. It is one per tick on
 * average, with ticks that get two and ticks that get none, for ever, because the clocks drift past
 * each other — and that is before a single packet is late.
 *
 * <p>Working the velocity out from consecutive positions therefore reads a stutter that is not
 * there: two updates in one tick and the aircraft appears to be doing twice its speed, none the next
 * and it appears to have stopped. Dead reckoning on that estimate does not merely fail to help, it
 * amplifies the jitter into the drawn motion. So the velocity is not guessed here at all: the side
 * that actually knows — the pilot's client, or the server for an aircraft nobody is flying — sends
 * it, and both the speed and the turn rate arrive alongside the position. A tick that brings no
 * update now costs nothing, because the prediction already knew what to do with it.
 *
 * <h2>Absorbing a correction without a visible surge</h2>
 *
 * <p>What is left is the difference between the prediction and the truth, and how that difference is
 * taken up is the whole of whether the aircraft looks smooth. Closing a fixed fraction of it each
 * tick — the obvious thing, and what this class used to do — is smooth in <em>position</em> but not
 * in speed: the tick a correction lands, the drawn step is the aircraft's own travel plus most of
 * the error, and the tick after it is back to normal. A correction worth one tick of travel
 * therefore draws one tick at nearly double speed. That is exactly the stutter it was supposed to
 * cure, and at three blocks a tick it is a two-block jump.
 *
 * <p>So the error is not decayed, it is <em>flown out</em>, by a critically damped spring: it carries
 * a rate of its own, that rate can only change gradually, and the drawn velocity is therefore
 * continuous across a correction instead of spiking on the tick it arrives. A correction changes how
 * the error will be taken up over the next several ticks and never what is drawn this one. Nothing
 * an aircraft does can put a step in the drawn speed, because there is no longer anywhere for a step
 * to come from.
 *
 * <p>The attitude is run the same way, on the same spring, in the same frame: an aeroplane's roll is
 * far more visible than its position — a wingtip travels much further than the fuselage does — and
 * an attitude that snapped to each update while the position was smoothed would put back, in the
 * part of the picture that shows it most, the stutter the position had just been rid of.
 */
public final class AircraftInterpolation {

    /** Ticks a correction is taken up over, if the aircraft's file names none. */
    public static final int DEFAULT_CORRECTION_TICKS = 5;

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
     * Most a reported velocity is believed, in blocks per tick. Nothing in the mod flies anywhere
     * near this; it is here so that a corrupt or hostile figure cannot fling an aircraft across the
     * world between two corrections.
     */
    private static final double MAX_SPEED = 40.0;

    /** Most a reported turn rate is believed, in radians a tick. A quarter turn a tick is already absurd. */
    private static final float MAX_BODY_RATE = (float) (Math.PI / 2.0);

    /** Attitude error past which there is nothing worth smoothing, in radians. */
    private static final float SNAP_ANGLE = (float) Math.toRadians(90.0);

    // Where the aircraft is really believed to be, dead-reckoned forward between corrections.
    private double simX;
    private double simY;
    private double simZ;
    private double velX;
    private double velY;
    private double velZ;

    /** How far what is drawn sits from that prediction, per axis, and how fast that is closing. */
    private final Offset offsetX = new Offset();
    private final Offset offsetY = new Offset();
    private final Offset offsetZ = new Offset();

    private double renderX;
    private double renderY;
    private double renderZ;

    private double lastTargetX;
    private double lastTargetY;
    private double lastTargetZ;

    private boolean seeded;
    private boolean hasLastTarget;
    private boolean snapRequested;
    /** Whether the velocity is being told to this side rather than guessed from the position stream. */
    private boolean velocityReported;
    private int sinceCorrection;

    private int correctionTicks = DEFAULT_CORRECTION_TICKS;
    private double snapDistance = DEFAULT_SNAP_DISTANCE;
    private int maxPredictionTicks = DEFAULT_MAX_PREDICTION_TICKS;

    /** The attitude being extrapolated, the last one that arrived, and the turn being carried on. */
    private final Quaternionf simAttitude = new Quaternionf();
    private final Quaternionf lastAttitude = new Quaternionf();
    /** Turn rate, radians a tick about the aircraft's own axes. See {@link Attitude#rotationVector}. */
    private final Vector3f spin = new Vector3f();
    /**
     * The drawn attitude's offset from the prediction, on the same spring the position uses: the
     * turn still to be made about each of the aircraft's own axes, in radians, in the order a
     * rotation vector writes them — x through the wings, y through the canopy, z along the nose.
     */
    private final Offset offsetAboutX = new Offset();
    private final Offset offsetAboutY = new Offset();
    private final Offset offsetAboutZ = new Offset();
    private boolean hasAttitude;
    private boolean rateReported;
    private int sinceAttitude;

    /** Scratch, so drawing an attitude every tick does not allocate. */
    private final Quaternionf scratch = new Quaternionf();
    private final Vector3f scratchVector = new Vector3f();

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

    /** Stops driving. The next update seeds again from scratch rather than from a stale pose. */
    public void release() {
        releasePosition();
        hasAttitude = false;
        rateReported = false;
        sinceAttitude = 0;
        spin.zero();
        offsetAboutX.clear();
        offsetAboutY.clear();
        offsetAboutZ.clear();
    }

    /**
     * Gives up on the position alone and leaves the attitude running.
     *
     * <p>The two arrive by different routes and go quiet for different reasons. An aircraft standing
     * still is not sent position updates at all — there is nothing to say — while a helicopter
     * turning on its wheels is sent attitudes every tick, and taking the silence on one as a reason
     * to reset the other would put a hitch into the pedal turn for no reason whatsoever.
     */
    private void releasePosition() {
        seeded = false;
        hasLastTarget = false;
        velocityReported = false;
        sinceCorrection = 0;
        velX = 0.0;
        velY = 0.0;
        velZ = 0.0;
        offsetX.clear();
        offsetY.clear();
        offsetZ.clear();
    }

    /**
     * Takes the velocity from the side that is actually flying the aircraft, in blocks a tick.
     *
     * <p>This is the difference between a prediction that works and one that stutters, and it is
     * worth being plain about why it cannot simply be measured here instead. Corrections do not
     * arrive one a tick; they arrive one a tick <em>on average</em>, because the pilot's clock, the
     * server's and this one all run at twenty hertz and none of them agree on when a tick starts.
     * Differencing consecutive positions reads that drift as speed — double one tick, nothing the
     * next — and the prediction would then carry the jitter into the drawn motion rather than
     * absorbing it. The figure below has none of that in it: it is what the flight model actually
     * produced, on the machine that ran it.
     */
    public void receiveVelocity(double x, double y, double z) {
        double speed = Math.sqrt(x * x + y * y + z * z);

        if (speed > MAX_SPEED) {
            double scale = MAX_SPEED / speed;
            x *= scale;
            y *= scale;
            z *= scale;
        }

        velX = x;
        velY = y;
        velZ = z;
        velocityReported = true;
    }

    /**
     * Takes the turn rate from the side flying the aircraft, in radians a tick about its own axes,
     * for the same reasons and against the same drift as {@link #receiveVelocity}.
     */
    public void receiveBodyRate(float aboutX, float aboutY, float aboutZ) {
        scratchVector.set(aboutX, aboutY, aboutZ);

        float rate = scratchVector.length();

        if (rate > MAX_BODY_RATE) {
            scratchVector.mul(MAX_BODY_RATE / rate);
        }

        spin.set(scratchVector);
        rateReported = true;
    }

    /**
     * Takes a fresh authoritative position.
     *
     * <p>Note what this does not do: it does not move the drawn pose. A correction re-aims the
     * prediction and hands the whole of the difference to the spring, which is precisely what stops
     * a correction from ever producing a visible step of its own. The one exception is an error too
     * large to be flying error at all — a teleport, a respawn, an aircraft that was out of range and
     * is back — where there is nothing to smooth and pretending otherwise would send it sliding
     * across the world.
     *
     * @param currentX where the aircraft is drawn right now, so a first correction can seed from it
     */
    public void receivePosition(double x, double y, double z,
            double currentX, double currentY, double currentZ) {
        if (!seeded) {
            simX = x;
            simY = y;
            simZ = z;
            // Start from where the aircraft already is and let the spring fly the difference out,
            // rather than putting it somewhere else the moment it is first heard about.
            offsetX.set(currentX - x);
            offsetY.set(currentY - y);
            offsetZ.set(currentZ - z);
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

        // Only ever a fallback. If the aircraft is reporting its velocity — everything in the mod
        // does — this is not reached, and the note on receiveVelocity says why guessing is worse.
        // Measured in ticks rather than wall-clock: a burst of packets landing together after a
        // hitch would read as an enormous speed on a clock, and as the interval it really was here.
        if (!velocityReported && hasLastTarget) {
            int gap = Math.max(1, sinceCorrection);

            receiveVelocity((x - lastTargetX) / gap, (y - lastTargetY) / gap, (z - lastTargetZ) / gap);
            // Guessed, not told. Say so, or one guess would silence the next.
            velocityReported = false;
        }

        lastTargetX = x;
        lastTargetY = y;
        lastTargetZ = z;
        hasLastTarget = true;
        sinceCorrection = 0;

        // The drawn pose stays exactly where it is: the prediction moves under it, and the offset
        // takes up the difference. render = sim + offset before and after, to the last digit.
        offsetX.shift(simX - x);
        offsetY.shift(simY - y);
        offsetZ.shift(simZ - z);

        simX = x;
        simY = y;
        simZ = z;

        double errX = offsetX.value();
        double errY = offsetY.value();
        double errZ = offsetZ.value();

        if (errX * errX + errY * errY + errZ * errZ > snapDistance * snapDistance) {
            offsetX.clear();
            offsetY.clear();
            offsetZ.clear();
            renderX = x;
            renderY = y;
            renderZ = z;
            snapRequested = true;
        }
    }

    /**
     * Advances one tick of dead reckoning and flies out whatever error the drawn pose still carries.
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
            releasePosition();
            return false;
        }

        simX += velX;
        simY += velY;
        simZ += velZ;

        offsetX.step(correctionTicks);
        offsetY.step(correctionTicks);
        offsetZ.step(correctionTicks);

        renderX = simX + offsetX.value();
        renderY = simY + offsetY.value();
        renderZ = simZ + offsetZ.value();
        return true;
    }

    /** True once, after a seed or a snap, to tell the caller to place the aircraft outright. */
    public boolean consumeSnap() {
        boolean snap = snapRequested;
        snapRequested = false;
        return snap;
    }

    /**
     * Takes a fresh authoritative attitude.
     *
     * <p>Like a position, it re-aims the prediction and leaves what is drawn alone: the difference
     * between the two goes to the spring in {@link #advanceAttitude} and is turned out over the next
     * few ticks. Held as a rotation rather than as three angles throughout, because the aircraft this
     * belongs to is built on a quaternion precisely so that it can fly through the vertical and roll
     * inverted without an angle folding back on itself.
     */
    public void receiveAttitude(Quaternionfc authoritative) {
        if (!hasAttitude) {
            simAttitude.set(authoritative);
            lastAttitude.set(authoritative);
            offsetAboutX.clear();
            offsetAboutY.clear();
            offsetAboutZ.clear();
            spin.zero();
            hasAttitude = true;
            sinceAttitude = 0;
            return;
        }

        // What is on screen this instant, kept so the correction cannot move it.
        drawnAttitude(scratch);

        // Fallback only, for an aircraft that is not reporting its turn rate: the whole rotation
        // since the last authoritative attitude, divided down to one tick's worth.
        if (!rateReported) {
            int gap = Math.max(1, sinceAttitude);

            spin.set(Attitude.rotationVector(
                    new Quaternionf(lastAttitude).conjugate().mul(authoritative).normalize()))
                    .mul(1.0F / gap);
        }

        lastAttitude.set(authoritative);
        simAttitude.set(authoritative);
        sinceAttitude = 0;

        // The drawn attitude, written as a turn away from the new prediction in the aircraft's own
        // frame. The rates the spring is carrying are left alone: over the fraction of a degree a
        // correction usually moves the prediction, the old frame and the new one are the same frame.
        Vector3f error = Attitude.rotationVector(
                scratch.premul(new Quaternionf(simAttitude).conjugate()).normalize());

        if (error.length() > SNAP_ANGLE) {
            offsetAboutX.clear();
            offsetAboutY.clear();
            offsetAboutZ.clear();
            return;
        }

        offsetAboutX.set(error.x);
        offsetAboutY.set(error.y);
        offsetAboutZ.set(error.z);
    }

    /**
     * Keeps the attitude turning at the reported rate and flies out whatever is left over.
     *
     * <p>Without the first half the attitude would sit still between corrections and then have to
     * cover the whole accumulated turn the instant the next one lands; without the second it would
     * snap to each correction as it arrived. Both draw the same stutter, and an aeroplane shows it
     * far more readily in roll than in position, a wingtip having so much further to travel.
     *
     * <p>The turn is carried no longer than the position is dead-reckoned for. Past that the last
     * rate heard is no longer evidence of anything, and an aircraft that has stopped being talked
     * about should come to rest rather than wind on for ever about its own nose.
     */
    public void advanceAttitude(Quaternionf out) {
        if (!hasAttitude) {
            return;
        }

        sinceAttitude++;

        if (sinceAttitude <= maxPredictionTicks) {
            simAttitude.mul(Attitude.rotationOf(spin)).normalize();
        }

        offsetAboutX.step(correctionTicks);
        offsetAboutY.step(correctionTicks);
        offsetAboutZ.step(correctionTicks);

        drawnAttitude(out);
    }

    /** The prediction, turned by however much error the spring has still to take up. */
    private void drawnAttitude(Quaternionf out) {
        scratchVector.set((float) offsetAboutX.value(), (float) offsetAboutY.value(), (float) offsetAboutZ.value());
        out.set(simAttitude).mul(Attitude.rotationOf(scratchVector)).normalize();
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

    /**
     * One axis of the difference between where the aircraft is drawn and where it is predicted to
     * be, closing under a critically damped spring.
     *
     * <p>Critically damped is the point: the offset carries a rate, so it leaves and reaches zero
     * gradually instead of lurching, and it does so without ever crossing to the other side and
     * having to come back. What that buys is a drawn velocity that is continuous — the aircraft's own
     * speed plus a rate that can only change a little each tick — so no correction, however it
     * arrives, can put a step in how fast the aeroplane appears to be going.
     *
     * <p>The step is the closed form of a critically damped spring over one tick rather than an
     * Euler step of one, which matters because a tick is a long time next to the settling times
     * wanted here: integrated naively, a two-tick spring is unstable and rings.
     */
    private static final class Offset {
        private double value;
        private double rate;

        double value() {
            return value;
        }

        /** Moves the offset without disturbing the rate: the prediction moved, not the drawing. */
        void shift(double by) {
            value += by;
        }

        void set(double to) {
            value = to;
        }

        void clear() {
            value = 0.0;
            rate = 0.0;
        }

        /** @param smoothTicks roughly how long the offset takes to be flown out */
        void step(double smoothTicks) {
            double omega = 2.0 / smoothTicks;
            // One tick, so the time step is one and drops out of everything below.
            double decay = 1.0 / (1.0 + omega + 0.48 * omega * omega + 0.235 * omega * omega * omega);
            double travel = rate + omega * value;

            rate = (rate - omega * travel) * decay;
            value = (value + travel) * decay;
        }
    }
}
