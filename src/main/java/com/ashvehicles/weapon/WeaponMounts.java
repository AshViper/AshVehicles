package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.aircraft.AircraftManager;
import com.ashvehicles.aircraft.Attitude;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftProjectile;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What an aircraft is carrying on its hardpoints, and the firing of it.
 *
 * <p>There is one {@link Mount} per hardpoint in the aircraft's file, in the same order. A mount is
 * either empty, or holds a weapon and its remaining rounds. Fixed hardpoints are always full of the
 * weapon the file names; pylons hold whatever the player hung there.
 *
 * <p>The pilot selects a <em>weapon</em>, not a mount: every mount carrying the selected weapon
 * fires together, the way a pair of pods or the two barrels of a cannon would. Rounds leave from
 * each mount's own position, straight along the nose, with the aircraft's speed added on top.
 *
 * <p>The server owns all of this. It fires, counts rounds and rearms; clients hold a copy for the
 * instruments and for drawing pods on the pylons, kept up to date through the entity's synched
 * data.
 */
public final class WeaponMounts {
    /** Ticks a parked aircraft takes to refill a mount from empty. Ground crew, abstracted. */
    private static final int REARM_TICKS = 200;
    /** Prefix of the sound event a weapon is matched to by name: {@code weapon.<name>}. */
    public static final String SOUND_PREFIX = "weapon.";

    /**
     * The ground crew at work: a store going onto a pylon, or coming back off one.
     *
     * <p>Named here rather than with the rest of the mod's sounds because this is the one of them the
     * server asks for by name, and the server cannot see the client's list. A client with no
     * recording under this name falls back on something of the game's; see
     * {@link com.ashvehicles.client.sound.WeaponSounds}.
     */
    public static final ResourceLocation LOAD_SOUND =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, SOUND_PREFIX + "load");
    /** How loud that is. Work on the ground, heard by whoever is standing at the aeroplane. */
    public static final float LOAD_VOLUME = 0.9F;
    /** Hanging one on, and taking one off, which is the same noise made backwards. */
    public static final float LOAD_PITCH = 1.0F;
    public static final float UNLOAD_PITCH = 0.85F;

    /** One hardpoint's load. */
    public static final class Mount {
        @Nullable
        private ResourceLocation weapon;
        private int ammo;
        /** Ticks until this mount may fire again. Kept as a fraction so odd rates come out right. */
        private float cooldown;

        public boolean isEmpty() {
            return this.weapon == null;
        }

        @Nullable
        public ResourceLocation weapon() {
            return this.weapon;
        }

        public int ammo() {
            return this.ammo;
        }

        private void set(@Nullable ResourceLocation weapon, int ammo) {
            this.weapon = weapon;
            this.ammo = weapon == null ? 0 : ammo;
            this.cooldown = 0.0F;
        }
    }

    private final AircraftEntity aircraft;
    /** What the seeker of the selected weapon is on, for the weapons that have one. */
    private final TargetLock lock;
    private Mount[] mounts = new Mount[0];
    @Nullable
    private ResourceLocation selected;
    /** Whether the server's copy has changed since it was last sent to the clients. */
    private boolean dirty;
    /** Last tick's trigger, so a weapon that fires one at a time can tell a press from a hold. */
    private boolean triggerHeld;

    public WeaponMounts(AircraftEntity aircraft) {
        this.aircraft = aircraft;
        this.lock = new TargetLock(aircraft);
    }

    /** What the seeker is on. Read by the instruments; only the server ever decides it. */
    public TargetLock lock() {
        return this.lock;
    }

    /** The figures of the selected weapon, or null if nothing is selected. */
    @Nullable
    public WeaponDefinition selectedWeapon() {
        return this.selected == null ? null : AircraftManager.weapon(this.selected);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public List<Mount> mounts() {
        this.ensureLayout();

        return List.of(this.mounts);
    }

    /** The hardpoint a mount belongs to, or null if the file no longer has one for it. */
    @Nullable
    public AircraftDefinition.Hardpoint hardpoint(int slot) {
        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();

        return slot < hardpoints.size() ? hardpoints.get(slot) : null;
    }

    /** The weapon the trigger fires, or null if nothing is selected. */
    @Nullable
    public ResourceLocation selected() {
        return this.selected;
    }

    /** Rounds left across every mount carrying the selected weapon. */
    public int selectedAmmo() {
        int total = 0;

        for (Mount mount : this.mounts()) {
            if (Objects.equals(mount.weapon, this.selected)) {
                total += mount.ammo;
            }
        }

        return total;
    }

    /** Whether any pylon carries something the player could take back. */
    public boolean hasRemovable() {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

            if (hardpoint != null && !hardpoint.isFixed() && !this.mounts[slot].isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * How many stores are carried where a radar can see them.
     *
     * <p>Counted rather than weighed: what a radar objects to is the corner an external store makes
     * with the wing it is hanging under, and a small missile makes very nearly as bad a one as a
     * large bomb. A gun built into the airframe is part of the shape and is not a store at all.
     */
    public int externalStores() {
        this.ensureLayout();

        int outside = 0;

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

            if (hardpoint != null && !hardpoint.isFixed() && !hardpoint.internal()
                    && !this.mounts[slot].isEmpty()) {
                outside++;
            }
        }

        return outside;
    }

    /** Every distinct weapon aboard, in hardpoint order. */
    public List<ResourceLocation> carried() {
        List<ResourceLocation> weapons = new ArrayList<>();

        for (Mount mount : this.mounts()) {
            if (mount.weapon != null && !weapons.contains(mount.weapon)) {
                weapons.add(mount.weapon);
            }
        }

        return weapons;
    }

    // ------------------------------------------------------------------
    // Loading and unloading
    // ------------------------------------------------------------------

    /**
     * The first pylon with nothing hanging on it, or -1 if the aircraft is fully loaded.
     *
     * <p>Fixed hardpoints never count: the weapon built into one is part of the airframe and there
     * is no room beside it.
     */
    private int freePylon() {
        this.ensureLayout();

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

            if (hardpoint != null && !hardpoint.isFixed() && this.mounts[slot].isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    /**
     * Whether there is anywhere left to hang something.
     *
     * <p>Asked before the aircraft decides what a right-click meant, so that offering it a weapon it
     * has no room for falls through to whatever the click would otherwise have done rather than
     * quietly achieving nothing.
     */
    public boolean hasFreePylon() {
        return this.freePylon() >= 0;
    }

    /**
     * Hangs a weapon on the first free pylon.
     *
     * @param ammo rounds it comes with, or -1 for a full load
     * @return false if there is no pylon free for it
     */
    public boolean mount(ResourceLocation weapon, int ammo) {
        int slot = this.freePylon();

        if (slot < 0) {
            return false;
        }

        int capacity = AircraftManager.weapon(weapon).ammo();
        this.mounts[slot].set(weapon, ammo < 0 ? capacity : Math.min(ammo, capacity));

        if (this.selected == null) {
            this.selected = weapon;
        }

        this.dirty = true;
        this.playLoadSound(true);

        return true;
    }

    /** Whether this hardpoint is a bare pylon that would take the given weapon. */
    public boolean canMountAt(int slot) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        return hardpoint != null && !hardpoint.isFixed() && slot < this.mounts.length
                && this.mounts[slot].isEmpty();
    }

    /**
     * Hangs a weapon on one named pylon, rather than on whichever happened to be free.
     *
     * @param ammo rounds it comes with, or -1 for a full load
     * @return false if that pylon will not take it
     */
    public boolean mountAt(int slot, ResourceLocation weapon, int ammo) {
        if (!this.canMountAt(slot)) {
            return false;
        }

        int capacity = AircraftManager.weapon(weapon).ammo();
        this.mounts[slot].set(weapon, ammo < 0 ? capacity : Math.min(ammo, capacity));

        if (this.selected == null) {
            this.selected = weapon;
        }

        this.dirty = true;
        this.playLoadSound(true);

        return true;
    }

    /** Whether there is something on this pylon a player could take back. */
    public boolean canUnmountAt(int slot) {
        this.ensureLayout();
        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);

        return hardpoint != null && !hardpoint.isFixed() && slot < this.mounts.length
                && !this.mounts[slot].isEmpty();
    }

    /**
     * Takes what is on one named pylon back off it, as an item carrying whatever rounds were left.
     * Empty if there is nothing there to take.
     */
    public ItemStack unmountAt(int slot) {
        if (!this.canUnmountAt(slot)) {
            return ItemStack.EMPTY;
        }

        Mount mount = this.mounts[slot];
        ItemStack stack = WeaponItem.stackOf(mount.weapon, mount.ammo);
        mount.set(null, 0);
        this.dirty = true;
        this.reselect();
        this.playLoadSound(false);

        return stack;
    }

    /**
     * Takes the most recently hung weapon back off its pylon, as an item carrying whatever rounds
     * were left in it. Empty if there is nothing to take.
     */
    public ItemStack unmount() {
        this.ensureLayout();

        for (int slot = this.mounts.length - 1; slot >= 0; slot--) {
            AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);
            Mount mount = this.mounts[slot];

            if (hardpoint == null || hardpoint.isFixed() || mount.isEmpty()) {
                continue;
            }

            ItemStack stack = WeaponItem.stackOf(mount.weapon, mount.ammo);
            mount.set(null, 0);
            this.dirty = true;
            this.reselect();
            this.playLoadSound(false);

            return stack;
        }

        return ItemStack.EMPTY;
    }

    /** Selects the next weapon aboard, in hardpoint order, wrapping round. */
    public void selectNext() {
        List<ResourceLocation> weapons = this.carried();

        if (weapons.isEmpty()) {
            this.selected = null;
        } else {
            int index = weapons.indexOf(this.selected);
            this.selected = weapons.get((index + 1) % weapons.size());
        }

        this.dirty = true;
    }

    /** Makes sure something aboard is selected, if anything is. */
    private void reselect() {
        List<ResourceLocation> weapons = this.carried();

        if (this.selected == null || !weapons.contains(this.selected)) {
            this.selected = weapons.isEmpty() ? null : weapons.get(0);
            this.dirty = true;
        }
    }

    /**
     * Matches the mounts to the aircraft's file: one per hardpoint, fixed ones full of what the file
     * says. Cheap, and run before every use, so a {@code /reload} that adds a pylon shows up at
     * once and one that removes it does not leave a phantom mount firing from nowhere.
     */
    private void ensureLayout() {
        List<AircraftDefinition.Hardpoint> hardpoints = this.aircraft.getStats().hardpoints();

        if (this.mounts.length != hardpoints.size()) {
            Mount[] resized = new Mount[hardpoints.size()];

            for (int slot = 0; slot < resized.length; slot++) {
                resized[slot] = slot < this.mounts.length ? this.mounts[slot] : new Mount();
            }

            this.mounts = resized;
            this.dirty = true;
        }

        for (int slot = 0; slot < this.mounts.length; slot++) {
            AircraftDefinition.Hardpoint hardpoint = hardpoints.get(slot);

            if (hardpoint.isFixed() && !hardpoint.fixed().get().equals(this.mounts[slot].weapon)) {
                ResourceLocation weapon = hardpoint.fixed().get();
                this.mounts[slot].set(weapon, AircraftManager.weapon(weapon).ammo());
                this.dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // Firing (server)
    // ------------------------------------------------------------------

    /**
     * One server tick: fires if the trigger is held, counts down between rounds, and rearms a parked
     * aircraft.
     *
     * @param trigger whether the pilot is holding the trigger this tick
     */
    public void tick(boolean trigger) {
        this.ensureLayout();
        this.reselect();

        WeaponDefinition selectedWeapon = this.selected == null ? null : AircraftManager.weapon(this.selected);

        // The seeker only looks while something that can use a lock is selected.
        if (this.lock.tick(selectedWeapon == null ? null : selectedWeapon.guidance().orElse(null))) {
            this.dirty = true;
        }

        boolean fired = false;
        boolean armed = trigger && selectedWeapon != null && !this.aircraft.isCrashing()
                && this.aircraft.getControllingPassenger() instanceof Player;

        // A gun fires for as long as the trigger is held. Everything else goes one press at a time,
        // so holding the button does not empty a rail of missiles in half a second.
        if (armed && selectedWeapon.type().isSingleShot() && this.triggerHeld) {
            armed = false;
        }

        this.triggerHeld = trigger;

        for (int slot = 0; slot < this.mounts.length; slot++) {
            Mount mount = this.mounts[slot];

            if (mount.isEmpty()) {
                continue;
            }

            if (armed && mount.weapon.equals(this.selected)) {
                WeaponDefinition weapon = AircraftManager.weapon(mount.weapon);
                // Counted down without a floor while firing, so a rate that is not a whole number
                // of rounds a tick still averages out to the figure in the file.
                mount.cooldown -= 1.0F;

                while (mount.cooldown <= 0.0F && mount.ammo > 0) {
                    this.fireRound(slot, mount.weapon, weapon);
                    mount.ammo--;
                    mount.cooldown += weapon.firing().ticksPerRound();
                    this.dirty = true;
                    fired = true;

                    if (weapon.type().isSingleShot()) {
                        // One press, one launch, from one station. Letting every loaded pylon go at
                        // once would empty the aircraft in a press and waste half of it on a target
                        // the first one already killed.
                        armed = false;

                        break;
                    }
                }

                if (mount.ammo <= 0) {
                    mount.cooldown = 0.0F;
                }
            } else {
                mount.cooldown = Math.max(0.0F, mount.cooldown - 1.0F);
            }
        }

        if (fired) {
            this.playFireSound(selectedWeapon, this.selected);
        }

        if (this.isParked()) {
            this.rearm();
        }
    }

    /**
     * Sends one round on its way from a mount, or a whole salvo of them if the weapon lets go of
     * several at once, as a rocket pod does.
     */
    private void fireRound(int slot, ResourceLocation weaponId, WeaponDefinition weapon) {
        if (!(this.aircraft.level() instanceof ServerLevel level)) {
            return;
        }

        AircraftDefinition.Hardpoint hardpoint = this.hardpoint(slot);
        Vec3 muzzle = this.aircraft.toWorld(hardpoint == null ? Vec3.ZERO : hardpoint.pos(), 1.0F);
        Vec3 nose = this.aircraft.getNoseVector();
        Vec3 up = this.aircraft.getLiftVector();
        Vec3 right = Attitude.right(this.aircraft.getAttitude());
        LivingEntity pilot = this.aircraft.getControllingPassenger();

        // A missile takes whatever the seeker had when it left the rail, and nothing afterwards: it
        // is the missile's own problem to keep up with it.
        Entity locked = weapon.isGuided() && this.lock.isLocked() ? this.lock.target() : null;
        RandomSource random = this.aircraft.getRandom();

        // A cone about the nose: two gaussians across it, scaled so the file's half-angle holds most
        // of what is fired rather than being an edge nothing reaches. A salvo opens the cone further,
        // which is what makes a rocket salvo cover ground instead of landing in one hole.
        double scatter = Math.tan(Math.toRadians(weapon.firing().spread())) * 0.5;
        double spread = Math.tan(Math.toRadians(weapon.firing().salvoSpread())) * 0.5;

        for (int i = 0; i < Math.max(1, weapon.firing().salvo()); i++) {
            Vec3 direction = nose
                    .add(right.scale(random.nextGaussian() * (scatter + spread)))
                    .add(up.scale(random.nextGaussian() * (scatter + spread)))
                    .normalize();

            // A bomb is let go rather than fired: it leaves with the aircraft's speed and nothing
            // else, and its own figure is how hard it is pushed off the rack, downwards, which is
            // what stops it scraping along the belly it just left.
            Vec3 velocity = weapon.isDropped()
                    ? this.aircraft.getVelocity().add(up.scale(-weapon.projectile().speed()))
                    : direction.scale(weapon.projectile().speed()).add(this.aircraft.getVelocity());

            AircraftProjectile shot = weapon.type() == WeaponDefinition.Type.GUN
                    ? new BulletEntity(ModEntities.BULLET.get(), level)
                    : new RocketEntity(ModEntities.ROCKET.get(), level);

            shot.setup(weaponId, this.aircraft, pilot);
            shot.setPos(muzzle);
            // launch rather than setDeltaMovement: the speed has to reach the clients, and it is too
            // fast for the packets that would ordinarily carry it. See AircraftProjectile.
            shot.launch(velocity);

            if (shot instanceof RocketEntity rocket && locked != null) {
                rocket.setTarget(locked);
            }

            level.addFreshEntity(shot);
        }
    }

    /**
     * Once a tick while firing, however many rounds went out: the event the weapon's file names,
     * else one named after the weapon. A client with neither falls back to the mod's default in
     * {@link com.ashvehicles.client.sound.WeaponSounds}.
     */
    private void playFireSound(WeaponDefinition weapon, ResourceLocation weaponId) {
        ResourceLocation event = weapon.sound().fire()
                .orElseGet(() -> weaponId.withPath(SOUND_PREFIX + weaponId.getPath()));

        // Sent with the reach in the volume slot rather than the loudness: that slot is the only
        // thing deciding who is told about the sound at all, and at the weapon's own loudness the
        // answer is "everyone within thirty-two blocks", which in an air battle is nobody. What it
        // should actually sound like where it arrives is settled on the client, which is the only
        // side that knows how far away it is standing. See WeaponSounds.
        this.aircraft.level().playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(event), SoundSource.NEUTRAL,
                weapon.sound().packetVolume(), weapon.sound().pitch());
    }

    /**
     * A store going onto a pylon or coming back off it, heard by whoever is standing about the
     * aeroplane.
     *
     * <p>Played the ordinary way, unlike everything else the weapons do, because this is the one
     * thing that happens with the aircraft on the ground and someone next to it: sixteen blocks or so
     * of reach is exactly right, and nobody further off has any business hearing the ground crew.
     *
     * @param hanging true for a store going on, false for one coming off
     */
    private void playLoadSound(boolean hanging) {
        this.aircraft.level().playSound(null, this.aircraft.getX(), this.aircraft.getY(), this.aircraft.getZ(),
                SoundEvent.createVariableRangeEvent(LOAD_SOUND), SoundSource.NEUTRAL,
                LOAD_VOLUME, hanging ? LOAD_PITCH : UNLOAD_PITCH);
    }

    /** On the ground with the engine off and not rolling: safe for the ground crew to approach. */
    private boolean isParked() {
        return this.aircraft.onGround() && this.aircraft.getThrottle() <= 0.0F
                && this.aircraft.getVelocity().lengthSqr() < 1.0E-4;
    }

    /**
     * Refills every mount a little, so that a full load takes {@link #REARM_TICKS} whatever its size.
     *
     * <p>Which needs saying two ways round. A cannon holds hundreds of rounds, so several go on
     * every tick; a rail holds one missile, so the tick has to wait for it. Adding
     * {@code capacity / REARM_TICKS} alone gets the first case right and rounds down to nothing in
     * the second, and then the floor of one round a tick reloads a missile in a twentieth of a
     * second — which was invisible while an expended missile was still drawn on its rail, and is
     * very visible now that it is not.
     */
    private void rearm() {
        for (Mount mount : this.mounts) {
            if (mount.isEmpty()) {
                continue;
            }

            int capacity = AircraftManager.weapon(mount.weapon).ammo();

            if (mount.ammo >= capacity) {
                continue;
            }

            int perTick = capacity / REARM_TICKS;

            if (perTick > 0) {
                mount.ammo = Math.min(capacity, mount.ammo + perTick);
                this.dirty = true;
            } else if (this.aircraft.tickCount % Math.max(1, REARM_TICKS / capacity) == 0) {
                mount.ammo++;
                this.dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence and sync
    // ------------------------------------------------------------------

    /** True once, after any change since the last call: the cue to send the clients a fresh copy. */
    public boolean consumeDirty() {
        boolean was = this.dirty;
        this.dirty = false;

        return was;
    }

    /** Everything a client or a save needs: what is on each hardpoint, and what is selected. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();

        for (Mount mount : this.mounts) {
            CompoundTag entry = new CompoundTag();

            if (mount.weapon != null) {
                entry.putString("Weapon", mount.weapon.toString());
                entry.putInt("Ammo", mount.ammo);
            }

            list.add(entry);
        }

        tag.put("Mounts", list);

        if (this.selected != null) {
            tag.putString("Selected", this.selected.toString());
        }

        return tag;
    }

    /**
     * The same, plus what the seeker is on: what the clients are sent so the instruments can draw
     * it.
     *
     * <p>Kept apart from {@link #save()} because a lock is not worth saving. It names an entity by
     * the id it has in this session, which means nothing in the next one and could name something
     * else entirely; the seeker finds its own target again on the first tick after loading anyway.
     */
    public CompoundTag syncTag() {
        CompoundTag tag = this.save();
        this.lock.save(tag);

        return tag;
    }

    public void load(CompoundTag tag) {
        ListTag list = tag.getList("Mounts", Tag.TAG_COMPOUND);
        this.mounts = new Mount[list.size()];

        for (int slot = 0; slot < list.size(); slot++) {
            CompoundTag entry = list.getCompound(slot);
            this.mounts[slot] = new Mount();

            if (entry.contains("Weapon")) {
                this.mounts[slot].set(ResourceLocation.tryParse(entry.getString("Weapon")), entry.getInt("Ammo"));
            }
        }

        this.selected = tag.contains("Selected") ? ResourceLocation.tryParse(tag.getString("Selected")) : null;
        // Present in what the server sends, absent in what is read off disk, in which case the
        // seeker simply starts with nothing and finds its own target on the next tick.
        this.lock.load(tag);
        this.dirty = true;
    }

    /** Whether the given entity is this aircraft or part of it: something its own rounds pass through. */
    public static boolean isPartOf(AircraftEntity aircraft, Entity entity) {
        return entity == aircraft || entity.getRootVehicle() == aircraft
                || (entity instanceof com.ashvehicles.entity.AircraftPart part && part.getParent() == aircraft);
    }
}
