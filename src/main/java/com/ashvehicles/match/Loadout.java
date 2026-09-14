package com.ashvehicles.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.item.RackItem;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.weapon.RackDefinition;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 出撃時にステーションへ何を吊るか。選んだ機体1機ぶんの注文書。
 *
 * <p>人の出撃では {@link AirPresets} が組み、AI の出撃では {@code AirLoadout} が組む。どちらも通信には
 * 載らない——出撃盤が送るのはプリセットの名前だけで、サーバーが同じ規則で組み直す。
 *
 * <p><b>選ぶのは兵装であって金具ではない。</b> 翼下のパイロンに直接ミサイルは付かず、間にラックが要る
 * （{@link WeaponMounts#canMountAt} 参照）。それは地上作業の手順であって、出撃前の選択ではない——飛行
 * 隊の誰も「どのレールを使うか」を注文しない。だからここに書くのは兵装の名前だけで、それを載せられる
 * 金具は {@link #rackFor} が選ぶ。
 *
 * <p><b>special ステーションにはポッドが載る。</b> 同じ一覧に混ぜてあり、どちらとして扱うかは注文書では
 * なくステーションの種別が決める。撃つ物と見る物を別の注文書に分ける理由が無いからだ。
 *
 * <p>数量 0 は「そのラックに載るだけ載せる」の意味。ラックが4連装なら4発で、機体の搭載可能重量が先に
 * 尽きればそこで止まる。
 */
public record Loadout(ResourceLocation vehicle, List<Entry> entries) {
    /** ステーション1つぶんの注文。 */
    public record Entry(int slot, ResourceLocation weapon, int count) {
    }

    /**
     * 試合で出してよい機体か。
     *
     * <p><b>乗れない物は出せない。</b> 無人機は端末から飛ばす物で座席を持たず
     * （[[a-drone-is-an-aircraft-with-no-seats]]）、牽引砲は車外に立って回す物だ
     * （[[crew-served-guns-are-worked-from-outside]]）。どちらも出撃盤から出すと、ポイントだけ引かれて
     * 本人は旗の上に立ったままになる——出撃は「乗って出ること」であり、その2つはそこに当てはまらない。
     *
     * <p><b>走れない物と、座標へ撃つ物も外す</b>（2026-09-13 追加）。乗って撃つ固定兵器は旗の前から動けず、
     * 弾道弾の車両は目の前の敵に向ける手段を持たない。どちらも「出撃した」ことにならない。
     *
     * <p>判定はファイルが既に持っている事実から。座席の無い機体、{@code hull.crewed} の車両、
     * {@code powertrain.max_speed} が 0 の車両、そして直接照準の武器を1つも持たない車両。
     */
    public static boolean deployable(ResourceLocation vehicle) {
        if (ModItems.aircraft().containsKey(vehicle)) {
            return !Definitions.AIRCRAFT.get(vehicle).airframe().seats().isEmpty();
        }

        if (!ModItems.vehicles().containsKey(vehicle)) {
            return false;
        }

        GroundVehicleDefinition definition = Definitions.VEHICLES.get(vehicle);

        if (definition == null || definition.hull().crewed()) {
            return false;
        }

        // 走れない物は出撃の対象ではない。乗って撃つ固定兵器——ZU-23 や CIWS——は、旗の前に置かれた
        // まま試合の終わりまでそこにいる。出撃とは戦線へ出ることであって、湧いた場所に据え付けられる
        // ことではない。判定は駆動系が既に持っている事実（{@code powertrain.max_speed}）から。
        if (definition.powertrain().maxSpeed() <= 0.0F) {
            return false;
        }

        // 座標へ撃つ発射機しか持たない物——弾道弾の車両（Grim-2）——も外す。あれは端末に座標を打ち込んで
        // 撃つ物で（[[point-seeker-is-a-coordinate-not-a-target]]）、目の前の敵に向ける手段を1つも持たない。
        // 直接照準の砲か機銃が1つでもあれば、その車両は今まで通り出せる。
        return definition.armament().main().isPresent() || definition.coaxial().exists()
                || !laysPoint(definition);
    }

    /** その車両の発射機が、シーカーではなく据えた座標へ撃つ物か。発射機が無ければ偽。 */
    private static boolean laysPoint(GroundVehicleDefinition definition) {
        return definition.launcher().missile()
                .map(missile -> Definitions.weapon(missile).guidance()
                        .map(guidance -> guidance.seeker() == WeaponDefinition.Guidance.Seeker.POINT)
                        .orElse(false))
                .orElse(false);
    }

    /** 何も吊らない注文書。地上車両はこれで出る——載せる物は車両ファイルが既に決めている。 */
    public static Loadout empty(ResourceLocation vehicle) {
        return new Loadout(vehicle, List.of());
    }

    /**
     * 注文書の通りに機体を武装させる。サーバー専用。
     *
     * <p>通らない注文は黙って飛ばす。画面が出した選択肢は機体ファイルから組んだ物なので普通は全部通る
     * が、届いたパケットは誰でも作れる——載らない物が1行混ざっていたからといって、出撃そのものを止める
     * 必要は無い。
     *
     * @return 実際に吊れた兵装の数
     */
    public int apply(AircraftEntity aircraft) {
        WeaponMounts mounts = aircraft.getWeapons();
        int fitted = 0;

        for (Entry entry : this.entries) {
            AircraftDefinition.Hardpoint hardpoint = mounts.hardpoint(entry.slot());

            if (hardpoint == null) {
                continue;
            }

            if (hardpoint.isSpecialPylon()) {
                if (mounts.fitEquipmentAt(entry.slot(), entry.weapon())) {
                    fitted++;
                }

                continue;
            }

            if (!hardpoint.isWeaponPylon()) {
                // 内蔵砲。載せる物はファイルが決めていて、誰も変えられない。弾は rearm が入れる。
                continue;
            }

            fitted += this.load(mounts, entry);
        }

        return fitted;
    }

    /** パイロン1本に金具を付け、注文の数だけ兵装を吊る。 */
    private int load(WeaponMounts mounts, Entry entry) {
        if (!mounts.mounts().get(entry.slot()).hasRack()) {
            ResourceLocation rack = rackFor(mounts, entry.slot(), entry.weapon());

            if (rack == null) {
                return 0;
            }

            mounts.fitRackAt(entry.slot(), rack);
        }

        // 0 は「載るだけ」。ラックの空き位置と機体の搭載可能重量のうち、先に尽きた方で止まる。
        int wanted = entry.count() <= 0 ? Integer.MAX_VALUE : entry.count();
        int hung = 0;

        while (hung < wanted && mounts.mountAt(entry.slot(), entry.weapon(), -1)) {
            hung++;
        }

        return hung;
    }

    /**
     * その兵装をそのステーションに吊るための金具。無ければ null。
     *
     * <p>選ぶのは<b>一番多く載る物</b>。翼下の1本に4発載る金具と1発の金具があるなら、出撃前に選びたいの
     * は前者だ。同じ容量なら軽い方——搭載可能重量は機体の持ち物であり、金具に食わせる理由は無い。
     */
    @Nullable
    public static ResourceLocation rackFor(WeaponMounts mounts, int slot, ResourceLocation weapon) {
        WeaponDefinition fitted = Definitions.weapon(weapon);
        ResourceLocation best = null;
        RackDefinition bestRack = null;

        for (Map.Entry<ResourceLocation, DeferredItem<RackItem>> candidate : ModItems.racks().entrySet()) {
            RackDefinition rack = Definitions.rack(candidate.getKey());

            if (!rack.takes(fitted) || !mounts.canFitRackAt(slot, candidate.getKey())) {
                continue;
            }

            if (bestRack == null || rack.capacity() > bestRack.capacity()
                    || (rack.capacity() == bestRack.capacity() && rack.mass() < bestRack.mass())) {
                best = candidate.getKey();
                bestRack = rack;
            }
        }

        return best;
    }

    /**
     * そのステーションが受け付ける物の一覧。weapon パイロンなら兵装、special ステーションならポッド。
     *
     * <p><b>機体ではなく機体ファイルに訊く。</b> 選ぶのは出撃前——つまりその機体がまだ世界に存在しない
     * 時点だ。{@link WeaponMounts} は実在する1機の今の搭載内容を知っている代わりに1機を要求するので、
     * 画面はそれを使えない。ここで見るのはステーションの種別と翼端かどうかだけで、それはファイルに
     * 書いてある。
     *
     * <p>重量は見ない。搭載可能重量に収まるかは「その1本に何が載るか」ではなく「機体全体に何を積んだ
     * か」の問いで、順番にも依る。答えを出せるのは実際に吊る側——{@link #apply} を通るサーバーだけだ。
     */
    public static List<ResourceLocation> choicesFor(AircraftDefinition definition, int slot) {
        List<ResourceLocation> choices = new ArrayList<>();

        if (slot < 0 || slot >= definition.hardpoints().size()) {
            return choices;
        }

        AircraftDefinition.Hardpoint hardpoint = definition.hardpoints().get(slot);

        if (hardpoint.isSpecialPylon()) {
            choices.addAll(ModItems.equipment().keySet());

            return choices;
        }

        if (!hardpoint.isWeaponPylon()) {
            return choices;
        }

        for (ResourceLocation weapon : ModItems.weapons().keySet()) {
            if (takenAt(hardpoint, Definitions.weapon(weapon))) {
                choices.add(weapon);
            }
        }

        return choices;
    }

    /**
     * そのパイロンに吊れる兵装か。機内のレールのように金具が既に決まっているステーションではその金具が、
     * 裸のパイロンでは付けられる金具のどれか1つが、その兵装を受けるかを問う。
     */
    private static boolean takenAt(AircraftDefinition.Hardpoint hardpoint, WeaponDefinition weapon) {
        if (hardpoint.rack().isPresent()) {
            return Definitions.rack(hardpoint.rack().get()).takes(weapon);
        }

        for (ResourceLocation candidate : ModItems.racks().keySet()) {
            RackDefinition rack = Definitions.rack(candidate);

            if (rack.wingtip() == hardpoint.wingtip() && rack.takes(weapon)) {
                return true;
            }
        }

        return false;
    }
}
