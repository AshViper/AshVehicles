package com.ashvehicles.ai.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.match.Loadout;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * AI に渡す航空機と、その翼下に吊る物。
 *
 * <p><b>AI の航空機の武装は、自由落下爆弾・内蔵の機関砲・空対空ミサイルの3つだけ</b>（2026-09-13 の指定）。ロケット・
 * 対地ミサイル・誘導爆弾・機関砲のポッドは吊らない。空対空ミサイルは空の相手にだけ使い、吊るのは翼端（地上用の物が
 * 付かない場所）と、敵に航空機がいるときの翼下だけ（同日の指示「敵航空機がいる場合は空対空ミサイルをつけて撃退」）。
 * 空に敵のいない出撃では、固定翼機の翼下は全部爆弾にする——AI の航空機の本業は地上の戦いを助けることだ。
 *
 * <p><b>分類は兵装ファイルの事実から読む。</b> ID の一覧にしないのは、コンテンツパックの兵装も同じ規則で分けるため。
 * 自由落下爆弾は誘導を持たない爆弾。空対空ミサイルは、熱を追う物（AIM-9・R-73）と、電波で掴んで近くで炸裂させる
 * 物——近接信管の距離が {@value #DIRECT_HIT} ブロックを超える物（AIM-120 は 4、R-37M は 7）。電波で掴んで直撃させる
 * 物（Kh-25 は 2）は地上を撃つ弾で、使わない。
 *
 * <p><b>自国の物を先に吊る</b>（2026-09-14 追加）。機体の {@code airframe.nation} / {@code stores_from} と兵装・ポッドの
 * {@code nation} を突き合わせ（{@code AircraftDefinition.Airframe#uses}）、同じ分類の中で自国の物を先に試す——露機は R-73 と
 * FAB、米機は AIM-9 と CBU-87。自国の物が重さや金具で吊れなければ他国の物に下りる。人の出撃盤のプリセット
 * （{@code match/AirPresets}）と同じ考え方で、下り方も同じ。
 */
public final class AirLoadout {
    /** 空の物を落とす電波誘導弾と見なす、近接信管の距離の下限（ブロック）。これ以下は直撃させる対地の弾。 */
    private static final float DIRECT_HIT = 2.5F;

    /**
     * AI に渡さない機種。大型爆撃機と輸送機（2026-09-13 の指定）。無人機は座席が無いので
     * {@link Loadout#deployable} が先に外す。
     */
    private static final Set<ResourceLocation> GROUNDED = Set.of(id("b_1"), id("b_2"), id("ac_130u"), id("c_130"));

    /**
     * フレアとチャフを足すポッドと、シーカーを遠くまで・早く掴ませるポッド（空対空ミサイルのため）。デコイは自国の物が
     * あればそちら（{@link #decoyPod}）で、これは国籍の合う物が無いときの物。
     */
    private static final ResourceLocation DECOY_POD = id("ale40");
    private static final ResourceLocation TARGETING_POD = id("targeting_pod");

    /** 1本のパイロンに吊る爆弾とミサイルの数。 */
    private static final int PAIR = 2;

    /** 左右の対と見なす、ハードポイントの位置のずれ（ブロック）。 */
    private static final double MIRROR = 0.3;

    /** 戦闘機が翼下に空対空ミサイルを2対吊る、敵の航空機の数。それより少なければ1対。 */
    private static final int MANY_HOSTILE = 3;

    /** AI が使う物の分類。機関砲は空と地上の両方へ、爆弾は地上へ、空対空ミサイルは空へ。 */
    public enum Store {
        GUN, BOMB, AAM
    }

    private AirLoadout() {
    }

    /** その兵装を AI が使えるなら、その分類。使わない物（ロケット・対地ミサイル・誘導爆弾・増槽）は null。 */
    @Nullable
    public static Store storeOf(WeaponDefinition weapon) {
        return switch (weapon.type()) {
            case GUN -> Store.GUN;
            case BOMB -> weapon.isGuided() ? null : Store.BOMB;
            case MISSILE -> weapon.guidance().map(AirLoadout::hitsAir).orElse(false) ? Store.AAM : null;
            case ROCKET, TANK -> null;
        };
    }

    /** その誘導が空の物を落とす物か。熱を追うか、電波で掴んで近くで炸裂させるか。 */
    public static boolean hitsAir(WeaponDefinition.Guidance guidance) {
        return guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT
                || (guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR && guidance.proximity() > DIRECT_HIT);
    }

    /**
     * 吊りたい度合い。一撃の重い物ほど高い。空対空ミサイルは熱を追う物が先——押さずに掴み、機首の先で勝手に掴み直す
     * （{@code TargetLock.tick}）。電波の物は押して掴ませる手間が要る。{@code AirPilot} が同じ分類の中から選ぶ順もこれ。
     */
    public static double worth(WeaponDefinition weapon) {
        double worth = weapon.projectile().damage() + weapon.projectile().explosion() * 10.0;

        if (weapon.guidance().map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT)
                .orElse(false)) {
            worth += 1000.0;
        }

        return worth;
    }

    /**
     * AI が飛ばせる機体か。出撃できる（{@link Loadout#deployable}——座席を持つ）機体のうち、渡さない機種でなく、地上を
     * 撃てる物——パイロットの引き金に繋がる内蔵の機関砲か、吊れる自由落下爆弾（固定翼機だけ）——を持つ物。
     *
     * <p><b>砲座の砲は数えない。</b> 砲手席の乗員が撃つ物で、パイロットの引き金には繋がらない
     * （{@code WeaponMounts.carried}）。同梱のヘリは AH-64・RAH-66 とも機関砲が砲手席の物で、回転翼機は爆弾を吊らない
     * ので、AI の名簿に入らない。
     */
    public static boolean flyable(ResourceLocation id) {
        if (!ModItems.aircraft().containsKey(id) || GROUNDED.contains(id) || !Loadout.deployable(id)) {
            return false;
        }

        AircraftDefinition definition = Definitions.AIRCRAFT.get(id);
        boolean rotor = definition.rotor().isPresent();

        for (int slot = 0; slot < definition.hardpoints().size(); slot++) {
            AircraftDefinition.Hardpoint hardpoint = definition.hardpoints().get(slot);

            if (pilotGun(definition, hardpoint)) {
                return true;
            }

            if (!rotor && hardpoint.isWeaponPylon() && takes(definition, slot, Store.BOMB)) {
                return true;
            }
        }

        return false;
    }

    /** パイロットの引き金に繋がる内蔵の機関砲か。砲座が振る砲は違う。 */
    private static boolean pilotGun(AircraftDefinition definition, AircraftDefinition.Hardpoint hardpoint) {
        return hardpoint.isFixed() && !crewed(definition, hardpoint)
                && storeOf(Definitions.weapon(hardpoint.fixed().get())) == Store.GUN;
    }

    /** その固定砲を砲座が振るか。 */
    private static boolean crewed(AircraftDefinition definition, AircraftDefinition.Hardpoint hardpoint) {
        for (AircraftDefinition.Station station : definition.stations()) {
            if (station.pylons().contains(hardpoint.name())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 戦闘機か。レーダーを持ち、パイロットの引き金に繋がる機関砲を持つ機体。爆撃機（B-52）と攻撃機（Su-25・Ju 87）は
     * どちらかを欠く。
     */
    public static boolean isFighter(AircraftDefinition definition) {
        if (!definition.radar().fitted()) {
            return false;
        }

        for (AircraftDefinition.Hardpoint hardpoint : definition.hardpoints()) {
            if (pilotGun(definition, hardpoint)) {
                return true;
            }
        }

        return false;
    }

    /**
     * AI の機体を武装させる。人の出撃盤が注文書でやること（{@link Loadout#apply}）を、注文する者の代わりに。
     *
     * <p>special ステーションの1つ目にデコイのポッド（自国の物、{@link #decoyPod}）、2つ目に照準ポッド。翼端には空対空ミサイル。<b>敵に航空機が
     * いれば、翼下にも空対空ミサイルを吊る</b>——戦闘機（{@link #isFighter}）は胴体の中心線から遠い対から1対、敵の機体が
     * {@value #MANY_HOSTILE} 機以上なら2対。回転翼機は1対（爆弾を吊らないので、他に使い道が無い）。攻撃機と爆撃機は
     * 爆弾のまま機関砲で応じる。固定翼機の残りの weapon パイロンには自由落下爆弾。
     *
     * <p>空対空ミサイルを先に吊る。機体の搭載可能重量を爆弾が先に使い切ると、空の相手に向ける物が残らない。欲しい物が
     * 重さで吊れなければ軽い物で埋め、何も吊れないパイロンは空のまま。
     *
     * <p>出撃するたびに {@code match/Bots} がこれで積む。撃つ物の尽きた AI は自爆して出直すので（{@code AirPilot}）、
     * 積み方は出撃のたびに空の様子へ合わせ直される。
     *
     * @param hostile 敵の陣営の航空機の数（{@code Bots.enemyAircraft}）
     */
    public static void equip(AircraftEntity aircraft, int hostile) {
        AircraftDefinition definition = aircraft.getStats();
        WeaponMounts mounts = aircraft.getWeapons();
        List<Integer> specials = new ArrayList<>();
        List<Integer> tips = new ArrayList<>();
        List<Integer> pylons = new ArrayList<>();

        for (int slot = 0; slot < definition.hardpoints().size(); slot++) {
            AircraftDefinition.Hardpoint hardpoint = definition.hardpoints().get(slot);

            if (hardpoint.isSpecialPylon()) {
                specials.add(slot);
            } else if (hardpoint.isWeaponPylon()) {
                (hardpoint.wingtip() ? tips : pylons).add(slot);
            }
        }

        ResourceLocation decoy = decoyPod(definition);

        if (!specials.isEmpty() && ModItems.equipment().containsKey(decoy)) {
            mounts.fitEquipmentAt(specials.get(0), decoy);
        }

        if (specials.size() > 1 && ModItems.equipment().containsKey(TARGETING_POD)) {
            mounts.fitEquipmentAt(specials.get(1), TARGETING_POD);
        }

        boolean rotor = definition.rotor().isPresent();
        List<List<Integer>> pairs = mirrored(definition, pylons);
        int count = hostile <= 0 ? 0 : rotor ? 1 : !isFighter(definition) ? 0 : hostile >= MANY_HOSTILE ? 2 : 1;
        Set<Integer> air = airPairs(definition, pairs, count);

        for (int slot : tips) {
            hang(aircraft, definition, slot, Store.AAM);
        }

        for (int at : air) {
            for (int slot : pairs.get(at)) {
                hang(aircraft, definition, slot, Store.AAM);
            }
        }

        if (rotor) {
            return;
        }

        for (int at = 0; at < pairs.size(); at++) {
            if (air.contains(at)) {
                continue;
            }

            for (int slot : pairs.get(at)) {
                hang(aircraft, definition, slot, Store.BOMB);
            }
        }
    }

    /** パイロン1本に、その分類の物を吊りたい順に試して吊る。何も吊れなければ、付けたラックも外して空に戻す。 */
    private static void hang(AircraftEntity aircraft, AircraftDefinition definition, int slot, Store wanted) {
        WeaponMounts mounts = aircraft.getWeapons();

        for (ResourceLocation weapon : ranked(definition, Loadout.choicesFor(definition, slot), wanted)) {
            Loadout order = new Loadout(aircraft.getAircraftId(), List.of(new Loadout.Entry(slot, weapon, PAIR)));

            if (order.apply(aircraft) > 0) {
                return;
            }

            // 重さが尽きたか、前に付いたラックがこの種類を受けない。空のラックを残したまま次を試すと、次の物も同じ
            // ラックに断られる。
            while (mounts.canStripAt(slot)) {
                mounts.strip(slot);
            }
        }
    }

    /**
     * 吊れる物のうちその分類の物を、吊りたい順に。自国の物が先、その中は {@link #worth} の順。
     *
     * <p>国籍を先に並べるだけで、他国の物を捨てはしない。{@link #hang} は吊れた最初の1つで止まるので、自国の物が
     * 重さか金具で断られたときにだけ他国の物まで下りる。
     */
    private static List<ResourceLocation> ranked(AircraftDefinition definition, List<ResourceLocation> choices,
            Store wanted) {
        AircraftDefinition.Airframe airframe = definition.airframe();
        List<ResourceLocation> usable = new ArrayList<>();

        for (ResourceLocation id : choices) {
            if (storeOf(Definitions.weapon(id)) == wanted) {
                usable.add(id);
            }
        }

        usable.sort(Comparator.comparing((ResourceLocation id) -> !airframe.uses(Definitions.weapon(id).nation()))
                .thenComparingDouble(id -> -worth(Definitions.weapon(id))));

        return usable;
    }

    /**
     * その機体に付けるデコイのポッド。フレアを足すデコイのうち国籍を名乗っていて機体が自国の物として使う物を、
     * レジストリの順で最初の1つ（米機は ALE-40、露機は BOZ-107）。無ければ {@link #DECOY_POD}。
     *
     * <p>国籍を名乗る物に限るのは、国籍を持たない汎用のデコイ（{@code decoy_pod}、フレアを持たない）が
     * どの機体にとっても「自国の物」になり、パックが汎用のフレアポッドを足すと米機の ALE-40 を押しのけるから。
     */
    private static ResourceLocation decoyPod(AircraftDefinition definition) {
        for (ResourceLocation id : ModItems.equipment().keySet()) {
            EquipmentDefinition pod = Definitions.equipment(id);

            if (pod.kind() == EquipmentDefinition.Kind.DECOY && pod.flares() > 0 && pod.nation().isPresent()
                    && definition.airframe().uses(pod.nation())) {
                return id;
            }
        }

        return DECOY_POD;
    }

    /** そのパイロンがその分類の物を吊れるか。 */
    private static boolean takes(AircraftDefinition definition, int slot, Store store) {
        for (ResourceLocation weapon : Loadout.choicesFor(definition, slot)) {
            if (storeOf(Definitions.weapon(weapon)) == store) {
                return true;
            }
        }

        return false;
    }

    /** weapon パイロンを左右の対に分ける。ファイルの順を保つ。 */
    private static List<List<Integer>> mirrored(AircraftDefinition definition, List<Integer> pylons) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Vec3> keys = new ArrayList<>();

        for (int slot : pylons) {
            Vec3 pos = definition.hardpoints().get(slot).pos();
            Vec3 key = new Vec3(Math.abs(pos.x), pos.y, pos.z);
            int found = -1;

            for (int at = 0; at < keys.size() && found < 0; at++) {
                if (keys.get(at).distanceTo(key) <= MIRROR) {
                    found = at;
                }
            }

            if (found < 0) {
                keys.add(key);
                groups.add(new ArrayList<>());
                found = groups.size() - 1;
            }

            groups.get(found).add(slot);
        }

        return groups;
    }

    /**
     * 空対空ミサイルを吊る対を {@code count} 組。空対空ミサイルを受ける左右の対のうち、胴体の中心線から遠い順（同じ
     * 幅ならファイルの順）。中心線の1本は数えない。
     */
    private static Set<Integer> airPairs(AircraftDefinition definition, List<List<Integer>> pairs, int count) {
        List<Integer> candidates = new ArrayList<>();

        for (int at = 0; at < pairs.size() && count > 0; at++) {
            if (pairs.get(at).size() >= 2 && takes(definition, pairs.get(at).get(0), Store.AAM)) {
                candidates.add(at);
            }
        }

        candidates.sort(Comparator.comparingDouble(
                (Integer at) -> -Math.abs(definition.hardpoints().get(pairs.get(at).get(0)).pos().x)));

        return Set.copyOf(candidates.subList(0, Math.min(count, candidates.size())));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, path);
    }
}
