package com.csclub05.waypointsync.client.sync;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.client.minimap.MinimapAdapter;
import com.csclub05.waypointsync.client.minimap.MinimapAdapter.ObservedWaypoint;
import com.csclub05.waypointsync.model.Waypoint;
import com.csclub05.waypointsync.network.payload.CreateWaypointPayload;
import com.csclub05.waypointsync.network.payload.DeleteWaypointPayload;
import com.csclub05.waypointsync.network.payload.EditWaypointPayload;
import com.csclub05.waypointsync.network.payload.SnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ClientSyncManager {
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final int MAX_CONSECUTIVE_ADAPTER_FAILURES_BEFORE_BACKOFF = 3;
    private static final int ADAPTER_FAILURE_BACKOFF_TICKS = 100;

    private final MinimapAdapter adapter;

    private long lastRevision = -1L;
    private List<Waypoint> serverWaypoints = List.of();
    private List<ObservedWaypoint> localBaseline = List.of();
    private SnapshotPayload pendingSnapshot;
    private String adapterContextKey;
    private int ticks;
    private int consecutiveAdapterFailures;
    private int adapterBackoffUntilTick;
    private boolean mutationInFlight;

    public ClientSyncManager(MinimapAdapter adapter) {
        this.adapter = adapter;
    }

    public void acceptSnapshot(SnapshotPayload payload) {
        if (payload.revision() < lastRevision) {
            return;
        }

        this.pendingSnapshot = payload;
        applyPendingSnapshot();
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        ticks++;
        if (ticks < adapterBackoffUntilTick) {
            return;
        }

        if (pendingSnapshot != null) {
            applyPendingSnapshot();
            return;
        }

        if (lastRevision < 0 || !adapter.isReady()) {
            return;
        }

        try {
            String currentContextKey = adapter.contextKey();
            if (!Objects.equals(adapterContextKey, currentContextKey)) {
                adapter.applyServerSnapshot(serverWaypoints);
                localBaseline = List.copyOf(adapter.readObservedWaypoints());
                adapterContextKey = currentContextKey;
                noteAdapterSuccess();
                return;
            }
        } catch (Exception e) {
            noteAdapterFailure("Could not refresh " + adapter.name() + " after its active waypoint context changed.", e);
            return;
        }

        if (mutationInFlight || ticks % POLL_INTERVAL_TICKS != 0) {
            return;
        }

        try {
            detectLocalChanges();
            noteAdapterSuccess();
        } catch (Exception e) {
            noteAdapterFailure("Could not inspect " + adapter.name() + " waypoints for local changes.", e);
        }
    }

    public void reset() {
        lastRevision = -1L;
        serverWaypoints = List.of();
        localBaseline = List.of();
        pendingSnapshot = null;
        adapterContextKey = null;
        mutationInFlight = false;
        ticks = 0;
        consecutiveAdapterFailures = 0;
        adapterBackoffUntilTick = 0;

        try {
            adapter.resetSession();
        } catch (RuntimeException e) {
            WaypointSync.LOGGER.warn("Could not reset {} session state cleanly.", adapter.name(), e);
        }
    }

    private void applyPendingSnapshot() {
        SnapshotPayload payload = pendingSnapshot;
        if (payload == null || !adapter.isReady()) {
            return;
        }

        try {
            adapter.applyServerSnapshot(payload.waypoints());

            this.serverWaypoints = List.copyOf(payload.waypoints());
            this.localBaseline = List.copyOf(adapter.readObservedWaypoints());
            this.lastRevision = payload.revision();
            this.adapterContextKey = adapter.contextKey();
            this.pendingSnapshot = null;
            this.mutationInFlight = false;
            noteAdapterSuccess();
        } catch (Exception e) {
            noteAdapterFailure(
                    "Could not apply server waypoint revision " + payload.revision() + " to " + adapter.name()
                            + ". The snapshot will be retried.",
                    e
            );
        }
    }

    private void detectLocalChanges() throws Exception {
        List<ObservedWaypoint> current = List.copyOf(adapter.readObservedWaypoints());
        Map<Object, ObservedWaypoint> previousByIdentity = byIdentity(localBaseline);
        Map<Object, ObservedWaypoint> currentByIdentity = byIdentity(current);

        List<ObservedWaypoint> disappeared = new ArrayList<>();
        List<ObservedWaypoint> appeared = new ArrayList<>();

        // Send at most one mutation per authoritative revision. The server answers every accepted
        // or rejected mutation with a snapshot, which advances/re-establishes the baseline before
        // another local change is uploaded. This prevents a burst of GUI changes from racing each
        // other with the same revision.
        for (ObservedWaypoint previous : localBaseline) {
            ObservedWaypoint now = currentByIdentity.get(previous.localIdentity());
            if (now == null) {
                disappeared.add(previous);
                continue;
            }

            if (!VisibleWaypoint.of(previous.waypoint()).equals(VisibleWaypoint.of(now.waypoint()))
                    && sendEditOrCreate(previous.waypoint(), now.waypoint())) {
                mutationInFlight = true;
                return;
            }
        }

        for (ObservedWaypoint now : current) {
            if (!previousByIdentity.containsKey(now.localIdentity())) {
                appeared.add(now);
            }
        }

        RecreatedEdit recreatedEdit = findRecreatedEdit(disappeared, appeared);
        if (recreatedEdit != null && ClientPlayNetworking.canSend(EditWaypointPayload.ID)) {
            ClientPlayNetworking.send(new EditWaypointPayload(
                    lastRevision,
                    recreatedEdit.oldServerWaypoint(),
                    mergeVisibleEdit(recreatedEdit.oldServerWaypoint(), recreatedEdit.newObserved().waypoint())
            ));
            mutationInFlight = true;
            return;
        }

        for (ObservedWaypoint removed : disappeared) {
            Waypoint serverWaypoint = findServerEquivalent(removed.waypoint());
            if (serverWaypoint != null && ClientPlayNetworking.canSend(DeleteWaypointPayload.ID)) {
                ClientPlayNetworking.send(new DeleteWaypointPayload(lastRevision, serverWaypoint));
                mutationInFlight = true;
                return;
            }
        }

        for (ObservedWaypoint added : appeared) {
            if (findServerEquivalent(added.waypoint()) != null) {
                continue;
            }
            if (ClientPlayNetworking.canSend(CreateWaypointPayload.ID)) {
                ClientPlayNetworking.send(new CreateWaypointPayload(lastRevision, added.waypoint()));
                mutationInFlight = true;
                return;
            }
        }

        // No upload was required (for example, the minimap recreated an already-authoritative
        // presentation object). Accept the current local view as the new observation baseline.
        localBaseline = current;
    }

    private boolean sendEditOrCreate(Waypoint previousVisible, Waypoint currentVisible) {
        Waypoint oldServerWaypoint = findServerEquivalent(previousVisible);
        if (oldServerWaypoint != null && ClientPlayNetworking.canSend(EditWaypointPayload.ID)) {
            ClientPlayNetworking.send(new EditWaypointPayload(
                    lastRevision,
                    oldServerWaypoint,
                    mergeVisibleEdit(oldServerWaypoint, currentVisible)
            ));
            return true;
        }

        // A pre-existing local waypoint that was not in server state becomes synchronized when the
        // user edits it. This preserves the v1.0.0 rule that player-created/managed waypoints are
        // shared without force-importing every local waypoint immediately on join.
        if (findServerEquivalent(currentVisible) == null && ClientPlayNetworking.canSend(CreateWaypointPayload.ID)) {
            ClientPlayNetworking.send(new CreateWaypointPayload(lastRevision, currentVisible));
            return true;
        }
        return false;
    }

    private RecreatedEdit findRecreatedEdit(
            List<ObservedWaypoint> disappeared,
            List<ObservedWaypoint> appeared
    ) {
        for (ObservedWaypoint oldObserved : disappeared) {
            Waypoint oldServerWaypoint = findServerEquivalent(oldObserved.waypoint());
            if (oldServerWaypoint == null) {
                continue;
            }

            int newIndex = bestReplacementIndex(oldObserved.waypoint(), appeared);
            if (newIndex >= 0) {
                return new RecreatedEdit(oldServerWaypoint, appeared.get(newIndex));
            }
        }
        return null;
    }

    private static int bestReplacementIndex(Waypoint oldWaypoint, List<ObservedWaypoint> candidates) {
        int bestIndex = -1;
        int bestScore = 0;
        boolean tied = false;

        for (int i = 0; i < candidates.size(); i++) {
            int score = replacementScore(oldWaypoint, candidates.get(i).waypoint());
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
                tied = false;
            } else if (score > 0 && score == bestScore) {
                tied = true;
            }
        }

        // Require a strong, unique relationship. Ambiguous replacements are safer as separate
        // delete/create operations than as an edit of the wrong shared waypoint.
        return bestScore >= 2 && !tied ? bestIndex : -1;
    }

    private static int replacementScore(Waypoint oldWaypoint, Waypoint newWaypoint) {
        int score = 0;
        if (oldWaypoint.name().equals(newWaypoint.name())) {
            score++;
        }
        if (oldWaypoint.dimension().equals(newWaypoint.dimension())) {
            score++;
        }
        if (oldWaypoint.x() == newWaypoint.x()
                && Objects.equals(oldWaypoint.y(), newWaypoint.y())
                && oldWaypoint.z() == newWaypoint.z()) {
            score++;
        }
        if (oldWaypoint.initials().equals(newWaypoint.initials())) {
            score++;
        }
        return score;
    }

    private Waypoint findServerEquivalent(Waypoint localWaypoint) {
        VisibleWaypoint visible = VisibleWaypoint.of(localWaypoint);
        for (Waypoint candidate : serverWaypoints) {
            if (VisibleWaypoint.of(candidate).equals(visible)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<Object, ObservedWaypoint> byIdentity(List<ObservedWaypoint> waypoints) {
        Map<Object, ObservedWaypoint> result = new LinkedHashMap<>();
        for (ObservedWaypoint waypoint : waypoints) {
            result.putIfAbsent(waypoint.localIdentity(), waypoint);
        }
        return result;
    }

    private static Waypoint mergeVisibleEdit(Waypoint oldServerWaypoint, Waypoint visibleEdit) {
        return new Waypoint(
                visibleEdit.name(),
                visibleEdit.initials(),
                visibleEdit.x(),
                visibleEdit.y(),
                visibleEdit.z(),
                visibleEdit.dimension(),
                visibleEdit.color(),
                visibleEdit.disabled(),
                oldServerWaypoint.type(),
                oldServerWaypoint.set(),
                oldServerWaypoint.rotateOnTeleport(),
                oldServerWaypoint.teleportYaw(),
                oldServerWaypoint.visibilityType(),
                oldServerWaypoint.destination()
        );
    }

    private void noteAdapterSuccess() {
        consecutiveAdapterFailures = 0;
        adapterBackoffUntilTick = 0;
    }

    private void noteAdapterFailure(String message, Exception error) {
        consecutiveAdapterFailures++;
        if (consecutiveAdapterFailures == 1
                || consecutiveAdapterFailures == MAX_CONSECUTIVE_ADAPTER_FAILURES_BEFORE_BACKOFF) {
            WaypointSync.LOGGER.error(message, error);
        }

        if (consecutiveAdapterFailures >= MAX_CONSECUTIVE_ADAPTER_FAILURES_BEFORE_BACKOFF) {
            adapterBackoffUntilTick = ticks + ADAPTER_FAILURE_BACKOFF_TICKS;
            consecutiveAdapterFailures = 0;
            WaypointSync.LOGGER.warn(
                    "Temporarily pausing {} waypoint integration after repeated adapter errors; networking will remain active.",
                    adapter.name()
            );
        }
    }

    private record RecreatedEdit(Waypoint oldServerWaypoint, ObservedWaypoint newObserved) {
    }

    private record VisibleWaypoint(
            String name,
            int x,
            Integer y,
            int z,
            String dimension,
            int color,
            boolean disabled
    ) {
        private VisibleWaypoint {
            Objects.requireNonNull(name);
            Objects.requireNonNull(dimension);
        }

        static VisibleWaypoint of(Waypoint waypoint) {
            return new VisibleWaypoint(
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
}
