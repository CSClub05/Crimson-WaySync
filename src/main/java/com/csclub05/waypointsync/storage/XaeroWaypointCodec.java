package com.csclub05.waypointsync.storage;

import com.csclub05.waypointsync.model.Waypoint;

import java.util.ArrayList;
import java.util.List;

public final class XaeroWaypointCodec {
    public static final String HEADER =
            "#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination";

    public Waypoint decode(String line, String dimension, int lineNumber) throws WaypointStorageException {
        String[] tokens = line.split(":", -1);
        if (tokens.length != 14 || !"waypoint".equals(tokens[0])) {
            throw new WaypointStorageException(
                    "Invalid Xaero waypoint line at " + dimension + ":" + lineNumber
                            + ". Expected 14 colon-separated tokens."
            );
        }

        try {
            return new Waypoint(
                    decodeText(tokens[1]),
                    decodeText(tokens[2]),
                    Integer.parseInt(tokens[3]),
                    "~".equals(tokens[4]) ? null : Integer.parseInt(tokens[4]),
                    Integer.parseInt(tokens[5]),
                    dimension,
                    Integer.parseInt(tokens[6]),
                    parseBoolean(tokens[7], "disabled"),
                    Integer.parseInt(tokens[8]),
                    tokens[9],
                    parseBoolean(tokens[10], "rotate_on_tp"),
                    Integer.parseInt(tokens[11]),
                    Integer.parseInt(tokens[12]),
                    parseBoolean(tokens[13], "destination")
            );
        } catch (IllegalArgumentException e) {
            throw new WaypointStorageException(
                    "Invalid waypoint value at " + dimension + ":" + lineNumber + ": " + e.getMessage(),
                    e
            );
        }
    }

    public String encode(Waypoint waypoint) {
        List<String> tokens = new ArrayList<>(14);
        tokens.add("waypoint");
        tokens.add(encodeText(waypoint.name()));
        tokens.add(encodeText(waypoint.initials()));
        tokens.add(Integer.toString(waypoint.x()));
        tokens.add(waypoint.y() == null ? "~" : Integer.toString(waypoint.y()));
        tokens.add(Integer.toString(waypoint.z()));
        tokens.add(Integer.toString(waypoint.color()));
        tokens.add(Boolean.toString(waypoint.disabled()));
        tokens.add(Integer.toString(waypoint.type()));
        tokens.add(waypoint.set());
        tokens.add(Boolean.toString(waypoint.rotateOnTeleport()));
        tokens.add(Integer.toString(waypoint.teleportYaw()));
        tokens.add(Integer.toString(waypoint.visibilityType()));
        tokens.add(Boolean.toString(waypoint.destination()));
        return String.join(":", tokens);
    }

    private static String decodeText(String value) {
        return value.replace("§§", ":");
    }

    private static String encodeText(String value) {
        return value.replace(":", "§§");
    }

    private static boolean parseBoolean(String value, String field) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Expected true or false for " + field + ", got: " + value);
    }
}
