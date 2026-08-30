package com.ashvehicles.compat.jei;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.crafting.VehicleWorkbenchRecipe;
import com.ashvehicles.registry.ModBlocks;
import com.mojang.serialization.Codec;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.ICodecHelper;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * 車両工廠のレシピを JEI に見せるための1枚。
 *
 * <p>工廠のレシピはバニラのレシピ型ではないので、JEI の作業台の頁には出てこない。出ないと、機体の
 * 作り方を知る手立てがゲーム内に無いことになる。
 *
 * <p>盤面が無いので、絵も配置ではなく素材の並びになる。工廠の画面と同じく「何が何個」だけを出す。
 */
public class VehicleWorkbenchCategory implements IRecipeCategory<RecipeHolder<VehicleWorkbenchRecipe>> {
    /** JEI 側から見たこのレシピの種類。MOD 側のレジストリには触らない名前だけの札。 */
    public static final RecipeType<RecipeHolder<VehicleWorkbenchRecipe>> TYPE = RecipeType.createRecipeHolderType(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_crafting"));

    /** 素材は7種しか無いので、1行に7枠あれば必ず足りる。 */
    private static final int MATERIALS = 7;
    private static final int SLOT = 18;

    private static final int INPUT_X = 1;
    private static final int INPUT_Y = 5;
    private static final int ARROW_X = INPUT_X + MATERIALS * SLOT + 4;
    private static final int ARROW_Y = 6;
    private static final int OUTPUT_X = ARROW_X + 28;
    private static final int OUTPUT_Y = INPUT_Y;

    private static final int WIDTH = OUTPUT_X + 16 + 6;
    private static final int HEIGHT = 26;

    private final IDrawableStatic arrow;
    private final IDrawable icon;

    public VehicleWorkbenchCategory(IGuiHelper helper) {
        this.arrow = helper.getRecipeArrow();
        this.icon = helper.createDrawableItemLike(ModBlocks.VEHICLE_WORKBENCH.get());
    }

    @Override
    public RecipeType<RecipeHolder<VehicleWorkbenchRecipe>> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("container.ashvehicles.vehicle_workbench");
    }

    @Override
    public IDrawable getIcon() {
        return this.icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    /**
     * 素材を左から順に、必要数を数字として乗せて置く。数は
     * {@link SizedIngredient#getItems()} が個数を入れて返すので、JEI がスタック数として描く。
     */
    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<VehicleWorkbenchRecipe> holder,
            IFocusGroup focuses) {
        List<SizedIngredient> materials = holder.value().materials();

        for (int i = 0; i < materials.size() && i < MATERIALS; i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, INPUT_X + i * SLOT, INPUT_Y)
                    .setStandardSlotBackground()
                    .addItemStacks(List.of(materials.get(i).getItems()));
        }

        builder.addOutputSlot(OUTPUT_X, OUTPUT_Y)
                .setOutputSlotBackground()
                .addItemStack(holder.value().result());
    }

    @Override
    public void draw(RecipeHolder<VehicleWorkbenchRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics,
            double mouseX, double mouseY) {
        this.arrow.draw(graphics, ARROW_X, ARROW_Y);
    }

    @Override
    public ResourceLocation getRegistryName(RecipeHolder<VehicleWorkbenchRecipe> holder) {
        return holder.id();
    }

    @Override
    public Codec<RecipeHolder<VehicleWorkbenchRecipe>> getCodec(ICodecHelper codecHelper, IRecipeManager manager) {
        return codecHelper.getRecipeHolderCodec();
    }
}
