package com.ashvehicles.network;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.HitReadout;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.vehicle.Attitude;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 *
 * <p><b>撃破もここに乗る。</b>「当たった」「弾かれた」「倒した」は撃った者にとって同じ1つの問いへの3つの
 * 答えであり、同じ弾の同じ着弾から出る。倒したかどうかをクライアントが自分で判定することはできない
 * ——遠方の目標は大抵そこに存在しないし（{@code HitReadout.copyOf} 参照）、存在しても全損フラグが届く
 * 頃には次の弾が出ている。だから damage と同じ場所でサーバーが述べる。{@link #isDown} 参照。
 */
public record HitReportPayload(int target, ResourceLocation vehicle, int box, Vec3 within, Vec3 line,
        float traverse, float gunPitch, float damage, boolean bounced, boolean held, boolean killed)
        implements CustomPacketPayload {
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
                buf.writeBoolean(payload.held());
                buf.writeBoolean(payload.killed());
            },
            buf -> new HitReportPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt() - 1,
                    read(buf), read(buf), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

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
     * @param held 板に入ったが抜けなかったか。損害は半分になっている。{@code weapon.Penetration} 参照
     * @param killed この打撃で相手が終わったか。既に残骸だった物への追撃は偽（呼ぶ側が打撃の前後で
     *     {@link #isDown} を比べる）
     */
    public static void report(@Nullable Entity shooter, Entity struck, Vec3 at, Vec3 travel,
            float damage, boolean bounced, boolean held, boolean killed) {
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
        } else if (struck instanceof RocketEntity missile) {
            // 迎撃にも集計を返す。撃ったミサイルが何かを仕留めたなら乗員はそれを知る権利があり、相手が
            // ミサイルだった場合も同じ。名前は兵装名から引く——エンティティ型は全ミサイル共通なので、
            // それを出すと何を落としたのか分からない。レジストリ照合に外れて名前がパスの大文字になる
            // のは仕様（HitReadout.name 参照）。箱も絵も無いのは標的ドローンと同じ理屈。
            PacketDistributor.sendToPlayer(crew, new HitReportPayload(missile.getId(),
                    missile.getWeaponId(), -1, Vec3.ZERO,
                    travel.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : travel.normalize(),
                    0.0F, 0.0F, damage, bounced, held, killed));

            return;
        } else if (struck instanceof TargetDroneEntity drone) {
            // 標的ドローンにも集計を返す。的の存在理由は「当たったかを知る」ことで、800m 先の的は
            // まさにこの計器の距離にいる。箱も車体座標も持たないので名前とダメージ集計だけの札になり、
            // 絵は {@code HitReadout.copyOf} が機体以外を描かないため元から出ない。
            PacketDistributor.sendToPlayer(crew, new HitReportPayload(drone.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(drone.getType()), -1, Vec3.ZERO,
                    travel.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : travel.normalize(),
                    0.0F, 0.0F, damage, bounced, held, killed));

            return;
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
                slot, within, line, traverse, gunPitch, damage, bounced, held, killed));
    }

    /**
     * この相手はもう終わっているか。<b>打撃の前と後で比べるために呼ぶ。</b>
     *
     * <p>「今そうなっている」だけでは撃破にならない。残骸は世界に立ち続けるので（{@code VehicleEntityBase.wreck}
     * 参照）、燃えている車体へ撃ち込む2発目3発目が全部「撃破」を名乗ってしまう。だから呼ぶ側は打撃の直前に
     * 一度、直後にもう一度これを問い、偽から真へ変わった1発だけが撃破を報告する。
     *
     * <p>箱ではなく機体について答える。当たったのは砲塔や主翼でも、終わったかどうかを持っているのは機体だ。
     */
    public static boolean isDown(Entity struck) {
        Entity subject = struck instanceof VehiclePart part && part.getParent() != null
                ? part.getParent() : struck;

        if (subject instanceof VehicleEntityBase machine) {
            // 除去ではなく全損フラグ。機体は倒れても残骸として残るので、消えるのを待っていたら誰も撃破を
            // 報告しない。
            return machine.isWrecked();
        }

        if (subject instanceof LivingEntity living) {
            return living.isDeadOrDying();
        }

        return subject.isRemoved();
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
                payload.damage(), payload.bounced(), payload.held(), payload.killed()));
    }
}
