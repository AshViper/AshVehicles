package com.ashvehicles.compat.jei;

import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.crafting.VehicleWorkbenchRecipe;
import com.ashvehicles.registry.ModBlocks;
import com.ashvehicles.registry.ModRecipes;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI に工廠の頁を足す。
 *
 * <p>このクラスを読み込むのは JEI だけだ（{@link JeiPlugin} を探すのが JEI 自身）。だから JEI が
 * 無い環境ではここは一度も触られず、MOD 側も JEI があるかを問い合わせない。
 */
@JeiPlugin
public class AshVehiclesJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new VehicleWorkbenchCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    /**
     * 世界のレシピ帳から工廠の型のものを全部。JEI が読み直すのはワールドに入った後なので、
     * ここでは既にサーバーから送られたレシピが揃っている。
     */
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        ClientLevel level = Minecraft.getInstance().level;

        if (level == null) {
            return;
        }

        List<RecipeHolder<VehicleWorkbenchRecipe>> recipes =
                level.getRecipeManager().getAllRecipesFor(ModRecipes.VEHICLE_CRAFTING_TYPE.get());

        registration.addRecipes(VehicleWorkbenchCategory.TYPE, recipes);
    }

    /**
     * 工廠を、工廠でしか作れないものの「道具」として出す。JEI で機体を引いたとき、どこで作るのかが
     * 出来上がりの隣に並ぶように。
     *
     * <p>材料を盤面へ移す「＋」は無い。工廠に盤面が無いので移す先が無く、持っていればボタンが押せる。
     */
    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(ModBlocks.VEHICLE_WORKBENCH.get(), VehicleWorkbenchCategory.TYPE);
    }
}
