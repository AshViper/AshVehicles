package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.weapon.AmmoKind;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * What a gun is loaded out of: a shell for a main armament, or a belt for an autocannon.
 *
 * <p>Put them in a vehicle's hold and its crew load them while it is standing still; see
 * {@code MainGun.resupply}. A vehicle with none in the hold goes out with whatever is already in the
 * magazine and comes home empty, which is the whole point of the thing — what a tank can fire is
 * what somebody put aboard it, exactly as it already is for what an aeroplane carries under its
 * wings.
 *
 * <p><b>Nothing is written on the stack.</b> A shell is a shell and a belt is a belt, so one of
 * these is worth its kind's {@link AmmoKind#roundsPerItem()} and never a fraction of it: the crew
 * take a whole one or they take none, and a magazine with room for less than one is as full as it is
 * going to get. That is what keeps them plain stackable items, which is what an ammunition crate
 * wants to be — the alternative is the stores an aircraft carries, where a pod really can come back
 * half empty and really does have to remember it.
 */
public class AmmoItem extends Item {
    private final AmmoKind kind;

    public AmmoItem(AmmoKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    /** Which gun this feeds. */
    public AmmoKind getKind() {
        return this.kind;
    }

    /** Whether a stack is ammunition of a given kind: what a hold is searched for. */
    public static boolean isKind(ItemStack stack, AmmoKind kind) {
        return stack.getItem() instanceof AmmoItem ammo && ammo.kind == kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        // A shell is one round, and saying so on a shell tells nobody anything they did not know.
        if (this.kind.roundsPerItem() > 1) {
            lines.add(Component.translatable("tooltip.ashvehicles.rounds", this.kind.roundsPerItem())
                    .withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.translatable("tooltip.ashvehicles.ammo_" + this.kind.getSerializedName())
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
