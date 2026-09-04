package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleEntity.Armament;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 1つの架台が積んでいる弾を、種類ごとに。
 *
 * <p><b>これが要る理由。</b> 戦車が積むのは「40発」ではなく「徹甲弾20発と榴弾15発と成形炸薬弾5発」だ。
 * 装填手が薬室へ送るのは車長が名指しした1種で、他の種類はその間ずっとラックにある。数える対象が1つから
 * 種類の数だけに増えるのがこの仕組みの全部で、増えたのはそこだけになるようにしてある。
 *
 * <p><b>収容は種類ごとではなく合計で決まる。</b> 車両ファイルが弾種を3つ並べても、砲が積めるのは兵装
 * ファイルの {@code ammo} が言う発数のまま。ラックは1つで、そこへ何をどれだけ入れるかを積む側が決める。
 * 徹甲弾だけ40発の戦車も、3種を均等に積んだ戦車も同じように成立する——実物の弾薬搭載がまさにそれだ。
 *
 * <p><b>置き場所。</b> 車両の同期データにある {@link GroundVehicleEntity#getMagazineTag()} ——架台ごとに
 * 「今選んでいる弾種」と「種類ごとの発数」を持つタグ1つ——と、既存の残弾カウンタ。カウンタには常に
 * <em>選択中の弾種</em>の発数を映す。だから計器も、装填の判定も、発砲の可否も、弾種を知らないまま今まで
 * 通りの1つの数を読み続けられる。書き込み口を {@link #store} 1つに絞ってあるので、2つの値がずれる場所は
 * 存在しない。
 *
 * <p><b>弾種を並べていない架台では何もしない。</b> MOD 内の大半の車両がそれで、そこではタグは空のまま、
 * 残弾カウンタが従来通り唯一の値になる。{@link #types} が空を返す架台に対しては、このクラスの全メソッドが
 * 「弾種など無かった」場合の答えを返す。
 */
public final class Magazine {
    /** 選択中の弾種を、架台のタグの中で何と呼ぶか。 */
    private static final String SELECTED = "Selected";

    /**
     * その架台が受け付ける弾種。並べていなければ空で、そこは弾種を持たない架台。
     *
     * <p>並べてあっても、砲の種類に合わない弾はここに現れない。戦車砲へ機関砲弾を並べた車両ファイルは、
     * その行が無かったかのように動く——弾倉に入らない弾を切り替えで選べてしまう方が、静かに消えるより
     * たちが悪い。砲が種類を名乗っていなければ制限は無く、並んだ物がそのまま全部入る。
     */
    public static List<ResourceLocation> types(GroundVehicleEntity vehicle, Armament station) {
        List<ResourceLocation> listed = listed(vehicle, station);

        if (listed.isEmpty()) {
            return listed;
        }

        ResourceLocation gun = weapon(vehicle, station);
        GunClass takes = gun == null ? null : Definitions.weapon(gun).takes().orElse(null);

        if (takes == null) {
            return listed;
        }

        // 大半の車両ファイルは正しく書かれている。合っている限り元の一覧をそのまま返し、写しを作らない
        // ——この呼び出しは計器の毎フレームと発砲の毎tickに乗っている。
        for (ResourceLocation type : listed) {
            if (Definitions.ammunition(type).gunClass() != takes) {
                return fitting(listed, takes, gun);
            }
        }

        return listed;
    }

    /** 車両ファイルがその架台に並べた弾種。大きさで絞る前の、書いてある通りの一覧。 */
    private static List<ResourceLocation> listed(GroundVehicleEntity vehicle, Armament station) {
        return switch (station) {
            case MAIN -> vehicle.getStats().armament().ammunition();
            case COAX -> vehicle.getStats().coaxial().ammunition();
            case MISSILE -> vehicle.getStats().launcher().ammunition();
        };
    }

    /** その架台の兵装。積んでいなければ null。 */
    @Nullable
    public static ResourceLocation weapon(GroundVehicleEntity vehicle, Armament station) {
        return (switch (station) {
            case MAIN -> vehicle.getStats().armament().main();
            case COAX -> vehicle.getStats().coaxial().gun();
            case MISSILE -> vehicle.getStats().launcher().missile();
        }).orElse(null);
    }

    /**
     * 大きさの合う弾種だけ。合わない物は1度だけログに残す。
     *
     * <p>黙って消すとパックの作者には理由が分からない。毎tick叫ぶのも同じくらい役に立たないので、
     * 砲と弾の組ごとに1度。
     */
    private static List<ResourceLocation> fitting(List<ResourceLocation> listed, GunClass takes,
            ResourceLocation gun) {
        List<ResourceLocation> fits = new ArrayList<>(listed.size());

        for (ResourceLocation type : listed) {
            if (Definitions.ammunition(type).gunClass() == takes) {
                fits.add(type);
            } else if (COMPLAINED.add(gun + " " + type)) {
                AshVehicles.LOGGER.error("Ammunition {} is for a {}, but {} is a {}; it will not load",
                        type, Definitions.ammunition(type).gunClass().getSerializedName(), gun,
                        takes.getSerializedName());
            }
        }

        return fits;
    }

    /** 既に文句を言った砲と弾の組。{@link #fitting} 参照。 */
    private static final Set<String> COMPLAINED = ConcurrentHashMap.newKeySet();

    /** その架台が弾種を持つか。持たない架台は残弾カウンタ1つで足りる。 */
    public static boolean typed(GroundVehicleEntity vehicle, Armament station) {
        return !types(vehicle, station).isEmpty();
    }

    /**
     * 今その架台の薬室に送られる弾種。弾種を持たない架台では null。
     *
     * <p>保存された値をそのまま信じない。理由は {@link GroundVehicleEntity#selected()} と同じで、車両
     * ファイルが書き換われば、以前選ばれていた弾種はもうその架台の一覧に無い。読めなければ一覧の先頭
     * ——それがその砲の既定弾だ。
     */
    @Nullable
    public static ResourceLocation selected(GroundVehicleEntity vehicle, Armament station) {
        List<ResourceLocation> types = types(vehicle, station);

        if (types.isEmpty()) {
            return null;
        }

        ResourceLocation stored = ResourceLocation.tryParse(
                vehicle.getMagazineTag().getCompound(station.name()).getString(SELECTED));

        return stored != null && types.contains(stored) ? stored : types.get(0);
    }

    /** その架台のその弾種が今何発あるか。 */
    public static int rounds(GroundVehicleEntity vehicle, Armament station, ResourceLocation type) {
        return vehicle.getMagazineTag().getCompound(station.name()).getInt(type.toString());
    }

    /**
     * 今すぐ撃てる発数。弾種を持つ架台では選択中の弾種の分、持たない架台では残弾カウンタそのもの。
     *
     * <p>発砲の可否を決めるのはこの値だ。榴弾しか残っていない戦車で徹甲弾を選んでいれば撃てない——
     * 実際にそうなるべきで、砲手は弾種を切り替えることになる。
     */
    public static int ready(GroundVehicleEntity vehicle, Armament station) {
        return vehicle.getRounds(station);
    }

    /** その架台が積んでいる全弾種の合計。収容の空きはここから求める。 */
    public static int total(GroundVehicleEntity vehicle, Armament station) {
        List<ResourceLocation> types = types(vehicle, station);

        if (types.isEmpty()) {
            return vehicle.getRounds(station);
        }

        CompoundTag mount = vehicle.getMagazineTag().getCompound(station.name());
        int total = 0;

        for (ResourceLocation type : types) {
            total += mount.getInt(type.toString());
        }

        return total;
    }

    /**
     * その架台のその弾種を、その発数にする。
     *
     * <p>唯一の書き込み口。タグを書いた後で残弾カウンタへ選択中の弾種を映すところまでが1回の操作なので、
     * 2つの値が食い違ったまま残る経路が無い。
     */
    public static void store(GroundVehicleEntity vehicle, Armament station, ResourceLocation type, int rounds) {
        CompoundTag tag = vehicle.getMagazineTag().copy();
        CompoundTag mount = tag.getCompound(station.name());

        mount.putInt(type.toString(), Math.max(rounds, 0));
        tag.put(station.name(), mount);
        vehicle.setMagazineTag(tag);
        mirror(vehicle, station);
    }

    /** 弾種を切り替える。一覧に無い物は受け付けない。 */
    public static void select(GroundVehicleEntity vehicle, Armament station, ResourceLocation type) {
        if (!types(vehicle, station).contains(type)) {
            return;
        }

        CompoundTag tag = vehicle.getMagazineTag().copy();
        CompoundTag mount = tag.getCompound(station.name());

        mount.putString(SELECTED, type.toString());
        tag.put(station.name(), mount);
        vehicle.setMagazineTag(tag);
        mirror(vehicle, station);
    }

    /**
     * その架台の一覧で、今の弾種の次にある物。最後まで来ていれば null。
     *
     * <p>null が「この架台の弾種は撃ち尽くした」の合図になり、切り替えはそこで次の架台へ移る。
     * {@link GroundVehicleEntity#cycleWeapon()} 参照。
     */
    @Nullable
    public static ResourceLocation next(GroundVehicleEntity vehicle, Armament station) {
        List<ResourceLocation> types = types(vehicle, station);
        ResourceLocation now = selected(vehicle, station);

        if (now == null) {
            return null;
        }

        int at = types.indexOf(now);

        return at >= 0 && at + 1 < types.size() ? types.get(at + 1) : null;
    }

    /** その架台を一覧の先頭の弾種へ戻す。切り替えが架台へ入り直すたびに呼ぶ。 */
    public static void rewind(GroundVehicleEntity vehicle, Armament station) {
        List<ResourceLocation> types = types(vehicle, station);

        if (!types.isEmpty()) {
            select(vehicle, station, types.get(0));
        }
    }

    /**
     * 差し出された弾薬アイテムを積み込み、実際に受け取った<em>個数</em>を返す。
     *
     * <p>丸ごとか無しかは {@link BuiltInGun#load} の規則そのまま。空きは架台の収容から全弾種の合計を
     * 引いた分で、種類ごとの上限は無い——ラックは1つだ。
     *
     * @param capacity その架台の収容発数。兵装ファイルの {@code ammo}
     * @param offered 手にある個数
     * @return 積み込んだ個数。0 なら満載か、そもそもこの架台に入らない弾種
     */
    public static int load(GroundVehicleEntity vehicle, Armament station, ResourceLocation type,
            int capacity, int offered) {
        if (!types(vehicle, station).contains(type) || offered <= 0) {
            return 0;
        }

        int perItem = Definitions.ammunition(type).perItem();
        int taken = Math.min(offered, (capacity - total(vehicle, station)) / perItem);

        if (taken <= 0) {
            return 0;
        }

        store(vehicle, station, type, rounds(vehicle, station, type) + taken * perItem);

        return taken;
    }

    /**
     * 撃った分を減らす。弾種を持つ架台では選択中の弾種から、持たない架台では残弾カウンタから。
     *
     * <p>撃つ側が「どちらの車両か」を気にせずに済むように、両方をここで飲み込む。
     */
    public static void spend(GroundVehicleEntity vehicle, Armament station, int rounds) {
        ResourceLocation type = selected(vehicle, station);

        if (type == null) {
            vehicle.setRounds(station, vehicle.getRounds(station) - rounds);

            return;
        }

        store(vehicle, station, type, rounds(vehicle, station, type) - rounds);
    }

    /**
     * 選択中の弾種の発数を残弾カウンタへ映す。
     *
     * <p>これがあるおかげで、計器も装填の判定も発砲の可否も、弾種という概念を一切知らないまま今まで
     * 通りの1つの数を読み続けられる。
     */
    private static void mirror(GroundVehicleEntity vehicle, Armament station) {
        ResourceLocation type = selected(vehicle, station);

        if (type != null) {
            vehicle.setRounds(station, rounds(vehicle, station, type));
        }
    }

    /**
     * セーブから戻ってきた車両のタグを受け取る。
     *
     * <p><b>弾種より前に保存された車両。</b> 数だけがあって内訳が無い。そこにある弾は一覧の先頭——その砲の
     * 既定弾——として戻す。空の戦車で帰ってくるより親切で、{@code BuiltInGun.load} が「弾倉より前に保存
     * された車両を満載で戻す」のと同じ種類の推測だ。
     */
    public static void restore(GroundVehicleEntity vehicle, CompoundTag tag) {
        vehicle.setMagazineTag(tag.getCompound("Magazine"));

        for (Armament station : Armament.VALUES) {
            List<ResourceLocation> types = types(vehicle, station);

            if (types.isEmpty()) {
                continue;
            }

            if (total(vehicle, station) <= 0 && vehicle.getRounds(station) > 0) {
                store(vehicle, station, types.get(0), vehicle.getRounds(station));
            }

            mirror(vehicle, station);
        }
    }

    public static void save(GroundVehicleEntity vehicle, CompoundTag tag) {
        tag.put("Magazine", vehicle.getMagazineTag());
    }

    private Magazine() {
    }
}
