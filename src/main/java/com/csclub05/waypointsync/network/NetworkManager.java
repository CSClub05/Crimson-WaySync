package com.csclub05.waypointsync.network;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.network.payload.CreateWaypointPayload;
import com.csclub05.waypointsync.network.payload.DeleteWaypointPayload;
import com.csclub05.waypointsync.network.payload.EditWaypointPayload;
import com.csclub05.waypointsync.network.payload.SnapshotPayload;
import com.csclub05.waypointsync.network.payload.SyncReadyPayload;
import com.csclub05.waypointsync.server.ServerWaypointService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class NetworkManager {
    public static final int PROTOCOL_VERSION = 1;

    private NetworkManager() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(SyncReadyPayload.ID, SyncReadyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CreateWaypointPayload.ID, CreateWaypointPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(EditWaypointPayload.ID, EditWaypointPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DeleteWaypointPayload.ID, DeleteWaypointPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotPayload.ID, SnapshotPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SyncReadyPayload.ID, (payload, context) -> {
            ServerWaypointService service = WaypointSync.serverService();
            if (service != null) {
                service.clientReady(context.player(), payload.protocolVersion());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(CreateWaypointPayload.ID, (payload, context) -> {
            ServerWaypointService service = WaypointSync.serverService();
            if (service != null) {
                service.createWaypoint(context.player(), payload.expectedRevision(), payload.waypoint());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(EditWaypointPayload.ID, (payload, context) -> {
            ServerWaypointService service = WaypointSync.serverService();
            if (service != null) {
                service.editWaypoint(
                        context.player(),
                        payload.expectedRevision(),
                        payload.oldWaypoint(),
                        payload.newWaypoint()
                );
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(DeleteWaypointPayload.ID, (payload, context) -> {
            ServerWaypointService service = WaypointSync.serverService();
            if (service != null) {
                service.deleteWaypoint(context.player(), payload.expectedRevision(), payload.waypoint());
            }
        });
    }
}
