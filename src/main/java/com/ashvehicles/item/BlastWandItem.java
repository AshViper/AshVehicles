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
 * <p>そして、地形を壊さないからこそ規模を {@link Effects#LARGEST} まで開けている。兵装が使うのは
 * {@link Effects#BIGGEST}（12）までだが、この棒はその先——キノコ雲が立つ領域——を見るためにある。もし本物の
 * 爆発を起こしていたら、255 は「クレーター」ではなく「数秒固まるサーバー」を意味していた。
 *
 * <p><b>スニークで使えばスライダー。</b> 規模はスタックに持たせるので、棒を2本持てば2つの規模を並べて
 * 比べられる。{@link com.ashvehicles.client.screen.BlastWandScreen} 参照。
 */
public class BlastWandItem extends Item {
    /** 選べる規模の下限と上限。 */
    public static final int LEAST = 1;
    public static final int MOST = (int) Effects.LARGEST;

    /**
     * 何も選んでいない棒の規模。
     *
     * <p>中型爆弾のあたり。ロケット弾では衝撃波が小さすぎて何を作ったのか分からず、最大級ではその1発で
     * 画面が埋まる。最初に振ったときに「爆発だ」と分かる大きさ。
     */
    private static final int STARTING_POWER = 6;

    /**
     * 規模を持たせるタグ。
     *
     * <p>かつてここには段階の<em>番号</em>（0〜3）が入っていた。今入っているのは規模そのものなので、名前も
     * 変えてある——古い棒の 3 が規模3として読まれるのは害が無いが、名前が同じままだと次に読む人が番号と規模の
     * どちらなのか調べる羽目になる。
     */
    private static final String SCALE_TAG = "blast_scale";

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

        if (player.isSecondaryUseActive()) {
            if (level.isClientSide) {
                // 画面はクライアントにしか無い。専用サーバーがこの分岐へ入ることは無いので、ここから先の
                // クラスがサーバーで解決されることもない。BlastSoundPayload が音でやっているのと同じ形。
                com.ashvehicles.client.screen.BlastWandScreen.open(power(stack));
            }

            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        this.detonate(server, player, power(stack));
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** 視線の先の地面（無ければ射程いっぱいの空中）で、本物の着弾と同じ演出を起こす。 */
    private void detonate(ServerLevel level, Player player, int power) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 at = hit.getLocation();

        if (hit.getType() == HitResult.Type.BLOCK) {
            at = at.add(eye.subtract(at).normalize().scale(OFF_THE_FACE));
        }

        Effects.detonate(level, at, power, Effects.EMBER);
    }

    /** この棒に据わっている規模。 */
    public static int power(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);

        if (data == null || !data.copyTag().contains(SCALE_TAG)) {
            return STARTING_POWER;
        }

        return Mth.clamp(data.copyTag().getInt(SCALE_TAG), LEAST, MOST);
    }

    /** スライダーが決めた規模を据える。{@link com.ashvehicles.network.BlastPowerPayload} から。 */
    public static void setPower(ItemStack stack, int power) {
        int held = Mth.clamp(power, LEAST, MOST);

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(SCALE_TAG, held));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.blast_wand", power(stack))
                .withStyle(ChatFormatting.GRAY));
    }
}
