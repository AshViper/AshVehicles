package com.ashvehicles.registry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.item.AircraftItem;
import com.ashvehicles.item.AmmoItem;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.FuelItem;
import com.ashvehicles.item.GroundVehicleItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.VehicleWorkbenchItem;
import com.ashvehicles.item.WeaponItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.weapon.AmmoKind;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 機体1つにつき同名のアイテム1つ（機体を持ち運んで設置するため）。加えて、要求する兵装・ラック・
 * ポッドそれぞれにも1つずつ（インベントリから機体を武装させるため）。
 *
 * <p>全部が同じアイテム名前空間を共有するので、名前は全体で1度しか使えない。マップを組み立てる順が
 * 名前争いの決着順で、先に登録した者が名前を取り、後から来た者は失ってログに残る。機体と車両が先なの
 * はエンティティ型が最も替えの利かないものだから。次に弾薬（名前は MOD 固有の数個の定数）、最後に
 * 誰でもデータパックに置ける3種のファイル。
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AshVehicles.MODID);

    /** 1スロットに入るラック数。ただの金具で、搭載構成では同じレールを複数使う。 */
    private static final int RACK_STACK = 16;

    /** 機体でも機体に付く物でもない唯一のアイテム。これらをばらす道具。 */
    public static final DeferredItem<WrenchItem> WRENCH =
            ITEMS.registerItem("wrench", WrenchItem::new, new Item.Properties().stacksTo(1));

    /**
     * 燃料缶。これも機体に付く物ではなく、機体に注ぐ物。
     *
     * <p>ファイル由来の名前より先に登録するので、この名前はデータパックに奪われない。空になった缶は返らない
     * ——1個が1回の給油だ——ので、まとめて持ち運べるようにスタックする。
     */
    public static final DeferredItem<FuelItem> FUEL_CAN =
            ITEMS.registerItem("fuel_can", FuelItem::new, new Item.Properties().stacksTo(16));

    /**
     * 中間素材。機体も車両も、鉄と赤石を卓に積み上げれば出てくる物ではなくなった。
     *
     * <p>7種のうち板と部品と基板が土台で、そこから装甲板・エンジン・ジェットエンジン・アビオニクスが
     * 出る。どの機体もどの車両もこの7種だけで組める——違うのは何を何枚どこに置くかだけだ。素材の種類
     * を機体ごとに増やさないのは、格納庫に必要な物が7種の山として見えていた方が、レシピを1つずつ
     * 覚えるより先に進めるから。
     */
    public static final DeferredItem<Item> STEEL_PLATE = ITEMS.registerSimpleItem("steel_plate");
    public static final DeferredItem<Item> MACHINE_PARTS = ITEMS.registerSimpleItem("machine_parts");
    public static final DeferredItem<Item> CIRCUIT_BOARD = ITEMS.registerSimpleItem("circuit_board");
    public static final DeferredItem<Item> ARMOR_PLATE = ITEMS.registerSimpleItem("armor_plate");
    public static final DeferredItem<Item> ENGINE = ITEMS.registerSimpleItem("engine");
    public static final DeferredItem<Item> JET_ENGINE = ITEMS.registerSimpleItem("jet_engine");
    public static final DeferredItem<Item> AVIONICS = ITEMS.registerSimpleItem("avionics");

    /** 中間素材を作った順に。クリエイティブのタブもこの順で出す。 */
    public static final List<DeferredItem<Item>> MATERIALS =
            List.of(STEEL_PLATE, MACHINE_PARTS, CIRCUIT_BOARD, ARMOR_PLATE, ENGINE, JET_ENGINE, AVIONICS);

    /** 車両工廠を置くためのアイテム。機体と車両はこの上でしか組めない。 */
    public static final DeferredItem<VehicleWorkbenchItem> VEHICLE_WORKBENCH =
            ITEMS.registerItem("vehicle_workbench",
                    properties -> new VehicleWorkbenchItem(ModBlocks.VEHICLE_WORKBENCH.get(), properties),
                    new Item.Properties());

    /**
     * ここまでに名前を取った、ファイル由来でないアイテム。
     *
     * <p>データパックの兵装がこの名前を名乗ったら、争いになる前に負けてもらう。機体や弾薬と同じ扱いだが、
     * こちらは登録が先に済んでいるので、防がないと名前の二重登録でゲームが上がらなくなる。
     */
    private static final Set<String> FIXED_NAMES = Set.of("wrench", "fuel_can", "vehicle_workbench",
            "steel_plate", "machine_parts", "circuit_board", "armor_plate", "engine", "jet_engine", "avionics");

    private static final Map<ResourceLocation, DeferredItem<AircraftItem>> AIRCRAFT = registerAircraft();
    private static final Map<ResourceLocation, DeferredItem<GroundVehicleItem>> VEHICLES = registerVehicles();
    /**
     * 兵装より先に登録する。同名の兵装があってもゲームが落ちるのではなく兵装側が負けるように。弾薬名
     * は MOD 固有の数個の定数、兵装名は誰かがデータパックに置いた任意の文字列。
     */
    private static final Map<AmmoKind, DeferredItem<AmmoItem>> AMMO = registerAmmo();
    private static final Map<ResourceLocation, DeferredItem<WeaponItem>> WEAPONS = registerWeapons();
    private static final Map<ResourceLocation, DeferredItem<RackItem>> RACKS = registerRacks();
    private static final Map<ResourceLocation, DeferredItem<EquipmentItem>> EQUIPMENT = registerEquipment();

    private static Map<ResourceLocation, DeferredItem<AircraftItem>> registerAircraft() {
        Map<ResourceLocation, DeferredItem<AircraftItem>> items = new LinkedHashMap<>();

        ModEntities.aircraft().forEach((id, type) -> items.put(id, ITEMS.registerItem(id.getPath(),
                properties -> new AircraftItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /** 地上車両にも同条件で1つずつ。同名で、置けばその車両が出る。 */
    private static Map<ResourceLocation, DeferredItem<GroundVehicleItem>> registerVehicles() {
        Map<ResourceLocation, DeferredItem<GroundVehicleItem>> items = new LinkedHashMap<>();

        ModEntities.vehicles().forEach((id, type) -> items.put(id, ITEMS.registerItem(id.getPath(),
                properties -> new GroundVehicleItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /**
     * 兵装は車両とアイテム名前空間を共有するので、車両と同名の兵装は認められない。ファイルに
     * {@code "item": false} と書かれた兵装は機体に内蔵されるものなのでアイテムを持たない。
     */
    private static Map<ResourceLocation, DeferredItem<WeaponItem>> registerWeapons() {
        Map<ResourceLocation, DeferredItem<WeaponItem>> items = new LinkedHashMap<>();

        Definitions.WEAPONS.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (isTaken(id)) {
                AshVehicles.LOGGER.error("Weapon {} shares its name with a vehicle or with ammunition;"
                        + " it gets no item", id);

                return;
            }

            items.put(id, ITEMS.registerItem(id.getPath(),
                    properties -> new WeaponItem(id, properties), new Item.Properties().stacksTo(1)));
        });

        return Collections.unmodifiableMap(items);
    }

    /**
     * ラックにも兵装と同条件で1つずつ。{@code "item": false} のラックは機体が最初から積んでいる物
     * なのでアイテムを持たない。
     *
     * <p>兵装と違いラックはスタックする。覚えておくことが何も無いただの金具で、戦争を1つ翼下で過ごした
     * レールも箱から出したてのレールも同じ物。スタックに載せる情報が無く、1本ごとに1スロット渡す理由も
     * 無い。
     */
    private static Map<ResourceLocation, DeferredItem<RackItem>> registerRacks() {
        Map<ResourceLocation, DeferredItem<RackItem>> items = new LinkedHashMap<>();

        Definitions.RACKS.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (isTaken(id) || WEAPONS.containsKey(id)) {
                AshVehicles.LOGGER.error("Rack {} shares its name with something else; it gets no item", id);

                return;
            }

            items.put(id, ITEMS.registerItem(id.getPath(),
                    properties -> new RackItem(id, properties), new Item.Properties().stacksTo(RACK_STACK)));
        });

        return Collections.unmodifiableMap(items);
    }

    /** ポッドにも同条件で1つずつ。かさばる物なのでスタックしない。 */
    private static Map<ResourceLocation, DeferredItem<EquipmentItem>> registerEquipment() {
        Map<ResourceLocation, DeferredItem<EquipmentItem>> items = new LinkedHashMap<>();

        Definitions.EQUIPMENT.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (isTaken(id) || WEAPONS.containsKey(id) || RACKS.containsKey(id)) {
                AshVehicles.LOGGER.error("Equipment {} shares its name with something else;"
                        + " it gets no item", id);

                return;
            }

            items.put(id, ITEMS.registerItem(id.getPath(),
                    properties -> new EquipmentItem(id, properties), new Item.Properties().stacksTo(1)));
        });

        return Collections.unmodifiableMap(items);
    }

    /** その名前を機体か弾薬か、MOD 固有のアイテムが既に取っているか。 */
    private static boolean isTaken(ResourceLocation id) {
        return AIRCRAFT.containsKey(id) || VEHICLES.containsKey(id) || isAmmoName(id)
                || FIXED_NAMES.contains(id.getPath());
    }

    /**
     * 機銃・砲の弾薬の種類ごとに1つ（砲弾とベルト）。口径ではなく物の種類で名付ける。弾がどこに入るか
     * を決めるのは兵装ファイルでありアイテムではないから。同じ砲弾がレオパルトにも T-64 にも BMD にも
     * 入る。
     *
     * <p>1つずつ書かずに enum から登録するので、3種目が要るときは定数とテクスチャを足すだけで済む。
     */
    private static Map<AmmoKind, DeferredItem<AmmoItem>> registerAmmo() {
        Map<AmmoKind, DeferredItem<AmmoItem>> items = new EnumMap<>(AmmoKind.class);

        for (AmmoKind kind : AmmoKind.values()) {
            items.put(kind, ITEMS.registerItem(kind.itemName(),
                    properties -> new AmmoItem(kind, properties), new Item.Properties()));
        }

        return Collections.unmodifiableMap(items);
    }

    private static boolean isAmmoName(ResourceLocation id) {
        for (AmmoKind kind : AmmoKind.values()) {
            if (kind.itemName().equals(id.getPath())) {
                return true;
            }
        }

        return false;
    }

    /** 全機体アイテム（設置する機体の ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<AircraftItem>> aircraft() {
        return AIRCRAFT;
    }

    /** 全地上車両アイテム（設置する車両の ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<GroundVehicleItem>> vehicles() {
        return VEHICLES;
    }

    /** 全兵装アイテム（その兵装の ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<WeaponItem>> weapons() {
        return WEAPONS;
    }

    /** 全ラックアイテム（そのラックの ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<RackItem>> racks() {
        return RACKS;
    }

    /** 全ポッドアイテム（その装備の ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<EquipmentItem>> equipment() {
        return EQUIPMENT;
    }

    /** 弾薬アイテム（供給先の火砲の種類ごと）。 */
    public static Map<AmmoKind, DeferredItem<AmmoItem>> ammo() {
        return AMMO;
    }

    private ModItems() {
    }
}
