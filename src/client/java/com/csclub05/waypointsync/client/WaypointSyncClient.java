package com.csclub05.waypointsync.client;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.client.minimap.MinimapDetector;
import com.csclub05.waypointsync.client.sync.ClientSyncManager;
import com.csclub05.waypointsync.network.NetworkManager;
import com.csclub05.waypointsync.network.payload.SnapshotPayload;
import com.csclub05.waypointsync.network.payload.SyncReadyPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class WaypointSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientSyncManager syncManager = new ClientSyncManager(MinimapDetector.detect());

        ClientPlayNetworking.registerGlobalReceiver(SnapshotPayload.ID, (payload, context) ->
                syncManager.acceptSnapshot(payload)
        );

        ClientTickEvents.END_CLIENT_TICK.register(syncManager::tick);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            syncManager.reset();
            if (ClientPlayNetworking.canSend(SyncReadyPayload.ID)) {
                ClientPlayNetworking.send(new SyncReadyPayload(NetworkManager.PROTOCOL_VERSION));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> syncManager.reset());

        WaypointSync.LOGGER.info("Crimson WaySync client initialized.");
    }
}
