package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.menu.VehicleWorkbenchMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 開く盤面の種類。工廠が1つだけ。 */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AshVehicles.MODID);

    /**
     * 工廠の盤面。開くときにサーバーから渡す物が無いので、素の {@link MenuType} で足りる。
     * クライアント側はブロックの位置を知らないまま同じ枠組みを作り、中身はサーバーが送ってくる。
     */
    public static final DeferredHolder<MenuType<?>, MenuType<VehicleWorkbenchMenu>> VEHICLE_WORKBENCH =
            MENUS.register("vehicle_workbench",
                    () -> new MenuType<>(VehicleWorkbenchMenu::new, FeatureFlags.VANILLA_SET));

    private ModMenus() {
    }
}
