package com.ashvehicles.menu;

import java.util.Comparator;
import java.util.List;

import com.ashvehicles.crafting.MaterialPool;
import com.ashvehicles.crafting.VehicleWorkbenchRecipe;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModMenus;
import com.ashvehicles.registry.ModRecipes;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * 車両工廠の中身。作業台ではない。
 *
 * <p>枠はプレイヤーの持ち物36個だけで、材料を置く場所は無い。組めるものの一覧を持っていて、押された
 * 番号の1機を、持ち物から材料を引いて渡す。押した合図はバニラの「容器のボタン」——本棚や石切台と同じ
 * 経路——で届くので、自前のパケットは要らないし、届いた時点でサーバーは既に
 * {@link #stillValid} を確かめている。
 *
 * <p>一覧はクライアントとサーバーが別々に組み立てる。レシピはサーバーから配られていて両側で同じ物が
 * 揃っているので、ID で並べ替えれば番号は必ず一致する。
 */
public class VehicleWorkbenchMenu extends AbstractContainerMenu {
    /** 持ち物の枠。画面側もここを見る。 */
    public static final int INVENTORY_X = 51;
    public static final int INVENTORY_Y = 156;
    public static final int HOTBAR_Y = 216;

    private static final int INVENTORY_END = 27;
    private static final int HOTBAR_END = 36;

    private final ContainerLevelAccess access;
    private final Player player;
    private final List<RecipeHolder<VehicleWorkbenchRecipe>> recipes;

    public VehicleWorkbenchMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public VehicleWorkbenchMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(ModMenus.VEHICLE_WORKBENCH.get(), containerId);

        this.access = access;
        this.player = playerInventory.player;
        this.recipes = load(this.player.level());

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }

        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
    }

    /** 工廠で組めるもの、ID 順。両側で同じ並びになるのはこの並べ替えのおかげ。 */
    private static List<RecipeHolder<VehicleWorkbenchRecipe>> load(Level level) {
        return level.getRecipeManager()
                .getAllRecipesFor(ModRecipes.VEHICLE_CRAFTING_TYPE.get())
                .stream()
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .toList();
    }

    /** 組めるものの一覧。画面はこれを並べる。 */
    public List<RecipeHolder<VehicleWorkbenchRecipe>> recipes() {
        return this.recipes;
    }

    /** 今の持ち物で作れるか。画面がボタンの生き死にを決めるのに使う。 */
    public boolean canCraft(int index) {
        return index >= 0 && index < this.recipes.size()
                && MaterialPool.of(this.player.getInventory()).has(this.recipes.get(index).value().materials());
    }

    /**
     * 一覧の {@code id} 番目を1機組む。
     *
     * <p>クライアントは送るだけで、ここが走るのはサーバーだけ。材料が足りなければ何も減らさず false を
     * 返す——押せてしまったボタンで持ち物が削られることはない。
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id < 0 || id >= this.recipes.size()) {
            return false;
        }

        RecipeHolder<VehicleWorkbenchRecipe> holder = this.recipes.get(id);

        if (!MaterialPool.of(player.getInventory()).consume(holder.value().materials(), false)) {
            return false;
        }

        ItemStack built = holder.value().result().copy();

        built.onCraftedBy(player.level(), player, built.getCount());
        player.triggerRecipeCrafted(holder, List.of());
        player.awardRecipes(List.of(holder));

        if (!player.getInventory().add(built)) {
            player.drop(built, false);
        }

        this.access.execute((level, pos) -> level.playSound(null, pos, SoundEvents.ANVIL_USE,
                SoundSource.BLOCKS, 0.6F, 1.6F));

        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ModBlocks.VEHICLE_WORKBENCH.get());
    }

    /** 置く場所が無いので、Shift クリックは持ち物と手元の間を行き来するだけ。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot == null || !slot.hasItem()) {
            return moved;
        }

        ItemStack inSlot = slot.getItem();
        moved = inSlot.copy();

        if (index < INVENTORY_END) {
            if (!moveItemStackTo(inSlot, INVENTORY_END, HOTBAR_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(inSlot, 0, INVENTORY_END, false)) {
            return ItemStack.EMPTY;
        }

        if (inSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return moved;
    }
}
