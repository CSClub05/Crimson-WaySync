package com.csclub05.waypointsync.client.minimap.voxelmap;

import com.csclub05.waypointsync.client.minimap.MinimapAdapter;
import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.world.dimension.DimensionType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reflective bridge to VoxelMap.
 *
 * VoxelMap is optional, so Crimson WaySync does not link against it at compile time.
 * Server snapshots are reconciled with the waypoints that Crimson WaySync previously
 * presented instead of clearing VoxelMap's entire waypoint collection.
 */
public final class VoxelMapAdapter implements MinimapAdapter {
    private static final float[][] COLORS = {
            {0.0f, 0.0f, 0.0f},
            {0.0f, 0.0f, 0.67f},
            {0.0f, 0.67f, 0.0f},
            {0.0f, 0.67f, 0.67f},
            {0.67f, 0.0f, 0.0f},
            {0.67f, 0.0f, 0.67f},
            {1.0f, 0.67f, 0.0f},
            {0.67f, 0.67f, 0.67f},
            {0.33f, 0.33f, 0.33f},
            {0.33f, 0.33f, 1.0f},
            {0.33f, 1.0f, 0.33f},
            {0.33f, 1.0f, 1.0f},
            {1.0f, 0.33f, 0.33f},
            {1.0f, 0.33f, 1.0f},
            {1.0f, 1.0f, 0.33f},
            {1.0f, 1.0f, 1.0f}
    };

    private final Set<Object> managedNativeWaypoints =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private Reflection reflection;

    @Override
    public String name() {
        return "VoxelMap";
    }

    @Override
    public boolean isReady() {
        try {
            Reflection r = reflection();
            return r.manager() != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<Waypoint> readWaypoints() throws Exception {
        return readObservedWaypoints().stream().map(ObservedWaypoint::waypoint).toList();
    }

    @Override
    public List<ObservedWaypoint> readObservedWaypoints() throws Exception {
        Reflection r = reflection();
        Object manager = r.manager();
        if (manager == null) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        Collection<Object> nativeWaypoints = (Collection<Object>) r.getWaypoints.invoke(manager);
        List<ObservedWaypoint> result = new ArrayList<>();

        for (Object nativeWaypoint : List.copyOf(nativeWaypoints)) {
            for (Waypoint common : toCommon(r, nativeWaypoint)) {
                result.add(new ObservedWaypoint(
                        common,
                        new IdentityRef(nativeWaypoint, common.dimension())
                ));
            }
        }

        return List.copyOf(result);
    }

    @Override
    public void applyServerSnapshot(List<Waypoint> waypoints) throws Exception {
        Reflection r = reflection();
        Object manager = r.manager();
        if (manager == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Collection<Object> currentCollection = (Collection<Object>) r.getWaypoints.invoke(manager);
        List<Object> current = List.copyOf(currentCollection);
        managedNativeWaypoints.retainAll(current);

        List<Waypoint> unmatchedServerWaypoints = new ArrayList<>(waypoints);

        // Keep already-managed entries when their current visible state is still authoritative.
        // This is also what makes a local edit survive until the server confirms it.
        for (Object managed : List.copyOf(managedNativeWaypoints)) {
            int match = findMatchingServerWaypoint(r, managed, unmatchedServerWaypoints);
            if (match >= 0) {
                unmatchedServerWaypoints.remove(match);
                continue;
            }

            r.deleteWaypoint.invoke(manager, managed);
            managedNativeWaypoints.remove(managed);
        }

        @SuppressWarnings("unchecked")
        Collection<Object> refreshedCollection = (Collection<Object>) r.getWaypoints.invoke(manager);
        List<Object> refreshed = List.copyOf(refreshedCollection);

        for (Waypoint serverWaypoint : unmatchedServerWaypoints) {
            Object equivalentLocal = findEquivalentNative(r, refreshed, serverWaypoint);
            if (equivalentLocal != null) {
                managedNativeWaypoints.add(equivalentLocal);
                continue;
            }

            Object nativeWaypoint = fromCommon(r, serverWaypoint);
            r.addWaypoint.invoke(manager, nativeWaypoint);
            managedNativeWaypoints.add(nativeWaypoint);
            refreshed = append(refreshed, nativeWaypoint);
        }
    }

    @Override
    public void resetSession() {
        managedNativeWaypoints.clear();
    }

    private int findMatchingServerWaypoint(Reflection r, Object nativeWaypoint, List<Waypoint> candidates)
            throws Exception {
        List<Waypoint> nativeStates = toCommon(r, nativeWaypoint);
        for (int i = 0; i < candidates.size(); i++) {
            Waypoint candidate = candidates.get(i);
            for (Waypoint nativeState : nativeStates) {
                if (WaypointView.of(nativeState).equals(WaypointView.of(candidate))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Object findEquivalentNative(Reflection r, List<Object> current, Waypoint desired) throws Exception {
        WaypointView desiredView = WaypointView.of(desired);
        for (Object nativeWaypoint : current) {
            if (managedNativeWaypoints.contains(nativeWaypoint)) {
                continue;
            }
            for (Waypoint state : toCommon(r, nativeWaypoint)) {
                if (WaypointView.of(state).equals(desiredView)) {
                    return nativeWaypoint;
                }
            }
        }
        return null;
    }

    private static List<Object> append(List<Object> existing, Object value) {
        List<Object> result = new ArrayList<>(existing.size() + 1);
        result.addAll(existing);
        result.add(value);
        return List.copyOf(result);
    }

    private List<Waypoint> toCommon(Reflection r, Object nativeWaypoint) throws Exception {
        String name = (String) r.name.get(nativeWaypoint);
        int rawX = r.x.getInt(nativeWaypoint);
        int rawZ = r.z.getInt(nativeWaypoint);
        int y = r.y.getInt(nativeWaypoint);
        boolean enabled = r.enabled.getBoolean(nativeWaypoint);
        float red = r.red.getFloat(nativeWaypoint);
        float green = r.green.getFloat(nativeWaypoint);
        float blue = r.blue.getFloat(nativeWaypoint);

        @SuppressWarnings("unchecked")
        Collection<Object> dimensions = (Collection<Object>) r.dimensions.get(nativeWaypoint);

        List<Object> dimensionList = dimensions.isEmpty()
                ? List.of(r.dimensionContainer("minecraft:overworld"))
                : List.copyOf(dimensions);

        List<Waypoint> result = new ArrayList<>(dimensionList.size());
        for (Object dimensionContainer : dimensionList) {
            String dimension = r.dimensionId(dimensionContainer);
            double scale = r.coordinateScale(dimensionContainer);
            int x = (int) Math.round(rawX / scale);
            int z = (int) Math.round(rawZ / scale);

            Waypoint base = Waypoint.normal(name, x, y == -1 ? null : y, z, dimension);
            result.add(new Waypoint(
                    base.name(),
                    base.initials(),
                    base.x(),
                    base.y(),
                    base.z(),
                    base.dimension(),
                    nearestColor(red, green, blue),
                    !enabled,
                    base.type(),
                    base.set(),
                    base.rotateOnTeleport(),
                    base.teleportYaw(),
                    base.visibilityType(),
                    base.destination()
            ));
        }
        return result;
    }

    private Object fromCommon(Reflection r, Waypoint waypoint) throws Exception {
        Object dimension = r.dimensionContainer(waypoint.dimension());
        double scale = r.coordinateScale(dimension);

        TreeSet<Object> dimensions = new TreeSet<>((left, right) -> {
            try {
                return ((Comparable<Object>) left).compareTo(right);
            } catch (ClassCastException e) {
                return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
            }
        });
        dimensions.add(dimension);

        float[] rgb = COLORS[Math.max(0, Math.min(15, waypoint.color()))];
        int rawX = (int) Math.round(waypoint.x() * scale);
        int rawZ = (int) Math.round(waypoint.z() * scale);
        int y = waypoint.y() == null ? -1 : waypoint.y();

        return r.waypointConstructor.newInstance(
                waypoint.name(),
                rawX,
                rawZ,
                y,
                !waypoint.disabled(),
                rgb[0],
                rgb[1],
                rgb[2],
                "selectable/point",
                "",
                dimensions
        );
    }

    private Reflection reflection() throws ReflectiveOperationException {
        if (reflection == null) {
            reflection = new Reflection();
        }
        return reflection;
    }

    private static int nearestColor(float red, float green, float blue) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < COLORS.length; i++) {
            float[] candidate = COLORS[i];
            double distance =
                    Math.pow(red - candidate[0], 2)
                            + Math.pow(green - candidate[1], 2)
                            + Math.pow(blue - candidate[2], 2);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** Identity compares the underlying VoxelMap waypoint by reference for one dimension. */
    private static final class IdentityRef {
        private final Object target;
        private final String dimension;

        private IdentityRef(Object target, String dimension) {
            this.target = Objects.requireNonNull(target);
            this.dimension = Objects.requireNonNull(dimension);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityRef ref && target == ref.target && dimension.equals(ref.dimension);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(target) + dimension.hashCode();
        }
    }

    private record WaypointView(
            String name,
            int x,
            Integer y,
            int z,
            String dimension,
            int color,
            boolean disabled
    ) {
        private static WaypointView of(Waypoint waypoint) {
            return new WaypointView(
                    waypoint.name(),
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    waypoint.dimension(),
                    waypoint.color(),
                    waypoint.disabled()
            );
        }
    }

    private static final class Reflection {
        private final Object voxelMap;
        private final Method getWaypointManager;
        private final Method getDimensionManager;
        private final Method getWaypoints;
        private final Method addWaypoint;
        private final Method deleteWaypoint;
        private final Method getDimensionContainerByIdentifier;
        private final Constructor<?> waypointConstructor;

        private final Field name;
        private final Field x;
        private final Field y;
        private final Field z;
        private final Field enabled;
        private final Field red;
        private final Field green;
        private final Field blue;
        private final Field dimensions;

        private final Method getStorageName;
        private final Field dimensionType;

        private Reflection() throws ReflectiveOperationException {
            Class<?> constantsClass = Class.forName("com.mamiyaotaru.voxelmap.VoxelConstants");
            Object voxelMapInstance = constantsClass.getMethod("getVoxelMapInstance").invoke(null);
            this.voxelMap = voxelMapInstance;

            Class<?> voxelMapClass = voxelMapInstance.getClass();
            this.getWaypointManager = voxelMapClass.getMethod("getWaypointManager");
            this.getDimensionManager = voxelMapClass.getMethod("getDimensionManager");

            Object manager = manager();
            Class<?> managerClass = manager.getClass();
            this.getWaypoints = managerClass.getMethod("getWaypoints");

            Class<?> waypointClass = Class.forName("com.mamiyaotaru.voxelmap.util.Waypoint");
            this.addWaypoint = managerClass.getMethod("addWaypoint", waypointClass);
            this.deleteWaypoint = managerClass.getMethod("deleteWaypoint", waypointClass);
            this.waypointConstructor = findWaypointConstructor(waypointClass);

            this.name = waypointClass.getField("name");
            this.x = waypointClass.getField("x");
            this.y = waypointClass.getField("y");
            this.z = waypointClass.getField("z");
            this.enabled = waypointClass.getField("enabled");
            this.red = waypointClass.getField("red");
            this.green = waypointClass.getField("green");
            this.blue = waypointClass.getField("blue");
            this.dimensions = waypointClass.getField("dimensions");

            Object dimensionManager = getDimensionManager.invoke(voxelMap);
            this.getDimensionContainerByIdentifier = findOneArgumentMethod(
                    dimensionManager.getClass(),
                    "getDimensionContainerByIdentifier"
            );

            Class<?> dimensionContainerClass =
                    Class.forName("com.mamiyaotaru.voxelmap.util.DimensionContainer");
            this.getStorageName = dimensionContainerClass.getMethod("getStorageName");
            this.dimensionType = dimensionContainerClass.getField("type");
        }

        private Object manager() throws ReflectiveOperationException {
            return getWaypointManager.invoke(voxelMap);
        }

        private Object dimensionContainer(String dimension) throws ReflectiveOperationException {
            String normalized = normalizeDimensionIdentifier(dimension);
            Object dimensionManager = getDimensionManager.invoke(voxelMap);
            Object argument = dimensionLookupArgument(normalized);
            Object container = getDimensionContainerByIdentifier.invoke(dimensionManager, argument);
            if (container == null) {
                throw new IllegalStateException("VoxelMap does not know dimension " + dimension);
            }
            return container;
        }

        private Object dimensionLookupArgument(String normalized) throws ReflectiveOperationException {
            Class<?> parameterType = getDimensionContainerByIdentifier.getParameterTypes()[0];

            // VoxelMap 1.21.4's getDimensionContainerByIdentifier method takes the storage-name
            // String used in its .points files (for example "overworld" or "the_nether").
            // Newer VoxelMap revisions have used Minecraft identifier/resource-location objects,
            // so keep this bridge tolerant of either signature.
            if (parameterType == String.class) {
                return voxelMapStorageName(normalized);
            }

            if (parameterType.getName().equals("net.minecraft.util.Identifier")
                    || parameterType.getName().equals("net.minecraft.resources.ResourceLocation")) {
                return createMinecraftIdentifier(parameterType, normalized);
            }

            throw new NoSuchMethodException(
                    "Unsupported VoxelMap dimension lookup parameter type: " + parameterType.getName()
            );
        }

        private static String normalizeDimensionIdentifier(String dimension) {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("Dimension identifier cannot be blank.");
            }

            String normalized = dimension.trim().toLowerCase(java.util.Locale.ROOT);
            int separator = normalized.indexOf(':');
            if (separator < 0) {
                return "minecraft:" + normalized;
            }
            if (separator == 0 || separator == normalized.length() - 1) {
                throw new IllegalArgumentException("Invalid dimension identifier: " + dimension);
            }
            return normalized;
        }

        private static String voxelMapStorageName(String normalized) {
            if (normalized.startsWith("minecraft:")) {
                return normalized.substring("minecraft:".length());
            }
            return normalized;
        }

        private static Object createMinecraftIdentifier(Class<?> identifierType, String normalized)
                throws ReflectiveOperationException {
            for (String factoryName : List.of("tryParse", "of", "parse")) {
                try {
                    Method factory = identifierType.getMethod(factoryName, String.class);
                    Object value = factory.invoke(null, normalized);
                    if (value != null) {
                        return value;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the next common mapped factory name.
                }
            }

            int separator = normalized.indexOf(':');
            String namespace = normalized.substring(0, separator);
            String path = normalized.substring(separator + 1);
            for (String factoryName : List.of("of", "fromNamespaceAndPath")) {
                try {
                    Method factory = identifierType.getMethod(factoryName, String.class, String.class);
                    Object value = factory.invoke(null, namespace, path);
                    if (value != null) {
                        return value;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try the next common mapped factory name.
                }
            }

            try {
                Constructor<?> constructor = identifierType.getConstructor(String.class, String.class);
                return constructor.newInstance(namespace, path);
            } catch (NoSuchMethodException ignored) {
                throw new NoSuchMethodException(
                        "Could not construct Minecraft identifier type " + identifierType.getName()
                );
            }
        }

        private String dimensionId(Object container) throws ReflectiveOperationException {
            String storageName = (String) getStorageName.invoke(container);
            if ("UNKNOWN".equals(storageName)) {
                throw new IllegalStateException("VoxelMap waypoint has an unknown dimension.");
            }
            return storageName.contains(":") ? storageName : "minecraft:" + storageName;
        }

        private double coordinateScale(Object container) throws ReflectiveOperationException {
            Object type = dimensionType.get(container);
            if (type == null) {
                return 1.0D;
            }
            return ((DimensionType) type).coordinateScale();
        }

        private static Constructor<?> findWaypointConstructor(Class<?> waypointClass)
                throws NoSuchMethodException {
            for (Constructor<?> constructor : waypointClass.getConstructors()) {
                if (constructor.getParameterCount() == 11) {
                    return constructor;
                }
            }
            throw new NoSuchMethodException("VoxelMap Waypoint constructor with 11 parameters was not found.");
        }

        private static Method findOneArgumentMethod(Class<?> type, String name) throws NoSuchMethodException {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1) {
                    return method;
                }
            }
            throw new NoSuchMethodException(type.getName() + "." + name);
        }
    }
}
