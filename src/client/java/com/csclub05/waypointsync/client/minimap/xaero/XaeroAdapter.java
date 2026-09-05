package com.csclub05.waypointsync.client.minimap.xaero;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.client.minimap.MinimapAdapter;
import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional bridge to Xaero's Minimap.
 *
 * Server snapshots are reconciled into Xaero's active native waypoint set so synchronized entries
 * participate in the minimap, in-world renderer, and Xaero World Map. Native waypoint identity is
 * tracked in-memory so only synchronized entries are ever removed automatically.
 */
public final class XaeroAdapter implements MinimapAdapter {
    private static final String CUSTOM_OWNER = "Crimson WaySync";
    private static final String XAERO_DEATHPOINT_NAME_PREFIX = "gui.xaero_deathpoint";

    private final AtomicBoolean compatibilityWarningLogged = new AtomicBoolean();
    private final Map<Object, String> managedNativeWaypoints = new IdentityHashMap<>();
    private Reflection reflection;

    @Override
    public String name() {
        return "Xaero's Minimap";
    }

    @Override
    public boolean isReady() {
        try {
            return reflection().currentWorld() != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (compatibilityWarningLogged.compareAndSet(false, true)) {
                WaypointSync.LOGGER.warn(
                        "Xaero's Minimap was detected, but Crimson WaySync could not initialize its 1.21.4 compatibility bridge. "
                                + "Xaero synchronization will remain disabled for this session.",
                        e
                );
            }
            return false;
        }
    }

    @Override
    public String contextKey() throws Exception {
        return currentDimension();
    }

    @Override
    public List<Waypoint> readWaypoints() throws Exception {
        return readObservedWaypoints().stream().map(ObservedWaypoint::waypoint).toList();
    }

    @Override
    public List<ObservedWaypoint> readObservedWaypoints() throws Exception {
        Reflection r = reflection();
        Object set = r.currentWaypointSet();
        if (set == null) {
            return List.of();
        }

        String dimension = currentDimension();
        Collection<?> nativeWaypoints = r.waypointsInSet(set);
        Map<WaypointView, ObservedWaypoint> visible = new LinkedHashMap<>();

        // Prefer native Xaero entries when the same waypoint is also present in Crimson WaySync's
        // third-party provider table. A waypoint created by this player starts life as a native
        // Xaero object. After the server confirms it, keeping that native object as the observed
        // representation is critical: if the player later deletes it, the disappearance must be
        // visible to the sync manager instead of being hidden by an overlapping provider entry.
        for (Object nativeWaypoint : List.copyOf(nativeWaypoints)) {
            if (r.isThirdParty(nativeWaypoint)) {
                continue;
            }
            Waypoint common = r.toCommon(nativeWaypoint, dimension);
            if (isAutomaticXaeroWaypoint(common)) {
                continue;
            }
            visible.putIfAbsent(
                    WaypointView.of(common),
                    new ObservedWaypoint(common, new IdentityRef(nativeWaypoint, dimension))
            );
        }

        // Provider entries represent server waypoints that do not already have an equivalent
        // native Xaero object on this client (for example a waypoint created by another player).
        Map<Object, Object> customWaypoints = r.customWaypointsIfAvailable();
        for (Object customWaypoint : customWaypoints == null ? List.of() : List.copyOf(customWaypoints.values())) {
            if (customWaypoint == null || r.isDeletedThirdParty(customWaypoint)) {
                continue;
            }
            Waypoint common = r.toCommon(customWaypoint, dimension);
            if (isAutomaticXaeroWaypoint(common)) {
                continue;
            }
            visible.putIfAbsent(
                    WaypointView.of(common),
                    new ObservedWaypoint(common, new IdentityRef(customWaypoint, dimension))
            );
        }

        return List.copyOf(visible.values());
    }

    @Override
    public void applyServerSnapshot(List<Waypoint> waypoints) throws Exception {
        Reflection r = reflection();
        Object world = r.currentWorld();
        Object set = world == null ? null : r.currentWaypointSet(world);
        if (set == null) {
            return;
        }

        String currentDimension = currentDimension();
        List<Waypoint> desired = waypoints.stream()
                .filter(waypoint -> currentDimension.equals(waypoint.dimension()))
                .filter(waypoint -> !isAutomaticXaeroWaypoint(waypoint))
                .toList();

        // Older Crimson WaySync builds rendered remote entries through Xaero's custom/third-party
        // table. That is sufficient for in-world rendering but those entries are not reliably
        // exposed on Xaero's full-screen World Map. Remove only our own legacy provider entries as
        // we migrate to native WaypointSet entries; no other mod's custom waypoints are touched.
        Map<Object, Object> legacyCustom = r.customWaypointsIfAvailable();
        if (legacyCustom != null) {
            legacyCustom.clear();
        }

        Collection<?> rawNative = r.waypointsInSet(set);
        List<Object> nativeSnapshot = List.copyOf(rawNative);

        // Only native entries that have previously represented authoritative server waypoints are
        // candidates for automatic removal. Unrelated Xaero waypoints remain completely owned by
        // the player. If a synchronized entry was edited locally, the authoritative snapshot either
        // adopts that edit (accepted by the server) or replaces it with the server version.
        for (Map.Entry<Object, String> managedEntry : List.copyOf(managedNativeWaypoints.entrySet())) {
            Object managed = managedEntry.getKey();
            if (!currentDimension.equals(managedEntry.getValue())) {
                continue;
            }
            if (!nativeSnapshot.contains(managed)) {
                managedNativeWaypoints.remove(managed);
                continue;
            }

            Waypoint managedCommon = r.toCommon(managed, currentDimension);
            boolean stillDesired = desired.stream()
                    .anyMatch(waypoint -> WaypointView.of(waypoint).equals(WaypointView.of(managedCommon)));
            if (!stillDesired) {
                r.removeWaypoint(set, managed);
                managedNativeWaypoints.remove(managed);
            }
        }

        // Refresh after removals. Exact native matches are adopted instead of duplicated. This is
        // especially important on the originating client, where the player's own Xaero object is
        // already the best representation of the shared waypoint.
        nativeSnapshot = List.copyOf(r.waypointsInSet(set));
        Map<WaypointView, Object> nativeByView = new LinkedHashMap<>();
        for (Object nativeWaypoint : nativeSnapshot) {
            if (nativeWaypoint == null || r.isThirdParty(nativeWaypoint)) {
                continue;
            }
            Waypoint common = r.toCommon(nativeWaypoint, currentDimension);
            if (isAutomaticXaeroWaypoint(common)) {
                continue;
            }
            nativeByView.putIfAbsent(WaypointView.of(common), nativeWaypoint);
        }

        for (Waypoint waypoint : desired) {
            WaypointView view = WaypointView.of(waypoint);
            Object existing = nativeByView.get(view);
            if (existing != null) {
                // Once an authoritative waypoint is represented by a native Xaero object, track it
                // as synchronized even when the player originally created that object. This lets a
                // later deletion by another player remove the local representation too.
                managedNativeWaypoints.put(existing, currentDimension);
                continue;
            }

            Object nativeWaypoint = r.newWaypoint(waypoint);
            if (r.addWaypoint(set, nativeWaypoint)) {
                managedNativeWaypoints.put(nativeWaypoint, currentDimension);
                nativeByView.put(view, nativeWaypoint);
            }
        }

        // Deliberately do not force-save Xaero's local waypoint file here. Native in-memory
        // WaypointSet membership is what Xaero's renderers consume, while avoiding a forced save
        // prevents server-owned presentation entries from becoming stale personal waypoints after
        // disconnecting from a server. Xaero may still persist changes through its own normal
        // lifecycle, which subsequent snapshots reconcile safely.
    }

    @Override
    public void resetSession() {
        compatibilityWarningLogged.set(false);
        managedNativeWaypoints.clear();
    }

    private static boolean isAutomaticXaeroWaypoint(Waypoint waypoint) {
        // Xaero creates deathpoints automatically after player deaths. Their internal names use
        // translation keys such as gui.xaero_deathpoint and gui.xaero_deathpoint_old. They are
        // client utility markers rather than player-created shared waypoints, so they must never
        // enter Crimson WaySync's observed state or be re-rendered from an older server snapshot.
        return waypoint.name().startsWith(XAERO_DEATHPOINT_NAME_PREFIX);
    }

    private String currentDimension() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return "minecraft:overworld";
        }
        return client.world.getRegistryKey().getValue().toString();
    }


    /** Identity compares the underlying Xaero object by reference, not by mutable waypoint data. */
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

    private Reflection reflection() throws ReflectiveOperationException {
        if (reflection == null) {
            reflection = new Reflection();
        }
        return reflection;
    }

    private static final class Reflection {
        private final Class<?> waypointClass;
        private final Constructor<?> sixArgumentWaypointConstructor;
        private final Method getName;
        private final Method getSymbol;
        private final Method getX;
        private final Method getY;
        private final Method getZ;
        private final Method getColor;
        private final Method isDisabled;
        private final Method getCustomWaypoints;
        private final Object customWaypointsReceiver;

        private Reflection() throws ReflectiveOperationException {
            waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            sixArgumentWaypointConstructor = waypointClass.getConstructor(
                    int.class,
                    int.class,
                    int.class,
                    String.class,
                    String.class,
                    int.class
            );

            getName = findZeroArgumentMethod(waypointClass, "getName");
            getSymbol = findZeroArgumentMethod(waypointClass, "getSymbol");
            getX = findZeroArgumentMethod(waypointClass, "getX");
            getY = findZeroArgumentMethod(waypointClass, "getY");
            getZ = findZeroArgumentMethod(waypointClass, "getZ");
            getColor = findZeroArgumentMethod(waypointClass, "getColor");
            isDisabled = findZeroArgumentMethod(waypointClass, "isDisabled");

            Method customMethod = null;
            Object customReceiver = null;
            try {
                Class<?> managerClass = Class.forName("xaero.common.minimap.waypoints.WaypointsManager");
                customMethod = optionalOneArgumentMethod(managerClass, "getCustomWaypoints", String.class);
                if (customMethod != null && !Modifier.isStatic(customMethod.getModifiers())) {
                    customReceiver = resolveLegacyWaypointsManager(managerClass);
                }
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Native WaypointSet integration does not require Xaero's optional custom waypoint API.
            }
            getCustomWaypoints = customMethod;
            customWaypointsReceiver = customReceiver;
        }

        private Object currentSession() throws ReflectiveOperationException {
            Object minimapModule = staticField("xaero.hud.minimap.BuiltInHudModules", "MINIMAP");
            return invokeZero(minimapModule, "getCurrentSession");
        }

        private Object currentWorld() throws ReflectiveOperationException {
            Object currentSession = currentSession();
            if (currentSession == null) {
                return null;
            }
            Object waypointSession = invokeZero(currentSession, "getWaypointSession");
            Object session = invokeZero(waypointSession, "getSession");
            Object worldManager = invokeZero(session, "getWorldManager");
            return invokeZero(worldManager, "getCurrentWorld");
        }

        private Object currentWaypointSet() throws ReflectiveOperationException {
            Object world = currentWorld();
            return currentWaypointSet(world);
        }

        private Object currentWaypointSet(Object world) throws ReflectiveOperationException {
            return world == null ? null : invokeZero(world, "getCurrentWaypointSet");
        }

        @SuppressWarnings("unchecked")
        private Map<Object, Object> customWaypointsIfAvailable() throws ReflectiveOperationException {
            if (getCustomWaypoints == null) {
                return null;
            }
            Object value = getCustomWaypoints.invoke(customWaypointsReceiver, CUSTOM_OWNER);
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            return (Map<Object, Object>) map;
        }

        private Collection<?> waypointsInSet(Object set) throws ReflectiveOperationException {
            for (String methodName : List.of("getWaypoints", "getList")) {
                Method method = optionalZeroArgumentMethod(set.getClass(), methodName);
                if (method != null) {
                    Object value = method.invoke(set);
                    if (value instanceof Collection<?> collection) {
                        return collection;
                    }
                }
            }

            for (Field field : set.getClass().getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(set);
                if (value instanceof Collection<?> collection) {
                    return collection;
                }
            }

            throw new NoSuchMethodException("Could not locate Xaero WaypointSet waypoint collection.");
        }

        @SuppressWarnings("unchecked")
        private boolean addWaypoint(Object set, Object waypoint) throws ReflectiveOperationException {
            Method add = optionalOneArgumentMethod(set.getClass(), "add", waypointClass);
            if (add != null) {
                add.invoke(set, waypoint);
                return true;
            }

            Collection<Object> collection = (Collection<Object>) waypointsInSet(set);
            return collection.add(waypoint);
        }

        @SuppressWarnings("unchecked")
        private boolean removeWaypoint(Object set, Object waypoint) throws ReflectiveOperationException {
            Method remove = optionalOneArgumentMethod(set.getClass(), "remove", waypointClass);
            if (remove != null) {
                remove.invoke(set, waypoint);
                return true;
            }

            Collection<Object> collection = (Collection<Object>) waypointsInSet(set);
            return collection.remove(waypoint);
        }

        private Waypoint toCommon(Object nativeWaypoint, String dimension) throws ReflectiveOperationException {
            String name = Objects.toString(getName.invoke(nativeWaypoint), "Waypoint");
            String symbol = Objects.toString(getSymbol.invoke(nativeWaypoint), initials(name));
            int x = ((Number) getX.invoke(nativeWaypoint)).intValue();
            int y = ((Number) getY.invoke(nativeWaypoint)).intValue();
            int z = ((Number) getZ.invoke(nativeWaypoint)).intValue();
            int color = ((Number) getColor.invoke(nativeWaypoint)).intValue();
            boolean disabled = (Boolean) isDisabled.invoke(nativeWaypoint);

            Waypoint base = Waypoint.normal(name, x, y, z, dimension);
            return new Waypoint(
                    base.name(),
                    symbol,
                    base.x(),
                    base.y(),
                    base.z(),
                    base.dimension(),
                    Math.max(0, Math.min(15, color)),
                    disabled,
                    base.type(),
                    base.set(),
                    base.rotateOnTeleport(),
                    base.teleportYaw(),
                    base.visibilityType(),
                    base.destination()
            );
        }

        private Object newWaypoint(Waypoint waypoint) throws ReflectiveOperationException {
            Object nativeWaypoint = sixArgumentWaypointConstructor.newInstance(
                    waypoint.x(),
                    waypoint.y() == null ? 64 : waypoint.y(),
                    waypoint.z(),
                    waypoint.name(),
                    waypoint.initials(),
                    waypoint.color()
            );

            Method setDisabled = optionalOneArgumentMethod(waypointClass, "setDisabled", boolean.class);
            if (setDisabled != null) {
                setDisabled.invoke(nativeWaypoint, waypoint.disabled());
            }
            return nativeWaypoint;
        }

        private Object customWaypointKey(Waypoint waypoint) {
            // Xaero's provider table uses caller-owned keys. A deterministic key based on the last
            // authoritative visible state lets us distinguish a server update from a local mutation
            // of the existing waypoint object.
            return waypoint.dimension() + "|" + waypoint.x() + "|" + waypoint.y() + "|" + waypoint.z() + "|"
                    + waypoint.name() + "|" + waypoint.color() + "|" + waypoint.disabled();
        }

        private boolean isThirdParty(Object nativeWaypoint) {
            return booleanProperty(nativeWaypoint, List.of("isThirdParty", "isCustom", "isServerWaypoint"));
        }

        private boolean isDeletedThirdParty(Object nativeWaypoint) {
            // Newer Xaero versions keep deleted third-party entries restorable instead of removing
            // the provider's table entry. Probe the public-facing state without depending on one
            // exact implementation name so minor Xaero updates do not silently re-show deletions.
            return booleanProperty(nativeWaypoint, List.of("isDeleted", "isThirdPartyDeleted", "isMarkedDeleted"));
        }

        private static boolean booleanProperty(Object target, List<String> names) {
            for (String name : names) {
                Method method = optionalZeroArgumentMethod(target.getClass(), name);
                if (method != null && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    try {
                        return Boolean.TRUE.equals(method.invoke(target));
                    } catch (ReflectiveOperationException ignored) {
                        // Try a field or the next known name.
                    }
                }

                try {
                    Field field = target.getClass().getDeclaredField(name);
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        field.setAccessible(true);
                        return Boolean.TRUE.equals(field.get(target));
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Try the next known name.
                }
            }
            return false;
        }

        private static Object resolveLegacyWaypointsManager(Class<?> managerClass) throws ReflectiveOperationException {
            for (String holderClassName : List.of("xaero.common.XaeroMinimapSession", "xaero.common.HudMod")) {
                try {
                    Class<?> holderClass = Class.forName(holderClassName);
                    for (Method method : holderClass.getMethods()) {
                        if (method.getParameterCount() == 0 && managerClass.isAssignableFrom(method.getReturnType())) {
                            Object receiver = Modifier.isStatic(method.getModifiers()) ? null : staticInstance(holderClass);
                            if (receiver != null || Modifier.isStatic(method.getModifiers())) {
                                return method.invoke(receiver);
                            }
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                    // Try the next known legacy holder.
                }
            }
            throw new NoSuchMethodException("Xaero getCustomWaypoints is not static and no WaypointsManager receiver was found.");
        }

        private static Object staticInstance(Class<?> type) throws ReflectiveOperationException {
            for (String fieldName : List.of("INSTANCE", "instance")) {
                try {
                    Field field = type.getField(fieldName);
                    if (Modifier.isStatic(field.getModifiers())) {
                        return field.get(null);
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try next field.
                }
            }
            return null;
        }

        private static Object staticField(String className, String fieldName) throws ReflectiveOperationException {
            Class<?> type = Class.forName(className);
            Field field = type.getField(fieldName);
            return field.get(null);
        }

        private static Object invokeZero(Object receiver, String methodName) throws ReflectiveOperationException {
            if (receiver == null) {
                return null;
            }
            try {
                return receiver.getClass().getMethod(methodName).invoke(receiver);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ReflectiveOperationException reflective) {
                    throw reflective;
                }
                throw e;
            }
        }

        private static Method findZeroArgumentMethod(Class<?> type, String name) throws NoSuchMethodException {
            Method method = optionalZeroArgumentMethod(type, name);
            if (method == null) {
                throw new NoSuchMethodException(type.getName() + "." + name + "()");
            }
            return method;
        }

        private static Method optionalZeroArgumentMethod(Class<?> type, String name) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }

        private static Method findOneArgumentMethod(Class<?> type, String name, Class<?> argumentType)
                throws NoSuchMethodException {
            Method method = optionalOneArgumentMethod(type, name, argumentType);
            if (method == null) {
                throw new NoSuchMethodException(type.getName() + "." + name + "(" + argumentType.getName() + ")");
            }
            return method;
        }

        private static Method optionalOneArgumentMethod(Class<?> type, String name, Class<?> argumentType) {
            try {
                return type.getMethod(name, argumentType);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }

        private static String initials(String name) {
            String stripped = name == null ? "" : name.trim();
            if (stripped.isEmpty()) {
                return "?";
            }
            return stripped.substring(0, Math.min(2, stripped.length())).toUpperCase();
        }
    }
}
