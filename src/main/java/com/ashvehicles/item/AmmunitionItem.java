package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.weapon.AmmunitionDefinition;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 1種類の砲弾。徹甲弾、榴弾、成形炸薬弾——同じ砲へ入り、当たった先で違うことをする物。
 *
 * <p>持って停止中の車両を右クリックすれば、その弾種を受け付ける架台へ入る。受け付けるかどうかは車両
 * ファイルが並べた一覧が決めるので、120mm 砲の徹甲弾を 125mm 砲の戦車へ差し出しても入らない。それが
 * 正しい。{@code GroundVehicleDefinition.Armament#ammunition} 参照。
 *
 * <p><b>{@link AmmoItem} との違い。</b> あちらは「弾薬の<em>種別</em>」——砲弾かベルトか筒物か——を表す
 * 数個の定数で、どの砲にも入る汎用の補給だ。こちらはファイル1つで書かれた具体的な1弾種で、威力も初速も
 * 落ち方も自分で持っている。弾種を並べていない車両は今まで通りあちらで補給し、並べた車両はこちらしか
 * 受け取らない。どちらか一方だけを使う車両ばかりになるので、2つが同じ弾倉で混ざることは無い。
 *
 * <p><b>スタックには何も書かない。</b> {@link AmmoItem} と同じ理由で、1個は必ずその弾種の
 * {@link AmmunitionDefinition#perItem()} 相当。1個分に満たない空きしか無い弾倉はもう満載扱い。
 */
public class AmmunitionItem extends Item {
    private final ResourceLocation ammunition;

    public AmmunitionItem(ResourceLocation ammunition, Properties properties) {
        super(properties);
        this.ammunition = ammunition;
    }

    /** このアイテムがどの弾種ファイルか。 */
    public ResourceLocation getAmmunitionId() {
        return this.ammunition;
    }

    public AmmunitionDefinition getAmmunition() {
        return Definitions.ammunition(this.ammunition);
    }

    /**
     * 弾種の性能を、その弾を積むかどうか決める場所——インベントリの中——で読めるように出す。車両に
     * 入れてしまえば数値を見る手段はもう無く、そして「どちらの弾を積むか」はまさに数値で決める判断だ。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        AmmunitionDefinition round = this.getAmmunition();

        if (round.perItem() > 1) {
            lines.add(Component.translatable("tooltip.ashvehicles.rounds", round.perItem())
                    .withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.translatable("tooltip.ashvehicles.round_damage",
                Math.round(round.projectile().damage())).withStyle(ChatFormatting.GRAY));
        // 初速はブロック毎tickで持っているが、それを読める者はいない。秒速20倍がブロック毎秒で、
        // 兵装ファイルを書く者以外にとってはそちらが実際の速さだ。
        lines.add(Component.translatable("tooltip.ashvehicles.round_speed",
                Math.round(round.projectile().speed() * 20.0F)).withStyle(ChatFormatting.GRAY));

        if (round.projectile().explosion() > 0.0F) {
            lines.add(Component.translatable("tooltip.ashvehicles.round_explosion",
                    String.format("%.1f", round.projectile().explosion())).withStyle(ChatFormatting.GRAY));
        }

        // どの砲へ入るか。数値より先に読みたいのはこれで、種類の合わない弾は他の全部が良くても入らない。
        lines.add(Component.translatable("tooltip.ashvehicles.round_class_"
                + round.gunClass().getSerializedName()).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("tooltip.ashvehicles.ammunition").withStyle(ChatFormatting.DARK_GRAY));
    }
}
