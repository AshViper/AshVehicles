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
 * 撃った本人の弾が機体に着弾したこと、そして機体のどこに当たったか。
 *
 * <p>撃った1人にだけ送り、他の誰にも送らない。この情報はクライアントでは出せない。弾は砲口で与えられた
 * 値から各クライアントが自前で飛ばすが、どこで<em>止まった</em>かはクライアントが判定していない箱に対し
 * てサーバーが決めるし、装甲が通したか弾いたかもサーバーが決める。だから答えを送る。送るのはそれだけ
 * ——撃った本人は自分の弾が何をしたかを知る。50m なら自分の目で見えたはずで、800m では到底見えない情報。
 *
 * <p><b>位置は空間座標ではなく箱に対して持たせる。</b> 世界座標では描く頃には古くなっている——目標は
 * まだ走っており砲塔もまだ旋回している——ので、通信に載せるのは「機体のどの箱に入ったか」と「その箱の
 * 中のどこか」（各半長に対する比率）。{@link HitReadout} が今の位置に箱を戻し、その上にマークを戻す。
 */
public record HitReportPayload(int target, ResourceLocation vehicle, int box, Vec3 within, Vec3 line,
        float traverse, float gunPitch, float damage, boolean bounced) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HitReportPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "hit_report"));

    public static final StreamCodec<FriendlyByteBuf, HitReportPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.target());
                buf.writeResourceLocation(payload.vehicle());
                // 1 足してから書く。「箱に当たっていない」場合を 0 にするため。そうしないと varint が
                // マイナス符号のために5バイト使う。
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
     * 撃った者がプレイヤーで、当たった相手が機体なら、弾が何に当たったかを伝える。
     *
     * <p>装甲に対する弾の結末の両方——貫通した場合と弾かれた場合——から呼ばれる。2つは同じ問いへの違う
     * 答えであり、前者しか知らされない砲手は跳弾と外れを区別できない。
     *
     * @param shooter 引き金を引いた者。プレイヤーとは限らない
     * @param struck 弾が当たった物。機体の箱の1つか、機体そのもの
     * @param at 世界座標での着弾点
     * @param travel 着弾時の進行方向
     * @param damage 与えた損害。装甲に弾かれた弾では 0
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
            // 箱の面に丸める。ゲームは実際に寝ている装甲板ではなく、パーツを運ぶ直立した箱に対して命中
            // を求めるので、急傾斜した板への掠りが装甲の少し外側として報告されることがある。
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
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link HitReadout} を解決することはない。
     */
    public static void handle(HitReportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> HitReadout.report(payload.target(), payload.vehicle(), payload.box(),
                payload.within(), payload.line(), payload.traverse(), payload.gunPitch(),
                payload.damage(), payload.bounced()));
    }
}
