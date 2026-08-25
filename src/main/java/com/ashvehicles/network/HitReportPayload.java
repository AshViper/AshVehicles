package com.ashvehicles.network;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.HitReadout;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One of the shooter's rounds arriving on a machine, and whereabouts on it.
 *
 * <p>Sent to the one player who fired it and to nobody else. None of it can be worked out on the
 * client: a round is flown on every client from the figure it was given at the muzzle, but where it
 * <em>stopped</em> is decided on the server against boxes the client is not testing against, and
 * whether the armour let it in or threw it off is decided there too. So the answer is sent, and it
 * is the only thing sent — the shooter learns what their own round did, which they would have seen
 * for themselves at fifty metres and cannot possibly see at eight hundred.
 *
 * <p><b>The place is held against the box rather than in the air.</b> A point in the world would be
 * stale by the time it was drawn — the target is still driving, and its turret is still traversing —
 * so what goes over the wire is which of the machine's boxes the round went into and how far across
 * that box it landed, as a fraction of each half-length. See {@link HitReadout}, which puts the box
 * back where it now is and the mark back on it.
 */
public record HitReportPayload(int target, ResourceLocation vehicle, int box, Vec3 within, Vec3 line,
        float traverse, float gunPitch, float damage, boolean bounced) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HitReportPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "hit_report"));

    public static final StreamCodec<FriendlyByteBuf, HitReportPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.target());
                buf.writeResourceLocation(payload.vehicle());
                // Shifted up by one so that the "no box at all" case is a zero rather than a varint
                // spending five bytes on a minus sign.
                buf.writeVarInt(payload.box() + 1);
                write(buf, payload.within());
                write(buf, payload.line());
                buf.writeFloat(payload.traverse());
                buf.writeFloat(payload.gunPitch());
                buf.writeFloat(payload.damage());
                buf.writeBoolean(payload.bounced());
            },
            buf -> new HitReportPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt() - 1,
                    read(buf), read(buf), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readBoolean()));

    private static void write(FriendlyByteBuf buf, Vec3 vector) {
        buf.writeFloat((float) vector.x);
        buf.writeFloat((float) vector.y);
        buf.writeFloat((float) vector.z);
    }

    private static Vec3 read(FriendlyByteBuf buf) {
        return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public CustomPacketPayload.Type<HitReportPayload> type() {
        return TYPE;
    }

    /**
     * Tells whoever fired a round what it found, if it was a player and what it found was a machine.
     *
     * <p>Called from both ends of a round's life against armour — the hit that went in and the one
     * that was thrown off — because the two are the same question answered differently, and a gunner
     * who is only told about the first cannot tell a ricochet from a miss.
     *
     * @param shooter whoever pulled the trigger, which is only a player some of the time
     * @param struck what the round found: one of a machine's boxes, or a machine itself
     * @param at where it struck, in the world
     * @param travel the way it was going when it got there
     * @param damage what it took off, or zero for a round the armour threw off
     */
    public static void report(@Nullable Entity shooter, Entity struck, Vec3 at, Vec3 travel,
            float damage, boolean bounced) {
        if (!(shooter instanceof ServerPlayer crew)) {
            return;
        }

        VehicleEntityBase machine;
        int slot = -1;
        Vec3 within;

        if (struck instanceof VehiclePart part && !part.isPylon()
                && part.getParent() instanceof VehicleEntityBase parent) {
            Hitbox box = part.hitbox();

            if (box == null) {
                return;
            }

            machine = parent;
            slot = part.getBox();
            // Clamped to the box's own faces. The game finds the hit against the upright box it
            // carries the part around in rather than against the plate as it is really lying, so a
            // graze on a steeply angled one can be reported a little outside the metal.
            within = clamp(box.within(at));
        } else if (struck instanceof VehicleEntityBase hulk) {
            machine = hulk;
            within = Attitude.toBody(machine.getAttitude(), at.subtract(machine.position()));
        } else {
            return;
        }

        float traverse = 0.0F;
        float gunPitch = 0.0F;

        if (machine instanceof GroundVehicleEntity vehicle) {
            traverse = vehicle.getTurretYaw(1.0F);
            gunPitch = vehicle.getGunPitch(1.0F);
        }

        Vec3 line = travel.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0)
                : Attitude.toBody(machine.getAttitude(), travel.normalize());

        PacketDistributor.sendToPlayer(crew, new HitReportPayload(machine.getId(), machine.getVehicleId(),
                slot, within, line, traverse, gunPitch, damage, bounced));
    }

    private static Vec3 clamp(Vec3 within) {
        return new Vec3(Mth.clamp(within.x, -1.0, 1.0), Mth.clamp(within.y, -1.0, 1.0),
                Mth.clamp(within.z, -1.0, 1.0));
    }

    /**
     * Registered as client-bound only, so this runs on a client and nowhere else; a dedicated server
     * never resolves {@link HitReadout}.
     */
    public static void handle(HitReportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> HitReadout.report(payload.target(), payload.vehicle(), payload.box(),
                payload.within(), payload.line(), payload.traverse(), payload.gunPitch(),
                payload.damage(), payload.bounced()));
    }
}
