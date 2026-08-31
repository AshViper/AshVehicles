package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = AshVehicles.MODID)
public final class ModNetwork {
    /**
     * ペイロードの通信形式を変えたら必ずこの番号を上げること。
     *
     * <p>MOD 独自エンティティが自前で書く spawn データも含む。あちらは下で登録するペイロードではなく
     * NeoForge 自身のペイロードに乗るが、サーバーが書いた形式と違う形式でクライアントが読めば、結果は
     * 同じ「接続が壊れる」。{@link com.ashvehicles.entity.VehicleProjectile#writeSpawnData} 参照。
     */
    private static final String PROTOCOL_VERSION = "17";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(AircraftInputPayload.TYPE, AircraftInputPayload.STREAM_CODEC, AircraftInputPayload::handle);
        registrar.playToServer(GroundVehicleInputPayload.TYPE, GroundVehicleInputPayload.STREAM_CODEC,
                GroundVehicleInputPayload::handle);
        registrar.playToServer(OpenVehicleHoldPayload.TYPE, OpenVehicleHoldPayload.STREAM_CODEC,
                OpenVehicleHoldPayload::handle);
        registrar.playToServer(SwitchSeatPayload.TYPE, SwitchSeatPayload.STREAM_CODEC,
                SwitchSeatPayload::handle);
        registrar.playToServer(DesignatePayload.TYPE, DesignatePayload.STREAM_CODEC,
                DesignatePayload::handle);
        registrar.playToServer(GunTriggerPayload.TYPE, GunTriggerPayload.STREAM_CODEC,
                GunTriggerPayload::handle);
        registrar.playToServer(EjectPayload.TYPE, EjectPayload.STREAM_CODEC, EjectPayload::handle);
        registrar.playToServer(BlastPowerPayload.TYPE, BlastPowerPayload.STREAM_CODEC,
                BlastPowerPayload::handle);
        registrar.playToClient(DefinitionSyncPayload.TYPE, DefinitionSyncPayload.STREAM_CODEC,
                DefinitionSyncPayload::handle);
        registrar.playToClient(BlastSoundPayload.TYPE, BlastSoundPayload.STREAM_CODEC, BlastSoundPayload::handle);
        registrar.playToClient(SensorPayload.TYPE, SensorPayload.STREAM_CODEC, SensorPayload::handle);
        registrar.playToClient(MissileTrackPayload.TYPE, MissileTrackPayload.STREAM_CODEC,
                MissileTrackPayload::handle);
        registrar.playToClient(HitReportPayload.TYPE, HitReportPayload.STREAM_CODEC,
                HitReportPayload::handle);
    }

    private ModNetwork() {
    }
}
