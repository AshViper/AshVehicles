package com.ashvehicles.entity;

import javax.annotation.Nullable;

import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * A rocket, and — when its weapon file gives it guidance and the pilot had something locked when it
 * left — a missile.
 *
 * <p>The two are the same object because they are nearly the same thing. Both leave the rail slowly
 * and are pushed by a motor for a few seconds, which is why an aircraft briefly outruns its own
 * rockets after launch and why one fired from a standing start still gets going. Both coast
 * afterwards and both go off where they land. Both hold the line they were fired on for as long as
 * the motor is alight — see {@link #axis} — and both begin to arc once it is out and there is nothing
 * holding the nose up any more. The only difference is that a missile bends that line towards
 * something while its motor burns.
 *
 * <p>The motor is alight from the rail, but it need not arrive at all of its thrust at once: a
 * weapon file can have it work up to that over a second or so, so the missile gathers speed rather
 * than snapping to its top speed in three ticks. A file that asks for no such light-up gets the
 * whole of its thrust from the first tick.
 *
 * <p>What a missile can do is bounded, deliberately. It turns at a fixed number of degrees a tick,
 * so a target that turns harder than that will be missed; and it only follows what it can still see
 * ahead of it, so a target that gets behind it is lost and the missile flies on as a rocket. It also
 * steers under power only — once the motor is out it is ballistic, like everything else. None of
 * this homes unconditionally, which is the point: a missile should be beatable.
 *
 * <p>And one fired with nothing locked is a rocket from the rail. Its seeker never runs at all, so it
 * does not go hunting for something of its own on the way out, and there is nothing running for a
 * flare to fool — which matters most to the pilot who fired it, since the nearest countermeasures to
 * an unlocked shot are usually the ones their own aircraft just dispensed.
 */
public class RocketEntity extends VehicleProjectile implements GeoEntity {
    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * Who it is chasing. Synced so a client can draw the missile pointing where it is really going
     * rather than where it was when it launched.
     */
    /** How far off a decoy can be and still tempt the missile, in blocks. */
    private static final double DECOY_REACH = 40.0;
    /** And the chance, each tick, that one of them in reach takes it. */
    private static final float DECOY_CHANCE = 0.2F;

    private static final EntityDataAccessor<Integer> DATA_TARGET =
            SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

    @Nullable
    private Entity target;
    /** True once the seeker has lost what it was chasing, so it stops looking. */
    private boolean lost;

    /**
     * The way it is pointing, which is not the same as the way it is going.
     *
     * <p>Held rather than worked out afresh each tick, and that difference is the whole of whether a
     * rocket flies straight. A motor pushes a rocket along its own axis; fins hold that axis where it
     * is. Taking the axis from the velocity instead — as this used to — hands the rocket back every
     * disturbance that has acted on it: a tick of gravity bends the velocity down a hair, the next
     * tick calls that bent line the way the rocket is pointing, and the motor then spends its whole
     * burn pushing along it. Nothing ever corrects it, so the error does not average out, it
     * accumulates, and the harder the motor pushes the more committed the rocket is to the wrong
     * line. Kept as its own value, the axis is a thing that is only ever changed deliberately — by a
     * seeker turning it, at the rate its file allows — and a rocket with no seeker holds the line it
     * was fired on until its motor is out.
     *
     * <p>Zero until the launch speed is known; see {@link #launched} and {@link #axis()}.
     */
    private Vec3 axis = Vec3.ZERO;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TARGET, -1);
    }

    /**
     * Takes the axis it was fired on. The same figure on both sides, since both are told the launch
     * speed and neither has had a chance to disturb it yet.
     */
    @Override
    protected void launched(Vec3 velocity) {
        super.launched(velocity);
        this.axis = velocity.lengthSqr() < 1.0E-8 ? Vec3.ZERO : velocity.normalize();
    }

    /**
     * The way it is pointing.
     *
     * <p>Falls back to the way it is going for anything that was never told — a rocket read back off
     * the disk, most likely, whose saved axis is restored below but whose file may predate it. Past
     * that there is nothing honest left to answer: a rocket with neither an axis nor a speed is not
     * pointing anywhere, and inventing a direction for it would send it off across the world on a
     * heading nobody chose. {@link #steer} therefore leaves it alone instead.
     */
    private Vec3 axis() {
        if (this.axis.lengthSqr() > 1.0E-8) {
            return this.axis;
        }

        Vec3 velocity = this.getDeltaMovement();

        return velocity.lengthSqr() < 1.0E-8 ? Vec3.ZERO : velocity.normalize();
    }

    /** Hands the missile what the pilot had locked. Without this it flies as an unguided rocket. */
    public void setTarget(@Nullable Entity target) {
        this.target = target;
        this.entityData.set(DATA_TARGET, target == null ? -1 : target.getId());
    }

    @Nullable
    public Entity getTarget() {
        if (this.target == null) {
            int id = this.entityData.get(DATA_TARGET);
            this.target = id < 0 ? null : this.level().getEntity(id);
        }

        return this.target;
    }

    /**
     * Forgets what a client thought it was chasing whenever the server says otherwise.
     *
     * <p>{@link #getTarget()} holds on to what it found, because a missile asks for it on every tick
     * of flight and on every frame it is drawn, and looking an entity up by its number each time is
     * not free. But holding on to it means nothing else can change it — so a client went on flying at
     * the aeroplane after the server had sent the missile off after a flare, or after the server had
     * given up on the target altogether. The two then steered towards different things until the gap
     * between them was wide enough to be called a teleport, and the missile was put back on the
     * server's path in one jump. Dropping the held answer here is what lets the next ask see the new
     * one.
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);

        if (DATA_TARGET.equals(key) && this.level().isClientSide) {
            this.target = null;
        }
    }

    /** Whether it is still under power, which is when it can both accelerate and steer. */
    public boolean isBurning() {
        return this.age <= this.getRound().burnTicks();
    }

    /** Under power there is a plume at the nozzle; coasting there is only the trail behind. */
    @Override
    protected boolean underPower() {
        return this.getRound().hasMotor() && this.isBurning();
    }

    @Override
    protected void steer() {
        WeaponDefinition.Projectile round = this.getRound();
        Vec3 velocity = this.getDeltaMovement();

        if (!this.isBurning() || !round.hasMotor()) {
            return;
        }

        Vec3 heading = this.axis();

        if (heading.lengthSqr() < 1.0E-8) {
            return;
        }

        // Where a seeker wants the nose, which for anything not chasing something is where the nose
        // already is. This is the only thing that ever turns a rocket, and it turns it by no more
        // than the file's turn_rate a tick.
        Vec3 wanted = this.guidedHeading(heading);

        // The motor pushes along the way it is pointing, up to the speed it will hold: a rocket motor
        // moves its own nose, it does not slide the rocket sideways. So the whole of this tick's
        // speed goes down the axis, and what gravity did to the velocity last tick is left as what it
        // is — a change of speed, not a change of heading. Which is why a rocket under power flies
        // the line it was fired on, and only begins to arc once the motor is out and there is nothing
        // holding the nose up any more.
        double speed = Math.min(velocity.length() + this.thrustNow(round),
                round.topSpeed() > 0.0F ? round.topSpeed() : Double.MAX_VALUE);

        this.axis = wanted;
        this.setDeltaMovement(wanted.scale(speed));
    }

    /**
     * What the motor is making this tick, in blocks per tick squared.
     *
     * <p>Not the whole of its thrust from the first tick of the burn. A motor handed all of it at
     * once has the missile at its top speed within a few ticks, which does not read as accelerating
     * at all — it reads as the missile having been fired at that speed. Worked up over
     * {@code spool_ticks} instead: a little off the rail, all of it once it is running, and the
     * missile visibly gathers pace over the second or two in between. A file that leaves the figure
     * out gets its full thrust immediately, as before.
     */
    private float thrustNow(WeaponDefinition.Projectile round) {
        int spool = round.spoolTicks();

        if (spool <= 0) {
            return round.thrust();
        }

        return round.thrust() * Mth.clamp((this.age + 1) / (float) spool, 0.0F, 1.0F);
    }

    /**
     * Whether anything the target has thrown out is more interesting than the target.
     *
     * <p>Checked every tick while the missile is guiding, and settled by chance rather than by
     * rules: each decoy in reach has its own small chance of taking the missile, so one flare is a
     * gamble, a burst of them is a fair bet, and none at all is certain death. That is what makes
     * the timing of pulling the handle worth anything.
     *
     * <p>Only the sort that fools <em>this</em> seeker counts, and once it has been taken it is not
     * given back: a missile that has gone for a flare has gone for the flare, and what it does after
     * that is fly into it and go off in empty air.
     */
    private void checkDecoys(WeaponDefinition.Guidance guidance) {
        if (this.getTarget() instanceof CountermeasureEntity) {
            return;
        }

        AABB box = this.getBoundingBox().inflate(DECOY_REACH);

        for (CountermeasureEntity decoy : this.level().getEntitiesOfClass(CountermeasureEntity.class, box,
                candidate -> candidate.fools(guidance.seeker()))) {
            if (this.random.nextFloat() < DECOY_CHANCE) {
                this.setTarget(decoy);

                return;
            }
        }
    }

    /**
     * Where the missile should be pointing this tick: at most {@code turn_rate} degrees off where it
     * is pointing now, bent towards the target. Anything with nothing to chase — an unguided rocket,
     * a missile fired with nothing locked, or one that has lost what it was chasing — simply keeps
     * its heading, and keeps it for good.
     */
    private Vec3 guidedHeading(Vec3 heading) {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.lost ? null : this.getTarget();

        // Nothing to chase, so nothing is chased: this is a rocket and stays one for the rest of its
        // flight. Asked before the decoys rather than after, and that is the whole of the rule. A
        // missile that left the rail with nothing locked has no seeker running, so it neither goes
        // looking for a target of its own nor is there to be fooled — anything thrown into the air in
        // front of it is just smoke. Nor does one that has already lost what it had start looking
        // again: losing it is precisely what turned it into this.
        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return heading;
        }

        if (!this.level().isClientSide) {
            this.checkDecoys(guidance);

            // A decoy may have taken it in the line above, in which case it is chasing that now.
            chasing = this.getTarget();

            if (chasing == null || !chasing.isAlive()) {
                return heading;
            }
        }

        // Aim at the middle of it rather than its feet, and lead it: where it will be by the time
        // this arrives, not where it is now. A missile aimed at where something was always trails it.
        Vec3 middle = chasing.position().add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 gap = middle.subtract(this.position());
        double flightTicks = gap.length() / Math.max(this.getDeltaMovement().length(), 1.0E-3);
        Vec3 lead = middle.add(velocityOf(chasing).scale(Math.min(flightTicks, 40.0)));
        Vec3 wanted = lead.subtract(this.position());

        if (wanted.lengthSqr() < 1.0E-8) {
            return heading;
        }

        wanted = wanted.normalize();

        // Anything it can no longer see ahead of it is gone for good. A seeker that could look
        // backwards would make the missile impossible to shake, which is not the intention.
        double off = Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, wanted.dot(heading)))));

        if (off > guidance.trackAngle()) {
            this.lose();

            return heading;
        }

        if (off <= guidance.turnRate()) {
            return wanted;
        }

        // Too far to reach this tick: turn as far as it can towards the target and no further.
        return this.turnTowards(heading, wanted, guidance.turnRate());
    }

    /**
     * Gives the target up — on the server, which is the only side entitled to decide it.
     *
     * <p>A client reaches the edge of the seeker's cone at a slightly different moment to the server:
     * it is working from a heading of its own and from figures a packet old. Let it act on that and
     * it stops steering a missile the server is still guiding, and the two fly apart until the gap is
     * wide enough to be put right in one jump. So a client only ever follows what it is told here.
     * The server clears the target, and the clearing arrives with the next packet.
     */
    private void lose() {
        if (this.level().isClientSide) {
            return;
        }

        this.lost = true;
        this.setTarget(null);
    }

    /** {@code heading} rotated {@code degrees} of the way towards {@code wanted}, in their own plane. */
    private Vec3 turnTowards(Vec3 heading, Vec3 wanted, float degrees) {
        Vec3 across = wanted.subtract(heading.scale(wanted.dot(heading)));

        if (across.lengthSqr() < 1.0E-10) {
            return heading;
        }

        across = across.normalize();
        double angle = Math.toRadians(degrees);

        return heading.scale(Math.cos(angle)).add(across.scale(Math.sin(angle))).normalize();
    }

    /**
     * How fast something the missile is interested in is really going, in blocks a tick.
     *
     * <p>Deliberately not its delta movement. A vehicle with somebody at the controls is not moved
     * by the server at all — its position arrives in packets and is applied between ticks — so
     * measured from inside the server's own tick it has not moved, and the server holds a flat zero
     * there on purpose. The client flying it holds the truth. Read the delta movement and the two
     * sides steer the same missile from different figures: the client leads the target and the
     * server chases it, and what is drawn is then dragged between the two paths every time a
     * position packet lands. {@link VehicleEntityBase#getVelocity()} is the same figure on both.
     */
    private static Vec3 velocityOf(Entity entity) {
        return entity instanceof VehicleEntityBase vehicle ? vehicle.getVelocity() : entity.getDeltaMovement();
    }

    /**
     * A missile need not touch what it is chasing: it goes off when it gets as close as it is going
     * to get.
     *
     * <p>What matters here is that the test is made against the whole of this tick's flight rather
     * than against where the missile ends up. One of these crosses thirty blocks in a tick, so a
     * missile that passes clean through its target is a hundred blocks past it by the time anything
     * looks, and a fuse that only measured the end of the step would never once go off. Measuring
     * the nearest point of the segment catches the pass wherever in the tick it happened.
     */
    @Override
    @Nullable
    protected Vec3 earlyDetonation() {
        WeaponDefinition.Guidance guidance = this.getWeapon().guidance().orElse(null);
        Entity chasing = this.getTarget();

        if (guidance == null || chasing == null || !chasing.isAlive()) {
            return null;
        }

        // Where the target will be at the end of this tick, since it is moving too.
        Vec3 middle = chasing.position().add(velocityOf(chasing))
                .add(0.0, chasing.getBbHeight() * 0.5, 0.0);
        Vec3 from = this.position();
        Vec3 to = from.add(this.getDeltaMovement());
        Vec3 nearest = nearestPointOn(from, to, middle);

        return nearest.distanceTo(middle) <= guidance.proximity() ? nearest : null;
    }

    /** The point of the segment {@code from}-{@code to} that lies closest to {@code target}. */
    private static Vec3 nearestPointOn(Vec3 from, Vec3 to, Vec3 target) {
        Vec3 along = to.subtract(from);
        double lengthSqr = along.lengthSqr();

        if (lengthSqr < 1.0E-8) {
            return from;
        }

        double t = Mth.clamp(target.subtract(from).dot(along) / lengthSqr, 0.0, 1.0);

        return from.add(along.scale(t));
    }

    /** A rocket that reaches the end of its life goes off rather than quietly disappearing. */
    @Override
    protected void expire() {
        if (this.getRound().explosion() > 0.0F && !this.level().isClientSide) {
            this.burst(this.position(), null);
        } else {
            this.discard();
        }
    }

    /**
     * No controllers: a missile has no animation to play. It is drawn from its geometry file and
     * turned to lie along its flight path by the renderer, and nothing about it moves on its own.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animatableCache;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_TARGET, tag.contains("Target") ? tag.getInt("Target") : -1);
        this.lost = tag.getBoolean("Lost");
        this.axis = tag.contains("Axis")
                ? new Vec3(tag.getDouble("AxisX"), tag.getDouble("AxisY"), tag.getDouble("AxisZ"))
                : Vec3.ZERO;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Target", this.entityData.get(DATA_TARGET));
        tag.putBoolean("Lost", this.lost);
        // Kept because the velocity is not a safe stand-in for it: one saved while it was still
        // falling clear of the wing would come back pointing at the ground.
        tag.putBoolean("Axis", true);
        tag.putDouble("AxisX", this.axis.x);
        tag.putDouble("AxisY", this.axis.y);
        tag.putDouble("AxisZ", this.axis.z);
    }
}
