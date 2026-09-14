package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleInput;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaternionf;

/**
 * 運転しているクライアントが毎tick 送る。
 *
 * <p>位置・ヨー・ピッチはバニラの乗り物移動パケットで既に届くので、こちらはバニラが知らない状態——車体の
 * ロール、実際の速度、砲塔の向き——を運ぶ。ペイロードはエンティティを名指ししない。サーバーは送信者が
 * 運転している車両に適用するので、他人の戦車を狙うことはできない。
 */
public record GroundVehicleInputPayload(GroundVehicleInput input, Quaternionf attitude, float speed,
        float turretYaw, float gunPitch, int cycleWeapon, float sightTilt) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GroundVehicleInputPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "vehicle_input"));

    public static final StreamCodec<FriendlyByteBuf, GroundVehicleInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.input().write(buf);
                buf.writeFloat(payload.attitude().x);
                buf.writeFloat(payload.attitude().y);
                buf.writeFloat(payload.attitude().z);
                buf.writeFloat(payload.attitude().w);
                buf.writeFloat(payload.speed());
                buf.writeFloat(payload.turretYaw());
                buf.writeFloat(payload.gunPitch());
                buf.writeByte(payload.cycleWeapon());
                buf.writeFloat(payload.sightTilt());
            },
            buf -> new GroundVehicleInputPayload(GroundVehicleInput.read(buf),
                    new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readByte(), buf.readFloat()));

    @Override
    public CustomPacketPayload.Type<GroundVehicleInputPayload> type() {
        return TYPE;
    }

    public static void handle(GroundVehicleInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().getVehicle() instanceof GroundVehicleEntity vehicle)) {
                return;
            }

            if (vehicle.getControllingPassenger() != context.player()) {
                return;
            }

            vehicle.setInput(payload.input());
            // 運転手が覗いている視界の倒し角。主砲塔は運転しているクライアントが自分で使うが、独立砲塔を
            // 据えているのはサーバーなので、こちらへ届かないと1人で乗っている運転手の砲塔だけが三人称の
            // 倒し角の分だけ低く狙う。{@code TurretStations.aim} 参照。
            vehicle.setSightTilt(payload.sightTilt());
            vehicle.reportState(payload.attitude(), payload.speed(), payload.turretYaw(), payload.gunPitch());

            if (payload.cycleWeapon() != 0) {
                vehicle.cycleWeapon(payload.cycleWeapon());
            }
        });
    }
}
