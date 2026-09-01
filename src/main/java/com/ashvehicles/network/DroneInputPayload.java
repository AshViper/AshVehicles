package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.AircraftInput;
import com.ashvehicles.entity.RemoteLink;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 遠隔操作中の操縦桿。操作者のクライアントが毎tick送る。
 *
 * <p><b>{@link AircraftInputPayload} と対になるが、運ぶ物は正反対だ。</b>あちらは有人機のもので、飛行モデルを
 * 回しているのはクライアントだから、姿勢も速度もスロットルの絶対値も「クライアントが出した答え」として送る。
 * こちらは無人機のもので、飛行モデルを回しているのはサーバーだ。だから送るのは操縦桿の位置——つまり
 * <em>問い</em>——だけで、機体がどこにいてどちらを向いているかを決めるのは常にサーバーである。
 *
 * <p>スロットルすら絶対値では送らない。{@link AircraftInput#throttle()} は変化率で、それを積分するのは
 * 機体の側だ（{@code AircraftEntity.flightTick}）。有人機ではクライアントが積分した結果を送り返していたが、
 * ここではサーバーが自分で積分する。同じ数式が回る場所が違うだけで、レバーはどちらも同じレバーだ。
 *
 * <p><b>往復1回分の遅れは残る。</b>舵を切ってから機体が応えるまで、操作者の画面では実際に往復1回分かかる。
 * 消す方法はない——消すには機体の位置をクライアントに決めさせることになり、そのための
 * ServerboundMoveVehiclePacket は操縦している乗り物にしか使えないからだ。無人機の操作感としては、
 * むしろ正しい方向の誤差ではある。
 *
 * <p>ペイロードは機体を名指ししない。サーバーは送信者が繋いでいる機体に適用するので、他人の無人機を
 * 狙うことはできない。{@link AircraftInputPayload} と同じ理屈。
 */
public record DroneInputPayload(AircraftInput input, boolean toggleGear, boolean toggleFlaps,
        boolean cycleWeapon, boolean jettison) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DroneInputPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "drone_input"));

    public static final StreamCodec<FriendlyByteBuf, DroneInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.input().write(buf);
                buf.writeBoolean(payload.toggleGear());
                buf.writeBoolean(payload.toggleFlaps());
                buf.writeBoolean(payload.cycleWeapon());
                buf.writeBoolean(payload.jettison());
            },
            buf -> new DroneInputPayload(AircraftInput.read(buf),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public CustomPacketPayload.Type<DroneInputPayload> type() {
        return TYPE;
    }

    public static void handle(DroneInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            AircraftEntity drone = RemoteLink.linkedDrone(context.player());

            // 繋いでいる者しか届かない。乗っていること自体がリンクなので、これ以上の確認は要らない。
            if (drone == null || drone.getOperator() != context.player()) {
                return;
            }

            drone.setInput(payload.input());

            if (payload.toggleGear()) {
                drone.toggleGear();
            }

            if (payload.toggleFlaps()) {
                drone.toggleFlaps();
            }

            if (payload.cycleWeapon()) {
                drone.cycleWeapon();
            }

            if (payload.jettison()) {
                drone.jettisonTanks();
            }
        });
    }
}
