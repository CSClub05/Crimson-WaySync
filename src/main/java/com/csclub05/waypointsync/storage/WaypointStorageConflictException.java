package com.csclub05.waypointsync.storage;

/**
 * Raised when the on-disk waypoint state no longer matches the state that a player mutation was based on.
 */
public final class WaypointStorageConflictException extends WaypointStorageException {
    public WaypointStorageConflictException(String message) {
        super(message);
    }
}
