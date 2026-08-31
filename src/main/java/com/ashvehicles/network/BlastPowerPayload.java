package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.item.BlastWandItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 試験棒に据えた爆発規模。スライダーを閉じたときに1度だけ送る。
 *
 * <p>スライダーはクライアントにしか無いが、爆発を起こすのはサーバーだ。つまり選んだ値はどこかで一度、
 * 線を越えなければならない。持たせる先はプレイヤーではなくスタックなので、棒を2本持てば2つの規模を並べて
 * 比べられるし、置いた棒は置いたときの値を覚えている。
 *
 * <p>書き込む相手は<b>今その手に持っている試験棒</b>だけに限る。任意のスロットの任意のアイテムに任意の値を
 * 書ける口にはしない——クライアントから来た数値でサーバー側のデータを触る以上、触れる範囲は狭いほどよい。
 * 値そのものも受け取った側で改めて範囲に収める。
 */
public record BlastPowerPayload(int power) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlastPowerPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "blast_power"));

    public static final StreamCodec<FriendlyByteBuf, BlastPowerPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeVarInt(payload.power()),
            buf -> new BlastPowerPayload(buf.readVarInt()));

    @Override
    public CustomPacketPayload.Type<BlastPowerPayload> type() {
        return TYPE;
    }

    public static void handle(BlastPowerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();

            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);

                if (stack.getItem() instanceof BlastWandItem) {
                    BlastWandItem.setPower(stack, payload.power());

                    return;
                }
            }
        });
    }
}
