package com.csclub05.waypointsync.client.minimap;

import com.csclub05.waypointsync.model.Waypoint;

import java.util.List;
import java.util.Objects;

public interface MinimapAdapter {
    String name();

    boolean isReady();

    /**
     * Identifies the minimap context that controls how a server snapshot is presented.
     * Adapters that can present all dimensions at once can keep the default value.
     */
    default String contextKey() throws Exception {
        return "global";
    }

    List<Waypoint> readWaypoints() throws Exception;

    /**
     * Reads waypoints together with an adapter-local identity that remains stable while the
     * minimap edits the same native waypoint object. Stable local identities let the sync layer
     * recognize a rename, move, color change or dimension change as one edit instead of guessing
     * from waypoint contents.
     */
    default List<ObservedWaypoint> readObservedWaypoints() throws Exception {
        return readWaypoints().stream()
                .map(waypoint -> new ObservedWaypoint(waypoint, waypoint))
                .toList();
    }

    void applyServerSnapshot(List<Waypoint> waypoints) throws Exception;

    /**
     * Clears adapter state that is scoped to one multiplayer connection/world session.
     */
    default void resetSession() {
    }

    record ObservedWaypoint(Waypoint waypoint, Object localIdentity) {
        public ObservedWaypoint {
            Objects.requireNonNull(waypoint, "waypoint");
            Objects.requireNonNull(localIdentity, "localIdentity");
        }
    }
}
