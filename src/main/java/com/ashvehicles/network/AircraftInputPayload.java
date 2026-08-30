package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaternionf;

/**
 * 操縦しているクライアントが毎tick 送る。
 *
 * <p>位置・ヨー・ピッチはバニラの乗り物移動パケットで既にサーバーへ届くので、こちらはバニラが知らない
 * 状態を運ぶ。ペイロードはエンティティを名指ししない。サーバーは送信者が操縦している機体に適用するので、
 * 他人の機体を狙うことはできない。
 */
public record AircraftInputPayload(AircraftInput input, float throttle, float afterburner,
        Quaternionf attitude, Vec3 velocity, boolean crashed, boolean toggleGear, boolean toggleFlaps,
        boolean toggleVtol, boolean cycleWeapon, boolean jettison)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AircraftInputPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft_input"));

    public static final StreamCodec<FriendlyByteBuf, AircraftInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.input().write(buf);
                buf.writeFloat(payload.throttle());
                // スロットルの隣に置くのは、これがスロットルの一部だから。点火のゲートは同じレバーの
                // 可動域の一番上にあり、パイロットがそこを越えたかを知っているのは飛ばしている
                // クライアントだけ。AircraftEntity.tickAfterburner 参照。
                buf.writeFloat(payload.afterburner());
                buf.writeFloat(payload.attitude().x);
                buf.writeFloat(payload.attitude().y);
                buf.writeFloat(payload.attitude().z);
                buf.writeFloat(payload.attitude().w);
                // 機体が実際に出している速度。クライアントが飛ばしている間、サーバーはこれを自力では
                // 出せない。AircraftEntity.getVelocity 参照。
                buf.writeFloat((float) payload.velocity().x);
                buf.writeFloat((float) payload.velocity().y);
                buf.writeFloat((float) payload.velocity().z);
                buf.writeBoolean(payload.crashed());
                buf.writeBoolean(payload.toggleGear());
                buf.writeBoolean(payload.toggleFlaps());
                buf.writeBoolean(payload.toggleVtol());
                buf.writeBoolean(payload.cycleWeapon());
                // 増槽の投棄。他の単発操作と同じ形なのは、同じ物だからだ——押した瞬間に1度だけ起きる。
                buf.writeBoolean(payload.jettison());
            },
            buf -> new AircraftInputPayload(AircraftInput.read(buf), buf.readFloat(), buf.readFloat(),
                    new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<AircraftInputPayload> type() {
        return TYPE;
    }

    public static void handle(AircraftInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().getVehicle() instanceof AircraftEntity aircraft)) {
                return;
            }

            if (aircraft.getControllingPassenger() != context.player()) {
                return;
            }

            aircraft.setInput(payload.input());
            aircraft.setThrottle(payload.throttle());
            aircraft.reportAfterburner(payload.afterburner());
            aircraft.reportAttitude(payload.attitude());
            aircraft.setPilotVelocity(payload.velocity());

            if (payload.crashed()) {
                aircraft.reportCrash();
            }

            if (payload.toggleGear()) {
                aircraft.toggleGear();
            }

            if (payload.toggleVtol()) {
                aircraft.toggleVtol();
            }

            if (payload.toggleFlaps()) {
                aircraft.toggleFlaps();
            }

            if (payload.cycleWeapon()) {
                aircraft.cycleWeapon();
            }

            if (payload.jettison()) {
                aircraft.jettisonTanks();
            }
        });
    }
}
