package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.particle.Effects;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 爆発演出のテスト用の棒。使えば視線の先で火球・炸裂音・衝撃波が起きる。
 *
 * <p>起こすのは演出だけで、{@link Effects#blast} は呼ばない。地形もエンティティも無傷なのは、この棒の
 * 用途が「衝撃波の見え方を好きな場所で何度も眺めること」だからだ。本物の爆発で試すと、2発目には試験場が
 * クレーターになっていて、波が走るはずだった地面が無い。
 *
 * <p><b>スニークで使えば規模の切り替え。</b> ロケット弾から大型爆弾まで4段階を巡回する。規模はスタックに
 * 持たせるので、棒を2本持てば2つの規模を並べて比べられる。
 */
public class BlastWandItem extends Item {
    /** 巡回する爆発規模。ロケット弾、小型爆弾、中型爆弾、そして描画上限の大型弾頭。 */
    private static final float[] POWERS = {1.0F, 3.0F, 6.0F, Effects.BIGGEST};
    /** 切り替える前の初期位置。小型爆弾から始める。 */
    private static final int STARTING_INDEX = 1;
    private static final String POWER_TAG = "blast_power";

    /**
     * 狙える距離。爆撃の照準距離ではなく、地上で眺めて歩く道具なので、確実にロード済みの範囲に収める。
     * サーバー側のクリップは未ロードチャンクの先を知らない。
     */
    private static final double RANGE = 160.0;
    /** 当たった面から手前へ引く距離。演出をブロックの中ではなく表面で起こすため。 */
    private static final double OFF_THE_FACE = 0.25;

    /** 連打しても前の波が読める程度の間。 */
    private static final int COOLDOWN_TICKS = 10;

    public BlastWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player.isSecondaryUseActive()) {
            this.cyclePower(stack, player);

            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        this.detonate(server, player, power(stack));
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** 視線の先の地面（無ければ射程いっぱいの空中）で、本物の着弾と同じ3点セットを起こす。 */
    private void detonate(ServerLevel level, Player player, float power) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 at = hit.getLocation();

        if (hit.getType() == HitResult.Type.BLOCK) {
            at = at.add(eye.subtract(at).normalize().scale(OFF_THE_FACE));
        }

        Effects.fireball(level, at, power, Effects.EMBER);
        Effects.boom(level, at, power);
        Effects.wave(level, at, power);
    }

    /** 次の規模へ。最大まで行ったら最小へ戻り、今の値をアクションバーに出す。 */
    private void cyclePower(ItemStack stack, Player player) {
        int next = (powerIndex(stack) + 1) % POWERS.length;

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(POWER_TAG, next));
        player.displayClientMessage(
                Component.translatable("message.ashvehicles.blast_wand.power", POWERS[next]), true);
    }

    private static float power(ItemStack stack) {
        return POWERS[powerIndex(stack)];
    }

    private static int powerIndex(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);

        return data == null ? STARTING_INDEX
                : Mth.clamp(data.copyTag().getInt(POWER_TAG), 0, POWERS.length - 1);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.blast_wand", power(stack))
                .withStyle(ChatFormatting.GRAY));
    }
}
