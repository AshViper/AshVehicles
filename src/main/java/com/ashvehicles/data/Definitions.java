package com.ashvehicles.data;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.network.DefinitionSyncPayload;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.RackDefinition;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * MOD が読むファイルの種類すべてと、それらが一覧される唯一の場所。
 *
 * <p>種類ごとにディレクトリ1つとレジストリ1つ、それだけ。読み込みもリロードもフォールバックも
 * {@link DefinitionRegistry} が全種類分やる。新しい種類を足すのはここに1行とコーデック1つで済み、
 * ローダーもマネージャーもパケットも書かなくてよい。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Definitions {
    /** 機体性能ファイルは {@code data/<namespace>/aircraft/} にある。 */
    public static final DefinitionRegistry<AircraftDefinition> AIRCRAFT = DefinitionRegistry.of(
            "aircraft", AircraftDefinition.CODEC, AircraftDefinition.FALLBACK, "aircraft");
    /** 地上車両は {@code vehicle/}。 */
    public static final DefinitionRegistry<GroundVehicleDefinition> VEHICLES = DefinitionRegistry.of(
            "vehicle", GroundVehicleDefinition.CODEC, GroundVehicleDefinition.FALLBACK, "ground vehicles");
    /** それらが撃つ物は {@code weapon/}。 */
    public static final DefinitionRegistry<WeaponDefinition> WEAPONS = DefinitionRegistry.of(
            "weapon", WeaponDefinition.CODEC, WeaponDefinition.FALLBACK, "weapons");
    /** 兵装を吊るす物は {@code rack/}。レールと投下ラック。 */
    public static final DefinitionRegistry<RackDefinition> RACKS = DefinitionRegistry.of(
            "rack", RackDefinition.CODEC, RackDefinition.FALLBACK, "racks");
    /** 撃つのではなく積む物は {@code equipment/}。ポッド類。 */
    public static final DefinitionRegistry<EquipmentDefinition> EQUIPMENT = DefinitionRegistry.of(
            "equipment", EquipmentDefinition.CODEC, EquipmentDefinition.FALLBACK, "equipment");

    /** 今どのファイル群が読まれているかを表す番号。どれか1つでも変われば変わる。 */
    public static int version() {
        return DefinitionRegistry.version();
    }

    /**
     * 機体・車両を構成する箱。どちらの種類でも同じ入口で取れる。
     *
     * <p>形状は今それぞれのファイルに入っているが、どちらのファイルを見るべきかは呼び出し側が知る必要
     * も、常に言えることでもない。形を持つ物は車両か機体のどちらかで、ID はちょうど一方に属し、両方に
     * 訊いても既に読んであるマップを1回引くだけ。
     */
    public static VehicleShape shape(ResourceLocation id) {
        if (VEHICLES.has(id)) {
            return VEHICLES.get(id).hitbox().shape();
        }

        if (AIRCRAFT.has(id)) {
            return AIRCRAFT.get(id).hitbox().shape();
        }

        return VehicleShape.NONE;
    }

    public static WeaponDefinition weapon(ResourceLocation id) {
        return WEAPONS.get(id);
    }

    public static RackDefinition rack(ResourceLocation id) {
        return RACKS.get(id);
    }

    public static EquipmentDefinition equipment(ResourceLocation id) {
        return EQUIPMENT.get(id);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        DefinitionRegistry.registries().forEach(registry -> event.addListener(registry.reloadListener()));
    }

    /**
     * ログイン時と {@code /reload} のたびに飛ぶ。まさにこれを送るべきタイミング。
     *
     * <p>種類ごとに分けず全部を1パケットに入れる。クライアントは自分で物理計算をする——機体も地上車両も
     * ——ので、これらの数値が両者で食い違えば、サーバーは機体を自分の思う位置へ引き戻し続けることになる。
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        List<DefinitionRegistry.Snapshot<?>> snapshots = new ArrayList<>();

        DefinitionRegistry.registries().forEach(registry ->
                snapshots.add(DefinitionRegistry.Snapshot.of(registry)));

        DefinitionSyncPayload payload = new DefinitionSyncPayload(snapshots);

        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private Definitions() {
    }
}
