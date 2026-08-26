package com.ashvehicles.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * How far a ground vehicle's body has moved on its springs, and the springs themselves.
 *
 * <p><b>What this is for.</b> A tank in this mod is held up by four probes: the ground under the
 * corners of its tracks is read, and the hull is laid along the plane they describe. That is a
 * faithful account of where a vehicle <em>ends up</em> and no account at all of how it gets there. A
 * real hull is not bolted to its running gear — it sits on torsion bars a foot or more deep, and
 * everything that makes a moving vehicle look like one happens in that foot: the nose dipping under
 * the brakes, the body leaning out of a turn, the whole machine rocking once and settling after it
 * drops off a kerb. Without it a tank slides over the landscape like a piece on a board.
 *
 * <p><b>Where it lives.</b> The body's movement is drawn and nothing else: the collision boxes, the
 * gun's aim and where the vehicle is standing are all worked out from the rigid hull exactly as
 * before, and none of them can see this. That is deliberate. A crew whose rounds went where the body
 * happened to be rocking at the moment of firing would be fighting the suspension rather than the
 * enemy, and a vehicle whose armour moved a few centimetres a tick would make every hit a lottery.
 * What moves is the picture — which is what suspension is <em>for</em>, on a machine whose weight the
 * game is not simulating.
 *
 * <p><b>Why it needs nothing sent.</b> Every side already knows how fast the vehicle is going, which
 * way it is heading, how high it is and which way the hull is lying: those are either simulated
 * locally by whoever is driving or sent for other reasons. The springs are driven from the
 * <em>changes</em> in those, so each side can run them for itself and arrive at the same picture
 * without a byte on the wire. Two sides that drift a little apart differ by a centimetre of body
 * movement, which is not a thing anybody can see and not a thing anything depends on.
 *
 * <p>The three figures are the three ways a body can move on its springs that are worth drawing.
 * There is no fourth: sideways and fore-and-aft travel exist on a real vehicle and amount to
 * millimetres, and yaw on the springs is not a thing a tracked hull does.
 *
 * @param heave how far the body has risen above where it sits at rest, in blocks; negative is the
 *              springs compressed
 * @param pitch how far the nose has risen above the tail, in degrees
 * @param lean how far the right-hand side has dropped below the left, in degrees — the same sign as
 *             the hull's own bank, so that the two read the same way round
 */
public record Ride(float heave, float pitch, float lean) {
    /** A body sitting square on its springs, which is what a vehicle with none has for ever. */
    public static final Ride LEVEL = new Ride(0.0F, 0.0F, 0.0F);

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /**
     * Whether the body has moved far enough to be worth drawing differently.
     *
     * <p>Worth asking: a vehicle standing still with its springs settled is the common case, and
     * everything that reads this — the pose stack, every road wheel, every link of track — can be
     * skipped outright when the answer is no.
     */
    public boolean isLevel() {
        return Math.abs(this.heave) < 1.0E-4F && Math.abs(this.pitch) < 1.0E-3F
                && Math.abs(this.lean) < 1.0E-3F;
    }

    /** The body between two of these, for a ghost, which has no springs of its own to ask. */
    public static Ride between(Ride previous, Ride now, float partialTick) {
        return new Ride(
                Mth.lerp(partialTick, previous.heave(), now.heave()),
                Mth.lerp(partialTick, previous.pitch(), now.pitch()),
                Mth.lerp(partialTick, previous.lean(), now.lean()));
    }

    /**
     * A point on the vehicle, carried to where the body's movement has put it — in the vehicle's own
     * axes, where x runs to the right, y up and z over the bow, which is what everything in a
     * machine's file is written in.
     *
     * <p>The whole movement rather than just the height. A crew member's eye a couple of blocks
     * above the hull floor and three blocks forward of the middle is thrown backwards as the nose
     * comes up and sideways as the body leans, and it is that — rather than a hull bobbing up and
     * down underneath a head that stays put — that reads from inside as being shaken about.
     *
     * <p>The angles are small enough for the small-angle form to be exact to the pixel: at the ten
     * degrees a badly abused suspension might reach, the error is a percent and a half of a
     * centimetre-scale movement.
     */
    public Vec3 carry(Vec3 point) {
        if (this.isLevel()) {
            return point;
        }

        float nod = this.pitch * DEG_TO_RAD;
        float heel = this.lean * DEG_TO_RAD;

        return new Vec3(
                point.x + point.y * heel,
                point.y + this.heave + point.z * nod - point.x * heel,
                point.z - point.y * nod);
    }

    /**
     * How far a point built into the model is carried straight up by the body's movement, in the
     * model's own blocks.
     *
     * <p>What this is for is the running gear, which has to be moved the other way by exactly this
     * much to stay where it was. The body is rocked on the pose stack, so every bone in the model
     * goes with it; a road wheel put back down by what this returns is a road wheel that stays on
     * the ground while the hull above it moves, which is the whole of what a suspension looks like
     * from outside.
     *
     * <p><b>The axes are the model's, not the vehicle's.</b> A machine is drawn with its attitude
     * applied and then turned half round — see {@code VehicleRenderer.applyRotations} — so a point
     * built at the model's +X is out to the vehicle's left of the screen's reckoning and one at its
     * +Z is towards the tail. That half turn is the whole of the difference, and it is why both
     * terms come out negated against {@link #carry}'s.
     *
     * @param scale the model's own scale, since the model is drawn inside it and a block of body
     *              movement is that much less of a block once the model has been scaled down
     */
    public float liftOf(float modelX, float modelZ, float scale) {
        return this.heave / Math.max(scale, 0.01F)
                - modelZ * this.pitch * DEG_TO_RAD
                - modelX * this.lean * DEG_TO_RAD;
    }

    // ------------------------------------------------------------------
    // The springs themselves
    // ------------------------------------------------------------------

    /**
     * The suspension of one vehicle, ticked by that vehicle and read by whatever draws it.
     *
     * <p>Three damped springs, one per way the body can move, each pulled towards where the forces
     * on the vehicle say the body should be sitting and each free to overshoot on the way. A spring
     * rather than an easing towards a target, because the overshoot is the point: a body that
     * settled straight onto its resting line would dip under the brakes and stay dipped, where a
     * real one dips, comes back past level and rocks itself still over the next second.
     *
     * <p>What excites them is measured rather than invented, and the measurements are the same on
     * every side:
     *
     * <ul>
     * <li><b>The hull's vertical acceleration.</b> Not how fast it is falling — a vehicle running
     * steadily down a hillside is not being thrown about — but how sharply that is changing. A hull
     * dropped a block and stopped dead compresses its springs by what the body was doing at the
     * moment it landed.
     * <li><b>The drivetrain.</b> Pulling away lifts the nose and braking drops it, and both in
     * proportion to what this particular vehicle can manage, so a file does not have to know
     * anything about blocks per tick squared to say how much its hull moves.
     * <li><b>The turn.</b> A body thrown outwards by a corner leans away from it, again as a
     * fraction of the hardest corner this vehicle can turn.
     * </ul>
     *
     * <p><b>And nothing else.</b> There is no running shudder laid over the top of these, from the
     * engine or from the going, and there deliberately is not. Such a thing cannot be measured: what
     * the four ground probes describe is the plane the hull lies in, and the difference between that
     * plane and what each individual road wheel is riding over is not something this vehicle has
     * ever known — so it would have to be invented, as a wave shaking a body that nothing is
     * actually shaking. What is here instead is only the vehicle answering things that really
     * happened to it, and a tank crossing broken ground has plenty of those.
     */
    public static final class Springs {
        /**
         * How much of a sudden change in the hull's rate of fall the body keeps for itself.
         *
         * <p>Not all of it. A hull that lands is decelerated by the ground over rather less than a
         * tick and the body is decelerated by the springs over most of a second, so what the body is
         * still doing at the end of the tick the hull stopped in is a good part of what it was doing
         * at the start of it, and not the whole.
         */
        private static final float IMPACT = 0.4F;

        /** The most one tick of falling is allowed to count for, so a teleport is not a launch. */
        private static final float MAX_JOLT = 1.5F;

        private final Axis heave = new Axis();
        private final Axis pitch = new Axis();
        private final Axis lean = new Axis();

        /**
         * Whether there is a previous tick to have changed from. Everything here is a difference, and
         * the first tick of a vehicle's life — or the first after one is read back out of the
         * world — has nothing to difference against.
         */
        private boolean primed;

        private float wasSpeed;
        private float wasHeading;
        private double wasY;
        private double wasSink;

        /**
         * One tick of the suspension.
         *
         * @param speed along the hull's nose, in blocks a tick
         * @param heading the hull's bearing, in degrees
         * @param y how high the vehicle is standing
         * @param onGround whether the vehicle has anything under it to be thrown about by
         */
        public void tick(GroundVehicleDefinition definition, float speed, float heading, double y,
                boolean onGround) {
            GroundVehicleDefinition.Suspension setup = definition.suspension();

            float sink = (float) (y - this.wasY);
            float jolt = Mth.clamp(sink - (float) this.wasSink, -MAX_JOLT, MAX_JOLT);
            float along = speed - this.wasSpeed;
            float turn = Mth.degreesDifference(this.wasHeading, heading);

            this.wasSink = sink;
            this.wasY = y;
            this.wasSpeed = speed;
            this.wasHeading = heading;

            if (!this.primed) {
                this.primed = true;
                jolt = 0.0F;
                along = 0.0F;
                turn = 0.0F;
            }

            float travel = Math.max(setup.travel(), 0.0F);

            if (travel <= 0.0F) {
                // A vehicle whose file says its body does not move. Every spring is given nothing to
                // aim at and no room to move, which pins the lot at rest without a second path
                // through any of this.
                this.heave.tick(0.0F, setup, 0.0F);
                this.pitch.tick(0.0F, setup, 0.0F);
                this.lean.tick(0.0F, setup, 0.0F);

                return;
            }

            // How far each spring may go before the body is against its stops. The two angles are
            // the same travel read across the vehicle: a wheel at one end of the contact patch that
            // has used up all of it has tipped the hull by however much that is over the distance
            // to the middle.
            float halfLength = Math.max(setup.contactLength() * 0.5F, 0.5F);
            float halfWidth = Math.max(setup.contactWidth() * 0.5F, 0.5F);
            float nodLimit = (float) Math.toDegrees(travel / halfLength);
            float heelLimit = (float) Math.toDegrees(travel / halfWidth);

            if (onGround) {
                this.heave.kick(-jolt * IMPACT);
            }

            // The body's own resting line is level and level is all it is pulled towards; what puts
            // it anywhere else is the jolt above. The other two are held wherever the drivetrain and
            // the corner are holding them, and let go the moment those stop.
            this.heave.tick(0.0F, setup, travel);
            this.pitch.tick(this.nod(definition, along), setup, nodLimit);
            this.lean.tick(this.heel(definition, speed, turn), setup, heelLimit);
        }

        /** The body a moment between two ticks, which is what anything drawing it wants. */
        public Ride at(float partialTick) {
            return new Ride(this.heave.at(partialTick), this.pitch.at(partialTick),
                    this.lean.at(partialTick));
        }

        /**
         * Where the drivetrain is asking the body to sit, in degrees of nose up.
         *
         * <p>Against what this vehicle can actually do rather than against a fixed number of blocks
         * a tick squared, so that {@code dive} says what it means — the nod at the hardest this
         * machine pulls or stops — whether the machine is a scout car or sixty tonnes.
         */
        private float nod(GroundVehicleDefinition definition, float along) {
            GroundVehicleDefinition.Powertrain powertrain = definition.powertrain();
            float hardest = Math.max(Math.max(powertrain.acceleration(), powertrain.braking()), 1.0E-4F);

            return Mth.clamp(along / hardest, -1.0F, 1.0F) * definition.suspension().dive();
        }

        /**
         * Where the corner is throwing the body, in degrees of right-hand side down.
         *
         * <p>Negated, and that is the whole of the physics: a body carried round to the right is
         * left behind to the left, so a right-hand turn leans the vehicle onto its left-hand
         * springs. Which is why it is worth drawing at all — a hull that banked <em>into</em> its
         * turns would read as an aeroplane.
         */
        private float heel(GroundVehicleDefinition definition, float speed, float turn) {
            GroundVehicleDefinition.Powertrain powertrain = definition.powertrain();
            float hardest = Math.max(powertrain.maxSpeed(), 1.0E-4F)
                    * Math.max(powertrain.steerRate(), 1.0E-4F) * DEG_TO_RAD;

            return -Mth.clamp(speed * turn * DEG_TO_RAD / hardest, -1.0F, 1.0F)
                    * definition.suspension().lean();
        }


        /**
         * One spring, and the one thing on it worth drawing.
         *
         * <p>Integrated as a plain damped spring a tick at a time. Nothing here is stiff enough for
         * that to be a problem — the fastest of these bodies takes half a second to come back to
         * level, which is ten ticks of a wave rather than one — and a step the size of a tick is
         * what the rest of the vehicle is worked out in anyway.
         */
        private static final class Axis {
            /** Where the body is, where it was at the end of the tick before, and how fast it moves. */
            private float value;
            private float previous;
            private float rate;

            /**
             * @param target where the forces on the vehicle are holding the body — level, unless the
             *               drivetrain or a corner is pulling it somewhere
             * @param limit how far the body may go before it is against its stops
             */
            void tick(float target, GroundVehicleDefinition.Suspension setup, float limit) {
                this.previous = this.value;
                this.rate += (target - this.value) * Mth.clamp(setup.stiffness(), 0.0F, 1.0F);
                this.rate -= this.rate * Mth.clamp(setup.damping(), 0.0F, 1.0F);

                float moved = this.value + this.rate;

                // The stops. A spring that has run out of travel stops, and stops carrying the speed
                // that got it there — a body against its bump stops is a body that has just had the
                // rest of its movement taken out by the vehicle rather than by the spring.
                if (moved > limit) {
                    moved = limit;
                    this.rate = Math.min(this.rate, 0.0F);
                } else if (moved < -limit) {
                    moved = -limit;
                    this.rate = Math.max(this.rate, 0.0F);
                }

                this.value = moved;
            }

            void kick(float impulse) {
                this.rate += impulse;
            }

            float at(float partialTick) {
                return Mth.lerp(partialTick, this.previous, this.value);
            }
        }
    }
}
