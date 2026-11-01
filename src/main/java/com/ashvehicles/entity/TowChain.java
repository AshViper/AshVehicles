package com.ashvehicles.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * 機体同士をつなぐ牽引の鎖。
 *
 * <p><b>付け方.</b> 鎖（バニラの {@code chain}）を持って1台目をクリックし、続けて2台目をクリックする。鎖は1本
 * 消費される。鋏で鎖のついた機体をクリックすると外れ、鎖が戻る。1台が引ける相手は1台、鎖は1台目（引く側）が
 * 持ち歩く。
 *
 * <p><b>引き方.</b> 鎖は縮まない紐で、張るまでは何もしない。両端の距離が長さを超えたら、離れていく速さを打ち
 * 消す一撃を質量比で2台に分ける——{@link VehicleCollisions} の衝突と同じ式の、向きが逆のもの。重い戦車は軽い
 * 車両を引きずり、同じ重さ同士なら引く側も重く引き戻される。一撃は端の点に掛かるので、引かれる車体は鎖の
 * 方へ向きを変える（{@code applyShove} のヨー）。車体を傾けはしない——倒れた車体を起こすのはレンチだ。
 *
 * <p>端の点は車体の中心から相手の方へ、大きさの4割だけ出た所、地面から1ブロック。鎖は描く側も同じ点を使う。
 */
public final class TowChain {
    /** 鎖の長さ（ブロック）。 */
    public static final double LENGTH = 8.0;
    /** これを超えて伸びたら切れる。地形に引っ掛かった・テレポートした。 */
    private static final double SNAP = LENGTH * 3.0;
    /** 伸びすぎた分を1tickで戻す割合。速さを打ち消すだけでは少しずつ伸びていく。 */
    private static final double CORRECTION = 0.2;
    private static final double ANCHOR_HEIGHT = 1.0;

    /** 鎖を持って1台目をクリックした者と、その機体。2台目のクリックを待っている。サーバー側。 */
    private static final Map<UUID, UUID> PENDING = new HashMap<>();

    private TowChain() {
    }

    /**
     * 機体のクリック。鎖か鋏を持っていなければ null を返し、クリックは通常の意味を持つ。サーバー側。
     */
    @Nullable
    static InteractionResult interact(VehicleEntityBase machine, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        if (held.is(Items.SHEARS) && (machine.getTowing() != null || towedBy(machine) != null)) {
            VehicleEntityBase owner = machine.getTowing() != null ? machine : towedBy(machine);
            owner.setTowing(null);
            player.getInventory().placeItemBackInInventory(new ItemStack(Items.CHAIN));
            machine.level().playSound(null, machine.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL,
                    1.0F, 1.0F);

            return InteractionResult.CONSUME;
        }

        if (!held.is(Items.CHAIN)) {
            return null;
        }

        UUID first = PENDING.get(player.getUUID());

        if (first == null || first.equals(machine.getUUID())
                || !(((ServerLevel) machine.level()).getEntity(first) instanceof VehicleEntityBase puller)
                || puller.isRemoved() || puller.distanceTo(machine) > SNAP) {
            PENDING.put(player.getUUID(), machine.getUUID());
            player.displayClientMessage(Component.translatable("message.ashvehicles.chain.first"), true);
            machine.level().playSound(null, machine.blockPosition(), SoundEvents.CHAIN_HIT, SoundSource.NEUTRAL,
                    1.0F, 1.0F);

            return InteractionResult.CONSUME;
        }

        PENDING.remove(player.getUUID());
        // 引く側が既に別の車両を引いていれば、その鎖は外して戻す。
        if (puller.getTowing() != null) {
            player.getInventory().placeItemBackInInventory(new ItemStack(Items.CHAIN));
        }

        puller.setTowing(machine);
        held.consume(1, player);
        player.displayClientMessage(Component.translatable("message.ashvehicles.chain.linked"), true);
        machine.level().playSound(null, machine.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.NEUTRAL,
                1.0F, 1.0F);

        return InteractionResult.CONSUME;
    }

    /** 引く側の1tick。張っていれば2台を引き合わせる。サーバー側。 */
    static void tick(VehicleEntityBase puller) {
        if (!(puller.level() instanceof ServerLevel level) || puller.getTowing() == null) {
            return;
        }

        Entity found = level.getEntity(puller.getTowing());

        if (!(found instanceof VehicleEntityBase towed) || towed.isRemoved()) {
            // まだロードされていないだけかもしれない。持ち続けて、描く側には居ないと伝える。
            puller.publishTowed(-1);

            return;
        }

        puller.publishTowed(towed.getId());

        Vec3 a = anchor(puller, towed.position(), 1.0F);
        Vec3 b = anchor(towed, puller.position(), 1.0F);
        Vec3 span = b.subtract(a);
        double length = span.length();

        if (length > SNAP) {
            puller.setTowing(null);
            level.playSound(null, puller.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL, 1.5F, 0.7F);
            puller.spawnAtLocation(new ItemStack(Items.CHAIN));

            return;
        }

        if (length <= LENGTH || length < 1.0E-6) {
            return;
        }

        Vec3 n = span.scale(1.0 / length);
        // 離れていく速さと、伸びすぎた分。両方を打ち消す。
        double separating = towed.getVelocity().subtract(puller.getVelocity()).dot(n);
        double wanted = Math.max(separating, 0.0) + (length - LENGTH) * CORRECTION;
        double m1 = puller.mass();
        double m2 = towed.mass();
        double impulse = wanted / (1.0 / m1 + 1.0 / m2);

        // 鎖は向きを変えるだけで、車体を傾けも起こしもしない。倒れた車両を起こすのはレンチ。
        puller.shove(n.scale(impulse / m1), a, false);
        towed.shove(n.scale(-impulse / m2), b, false);
    }

    /** 鎖の端。中心から相手の方へ大きさの4割、地面から1ブロック。 */
    public static Vec3 anchor(VehicleEntityBase machine, Vec3 toward, float partialTick) {
        Vec3 centre = machine.getPosition(partialTick);
        Vec3 flat = new Vec3(toward.x - centre.x, 0.0, toward.z - centre.z);
        Vec3 out = flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : flat.normalize().scale(machine.reach() * 0.4);

        return centre.add(out).add(0.0, ANCHOR_HEIGHT, 0.0);
    }

    /** この機体を引いている機体。近くだけを探す。 */
    @Nullable
    private static VehicleEntityBase towedBy(VehicleEntityBase machine) {
        for (Entity entity : machine.level().getEntities(machine, machine.getBoundingBox().inflate(SNAP))) {
            if (entity instanceof VehicleEntityBase other && machine.getUUID().equals(other.getTowing())) {
                return other;
            }
        }

        return null;
    }
}
