package com.ashvehicles.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * What a machine is carrying inside it: three rows of nine, and the magazine its ground crew rearm
 * it out of.
 *
 * <p>Three rows because that is what a machine's hold is being asked to be — a chest, in the shape
 * every player already knows — and because {@code ChestMenu.threeRows} then draws it with no screen
 * of our own. Nine by three is the whole of the interface.
 *
 * <p><b>It is not scenery.</b> Stores hung on the pylons come out of here and rounds are drawn from
 * here: see {@link com.ashvehicles.weapon.WeaponMounts}. An aircraft parked with an empty hold
 * stays as empty as it landed, which is the point of the thing — what an aeroplane can fire is what
 * somebody loaded aboard it.
 *
 * <p>The server owns it. Nothing about it is synched to the clients: while somebody has it open the
 * menu sends them what they are looking at, and nothing else drawn on any client depends on it.
 */
public final class VehicleHold extends SimpleContainer {
    /** Three rows of nine. */
    public static final int ROWS = 3;
    public static final int COLUMNS = 9;
    public static final int SIZE = ROWS * COLUMNS;

    /** Where the contents are kept in the machine's save data. */
    private static final String KEY = "Hold";
    /** How far from the machine somebody may stand with their hands still in it, in blocks. */
    private static final double REACH = 4.0;

    private final VehicleEntityBase vehicle;

    VehicleHold(VehicleEntityBase vehicle) {
        super(SIZE);
        this.vehicle = vehicle;
    }

    /**
     * Whoever opened this may keep it open while they are aboard, or while they are standing at the
     * machine.
     *
     * <p>Measured against the shape the machine really has rather than its plain box, which for a
     * fifteen-metre aeroplane covers the fuselage and nothing else: somebody loading stores at the
     * wingtip is standing at the aircraft by any reasonable reading of it, and their hands would
     * otherwise be slapped away from a hold they are leaning into.
     */
    @Override
    public boolean stillValid(Player player) {
        if (this.vehicle.isRemoved()) {
            return false;
        }

        return player.getRootVehicle() == this.vehicle
                || player.canInteractWithEntity(this.vehicle.getBoundingBoxForCulling(), REACH);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Written slot by slot rather than through {@code SimpleContainer.createTag}, which is what it
     * would otherwise be. That one reads back through {@code addItem} and packs everything into the
     * first free slots it finds, so a hold laid out by whoever loaded it comes back shuffled — and a
     * player who left the missiles on the left and the fuel on the right would find them stirred
     * together by nothing more than a reload.
     */
    void save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (int slot = 0; slot < this.getContainerSize(); slot++) {
            ItemStack stack = this.getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            list.add(stack.save(registries, entry));
        }

        tag.put(KEY, list);
    }

    void load(CompoundTag tag, HolderLookup.Provider registries) {
        this.clearContent();
        ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF;

            if (slot >= this.getContainerSize()) {
                // A hold that has shrunk since the world was last opened. Dropping what no longer
                // fits is better than throwing the rest of the load away with it.
                continue;
            }

            ItemStack.parse(registries, entry).ifPresent(stack -> this.setItem(slot, stack));
        }
    }
}
