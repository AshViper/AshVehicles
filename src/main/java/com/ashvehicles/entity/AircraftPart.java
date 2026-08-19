package com.ashvehicles.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * One box of an aircraft's shape: a wing, a tail, a stretch of deck.
 *
 * <p>Minecraft gives an entity a single upright box with a square footprint, which is no shape for
 * an aeroplane and no surface to walk on. An aircraft is instead made of several of these, each one
 * placed and sized from the aircraft's own file, and each one a real obstacle: shots land on them,
 * they collide with the world, and anything standing on one stays standing on it. That last part is
 * what a deck needs.
 *
 * <p>A part is still an upright box, because that is all the game can collide with. What moves with
 * the aircraft is the box that encloses the rotated one, so a wing is a thin slab when the aircraft
 * is level and a taller one when it is banked, which is the truth of where the wing is.
 */
public class AircraftPart extends PartEntity<AircraftEntity> {
    /** What a box that is not a piece of airframe stands for. */
    private static final int NOT_A_PYLON = -1;

    private final String name;
    private final int pylon;
    private EntityDimensions dimensions;

    public AircraftPart(AircraftEntity parent, String name) {
        this(parent, name, NOT_A_PYLON);
    }

    /**
     * @param pylon which hardpoint this box is, or {@link #NOT_A_PYLON} for a piece of the airframe
     */
    public AircraftPart(AircraftEntity parent, String name, int pylon) {
        super(parent);
        this.name = name;
        this.pylon = pylon;
        this.dimensions = EntityDimensions.scalable(1.0F, 1.0F);
        this.refreshDimensions();
    }

    public String getPartName() {
        return this.name;
    }

    /**
     * Whether this box is a pylon rather than a piece of the aeroplane.
     *
     * <p>A pylon is worth telling apart because it is worth clicking on its own: what hangs there is
     * a different thing from what hangs on the pylon beside it, and a player reaching for one means
     * that one.
     */
    public boolean isPylon() {
        return this.pylon != NOT_A_PYLON;
    }

    /** Which hardpoint this is, counting as the aircraft's file lists them. */
    public int getPylon() {
        return this.pylon;
    }

    /** Puts the part where the aircraft's attitude says it is, at the size that attitude implies. */
    public void place(Vec3 centre, double width, double height, double depth) {
        // Parts are not in the level's tick list, so nothing else moves their previous position on
        // for them. Left alone it stays wherever the part was born, and everything that interpolates
        // between the two - the hitbox overlay included - draws the part streaking in from there.
        this.setOldPosAndRot();

        this.dimensions = EntityDimensions.scalable((float) Math.max(width, depth), (float) height);
        this.setPos(centre.x, centre.y - height / 2.0, centre.z);
        // Set last: the box a rotated part needs is not square, and setPos would square it off.
        this.setBoundingBox(new AABB(
                centre.x - width / 2.0, centre.y - height / 2.0, centre.z - depth / 2.0,
                centre.x + width / 2.0, centre.y + height / 2.0, centre.z + depth / 2.0));
    }

    /**
     * A right-click anywhere on the aircraft is a right-click on the aircraft: climbing in works
     * from a wing or the tail, not only from the one box Minecraft would otherwise have offered.
     *
     * <p>A pylon is the exception, because it is the one part of an aeroplane where <em>which</em>
     * part was reached for is the whole of the meaning. Clicking one loads or unloads that pylon and
     * no other.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.isPylon()
                ? this.getParent().interactPylon(player, hand, this.pylon)
                : this.getParent().interact(player, hand);
    }

    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    /**
     * A hit anywhere on the <em>aeroplane</em> is a hit on the aeroplane. A pylon is not part of the
     * aeroplane in that sense: it is a place to hang something, and it takes no damage and passes
     * none on. Letting it would make it a shield — a round that struck the pylon would stop there
     * and never reach the wing behind it.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isPylon() && !this.isInvulnerableTo(source) && this.getParent().hurt(source, amount);
    }

    /**
     * Nothing fired at the aircraft can hit a pylon: shots go through to the airframe behind it.
     *
     * <p>This is what separates the two jobs. A projectile asks this, and gets no for a pylon. A
     * player's crosshair asks {@link #isPickable()} instead, and gets yes for a pylon it could hang
     * something on — so a pylon can be reached for and loaded, and cannot be shot at or hidden
     * behind.
     */
    @Override
    public boolean canBeHitByProjectile() {
        return !this.isPylon() && super.canBeHitByProjectile();
    }

    @Override
    public boolean is(net.minecraft.world.entity.Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.dimensions;
    }

    /** Nor does one part of an aircraft collide with the rest of the same aircraft. */
    @Override
    public boolean canCollideWith(net.minecraft.world.entity.Entity other) {
        if (other == this.getParent() || (other instanceof AircraftPart part && part.getParent() == this.getParent())) {
            return false;
        }

        return super.canCollideWith(other);
    }

    /**
     * Solid enough to stand on, which is the point of having these at all — except for a pylon,
     * which is a place on the aeroplane rather than a piece of it. A pylon can be reached for and
     * clicked, but walking into one would mean bumping against an invisible block hanging under the
     * wing, and there is already wing there to bump into.
     */
    @Override
    public boolean canBeCollidedWith() {
        return !this.isPylon() && !this.getParent().isRemoved();
    }

    /**
     * A pylon can only be reached for if there is something to be done with it. One with a weapon
     * built into the airframe can never be loaded or unloaded, so its box stands aside and lets the
     * click reach the aeroplane behind — otherwise it would sit invisibly over the nose swallowing
     * every attempt to climb aboard.
     */
    @Override
    public boolean isPickable() {
        if (this.getParent().isRemoved()) {
            return false;
        }

        return !this.isPylon() || this.getParent().isLoadablePylon(this.pylon);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
