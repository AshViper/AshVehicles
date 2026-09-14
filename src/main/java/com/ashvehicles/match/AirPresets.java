package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.ai.air.AirLoadout;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.EquipmentDefinition;
import com.ashvehicles.weapon.RackDefinition;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * 出撃盤で選ぶ航空機の搭載プリセット。<b>機体ファイルと兵装ファイルから自動で組む。</b>
 *
 * <p>2026-09-14 の指定で、出撃盤はステーションごとに1つずつ選ぶ形をやめ、ここが出す一覧から1つ選ぶだけに
 * なった。プリセットを機体ファイルに手書きさせないのは、同梱の29機にもコンテンツパックの機体にも、何も
 * 書かずに同じ規則で出るようにするため。代わりに中身は実機の搭載例ではなく規則通りになる。
 *
 * <p><b>画面とサーバーが同じ答えを出す。</b> 画面は一覧と中身を描くためにここを呼び、サーバーは届いた役割の
 * 名前からここでもう一度組み直して吊る（{@link #order}）。ステーションの並びをパケットで受け取らないので、
 * 作ったパケットで好きな物を吊ることはできない。組み方は定義ファイルとレジストリの順だけから決まる。
 *
 * <p><b>吊れる数は実際に吊る前に数える。</b> 機体はまだ世界に存在しないので {@code WeaponMounts} には訊けない
 * ——訊けたとしても、試しに吊るたびにサーバーが積み込みの音を鳴らす。だから {@link Plan} が同じ規則を
 * 定義ファイルだけでなぞる: 翼端かどうかが金具と一致すること、金具は一番多く載る物（同じなら軽い物、
 * {@link Loadout#rackFor} と同じ）、そして機体の搭載可能重量。ファイルが最初から付けている金具の重さも
 * 最初から数える（{@code WeaponMounts.storeMass} がそう数える）。<b>ここと {@code WeaponMounts} の規則は
 * 片方だけ変えてはいけない</b>——変えれば画面の数と吊られた数が食い違う。
 *
 * <p>役割ごとに「その役割の物」を吊るステーションの組を決め、その組に吊って合計の価値（1発の威力 ×
 * 吊れた数）が一番大きくなる兵装を選ぶ。重さで縛られる機体では軽い物が、余裕のある機体では重い物が勝つ。
 *
 * <p><b>自国の物を先に選ぶ</b>（2026-09-14 追加）。機体の {@code airframe.nation} と {@code stores_from}、兵装と
 * ポッドの {@code nation} を突き合わせ、自国の物で1発も吊れないときだけ他国の物に下りる。国籍が無かった頃は
 * 軽い方が勝つ規則のせいで MiG-29 も Su-57 も AIM-120 を吊っていた。
 */
public final class AirPresets {
    /** 左右の対と見なす、ハードポイントの位置のずれ（ブロック）。{@code AirLoadout} と同じ値。 */
    private static final double MIRROR = 0.3;

    /** 並びは出撃盤の上から下。 */
    public enum Role {
        /** 翼端と一番外の翼下に空対空ミサイル、残りの翼下に爆弾（回転翼機はロケット）。 */
        GENERAL("general"),
        /** 吊れる所すべてに空対空ミサイル。翼端は熱、翼下は電波を先に。 */
        AIR("air"),
        /** 翼下に対地ミサイル。要るなら照準ポッドを先に付ける。 */
        STRIKE("strike"),
        /** 翼下にロケット弾ポッド。 */
        ROCKETS("rockets"),
        /** 翼下に自由落下爆弾。固定翼機だけ。 */
        BOMBS("bombs"),
        /** 翼下に誘導爆弾と照準ポッド。固定翼機だけ。 */
        PRECISION("precision"),
        /** 何も吊らない。デコイのポッドだけ。 */
        CLEAN("clean");

        private final String id;

        Role(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public Component label() {
            return Component.translatable("gui.ashvehicles.deploy.preset." + this.id);
        }
    }

    /**
     * プリセットの中身1行。同じ物は1行にまとめる。
     *
     * @param item 兵装かポッド
     * @param count 吊る数。ポッドは付けた数
     */
    public record Line(ResourceLocation item, int count) {
    }

    /**
     * 出撃盤に並ぶ1つ。
     *
     * @param loadout サーバーが吊る注文書。数は数え済みで、{@link Loadout#apply} は同じ数を吊る
     * @param mass 吊る物の合計（kg）。ファイルが最初から付けている金具を含む
     * @param payload 機体の搭載可能重量（kg）。0 なら無制限
     */
    public record Preset(Role role, Loadout loadout, List<Line> lines, float mass, float payload) {
    }

    /** 役割が吊る物の分類。空対空・対地ミサイル・ロケット・自由落下爆弾・誘導爆弾。 */
    private enum Kind {
        AAM, AGM, ROCKET, BOMB, GUIDED_BOMB
    }

    private AirPresets() {
    }

    /**
     * その機体のプリセット。吊れる物が1つも無い機体（内蔵の武装だけの機体）と、航空機でない物は空。
     *
     * <p>役割の物を1発も吊れないプリセットは出さない（戦闘機に「精密爆撃」は無い、ではなく、誘導爆弾を
     * 受けるステーションが無い機体には出ない）。中身がそっくり同じになった物も後の方を出さない。
     */
    public static List<Preset> of(ResourceLocation aircraft) {
        List<Preset> presets = new ArrayList<>();

        if (!ModItems.aircraft().containsKey(aircraft)) {
            return presets;
        }

        AircraftDefinition definition = Definitions.AIRCRAFT.get(aircraft);

        if (definition == null || !hasPylons(definition)) {
            return presets;
        }

        Set<List<Loadout.Entry>> seen = new LinkedHashSet<>();

        for (Role role : Role.values()) {
            Preset preset = build(aircraft, definition, role);

            if (preset != null && seen.add(preset.loadout().entries())) {
                presets.add(preset);
            }
        }

        return presets;
    }

    /**
     * 届いた役割の名前から、サーバーが吊る注文書を組み直す。
     *
     * @return 航空機でない物と、プリセットを1つも持たない機体は空の注文書。その機体に無い役割なら null
     */
    @Nullable
    public static Loadout order(ResourceLocation vehicle, String role) {
        List<Preset> presets = of(vehicle);

        if (presets.isEmpty()) {
            return Loadout.empty(vehicle);
        }

        for (Preset preset : presets) {
            if (preset.role().id().equals(role)) {
                return preset.loadout();
            }
        }

        return null;
    }

    /** 何かを吊れる場所——weapon パイロンか special ステーション——を1つでも持つか。 */
    private static boolean hasPylons(AircraftDefinition definition) {
        for (AircraftDefinition.Hardpoint hardpoint : definition.hardpoints()) {
            if (hardpoint.isWeaponPylon() || hardpoint.isSpecialPylon()) {
                return true;
            }
        }

        return false;
    }

    /** 1つの役割を組む。役割の物を1発も吊れなければ null（{@link Role#CLEAN} は常に出る）。 */
    @Nullable
    private static Preset build(ResourceLocation aircraft, AircraftDefinition definition, Role role) {
        Plan plan = new Plan(definition);
        List<Integer> tips = new ArrayList<>();
        List<Integer> pylons = new ArrayList<>();
        List<Integer> specials = new ArrayList<>();

        for (int slot = 0; slot < definition.hardpoints().size(); slot++) {
            AircraftDefinition.Hardpoint hardpoint = definition.hardpoints().get(slot);

            if (hardpoint.isSpecialPylon()) {
                specials.add(slot);
            } else if (hardpoint.isWeaponPylon()) {
                (hardpoint.wingtip() ? tips : pylons).add(slot);
            }
        }

        boolean rotor = definition.rotor().isPresent();
        int main;

        switch (role) {
            case AIR -> {
                pod(plan, specials, EquipmentDefinition.Kind.DECOY);
                main = hangBest(plan, tips, Kind.AAM, WeaponDefinition.Guidance.Seeker.HEAT)
                        + hangBest(plan, pylons, Kind.AAM, WeaponDefinition.Guidance.Seeker.RADAR);
                pod(plan, specials, EquipmentDefinition.Kind.JAMMER);
            }
            case GENERAL -> {
                List<Integer> outer = outerPair(definition, pylons);
                List<Integer> rest = new ArrayList<>(pylons);

                rest.removeAll(outer);
                pod(plan, specials, EquipmentDefinition.Kind.DECOY);
                hangBest(plan, tips, Kind.AAM, WeaponDefinition.Guidance.Seeker.HEAT);

                int air = hangBest(plan, outer, Kind.AAM, WeaponDefinition.Guidance.Seeker.RADAR);
                int ground = hangBest(plan, rest, rotor ? Kind.ROCKET : Kind.BOMB, null);

                // 空と地上の両方を持って初めて汎用。片方しか吊れない機体では、対空か爆撃と同じ物になる。
                main = air > 0 && ground > 0 ? air + ground : 0;
            }
            case STRIKE -> main = ground(plan, tips, pylons, specials, Kind.AGM);
            case ROCKETS -> main = ground(plan, tips, pylons, specials, Kind.ROCKET);
            // 回転翼機は爆弾を吊らない（AI も同じ、{@code AirLoadout.equip}）。
            case BOMBS -> main = rotor ? 0 : ground(plan, tips, pylons, specials, Kind.BOMB);
            case PRECISION -> main = rotor ? 0 : ground(plan, tips, pylons, specials, Kind.GUIDED_BOMB);
            case CLEAN -> {
                pod(plan, specials, EquipmentDefinition.Kind.DECOY);
                main = 1;
            }
            default -> main = 0;
        }

        if (main <= 0) {
            return null;
        }

        return new Preset(role, new Loadout(aircraft, List.copyOf(plan.entries)), lines(plan.entries),
                plan.mass(), definition.airframe().payload());
    }

    /**
     * 地上を撃つ役割の共通の組み方。要るポッド、デコイのポッド、翼端に空対空ミサイル、翼下にその役割の物、の順。
     *
     * <p>ポッドを先に付けるのは重さの取り合いで負けさせないため。照準ポッドの無いレーザー誘導爆弾は撃てない
     * （{@code WeaponMounts.missingPod}）ので、爆弾に重さを使い切られたら全部が荷物になる。デコイも同じで、
     * 後に回すと爆弾とロケットが搭載可能重量を食い切り、A-10 や F-16 がフレア無しで出ていた。
     */
    private static int ground(Plan plan, List<Integer> tips, List<Integer> pylons, List<Integer> specials,
            Kind kind) {
        for (ResourceLocation weapon : candidates(plan.definition, pylons, kind)) {
            Definitions.weapon(weapon).requires().ifPresent(needed -> pod(plan, specials, needed));
        }

        pod(plan, specials, EquipmentDefinition.Kind.DECOY);
        hangBest(plan, tips, Kind.AAM, WeaponDefinition.Guidance.Seeker.HEAT);

        return hangBest(plan, pylons, kind, null);
    }

    /**
     * その組に、その分類の物を1種類だけ吊る。
     *
     * <p>候補を4段に分けて上から試し、1発でも吊れた段で決める: 自国の物でシーカーの好みに合う物、自国の物、
     * 他国も含めて好みに合う物、全部。<b>自国の物が1つも吊れないときだけ他国の物を使う</b>——ドイツの Ju 87D に
     * ドイツの爆弾は同梱に無いが、爆撃機から爆撃を取り上げる理由にはならない。自国かどうかは
     * {@code AircraftDefinition.Airframe#uses}。
     *
     * @param preferred 先に試すシーカー。null なら区別しない
     * @return 吊れた数
     */
    private static int hangBest(Plan plan, List<Integer> slots, Kind kind,
            @Nullable WeaponDefinition.Guidance.Seeker preferred) {
        List<ResourceLocation> all = candidates(plan.definition, slots, kind);
        // 撃てない物は吊らない。要るポッドが付いていないなら、それは荷物だ。
        all.removeIf(weapon -> Definitions.weapon(weapon).requires().map(needed -> !plan.hasPod(needed))
                .orElse(false));

        AircraftDefinition.Airframe airframe = plan.definition.airframe();
        List<ResourceLocation> own = new ArrayList<>();

        for (ResourceLocation weapon : all) {
            if (airframe.uses(Definitions.weapon(weapon).nation())) {
                own.add(weapon);
            }
        }

        List<List<ResourceLocation>> tiers = new ArrayList<>();

        if (preferred != null) {
            tiers.add(seeking(own, preferred));
        }

        tiers.add(own);

        if (preferred != null) {
            tiers.add(seeking(all, preferred));
        }

        tiers.add(all);

        for (List<ResourceLocation> tier : tiers) {
            int hung = tier.isEmpty() ? 0 : tryBest(plan, slots, tier, kind);

            if (hung > 0) {
                return hung;
            }
        }

        return 0;
    }

    /** そのシーカーを持つ物だけ。 */
    private static List<ResourceLocation> seeking(List<ResourceLocation> weapons,
            WeaponDefinition.Guidance.Seeker seeker) {
        List<ResourceLocation> found = new ArrayList<>();

        for (ResourceLocation weapon : weapons) {
            if (Definitions.weapon(weapon).guidance().map(guidance -> guidance.seeker() == seeker).orElse(false)) {
                found.add(weapon);
            }
        }

        return found;
    }

    /**
     * 候補を1つずつ組全体に試し、一番良かった物をその計画に採る。
     *
     * <p><b>空対空ミサイルだけは数で選ぶ</b>（同じ数なら軽い方）。合計の威力で比べると、重さに余裕のある機体ほど
     * 重い長射程弾を少なく吊ることになる——同梱の規則を試しに回したら、F-15 の対空が AIM-54 の14発、汎用の外側が
     * どの機体も R-37M になった。空の相手に効くのは1発の重さより撃てる回数だ。地上を撃つ物は合計の威力で選ぶ。
     */
    private static int tryBest(Plan plan, List<Integer> slots, List<ResourceLocation> candidates, Kind kind) {
        Plan best = null;
        WeaponDefinition bestWeapon = null;
        double bestWorth = 0.0;
        int bestHung = 0;

        for (ResourceLocation weapon : candidates) {
            Plan trial = new Plan(plan);
            WeaponDefinition fitted = Definitions.weapon(weapon);
            int hung = 0;

            for (int slot : slots) {
                hung += trial.hang(slot, weapon);
            }

            if (hung <= 0) {
                continue;
            }

            double worth = hung * worth(fitted);
            boolean better = best == null || (kind == Kind.AAM
                    ? hung > bestHung || (hung == bestHung && fitted.mass() < bestWeapon.mass())
                    : worth > bestWorth);

            if (better) {
                best = trial;
                bestWeapon = fitted;
                bestWorth = worth;
                bestHung = hung;
            }
        }

        if (best != null) {
            plan.adopt(best);
        }

        return bestHung;
    }

    /**
     * 1発の価値。直撃の威力と炸薬。
     *
     * <p>{@code AirLoadout.worth} と違って熱シーカーに下駄を履かせない。あちらは AI が押さずに撃てる物を
     * 選ぶための順位で、人が選ぶプリセットではシーカーの好みを {@code preferred} で別に持つ。
     */
    private static double worth(WeaponDefinition weapon) {
        return weapon.projectile().damage() + weapon.projectile().explosion() * 10.0;
    }

    /**
     * 組のどこかに吊れる、その分類の兵装。レジストリの順で、重複なし。
     *
     * <p><b>電子機器を1つも持たない機体には誘導兵器を出さない。</b> レーダーも警戒受信機も持たない機体
     * （{@code radar.exists()} が偽）——同梱では Ju 87D・P-51・Yak-9 の3機だけ——に規則をそのまま当てると、
     * プロペラ機が AIM-120 と Kh-25 を吊って出た。レーダーの有無では切らない。A-10・AH-64・Su-25 は探知
     * レーダーを持たないが、実機も誘導弾を撃つ。
     */
    private static List<ResourceLocation> candidates(AircraftDefinition definition, List<Integer> slots, Kind kind) {
        Set<ResourceLocation> found = new LinkedHashSet<>();
        boolean avionics = definition.radar().exists();

        for (int slot : slots) {
            for (ResourceLocation weapon : Loadout.choicesFor(definition, slot)) {
                WeaponDefinition fitted = Definitions.weapon(weapon);

                if (kindOf(fitted) == kind && (avionics || !fitted.isGuided())) {
                    found.add(weapon);
                }
            }
        }

        return new ArrayList<>(found);
    }

    /**
     * 兵装の分類。ガンポッドと増槽はどの役割も吊らない。
     *
     * <p>空対空ミサイルの見分けは AI と同じ（{@link AirLoadout#hitsAir}）——熱を追う物と、電波で掴んで近くで
     * 炸裂させる物。電波で掴んで直撃させる Kh-25 は対地ミサイルになる。
     */
    @Nullable
    private static Kind kindOf(WeaponDefinition weapon) {
        return switch (weapon.type()) {
            case MISSILE -> weapon.guidance().map(AirLoadout::hitsAir).orElse(false) ? Kind.AAM : Kind.AGM;
            case ROCKET -> Kind.ROCKET;
            case BOMB -> weapon.isGuided() ? Kind.GUIDED_BOMB : Kind.BOMB;
            case GUN, TANK -> null;
        };
    }

    /**
     * その種別のポッドを、空いている最初の special ステーションに。既に付いていれば何もしない。
     *
     * <p>兵装と同じく自国の物を先に探す（米機は ALE-40、露機は BOZ-107）。自国の物が付かなければ他国の物。
     */
    private static void pod(Plan plan, List<Integer> specials, EquipmentDefinition.Kind kind) {
        if (plan.hasPod(kind)) {
            return;
        }

        AircraftDefinition.Airframe airframe = plan.definition.airframe();

        for (boolean ownOnly : new boolean[] {true, false}) {
            for (ResourceLocation equipment : ModItems.equipment().keySet()) {
                EquipmentDefinition pod = Definitions.equipment(equipment);

                if (pod.kind() != kind || (ownOnly && !airframe.uses(pod.nation()))) {
                    continue;
                }

                for (int slot : specials) {
                    if (plan.fit(slot, equipment)) {
                        return;
                    }
                }
            }
        }
    }

    /** 翼下の、胴体の中心線から一番遠い左右の対。対が無ければ空。 */
    private static List<Integer> outerPair(AircraftDefinition definition, List<Integer> pylons) {
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

        List<Integer> outer = List.of();
        double widest = -1.0;

        for (int at = 0; at < groups.size(); at++) {
            if (groups.get(at).size() >= 2 && keys.get(at).x > widest) {
                widest = keys.get(at).x;
                outer = groups.get(at);
            }
        }

        return outer;
    }

    /** 注文書を、画面に出す行へ。同じ物は1行にまとめ、最初に出てきた順。 */
    private static List<Line> lines(List<Loadout.Entry> entries) {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();

        for (Loadout.Entry entry : entries) {
            counts.merge(entry.weapon(), entry.count(), Integer::sum);
        }

        List<Line> lines = new ArrayList<>();

        counts.forEach((item, count) -> lines.add(new Line(item, count)));

        return lines;
    }

    /**
     * 吊る前の帳簿。ステーションごとの金具・吊った数・ポッドと、残りの搭載可能重量。
     *
     * <p>{@code WeaponMounts} の規則を定義ファイルだけでなぞる。上の注記のとおり、片方だけ変えてはいけない。
     */
    private static final class Plan {
        private final AircraftDefinition definition;
        private final boolean unlimited;
        private final float payload;
        private float free;
        private final ResourceLocation[] racks;
        private final int[] loaded;
        private final ResourceLocation[] pods;
        private final List<Loadout.Entry> entries;

        Plan(AircraftDefinition definition) {
            int size = definition.hardpoints().size();

            this.definition = definition;
            this.payload = definition.airframe().payload();
            this.unlimited = this.payload <= 0.0F;
            this.free = this.payload;
            this.racks = new ResourceLocation[size];
            this.loaded = new int[size];
            this.pods = new ResourceLocation[size];
            this.entries = new ArrayList<>();

            // ファイルが最初から付けている金具。付けるときに重さは見ないが、重さ自体は数えられる
            // （{@code WeaponMounts.ensureLayout} と {@code storeMass}）。
            for (int slot = 0; slot < size; slot++) {
                AircraftDefinition.Hardpoint hardpoint = definition.hardpoints().get(slot);

                if (hardpoint.isWeaponPylon() && hardpoint.rack().isPresent()) {
                    RackDefinition rack = Definitions.rack(hardpoint.rack().get());

                    if (rack.wingtip() == hardpoint.wingtip()) {
                        this.racks[slot] = hardpoint.rack().get();
                        this.free -= rack.mass();
                    }
                }
            }

            this.free = Math.max(0.0F, this.free);
        }

        Plan(Plan other) {
            this.definition = other.definition;
            this.unlimited = other.unlimited;
            this.payload = other.payload;
            this.free = other.free;
            this.racks = other.racks.clone();
            this.loaded = other.loaded.clone();
            this.pods = other.pods.clone();
            this.entries = new ArrayList<>(other.entries);
        }

        void adopt(Plan other) {
            this.free = other.free;
            System.arraycopy(other.racks, 0, this.racks, 0, this.racks.length);
            System.arraycopy(other.loaded, 0, this.loaded, 0, this.loaded.length);
            System.arraycopy(other.pods, 0, this.pods, 0, this.pods.length);
            this.entries.clear();
            this.entries.addAll(other.entries);
        }

        /** 吊る物の合計（kg）。 */
        float mass() {
            float mass = 0.0F;

            for (int slot = 0; slot < this.racks.length; slot++) {
                if (this.racks[slot] != null) {
                    mass += Definitions.rack(this.racks[slot]).mass();
                }

                if (this.pods[slot] != null) {
                    mass += Definitions.equipment(this.pods[slot]).mass();
                }
            }

            for (Loadout.Entry entry : this.entries) {
                if (ModItems.weapons().containsKey(entry.weapon())) {
                    mass += Definitions.weapon(entry.weapon()).mass() * entry.count();
                }
            }

            return mass;
        }

        private boolean fits(float mass) {
            return this.unlimited || mass <= this.free;
        }

        private void spend(float mass) {
            if (!this.unlimited) {
                this.free -= mass;
            }
        }

        boolean hasPod(EquipmentDefinition.Kind kind) {
            for (ResourceLocation pod : this.pods) {
                if (pod != null && Definitions.equipment(pod).kind() == kind) {
                    return true;
                }
            }

            return false;
        }

        /** special ステーションにポッドを付ける。{@code WeaponMounts.canFitEquipmentAt} と同じ条件。 */
        boolean fit(int slot, ResourceLocation equipment) {
            AircraftDefinition.Hardpoint hardpoint = this.definition.hardpoints().get(slot);
            float mass = Definitions.equipment(equipment).mass();

            if (!hardpoint.isSpecialPylon() || this.pods[slot] != null || !this.fits(mass)) {
                return false;
            }

            this.spend(mass);
            this.pods[slot] = equipment;
            this.entries.add(new Loadout.Entry(slot, equipment, 1));

            return true;
        }

        /**
         * weapon パイロン1本に、金具が無ければ付けて、載るだけ吊る。{@code Loadout.load} と同じ手順。
         *
         * <p>1発も吊れなければ金具も付けなかったことにする。注文書に載せないので、サーバーの
         * {@link Loadout#apply} も付けない。
         */
        int hang(int slot, ResourceLocation weapon) {
            AircraftDefinition.Hardpoint hardpoint = this.definition.hardpoints().get(slot);

            if (!hardpoint.isWeaponPylon() || this.loaded[slot] > 0) {
                return 0;
            }

            WeaponDefinition fitted = Definitions.weapon(weapon);
            float before = this.free;
            ResourceLocation rack = this.racks[slot];

            if (rack == null) {
                rack = this.bestRack(hardpoint, fitted);

                if (rack == null) {
                    return 0;
                }

                this.spend(Definitions.rack(rack).mass());
            }

            RackDefinition carrier = Definitions.rack(rack);
            int hung = 0;

            if (carrier.takes(fitted)) {
                while (hung < carrier.capacity() && this.fits(fitted.mass())) {
                    this.spend(fitted.mass());
                    hung++;
                }
            }

            if (hung == 0) {
                this.free = before;

                return 0;
            }

            this.racks[slot] = rack;
            this.loaded[slot] = hung;
            this.entries.add(new Loadout.Entry(slot, weapon, hung));

            return hung;
        }

        /** {@link Loadout#rackFor} と同じ選び方。一番多く載る物、同じなら軽い物、重さの残りに収まる物。 */
        @Nullable
        private ResourceLocation bestRack(AircraftDefinition.Hardpoint hardpoint, WeaponDefinition weapon) {
            ResourceLocation best = null;
            RackDefinition bestRack = null;

            for (ResourceLocation candidate : ModItems.racks().keySet()) {
                RackDefinition rack = Definitions.rack(candidate);

                if (!rack.takes(weapon) || rack.wingtip() != hardpoint.wingtip() || !this.fits(rack.mass())) {
                    continue;
                }

                if (bestRack == null || rack.capacity() > bestRack.capacity()
                        || (rack.capacity() == bestRack.capacity() && rack.mass() < bestRack.mass())) {
                    best = candidate;
                    bestRack = rack;
                }
            }

            return best;
        }
    }
}
