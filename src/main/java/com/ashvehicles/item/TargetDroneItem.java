package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.registry.ModEntities;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * 標的ドローンの投入器。使えば1機が空へ上がり、自分の周りを回り始める。
 *
 * <p>置く場所を選ばせない——ブロックを狙う {@code useOn} ではなく空振りの {@code use} で動く——のは
 * 意図的だ。的の置き場所として意味があるのは「今いる場所の上空」だけで、輪の中心は使った本人になる。
 * 撃ちたい高度や地形が欲しければ、そこへ行ってから使えばいい。
 *
 * <p><b>スニークで使えば回収。</b> 自分が展開した物を、距離を問わず全機静かに消し、その分を返す。輪は
 * 150ブロック先を52m/sで回っており、手が届く物ではないので、道具の側に呼び戻しが要る。
 */
public class TargetDroneItem extends Item {
    /** 展開点。前方に少し投げ出すと「射出した」感じになり、自分の頭の中に湧かない。 */
    private static final double AHEAD = 4.0;
    private static final double ABOVE = 1.5;

    /** 連打で空を的だらけにしない程度の間。 */
    private static final int COOLDOWN_TICKS = 10;

    public TargetDroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player.isSecondaryUseActive()) {
            this.recall(server, player);

            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        this.launch(server, player);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        player.displayClientMessage(Component.translatable("message.ashvehicles.target_drone.deployed"), true);

        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** 1機を前方へ投げ出す。輪の中心は使った本人の位置の上空。 */
    private void launch(ServerLevel level, Player player) {
        TargetDroneEntity drone = new TargetDroneEntity(ModEntities.TARGET_DRONE.get(), level);
        Vec3 look = player.getLookAngle();
        Vec3 ahead = new Vec3(look.x, 0.0, look.z);

        ahead = ahead.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : ahead.normalize();

        drone.setPos(player.position().add(ahead.scale(AHEAD)).add(0.0, player.getEyeHeight() + ABOVE, 0.0));
        drone.setDeltaMovement(ahead.scale(0.6));
        drone.deploy(player.position().add(0.0, TargetDroneEntity.DEPLOY_CLIMB, 0.0), player.getUUID());
        level.addFreshEntity(drone);
    }

    /** 自分が展開した物を全機、距離を問わず消して返してもらう。他人の的には触れない。 */
    private void recall(ServerLevel level, Player player) {
        List<? extends TargetDroneEntity> drones = level.getEntities(
                EntityTypeTest.forClass(TargetDroneEntity.class), drone -> drone.ownedBy(player));

        if (drones.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.ashvehicles.target_drone.none"), true);

            return;
        }

        drones.forEach(TargetDroneEntity::recall);

        if (!player.getAbilities().instabuild) {
            ItemStack returned = new ItemStack(this, drones.size());

            if (!player.getInventory().add(returned)) {
                player.drop(returned, false);
            }
        }

        player.displayClientMessage(
                Component.translatable("message.ashvehicles.target_drone.recalled", drones.size()), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.target_drone").withStyle(ChatFormatting.GRAY));
    }
}
