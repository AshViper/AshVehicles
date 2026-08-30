package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.crafting.VehicleWorkbenchRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 車両工廠のレシピ型とその読み書き。型が1つ、serializer が1つ。 */
public final class ModRecipes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, AshVehicles.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, AshVehicles.MODID);

    /** 工廠でだけ引かれる型。バニラの作業台はこの型を知らないので、ここのレシピは卓に出てこない。 */
    public static final DeferredHolder<RecipeType<?>, RecipeType<VehicleWorkbenchRecipe>> VEHICLE_CRAFTING_TYPE =
            RECIPE_TYPES.register("vehicle_crafting", id -> RecipeType.<VehicleWorkbenchRecipe>simple(id));

    public static final DeferredHolder<RecipeSerializer<?>, VehicleWorkbenchRecipe.Serializer>
            VEHICLE_CRAFTING_SERIALIZER =
                    RECIPE_SERIALIZERS.register("vehicle_crafting", () -> new VehicleWorkbenchRecipe.Serializer());

    private ModRecipes() {
    }
}
