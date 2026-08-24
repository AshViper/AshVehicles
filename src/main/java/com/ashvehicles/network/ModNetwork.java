package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = AshVehicles.MODID)
public final class ModNetwork {
    /** Bump this whenever a payload's wire format changes. */
    private static final String PROTOCOL_VERSION = "10";

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
        registrar.playToClient(DefinitionSyncPayload.TYPE, DefinitionSyncPayload.STREAM_CODEC,
                DefinitionSyncPayload::handle);
        registrar.playToClient(BlastSoundPayload.TYPE, BlastSoundPayload.STREAM_CODEC, BlastSoundPayload::handle);
        registrar.playToClient(SensorPayload.TYPE, SensorPayload.STREAM_CODEC, SensorPayload::handle);
    }

    private ModNetwork() {
    }
}
