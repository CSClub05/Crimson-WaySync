package com.csclub05.waypointsync.model;

import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Objects;

public record Waypoint(
        String name,
        String initials,
        int x,
        Integer y,
        int z,
        String dimension,
        int color,
        boolean disabled,
        int type,
        String set,
        boolean rotateOnTeleport,
        int teleportYaw,
        int visibilityType,
        boolean destination
) {
    public static final String DEFAULT_SET = "gui.xaero_default";

    public Waypoint {
        name = Objects.requireNonNull(name, "name");
        initials = Objects.requireNonNullElse(initials, "");
        dimension = Objects.requireNonNull(dimension, "dimension").trim();
        set = Objects.requireNonNullElse(set, DEFAULT_SET);

        if (name.isBlank()) {
            throw new IllegalArgumentException("Waypoint name cannot be empty.");
        }
        if (dimension.isEmpty() || Identifier.tryParse(dimension) == null) {
            throw new IllegalArgumentException("Invalid dimension identifier: " + dimension);
        }
        if (set.isBlank()) {
            set = DEFAULT_SET;
        }

        requireSingleLine(name, "Waypoint name");
        requireSingleLine(initials, "Waypoint initials");
        requireSingleLine(set, "Waypoint set");
        if (set.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Waypoint set cannot contain ':'.");
        }

        if (color < 0 || color > 15) {
            throw new IllegalArgumentException("Xaero color index must be between 0 and 15.");
        }
    }

    public static Waypoint normal(String name, int x, Integer y, int z, String dimension) {
        return new Waypoint(
                name,
                deriveInitials(name),
                x,
                y,
                z,
                dimension,
                0,
                false,
                0,
                DEFAULT_SET,
                false,
                0,
                0,
                false
        );
    }

    public String displayCoordinates() {
        return "[" + x + ", " + (y == null ? "~" : y) + ", " + z + "]";
    }

    private static void requireSingleLine(String value, String field) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be a single line.");
        }
    }

    private static String deriveInitials(String name) {
        String trimmed = Objects.requireNonNullElse(name, "").trim();
        if (trimmed.isEmpty()) {
            return "?";
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }

        String first = parts[0].substring(0, 1);
        String second = parts[1].substring(0, 1);
        return (first + second).toUpperCase(Locale.ROOT);
    }
}
