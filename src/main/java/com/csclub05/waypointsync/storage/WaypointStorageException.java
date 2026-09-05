package com.csclub05.waypointsync.storage;

public class WaypointStorageException extends Exception {
    public WaypointStorageException(String message) {
        super(message);
    }

    public WaypointStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
