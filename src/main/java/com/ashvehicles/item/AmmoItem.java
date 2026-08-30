package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.weapon.AmmoKind;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 火砲の補給元。主砲なら砲弾、機関砲ならベルト。
 *
 * <p>車両の弾庫に入れておけば、停止中に乗員が積み込む（{@code BuiltInGun.resupply} 参照）。弾庫が空の
 * 車両は装填済みの分だけ持って出て、空で帰ってくる。それがこの仕組みの狙いで、戦車が撃てるのは誰かが
 * 積んだ分だけ——機体が翼下に積む物と全く同じ考え方。
 *
 * <p><b>スタックには何も書かない。</b> 砲弾は砲弾、ベルトはベルトなので、1個は必ずその種類の
 * {@link AmmoKind#roundsPerItem()} 相当で、端数にはならない。乗員は1個丸ごと取るか取らないかで、
 * 1個分に満たない空きしか無い弾倉はもう満載扱い。これがただのスタック可能アイテムでいられる理由で、
 * 弾薬箱はそうあってほしい。対極が機体の搭載兵装で、あちらはポッドが半分減って帰ってくるし、それを
 * 覚えておく必要が本当にある。
 */
public class AmmoItem extends Item {
    private final AmmoKind kind;

    public AmmoItem(AmmoKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    /** どの火砲に供給するか。 */
    public AmmoKind getKind() {
        return this.kind;
    }

    /** そのスタックが指定種類の弾薬か。弾庫を探すときの条件。 */
    public static boolean isKind(ItemStack stack, AmmoKind kind) {
        return stack.getItem() instanceof AmmoItem ammo && ammo.kind == kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        // 砲弾は1発なので、砲弾に「1発」と書いても誰も新しいことを知らない。
        if (this.kind.roundsPerItem() > 1) {
            lines.add(Component.translatable("tooltip.ashvehicles.rounds", this.kind.roundsPerItem())
                    .withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.translatable("tooltip.ashvehicles.ammo_" + this.kind.getSerializedName())
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
