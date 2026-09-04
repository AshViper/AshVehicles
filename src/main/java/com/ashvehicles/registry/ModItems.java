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
import com.ashvehicles.item.AmmunitionItem;
import com.ashvehicles.item.BlastWandItem;
import com.ashvehicles.item.DroneTerminalItem;
import com.ashvehicles.item.EquipmentItem;
import com.ashvehicles.item.FuelItem;
import com.ashvehicles.item.GroundVehicleItem;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.item.TargetDroneItem;
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
     * 携帯管制端末。無人機へ繋ぐための箱。
     *
     * <p>機体でも機体に付く物でもないのでここに並ぶ。1個持っていれば世界中のどの無人機にも繋げるので、
     * 束ねる意味は無い（{@link DroneTerminalItem} 参照）。
     */
    public static final DeferredItem<DroneTerminalItem> DRONE_TERMINAL =
            ITEMS.registerItem("drone_terminal", DroneTerminalItem::new, new Item.Properties().stacksTo(1));

    /**
     * 標的ドローンの投入器。これも機体に付く物ではなく、撃つ相手を空へ上げる道具。
     *
     * <p>ソロでミサイルを試すための的なので、機体・兵装のどれからも独立してここに置く。使えば1機、
     * スニークで使えば自分の分を全機回収（{@link TargetDroneItem} 参照）。
     */
    public static final DeferredItem<TargetDroneItem> TARGET_DRONE =
            ITEMS.registerItem("target_drone", TargetDroneItem::new, new Item.Properties().stacksTo(16));

    /**
     * 爆発演出のテスト用の棒。狙った地点で火球・炸裂音・衝撃波を起こすが、地形は壊さない。
     * スニークで使えば規模を切り替える（{@link BlastWandItem} 参照）。
     */
    public static final DeferredItem<BlastWandItem> BLAST_WAND =
            ITEMS.registerItem("blast_wand", BlastWandItem::new, new Item.Properties().stacksTo(1));

    /**
     * 中間素材。機体も車両も兵装も、鉄と赤石と TNT を卓に積み上げれば出てくる物ではなくなった。
     *
     * <p>前半の7種が機体の側。板と部品と基板が土台で、そこから装甲板・エンジン・ジェットエンジン・
     * アビオニクスが出る。どの機体もどの車両もこの7種だけで組める——違うのは何を何枚どこに置くかだけだ。
     *
     * <p>後半の4種が撃つ物の側。シーカー・推力部品・信管・高性能爆薬で、ミサイルも爆弾も、目・足・
     * 起爆の判断・炸薬という同じ4つの部品でできている。以前はここに TNT とガンパウダーとエンダーアイ
     * が直に置かれていたが、それだと「誘導弾を作る」が「エンダーアイを1個持つ」と同義になり、弾の
     * 中身がレシピから見えなかった。
     *
     * <p>素材の種類を機体ごと・兵装ごとに増やさないのは、格納庫に必要な物が11種の山として見えていた
     * 方が、レシピを1つずつ覚えるより先に進めるから。
     */
    public static final DeferredItem<Item> STEEL_PLATE = ITEMS.registerSimpleItem("steel_plate");
    public static final DeferredItem<Item> MACHINE_PARTS = ITEMS.registerSimpleItem("machine_parts");
    public static final DeferredItem<Item> CIRCUIT_BOARD = ITEMS.registerSimpleItem("circuit_board");
    public static final DeferredItem<Item> ARMOR_PLATE = ITEMS.registerSimpleItem("armor_plate");
    public static final DeferredItem<Item> ENGINE = ITEMS.registerSimpleItem("engine");
    public static final DeferredItem<Item> JET_ENGINE = ITEMS.registerSimpleItem("jet_engine");
    public static final DeferredItem<Item> AVIONICS = ITEMS.registerSimpleItem("avionics");

    /** 誘導弾の目。これ1個が「その弾は何かを追える」ということ。 */
    public static final DeferredItem<Item> SEEKER = ITEMS.registerSimpleItem("seeker");

    /** 推力部品。撃ち出される物と、落とすだけの物を分ける1個。 */
    public static final DeferredItem<Item> ROCKET_MOTOR = ITEMS.registerSimpleItem("rocket_motor");

    /** 信管。炸薬をいつ起こすかを決める部品。 */
    public static final DeferredItem<Item> FUZE = ITEMS.registerSimpleItem("fuze");

    /** 高性能爆薬。弾頭に詰める方の火薬で、装薬のガンパウダーとは別物。 */
    public static final DeferredItem<Item> HIGH_EXPLOSIVE = ITEMS.registerSimpleItem("high_explosive");

    /** 中間素材を作った順に。クリエイティブのタブもこの順で出す。 */
    public static final List<DeferredItem<Item>> MATERIALS =
            List.of(STEEL_PLATE, MACHINE_PARTS, CIRCUIT_BOARD, ARMOR_PLATE, ENGINE, JET_ENGINE, AVIONICS,
                    SEEKER, ROCKET_MOTOR, FUZE, HIGH_EXPLOSIVE);

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
    private static final Set<String> FIXED_NAMES = Set.of("wrench", "fuel_can", "target_drone",
            "blast_wand", "vehicle_workbench",
            "steel_plate", "machine_parts", "circuit_board", "armor_plate", "engine", "jet_engine", "avionics",
            "seeker", "rocket_motor", "fuze", "high_explosive");

    private static final Map<ResourceLocation, DeferredItem<AircraftItem>> AIRCRAFT = registerAircraft();
    private static final Map<ResourceLocation, DeferredItem<GroundVehicleItem>> VEHICLES = registerVehicles();
    /**
     * 兵装より先に登録する。同名の兵装があってもゲームが落ちるのではなく兵装側が負けるように。弾薬名
     * は MOD 固有の数個の定数、兵装名は誰かがデータパックに置いた任意の文字列。
     */
    private static final Map<AmmoKind, DeferredItem<AmmoItem>> AMMO = registerAmmo();
    /**
     * 弾種も兵装より先に。名前争いの理屈は弾薬と同じで、加えて弾種は兵装と隣り合わせの物なので、
     * {@code tank_gun_apfsds} のような名前が砲の名前と衝突したときに負けるべきなのは砲ではない方だ。
     */
    private static final Map<ResourceLocation, DeferredItem<AmmunitionItem>> AMMUNITION = registerAmmunition();
    private static final Map<ResourceLocation, DeferredItem<WeaponItem>> WEAPONS = registerWeapons();
    private static final Map<ResourceLocation, DeferredItem<RackItem>> RACKS = registerRacks();
    private static final Map<ResourceLocation, DeferredItem<EquipmentItem>> EQUIPMENT = registerEquipment();

    private static Map<ResourceLocation, DeferredItem<AircraftItem>> registerAircraft() {
        Map<ResourceLocation, DeferredItem<AircraftItem>> items = new LinkedHashMap<>();

        ModEntities.aircraft().forEach((id, type) -> items.put(id, itemsFor(id).registerItem(id.getPath(),
                properties -> new AircraftItem(type, properties), new Item.Properties().stacksTo(1))));

        return Collections.unmodifiableMap(items);
    }

    /** 地上車両にも同条件で1つずつ。同名で、置けばその車両が出る。 */
    private static Map<ResourceLocation, DeferredItem<GroundVehicleItem>> registerVehicles() {
        Map<ResourceLocation, DeferredItem<GroundVehicleItem>> items = new LinkedHashMap<>();

        ModEntities.vehicles().forEach((id, type) -> items.put(id, itemsFor(id).registerItem(id.getPath(),
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

            if (isTaken(id) || AMMUNITION.containsKey(id)) {
                AshVehicles.LOGGER.error("Weapon {} shares its name with a vehicle or with ammunition;"
                        + " it gets no item", id);

                return;
            }

            items.put(id, itemsFor(id).registerItem(id.getPath(),
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

            if (isTaken(id) || AMMUNITION.containsKey(id) || WEAPONS.containsKey(id)) {
                AshVehicles.LOGGER.error("Rack {} shares its name with something else; it gets no item", id);

                return;
            }

            items.put(id, itemsFor(id).registerItem(id.getPath(),
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

            if (isTaken(id) || AMMUNITION.containsKey(id) || WEAPONS.containsKey(id)
                    || RACKS.containsKey(id)) {
                AshVehicles.LOGGER.error("Equipment {} shares its name with something else;"
                        + " it gets no item", id);

                return;
            }

            items.put(id, itemsFor(id).registerItem(id.getPath(),
                    properties -> new EquipmentItem(id, properties), new Item.Properties().stacksTo(1)));
        });

        return Collections.unmodifiableMap(items);
    }

    /**
     * その名前を機体か弾薬か、MOD 固有のアイテムが既に取っているか。
     *
     * <p>弾薬名と MOD 固有名は {@code ashvehicles} の中でしか予約されない。コンテンツパックは自分の
     * 名前空間を持っているので、{@code mypack:engine} は誰とも争わない。
     */
    private static boolean isTaken(ResourceLocation id) {
        return AIRCRAFT.containsKey(id) || VEHICLES.containsKey(id)
                || (AshVehicles.MODID.equals(id.getNamespace())
                        && (isAmmoName(id) || FIXED_NAMES.contains(id.getPath())));
    }

    /**
     * その ID を登録すべきレジスタ。理由は {@code ModEntities.typesFor} と同じで、パックの物はパックの
     * 名前空間から登録される。
     */
    private static DeferredRegister.Items itemsFor(ResourceLocation id) {
        return AshVehicles.MODID.equals(id.getNamespace()) ? ITEMS : ModRegisters.items(id.getNamespace());
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

    /**
     * 弾種ファイル1つにつきアイテム1つ。機体や兵装とまったく同じ形で、ファイルを置けばアイテムが出る。
     *
     * <p>{@code "item": false} の弾種はアイテムを持たない。他の弾種を書くための下敷きや、何かが内部的に
     * だけ撃つ弾のための逃げ道で、兵装ファイルの同名フィールドと同じ意味。
     */
    private static Map<ResourceLocation, DeferredItem<AmmunitionItem>> registerAmmunition() {
        Map<ResourceLocation, DeferredItem<AmmunitionItem>> items = new LinkedHashMap<>();

        Definitions.AMMUNITION.builtIn().forEach((id, definition) -> {
            if (!definition.item()) {
                return;
            }

            if (isTaken(id)) {
                AshVehicles.LOGGER.error("Ammunition {} shares its name with a vehicle or with an"
                        + " ammunition box; it gets no item", id);

                return;
            }

            items.put(id, itemsFor(id).registerItem(id.getPath(),
                    properties -> new AmmunitionItem(id, properties), new Item.Properties()));
        });

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

    /** 全弾種アイテム（その弾種の ID 順）。 */
    public static Map<ResourceLocation, DeferredItem<AmmunitionItem>> ammunition() {
        return AMMUNITION;
    }

    private ModItems() {
    }
}
