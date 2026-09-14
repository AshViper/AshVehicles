package com.ashvehicles.network;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.AiDebugView;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 戦闘 AI の可視化。近くの AI の今の姿と、脅威マップの近くのマス。{@code /tdm ai debug} を開いた運営にだけ届く
 * （{@code ai/debug/AiDebug}）。
 *
 * <p><b>判断には使わない写し。</b> クライアントはこれを描くだけ（{@link AiDebugView}）。
 */
public record AiDebugPayload(List<Machine> machines, List<Cell> cells, int cellSize) implements CustomPacketPayload {
    /** 何も描かない。可視化を閉じたときに送る。 */
    public static final AiDebugPayload EMPTY = new AiDebugPayload(List.of(), List.of(), 8);

    /**
     * AI 1両。
     *
     * @param tacticalKind 探した点の種類（{@code TacticalPosition.Kind} の序数）。無ければ -1
     */
    public record Machine(int entityId, String label, int colour, String role, String action, String objective,
            String target, int targetId, float threat, float routeRisk, float health, String version, Vec3 at,
            @Nullable Vec3 destination, @Nullable Vec3 tactical, int tacticalKind, List<Vec3> route) {
    }

    /** 脅威マップの1マス。座標はマスの隅（ブロック）。 */
    public record Cell(int x, int z, float danger, float exposure) {
    }

    public static final CustomPacketPayload.Type<AiDebugPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "ai_debug"));

    public static final StreamCodec<FriendlyByteBuf, AiDebugPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.machines().size());

                for (Machine machine : payload.machines()) {
                    buf.writeVarInt(machine.entityId());
                    buf.writeUtf(machine.label());
                    buf.writeInt(machine.colour());
                    buf.writeUtf(machine.role());
                    buf.writeUtf(machine.action());
                    buf.writeUtf(machine.objective());
                    buf.writeUtf(machine.target());
                    buf.writeVarInt(machine.targetId() + 1);
                    buf.writeFloat(machine.threat());
                    buf.writeFloat(machine.routeRisk());
                    buf.writeFloat(machine.health());
                    buf.writeUtf(machine.version());
                    writeVec(buf, machine.at());
                    writeOptionalVec(buf, machine.destination());
                    writeOptionalVec(buf, machine.tactical());
                    buf.writeVarInt(machine.tacticalKind() + 1);
                    buf.writeVarInt(machine.route().size());

                    for (Vec3 point : machine.route()) {
                        buf.writeFloat((float) point.x);
                        buf.writeFloat((float) point.z);
                    }
                }

                buf.writeVarInt(payload.cells().size());

                for (Cell cell : payload.cells()) {
                    buf.writeVarInt(cell.x());
                    buf.writeVarInt(cell.z());
                    buf.writeFloat(cell.danger());
                    buf.writeFloat(cell.exposure());
                }

                buf.writeVarInt(payload.cellSize());
            },
            buf -> {
                int count = buf.readVarInt();
                List<Machine> machines = new ArrayList<>(count);

                for (int at = 0; at < count; at++) {
                    int entityId = buf.readVarInt();
                    String label = buf.readUtf();
                    int colour = buf.readInt();
                    String role = buf.readUtf();
                    String action = buf.readUtf();
                    String objective = buf.readUtf();
                    String target = buf.readUtf();
                    int targetId = buf.readVarInt() - 1;
                    float threat = buf.readFloat();
                    float routeRisk = buf.readFloat();
                    float health = buf.readFloat();
                    String version = buf.readUtf();
                    Vec3 position = readVec(buf);
                    Vec3 destination = readOptionalVec(buf);
                    Vec3 tactical = readOptionalVec(buf);
                    int kind = buf.readVarInt() - 1;
                    int points = buf.readVarInt();
                    List<Vec3> route = new ArrayList<>(points);

                    for (int point = 0; point < points; point++) {
                        route.add(new Vec3(buf.readFloat(), 0.0, buf.readFloat()));
                    }

                    machines.add(new Machine(entityId, label, colour, role, action, objective, target, targetId,
                            threat, routeRisk, health, version, position, destination, tactical, kind, route));
                }

                int cellCount = buf.readVarInt();
                List<Cell> cells = new ArrayList<>(cellCount);

                for (int at = 0; at < cellCount; at++) {
                    cells.add(new Cell(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat()));
                }

                return new AiDebugPayload(machines, cells, buf.readVarInt());
            });

    @Override
    public CustomPacketPayload.Type<AiDebugPayload> type() {
        return TYPE;
    }

    /** クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。 */
    public static void handle(AiDebugPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> AiDebugView.accept(payload));
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeOptionalVec(FriendlyByteBuf buf, @Nullable Vec3 vec) {
        buf.writeBoolean(vec != null);

        if (vec != null) {
            writeVec(buf, vec);
        }
    }

    @Nullable
    private static Vec3 readOptionalVec(FriendlyByteBuf buf) {
        return buf.readBoolean() ? readVec(buf) : null;
    }
}
