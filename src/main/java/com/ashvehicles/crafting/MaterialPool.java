package com.ashvehicles.crafting;

import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * 工廠が材料を数える相手。プレイヤーの持ち物そのもの。
 *
 * <p>工廠には盤面が無い。材料をどこかへ置くのではなく、持っているかどうかだけを見て、持っていれば
 * ボタン1つで組み上がる。機体1機は素材20個を越えることがあり、それを5x5に並べ直す作業に意味は無い
 * ——並べ方は図面であって、作業ではない。
 *
 * <p>数えるのと減らすのは同じ道筋を通る（{@link #consume}）。数えられたのに減らせなかった、という
 * ずれが起きないように。
 */
public final class MaterialPool implements RecipeInput {
    private final List<ItemStack> stacks;

    private MaterialPool(List<ItemStack> stacks) {
        this.stacks = stacks;
    }

    /**
     * 持ち物と手元から。防具と左手は含めない——着ている物を機体の材料にはしない。
     *
     * <p>スタックの実体をそのまま持つので、{@link #consume} は持ち物を直接減らす。
     */
    public static MaterialPool of(Inventory inventory) {
        return new MaterialPool(inventory.items);
    }

    /** 全部揃っているか。持ち物には触らない。 */
    public boolean has(List<SizedIngredient> materials) {
        return consume(materials, true);
    }

    /**
     * 材料を1組ぶん減らす。足りなければ何も減らさずに false。
     *
     * <p>材料ごとに独立して数えるのではなく、上から順に取っていく。ある素材が2種類の要求のどちらにも
     * 当てはまる（タグで書かれた材料同士が重なる）とき、独立に数えると同じ1個を二重に数えてしまう。
     *
     * @param simulate true なら数えるだけで減らさない
     */
    public boolean consume(List<SizedIngredient> materials, boolean simulate) {
        int[] left = new int[this.stacks.size()];

        for (int i = 0; i < left.length; i++) {
            left[i] = this.stacks.get(i).getCount();
        }

        for (SizedIngredient material : materials) {
            int needed = material.count();

            for (int i = 0; i < left.length && needed > 0; i++) {
                if (left[i] <= 0 || !material.ingredient().test(this.stacks.get(i))) {
                    continue;
                }

                int taken = Math.min(left[i], needed);

                left[i] -= taken;
                needed -= taken;
            }

            if (needed > 0) {
                return false;
            }
        }

        if (!simulate) {
            for (int i = 0; i < left.length; i++) {
                ItemStack stack = this.stacks.get(i);

                if (left[i] == stack.getCount()) {
                    continue;
                }

                // 0個のスタックは空スロットとは別物で、置いたままにすると持ち物に幽霊が残る
                this.stacks.set(i, left[i] > 0 ? stack.copyWithCount(left[i]) : ItemStack.EMPTY);
            }
        }

        return true;
    }

    /** その材料を今いくつ持っているか。画面に「所持/必要」を出すためだけのもの。 */
    public int count(SizedIngredient material) {
        int total = 0;

        for (ItemStack stack : this.stacks) {
            if (material.ingredient().test(stack)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    @Override
    public ItemStack getItem(int index) {
        return this.stacks.get(index);
    }

    @Override
    public int size() {
        return this.stacks.size();
    }
}
