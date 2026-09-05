package com.csclub05.waypointsync.client.minimap;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.client.minimap.voxelmap.VoxelMapAdapter;
import com.csclub05.waypointsync.client.minimap.xaero.XaeroAdapter;
import net.fabricmc.loader.api.FabricLoader;

public final class MinimapDetector {
    private MinimapDetector() {
    }

    public static MinimapAdapter detect() {
        FabricLoader loader = FabricLoader.getInstance();

        if (loader.isModLoaded("voxelmap")) {
            WaypointSync.LOGGER.info("Detected VoxelMap; enabling the VoxelMap waypoint adapter.");
            return new VoxelMapAdapter();
        }

        if (loader.isModLoaded("xaerominimap")) {
            WaypointSync.LOGGER.info("Detected Xaero's Minimap; enabling the Xaero compatibility adapter.");
            return new XaeroAdapter();
        }

        WaypointSync.LOGGER.warn(
                "No supported minimap was detected. Crimson WaySync will stay connected but cannot display waypoints."
        );
        return new UnavailableMinimapAdapter("No supported minimap");
    }

    private record UnavailableMinimapAdapter(String name) implements MinimapAdapter {
        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public java.util.List<com.csclub05.waypointsync.model.Waypoint> readWaypoints() {
            return java.util.List.of();
        }

        @Override
        public void applyServerSnapshot(java.util.List<com.csclub05.waypointsync.model.Waypoint> waypoints) {
        }
    }
}
