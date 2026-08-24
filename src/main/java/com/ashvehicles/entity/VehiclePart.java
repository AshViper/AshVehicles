package com.ashvehicles.entity;

import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Quaternionf;

import com.ashvehicles.vehicle.Hitbox;

/**
 * One box of a vehicle's shape: a wing, a tail, a stretch of deck, a glacis plate, a track.
 *
 * <p>Minecraft gives an entity a single upright box with a square footprint, which is no shape for
 * an aeroplane and no shape for a tank, and no surface to walk on either. A vehicle is instead made
 * of several of these, each one placed and sized from the boxes in the vehicle's own file, and each one
 * a real obstacle: shots land on them, they collide with the world, and anything standing on one
 * stays standing on it. That last part is what a deck needs.
 *
 * <p><b>The part is not its box.</b> What it really is, is a {@link Hitbox}: a box lying at whatever
 * angle the machine has left it at, of the mod's own making, which nothing of Minecraft's ever sees.
 * A wing banked over is a thin plate on a slant, and a gun laid at forty-five degrees is a barrel.
 * Everything that decides anything — what a shot hits, what a player walks into and stands on, what
 * the world stops the machine against — is measured against that, in {@link Hitboxes}.
 *
 * <p>The upright box the game carries the part around in is a search box and nothing else. It is
 * exactly the air the {@code Hitbox} exists to stop treating as machine, so the part is deliberately
 * not something the game collides with — {@link #canBeCollidedWith} says no — and the boxes it would
 * have collided against are laid over the top by the mod itself.
 *
 * <p>The parent is held twice over — once as the {@code Entity} the game needs and once as the
 * {@link PartHost} that answers for the shape — because a box belongs equally to an aeroplane and to
 * a tank, and those are different classes with nothing in common but this.
 */
public class VehiclePart extends PartEntity<Entity> {
    private final PartHost host;
    private final String name;
    /** Which of the file's boxes, or which hardpoint, this one stands for. */
    private final int slot;
    private final boolean pylon;
    private EntityDimensions dimensions;
    /** What the part really is. Null until the machine has placed it for the first time. */
    private Hitbox hitbox;

    /** A piece of the vehicle: {@code slot} is which of the file's boxes it is. */
    public static <T extends Entity & PartHost> VehiclePart airframe(T parent, String name, int slot) {
        return new VehiclePart(parent, name, slot, false);
    }

    /** A place to hang a weapon: {@code slot} is which hardpoint of the vehicle's file it is. */
    public static <T extends Entity & PartHost> VehiclePart pylon(T parent, String name, int slot) {
        return new VehiclePart(parent, name, slot, true);
    }

    private <T extends Entity & PartHost> VehiclePart(T parent, String name, int slot, boolean pylon) {
        super(parent);
        this.host = parent;
        this.name = name;
        this.slot = slot;
        this.pylon = pylon;
        this.dimensions = EntityDimensions.scalable(1.0F, 1.0F);
        this.refreshDimensions();
    }

    public String getPartName() {
        return this.name;
    }

    /**
     * Where a line first enters this box <em>as it is really lying</em>, or empty if it misses.
     *
     * @param margin how much to grow the box by first. Whatever is testing has to pass the same
     *               figure the game would have used, or a graze it would have counted is refused
     */
    public Optional<Vec3> clip(Vec3 from, Vec3 to, double margin) {
        return this.hitbox == null ? Optional.empty() : this.hitbox.grow(margin).clip(from, to);
    }

    /** The box as it is really lying, or null before the machine has ever placed it. */
    public Hitbox hitbox() {
        return this.hitbox;
    }

    /**
     * Whether this box is a pylon rather than a piece of the vehicle.
     *
     * <p>A pylon is worth telling apart because it is worth clicking on its own: what hangs there is
     * a different thing from what hangs on the pylon beside it, and a player reaching for one means
     * that one.
     */
    public boolean isPylon() {
        return this.pylon;
    }

    /** Which hardpoint this is, counting as the vehicle's file lists them. */
    public int getPylon() {
        return this.slot;
    }

    /** A square box of a given size, for a pylon, which is a place on the machine rather than metal. */
    public void place(Vec3 centre, double size) {
        this.place(new Hitbox(centre, new Vec3(size, size, size), new Quaternionf()));
    }

    /**
     * Folds the part away inside the machine, for one the file no longer describes after a
     * reload that shortened it. It cannot be got rid of — the count is fixed for the machine's life
     * — so it is made too small to be run into or hit.
     */
    public void fold(Vec3 inside) {
        this.place(inside, 1.0E-3);
    }

    /**
     * Puts the part where the machine has worked out it should be, lying the way the machine is
     * lying.
     *
     * <p>The upright box handed to the game afterwards is the one the {@code Hitbox} fits inside,
     * and it is only ever used to find the part. Nothing collides with it and nothing is hit by it.
     */
    public void place(Hitbox box) {
        // Parts are not in the level's tick list, so nothing else moves their previous position on
        // for them. Left alone it stays wherever the part was born, and everything that interpolates
        // between the two - the hitbox overlay included - draws the part streaking in from there.
        this.setOldPosAndRot();

        AABB reach = box.reach();

        this.hitbox = box;
        this.dimensions = EntityDimensions.scalable(
                (float) Math.max(reach.getXsize(), reach.getZsize()), (float) reach.getYsize());
        this.setPos(reach.getCenter().x, reach.minY, reach.getCenter().z);
        // Set last: setPos squares the box off to the entity's own width, which for a part that is
        // longer one way than the other is not the box it needs to be found in.
        this.setBoundingBox(reach);
    }

    /**
     * A right-click anywhere on the vehicle is a right-click on the vehicle: climbing in works from
     * a wing or the tail, not only from the one box Minecraft would otherwise have offered.
     *
     * <p>A pylon is the exception, because it is the one part of an aeroplane where <em>which</em>
     * part was reached for is the whole of the meaning. Clicking one loads or unloads that pylon and
     * no other.
     *
     * <p>Unless the player is crouching, which means the machine rather than the station: the hold,
     * wherever on the aeroplane the click landed. A pylon's box sits inside a wing's, so most of the
     * underside of a wing is pylon as far as a click is concerned, and a crouched click there with a
     * missile in hand would otherwise hang the missile on the wing it was meant to be stowed under.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.isPylon() && !player.isSecondaryUseActive()
                ? this.host.interactPylon(player, hand, this.slot)
                : this.getParent().interact(player, hand);
    }

    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    /**
     * A hit anywhere on the <em>vehicle</em> is a hit on the vehicle. A pylon is not part of it in
     * that sense: it is a place to hang something, and it takes no damage and passes none on.
     * Letting it would make it a shield — a round that struck the pylon would stop there and never
     * reach the wing behind it.
     *
     * <p>A bare hand is the one thing a pylon does pass on, because it is not fire and there is
     * nothing for it to be stopped by. All it is worth is a noise off the airframe, and a fist that
     * found the rack under the wing should get the same noise as one that found the wing.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source) || (this.isPylon() && VehicleEntityBase.knuckles(source) == null)) {
            return false;
        }

        return this.getParent().hurt(source, amount);
    }

    /**
     * Nothing fired at the vehicle can hit a pylon: shots go through to the airframe behind it.
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
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.dimensions;
    }

    /** Nor does one part of a vehicle collide with the rest of the same vehicle. */
    @Override
    public boolean canCollideWith(Entity other) {
        if (other == this.getParent()
                || (other instanceof VehiclePart part && part.getParent() == this.getParent())) {
            return false;
        }

        return super.canCollideWith(other);
    }

    /**
     * No — and that is the point of the arrangement rather than a piece of it that is not finished.
     *
     * <p>Saying yes here hands the game the upright box the part is carried in and lets it collide
     * with that, which for anything lying at an angle is a lid of air over the real shape: a player
     * standing a foot above a sloping deck, and a wing banked over that cannot be walked past
     * because the slab drawn round it is in the way. Saying no takes the part out of the game's
     * collision altogether, and {@link Hitboxes} puts the real box back — see
     * {@code EntityCollisionMixin}, which is where the two meet.
     */
    @Override
    public boolean canBeCollidedWith() {
        return false;
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

        return !this.isPylon() || this.host.isLoadablePylon(this.slot);
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
