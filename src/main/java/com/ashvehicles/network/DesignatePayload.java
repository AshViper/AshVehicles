package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * パイロットが照準ポッドを何かに向けて指示する、あるいは指示を解除する操作。
 *
 * <p>{@link SwitchSeatPayload} と同じく機体を名指ししない。機体は送信者が飛ばしている物なので、要求が
 * マップの向こうの他人の機体に届くことはない。運ぶのはキーを押した瞬間にパイロットが見ていた物——地上の
 * 1点と、同じ視線が最初にぶつかった物があればその ID。
 *
 * <p><b>十字線の下に何があったかはクライアントが決め、それを保持してよいかはサーバーが決める。</b>
 * この分担が唯一誠実な形。ポッドがどこを向いているかはクライアントしか知らない。ポッドはマウスで振られ、
 * その照準はどこでもシミュレートされていないから。一方、指示するポッドが積まれているか、機体が指示できる
 * 状態かはサーバーの担当で、{@link AircraftEntity#designate} が両方を決める。点について嘘をつく
 * クライアントができるのは「飛んでいって見ることもできた地面」を指示することだけ——それは指示の定義その
 * もので、それ以上ではない。
 *
 * <p><b>点は推定でもよく、その場合はそう名乗る。</b> ポッドはクライアントが chunk を持たない距離で使う
 * ので、ロード済み範囲の外では点はブロック上に見えた物ではなく、仮定した地面高さから計算した物になる
 * （{@link com.ashvehicles.client.Terrain} 参照）。このフラグがその申告をサーバーへ運び、サーバーは
 * マーカーへ渡す。実際の地面が現れた時点でマークがそこへ降りられるように。
 * {@link com.ashvehicles.entity.DesignationEntity} 参照。
 *
 * @param clear 保持中の指示を解除するなら true。その場合、残りは無視される
 * @param point ポッドが見ている地上の点
 * @param entityId 同じ視線がぶつかった物の ID。何も無ければ -1
 * @param estimated 点が、見えたブロックではなくクライアントの計算による物か
 */
public record DesignatePayload(boolean clear, Vec3 point, int entityId, boolean estimated)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DesignatePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "designate"));

    /** どこも指定しない解除。 */
    public static final DesignatePayload CLEAR = new DesignatePayload(true, Vec3.ZERO, -1, false);

    public static final StreamCodec<RegistryFriendlyByteBuf, DesignatePayload> STREAM_CODEC =
            StreamCodec.of(DesignatePayload::write, DesignatePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, DesignatePayload payload) {
        buf.writeBoolean(payload.clear);
        buf.writeDouble(payload.point.x);
        buf.writeDouble(payload.point.y);
        buf.writeDouble(payload.point.z);
        ByteBufCodecs.VAR_INT.encode(buf, payload.entityId);
        buf.writeBoolean(payload.estimated);
    }

    private static DesignatePayload read(RegistryFriendlyByteBuf buf) {
        return new DesignatePayload(buf.readBoolean(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                ByteBufCodecs.VAR_INT.decode(buf), buf.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<DesignatePayload> type() {
        return TYPE;
    }

    public static void handle(DesignatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // ポッドはパイロットの計器。同じキーを押した搭乗者は搭乗者のままで、複座機の後席に仕事を
            // 与えるのは別の課題。VehicleEntityBase.getControllingPassenger 参照。
            if (!(context.player().getVehicle() instanceof AircraftEntity aircraft)
                    || aircraft.getControllingPassenger() != context.player()) {
                return;
            }

            if (payload.clear) {
                aircraft.clearDesignation();

                return;
            }

            Entity looked = payload.entityId < 0 ? null : aircraft.level().getEntity(payload.entityId);
            aircraft.designate(payload.point, looked, payload.estimated);
        });
    }
}
