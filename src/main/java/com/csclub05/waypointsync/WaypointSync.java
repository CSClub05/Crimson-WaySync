package com.csclub05.waypointsync;

import com.csclub05.waypointsync.config.ConfigManager;
import com.csclub05.waypointsync.config.WaypointSyncConfig;
import com.csclub05.waypointsync.discord.DiscordWebhookService;
import com.csclub05.waypointsync.network.NetworkManager;
import com.csclub05.waypointsync.server.ServerWaypointService;
import com.csclub05.waypointsync.storage.FileWaypointRepository;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WaypointSync implements ModInitializer {
    public static final String MOD_ID = "waypointsync";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("Crimson WaySync");

    private static volatile ServerWaypointService serverService;

    @Override
    public void onInitialize() {
        NetworkManager.registerCommon();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Load at server start rather than mod-class initialization so a new integrated/dedicated
            // server session always gets the current administrator configuration. Runtime config edits
            // intentionally take effect on the next server start rather than being partially hot-reloaded.
            WaypointSyncConfig config = ConfigManager.load();
            FileWaypointRepository repository =
                    new FileWaypointRepository(ConfigManager.directory().resolve("waypoints"));
            DiscordWebhookService discord = new DiscordWebhookService(config.discord.webhookUrl);

            ServerWaypointService service = new ServerWaypointService(server, repository, config, discord);
            serverService = service;
            service.start();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerWaypointService service = serverService;
            if (service != null) {
                service.tick();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerWaypointService service = serverService;
            serverService = null;
            if (service != null) {
                service.close();
            }
        });

        LOGGER.info("Crimson WaySync {} initialized. Author: CSClub05.", VERSION);
    }

    public static ServerWaypointService serverService() {
        return serverService;
    }
}
