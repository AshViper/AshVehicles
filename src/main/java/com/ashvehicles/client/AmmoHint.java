package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleEntity.Armament;
import com.ashvehicles.registry.ModItems;
import com.ashvehicles.weapon.GunClass;
import com.ashvehicles.weapon.Magazine;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 空になった砲を満たすのに、何を持ってくればよいか。
 *
 * <p><b>なぜ計器に要るのか。</b> 弾倉を満たす手段は「その弾を手に持って車両を右クリックする」ことだけで、
 * 入る物は砲ごとに違う。乗員が「弾が無い」と知らされても、次に何を取りに行くかは知らされない——そして
 * その答えは、車両ファイルが並べた弾種と兵装ファイルが名乗る種類から求まる物であって、覚えている物では
 * ない。戦車が徹甲弾を欲しがっているのか、それとも汎用の砲弾箱でよいのかは、計器が言わなければ試すしかない。
 *
 * <p>返すのはアイテムの表示名。ID でも種類名でもないのは、乗員がこれから探すのがインベントリの中の
 * <em>アイテム</em>だからだ。翻訳を通るので、日本語の計器では日本語の名前が出る。
 */
public final class AmmoHint {
    /**
     * 車両のその架台を満たすアイテム。分からなければ null で、そのときは計器も何も言わない。
     *
     * <p>弾種を積む架台では、今選んでいる弾種そのもの。並べていない架台では、その砲が受け取る汎用の
     * 弾薬箱。どちらを言うべきかは架台が既に知っている。
     */
    @Nullable
    public static Component forStation(GroundVehicleEntity vehicle, Armament station) {
        ResourceLocation round = Magazine.selected(vehicle, station);

        if (round != null) {
            return nameOf(ModItems.ammunition().get(round));
        }

        ResourceLocation gun = Magazine.weapon(vehicle, station);

        return gun == null ? null : forGun(gun);
    }

    /**
     * 内蔵の砲を満たす弾薬箱。砲塔の主砲も、同軸機銃も、発射筒も、機体の機関砲もこれで給弾される。
     *
     * <p><b>パイロンに吊る物には使わないこと。</b> あちらを満たすのは同じ兵装をもう1つ吊ることであって
     * 弾薬箱ではない（{@code WeaponMounts.reload} 参照）し、その名前は計器が既にその行に出している。
     * 発射筒の弾がまさにその区別で、{@code 57e6} のように吊り物として存在しないミサイルでも、筒に入る
     * のは対空ミサイルの箱だ。
     */
    @Nullable
    public static Component forGun(ResourceLocation weaponId) {
        WeaponDefinition weapon = Definitions.weapon(weaponId);
        GunClass takes = weapon.takes().orElse(null);

        if (takes != null) {
            ResourceLocation round = anyRoundFor(takes);

            if (round != null) {
                return nameOf(ModItems.ammunition().get(round));
            }
        }

        return weapon.ammoKind().map(kind -> nameOf(ModItems.ammo().get(kind))).orElse(null);
    }

    /**
     * その種類の砲に入る弾種を1つ。読み込まれている中で ID 順に最初の物。
     *
     * <p>空の砲が要求しているのは「この砲に入る何か」であって特定の1弾種ではない。3種類あるうちどれを
     * 出すかは選べないので、決まった順で1つ出す。乗員はそれを手掛かりに棚を見に行き、そこで残り2つも
     * 一緒に目に入る。
     */
    @Nullable
    private static ResourceLocation anyRoundFor(GunClass takes) {
        for (var loaded : Definitions.AMMUNITION.all().entrySet()) {
            if (loaded.getValue().gunClass() == takes) {
                return loaded.getKey();
            }
        }

        // まだサーバーから届いていないクライアントでは、MOD 同梱の一覧が同じ答えを持っている。
        for (var built : Definitions.AMMUNITION.builtIn().entrySet()) {
            if (built.getValue().gunClass() == takes) {
                return built.getKey();
            }
        }

        return null;
    }

    /**
     * そのアイテムの表示名。登録されていなければ null。
     *
     * <p>{@code "item": false} と書かれた弾種や兵装がそれに当たる。持ってこられない物の名前を出しても、
     * 乗員は探しに行けない。
     */
    @Nullable
    private static Component nameOf(@Nullable DeferredItem<? extends Item> item) {
        return item == null ? null : Component.translatable(item.get().getDescriptionId());
    }

    private AmmoHint() {
    }
}
