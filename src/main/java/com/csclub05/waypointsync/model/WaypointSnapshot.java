package com.csclub05.waypointsync.model;

import java.util.List;

public record WaypointSnapshot(long revision, List<Waypoint> waypoints) {
    public WaypointSnapshot {
        waypoints = List.copyOf(waypoints);
    }
}
