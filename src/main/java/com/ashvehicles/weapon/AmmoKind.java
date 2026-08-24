package com.ashvehicles.weapon;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * What a gun is fed out of: which of the ammunition items goes into it, and how many rounds one of
 * them is worth.
 *
 * <p>A tank's main armament is loaded a shell at a time by hand, and one shell is one round; an
 * autocannon is fed from a belt, and nobody hands a Pantsir its fourteen hundred rounds one by one.
 * So one item is one shell or one belt, and a belt is worth {@link #AUTOCANNON}'s many rounds.
 *
 * <p>A launcher's tubes are the shell's case again rather than the belt's: a rocket is craned into
 * its tube one at a time, and there is nothing else a rocket could sensibly be counted in. Guided
 * and unguided share the one item, which is a simplification and a deliberate one — a file that
 * wants them apart says so with {@code ammo_item} and gets its own kind here.
 *
 * <p><b>Rounds are the currency and the item is the purse</b>, exactly as they are for the stores an
 * aircraft carries — see {@code WeaponMounts.draw}. A belt the crew have only half emptied goes back
 * in the hold as a half belt rather than being thrown away, so topping a magazine off costs what it
 * takes and nothing more.
 *
 * <p>Which kind a weapon takes is its own file's to say, under {@code ammo_item}; a file that leaves
 * it out is read off how the weapon fires, which gets every weapon in the mod right without a line
 * being added to any of them. See {@link WeaponDefinition#ammoKind()}.
 */
public enum AmmoKind implements StringRepresentable {
    /** Loaded by hand, one shell at a time. A tank gun, or the low-velocity gun on a BMD. */
    CANNON("cannon", "cannon_shell", 1),
    /** Fed from a belt. An autocannon, whether it is on a turret or under a wing. */
    AUTOCANNON("autocannon", "autocannon_belt", 30),
    /** Craned into a tube, one at a time. Anything a launcher fires, guided or not. */
    ROCKET("rocket", "launcher_rocket", 1);

    public static final Codec<AmmoKind> CODEC = StringRepresentable.fromEnum(AmmoKind::values);

    private final String name;
    private final String itemName;
    private final int roundsPerItem;

    AmmoKind(String name, String itemName, int roundsPerItem) {
        this.name = name;
        this.itemName = itemName;
        this.roundsPerItem = roundsPerItem;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /**
     * What the item is called, which is what the thing is rather than what it feeds: one is a shell
     * and the other is a belt, and a belt called a round would be lying about thirty of them.
     */
    public String itemName() {
        return this.itemName;
    }

    /** How many rounds one full item of this kind holds. */
    public int roundsPerItem() {
        return this.roundsPerItem;
    }
}
