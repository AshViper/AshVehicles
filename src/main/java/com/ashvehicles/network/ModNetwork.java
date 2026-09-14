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
    private static final String PROTOCOL_VERSION = "21";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(AircraftInputPayload.TYPE, AircraftInputPayload.STREAM_CODEC, AircraftInputPayload::handle);
        registrar.playToServer(GroundVehicleInputPayload.TYPE, GroundVehicleInputPayload.STREAM_CODEC,
                GroundVehicleInputPayload::handle);
        registrar.playToServer(SwitchSeatPayload.TYPE, SwitchSeatPayload.STREAM_CODEC,
                SwitchSeatPayload::handle);
        registrar.playToServer(DesignatePayload.TYPE, DesignatePayload.STREAM_CODEC,
                DesignatePayload::handle);
        registrar.playToServer(GunTriggerPayload.TYPE, GunTriggerPayload.STREAM_CODEC,
                GunTriggerPayload::handle);
        // 乗らずに車外から操作する砲。ハンドルと引き金。
        registrar.playToServer(GunCrewPayload.TYPE, GunCrewPayload.STREAM_CODEC,
                GunCrewPayload::handle);
        registrar.playToServer(EjectPayload.TYPE, EjectPayload.STREAM_CODEC, EjectPayload::handle);
        // 無人機。繋ぐ・切る と、繋いでいる間の操縦桿。
        registrar.playToServer(DroneLinkPayload.TYPE, DroneLinkPayload.STREAM_CODEC,
                DroneLinkPayload::handle);
        registrar.playToServer(DroneInputPayload.TYPE, DroneInputPayload.STREAM_CODEC,
                DroneInputPayload::handle);
        registrar.playToServer(BlastPowerPayload.TYPE, BlastPowerPayload.STREAM_CODEC,
                BlastPowerPayload::handle);
        // チームデスマッチ。出撃の注文と、旗が開かせる出撃盤、そして掲示板。
        registrar.playToServer(DeployPayload.TYPE, DeployPayload.STREAM_CODEC, DeployPayload::handle);
        registrar.playToClient(DeployOpenPayload.TYPE, DeployOpenPayload.STREAM_CODEC,
                DeployOpenPayload::handle);
        registrar.playToClient(MatchStatePayload.TYPE, MatchStatePayload.STREAM_CODEC,
                MatchStatePayload::handle);
        registrar.playToClient(DefinitionSyncPayload.TYPE, DefinitionSyncPayload.STREAM_CODEC,
                DefinitionSyncPayload::handle);
        registrar.playToClient(BlastSoundPayload.TYPE, BlastSoundPayload.STREAM_CODEC, BlastSoundPayload::handle);
        registrar.playToClient(SensorPayload.TYPE, SensorPayload.STREAM_CODEC, SensorPayload::handle);
        registrar.playToClient(MissileTrackPayload.TYPE, MissileTrackPayload.STREAM_CODEC,
                MissileTrackPayload::handle);
        registrar.playToClient(HitReportPayload.TYPE, HitReportPayload.STREAM_CODEC,
                HitReportPayload::handle);
        registrar.playToClient(VehicleGonePayload.TYPE, VehicleGonePayload.STREAM_CODEC,
                VehicleGonePayload::handle);
        // 戦闘 AI の可視化。/tdm ai debug を開いた運営にだけ届く。
        registrar.playToClient(AiDebugPayload.TYPE, AiDebugPayload.STREAM_CODEC, AiDebugPayload::handle);
    }

    private ModNetwork() {
    }
}
