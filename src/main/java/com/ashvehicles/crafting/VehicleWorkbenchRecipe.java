package com.ashvehicles.crafting;

import java.util.List;

import com.ashvehicles.registry.ModRecipes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * 車両工廠で組める1つぶんの図面。素材と、その必要数と、出来上がる物と、それが載る棚。
 *
 * <p>出来上がる物は機体とは限らない。兵装も装備も弾薬も同じ形の図面で、違うのは
 * {@link WorkbenchTab 棚} だけだ。
 *
 * <p>配置は無い。工廠は作業台ではなく、素材を持って行けば組み上げてくれる場所だ。何をどこに置くかを
 * 覚えることと、機体を作れるようになることの間には何の関係も無い——覚えることに意味があるのは
 * 「どれだけ要るか」の方で、それは数として画面に出ている。
 *
 * <p>レシピ型が {@link RecipeType#CRAFTING} でないので、これらはバニラの作業台にも、盤面のある
 * どんな MOD の卓にも現れない。工廠にしか無い。
 */
public class VehicleWorkbenchRecipe implements Recipe<MaterialPool> {
    private final String group;
    private final WorkbenchTab tab;
    private final List<SizedIngredient> materials;
    private final ItemStack result;

    public VehicleWorkbenchRecipe(String group, WorkbenchTab tab, List<SizedIngredient> materials,
            ItemStack result) {
        this.group = group;
        this.tab = tab;
        this.materials = List.copyOf(materials);
        this.result = result;
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.VEHICLE_CRAFTING_TYPE.get();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.VEHICLE_CRAFTING_SERIALIZER.get();
    }

    @Override
    public String getGroup() {
        return this.group;
    }

    /** どの棚に載るか。画面のタブはこれで分ける。 */
    public WorkbenchTab tab() {
        return this.tab;
    }

    /** 必要な素材と数。画面もこの順に並べる。 */
    public List<SizedIngredient> materials() {
        return this.materials;
    }

    /**
     * 出来上がる物。{@link #getResultItem} と同じだが、レジストリを1つも見ないので、世界の外——
     * 画面やレシピ表示 MOD の頁を組み立てるところ——からも呼べる。
     */
    public ItemStack result() {
        return this.result;
    }

    @Override
    public boolean matches(MaterialPool pool, Level level) {
        return pool.has(this.materials);
    }

    @Override
    public ItemStack assemble(MaterialPool pool, HolderLookup.Provider registries) {
        return this.result.copy();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return this.result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.createWithCapacity(this.materials.size());

        this.materials.forEach(material -> ingredients.add(material.ingredient()));

        return ingredients;
    }

    /** 盤面が無いので、盤面の広さは関係しない。 */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public boolean isIncomplete() {
        return this.materials.isEmpty()
                || this.materials.stream().anyMatch(material -> material.ingredient().hasNoItems());
    }

    /**
     * 読み書き。素材は {@code {"item": ..., "count": n}} の並び。
     *
     * <p>{@code "tab"} は省ける。省いた図面は機体の棚に載る——棚が無かった頃に書かれた図面を、
     * 1枚残らず書き直させないため。
     */
    public static class Serializer implements RecipeSerializer<VehicleWorkbenchRecipe> {
        public static final MapCodec<VehicleWorkbenchRecipe> CODEC = RecordCodecBuilder.mapCodec(
                instance -> instance.group(
                                Codec.STRING.optionalFieldOf("group", "").forGetter(recipe -> recipe.group),
                                WorkbenchTab.CODEC.optionalFieldOf("tab", WorkbenchTab.VEHICLE)
                                        .forGetter(recipe -> recipe.tab),
                                SizedIngredient.FLAT_CODEC.listOf().fieldOf("materials")
                                        .forGetter(recipe -> recipe.materials),
                                ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe -> recipe.result))
                        .apply(instance, VehicleWorkbenchRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, VehicleWorkbenchRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, recipe -> recipe.group,
                        WorkbenchTab.STREAM_CODEC, recipe -> recipe.tab,
                        SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), recipe -> recipe.materials,
                        ItemStack.STREAM_CODEC, recipe -> recipe.result,
                        VehicleWorkbenchRecipe::new);

        @Override
        public MapCodec<VehicleWorkbenchRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, VehicleWorkbenchRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
