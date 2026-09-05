package com.csclub05.waypointsync.server;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.config.WaypointSyncConfig;
import com.csclub05.waypointsync.discord.DiscordWebhookService;
import com.csclub05.waypointsync.model.Waypoint;
import com.csclub05.waypointsync.model.WaypointSnapshot;
import com.csclub05.waypointsync.network.NetworkManager;
import com.csclub05.waypointsync.network.payload.SnapshotPayload;
import com.csclub05.waypointsync.storage.FileWaypointRepository;
import com.csclub05.waypointsync.storage.WaypointFileWatcher;
import com.csclub05.waypointsync.storage.WaypointStorageConflictException;
import com.csclub05.waypointsync.storage.WaypointStorageException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerWaypointService implements AutoCloseable {
    private final MinecraftServer server;
    private final FileWaypointRepository repository;
    private final WaypointSyncConfig config;
    private final DiscordWebhookService discord;

    private List<Waypoint> waypoints = List.of();
    private long revision = 0L;
    private String acceptedFingerprint = "";
    private String rejectedFingerprint = "";
    private WaypointFileWatcher watcher;
    private boolean stateAvailable;
    private boolean writesEnabled;
    private long ticks;

    public ServerWaypointService(
            MinecraftServer server,
            FileWaypointRepository repository,
            WaypointSyncConfig config,
            DiscordWebhookService discord
    ) {
        this.server = server;
        this.repository = repository;
        this.config = config;
        this.discord = discord;
    }

    public void start() {
        try {
            repository.ensureFirstRunLayout();
            List<Waypoint> loaded = repository.loadAll();

            this.waypoints = List.copyOf(loaded);
            this.revision = 1L;
            this.acceptedFingerprint = repository.fingerprint();
            this.rejectedFingerprint = "";
            this.stateAvailable = true;
            this.writesEnabled = true;

            WaypointSync.LOGGER.info(
                    "Loaded {} synchronized waypoint(s) across the server waypoint files.",
                    waypoints.size()
            );
        } catch (WaypointStorageException e) {
            // There is deliberately no write-back/recovery path here. A failed startup read must never
            // replace the user's existing waypoint data with an empty/default state.
            this.stateAvailable = false;
            this.writesEnabled = false;
            WaypointSync.LOGGER.error(
                    "Crimson WaySync could not safely load server waypoint storage. "
                            + "Existing files were not reset and player waypoint writes are disabled until a valid reload succeeds.",
                    e
            );
        }

        if (config.storage.watchForExternalChanges) {
            try {
                watcher = new WaypointFileWatcher(
                        repository.root(),
                        () -> server.execute(this::reloadFromDiskIfChanged)
                );
                watcher.start();
            } catch (IOException e) {
                WaypointSync.LOGGER.error(
                        "Could not start the waypoint file watcher. Periodic disk checks will remain active.",
                        e
                );
            }
        }
    }

    public void tick() {
        ticks++;
        long intervalTicks = Math.max(20L, (long) config.storage.fallbackCheckSeconds * 20L);
        if (ticks % intervalTicks == 0L) {
            reloadFromDiskIfChanged();
        }
    }

    public WaypointSnapshot snapshot() {
        return new WaypointSnapshot(revision, waypoints);
    }

    public void sendSnapshot(ServerPlayerEntity player) {
        if (!stateAvailable) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, SnapshotPayload.ID)) {
            ServerPlayNetworking.send(player, new SnapshotPayload(snapshot()));
        }
    }

    public void clientReady(ServerPlayerEntity player, int protocolVersion) {
        if (protocolVersion != NetworkManager.PROTOCOL_VERSION) {
            WaypointSync.LOGGER.warn(
                    "Player {} connected with unsupported Crimson WaySync protocol {} (server protocol {}).",
                    player.getName().getString(),
                    protocolVersion,
                    NetworkManager.PROTOCOL_VERSION
            );
            return;
        }
        sendSnapshot(player);
    }

    public void createWaypoint(ServerPlayerEntity player, long expectedRevision, Waypoint waypoint) {
        if (expectedRevision > revision) {
            rejectMutation(player, expectedRevision, "client revision is ahead of the server");
            return;
        }
        if (!writesEnabled) {
            rejectMutation(player, expectedRevision, "server waypoint storage is not currently writable");
            return;
        }
        if (waypoints.contains(waypoint)) {
            rejectMutation(player, expectedRevision, "waypoint already exists");
            return;
        }

        List<Waypoint> candidate = new ArrayList<>(waypoints);
        candidate.add(waypoint);

        if (commitPlayerChange(candidate, Set.of(waypoint.dimension()))) {
            discord.waypointCreated(player.getName().getString(), waypoint);
        } else {
            sendSnapshot(player);
        }
    }

    public void deleteWaypoint(ServerPlayerEntity player, long expectedRevision, Waypoint waypoint) {
        if (expectedRevision > revision) {
            rejectMutation(player, expectedRevision, "client revision is ahead of the server");
            return;
        }
        if (!writesEnabled) {
            rejectMutation(player, expectedRevision, "server waypoint storage is not currently writable");
            return;
        }
        int index = waypoints.indexOf(waypoint);
        if (index < 0) {
            rejectMutation(player, expectedRevision, "waypoint no longer exists in the expected state");
            return;
        }

        List<Waypoint> candidate = new ArrayList<>(waypoints);
        Waypoint removed = candidate.remove(index);

        if (commitPlayerChange(candidate, Set.of(removed.dimension()))) {
            discord.waypointDeleted(player.getName().getString(), removed);
        } else {
            sendSnapshot(player);
        }
    }

    public void editWaypoint(
            ServerPlayerEntity player,
            long expectedRevision,
            Waypoint oldWaypoint,
            Waypoint newWaypoint
    ) {
        if (expectedRevision > revision) {
            rejectMutation(player, expectedRevision, "client revision is ahead of the server");
            return;
        }
        if (!writesEnabled) {
            rejectMutation(player, expectedRevision, "server waypoint storage is not currently writable");
            return;
        }
        int index = waypoints.indexOf(oldWaypoint);
        if (index < 0) {
            rejectMutation(player, expectedRevision, "waypoint changed before this edit was applied");
            return;
        }

        int duplicateIndex = waypoints.indexOf(newWaypoint);
        if (duplicateIndex >= 0 && duplicateIndex != index) {
            rejectMutation(player, expectedRevision, "edited waypoint would duplicate an existing waypoint");
            return;
        }

        if (oldWaypoint.equals(newWaypoint)) {
            sendSnapshot(player);
            return;
        }

        List<Waypoint> candidate = new ArrayList<>(waypoints);
        candidate.set(index, newWaypoint);

        Set<String> affected = new HashSet<>();
        affected.add(oldWaypoint.dimension());
        affected.add(newWaypoint.dimension());

        if (commitPlayerChange(candidate, affected)) {
            discord.waypointEdited(player.getName().getString(), oldWaypoint, newWaypoint);
        } else {
            sendSnapshot(player);
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    private void rejectMutation(ServerPlayerEntity player, long expectedRevision, String reason) {
        WaypointSync.LOGGER.debug(
                "Rejected waypoint mutation from {} at client revision {} while server revision is {}: {}.",
                player.getName().getString(),
                expectedRevision,
                revision,
                reason
        );
        sendSnapshot(player);
    }

    private boolean commitPlayerChange(List<Waypoint> candidate, Set<String> affectedDimensions) {
        try {
            Map<String, List<Waypoint>> grouped = repository.groupByDimension(candidate);
            String savedFingerprint = repository.saveDimensionsIfUnchanged(
                    grouped,
                    affectedDimensions,
                    acceptedFingerprint
            );

            this.waypoints = List.copyOf(candidate);
            this.revision++;
            this.acceptedFingerprint = savedFingerprint;
            this.rejectedFingerprint = "";
            this.stateAvailable = true;
            this.writesEnabled = true;

            broadcastSnapshot();
            return true;
        } catch (WaypointStorageConflictException e) {
            WaypointSync.LOGGER.info(
                    "A player waypoint change was deferred because the server waypoint files changed externally first. "
                            + "The external edit will be loaded and the client will be resynchronized."
            );
            reloadFromDiskIfChanged();
            return false;
        } catch (WaypointStorageException e) {
            WaypointSync.LOGGER.error(
                    "A player waypoint change could not be persisted. The in-memory mutation was not committed. "
                            + "Waypoint storage will be rechecked before accepting more writes.",
                    e
            );
            recoverAfterFailedPlayerWrite();
            return false;
        }
    }


    private void recoverAfterFailedPlayerWrite() {
        writesEnabled = false;
        try {
            String currentFingerprint = repository.fingerprint();
            if (currentFingerprint.equals(acceptedFingerprint)) {
                // The repository successfully rolled the disk back to the last accepted state.
                writesEnabled = stateAvailable;
                return;
            }
        } catch (WaypointStorageException ignored) {
            // reloadFromDiskIfChanged below will keep writes disabled and report the read failure.
        }
        reloadFromDiskIfChanged();
    }

    private void reloadFromDiskIfChanged() {
        try {
            String currentFingerprint = repository.fingerprint();
            if (currentFingerprint.equals(acceptedFingerprint) || currentFingerprint.equals(rejectedFingerprint)) {
                return;
            }

            List<Waypoint> candidate = repository.loadAll();
            boolean semanticChange = !candidate.equals(waypoints) || !stateAvailable;

            this.acceptedFingerprint = currentFingerprint;
            this.rejectedFingerprint = "";
            this.stateAvailable = true;
            this.writesEnabled = true;

            if (!semanticChange) {
                WaypointSync.LOGGER.debug(
                        "Reloaded waypoint storage metadata without changing the synchronized waypoint state."
                );
                return;
            }

            int previousCount = waypoints.size();
            this.waypoints = List.copyOf(candidate);
            this.revision++;

            WaypointSync.LOGGER.info(
                    "Applied externally edited waypoint files; synchronized waypoint count changed from {} to {}.",
                    previousCount,
                    waypoints.size()
            );

            // External edits intentionally do not trigger Discord notifications.
            broadcastSnapshot();
        } catch (WaypointStorageException e) {
            try {
                rejectedFingerprint = repository.fingerprint();
            } catch (WaypointStorageException ignored) {
                rejectedFingerprint = "";
            }

            // Never replace current state or write to the user's file after a failed parse/read.
            // Keep serving the last valid snapshot, but block player writes so a later in-game change cannot
            // accidentally overwrite the administrator's invalid/conflicted file. A valid disk reload re-enables writes.
            this.writesEnabled = false;
            WaypointSync.LOGGER.error(
                    "Ignored an invalid external waypoint-file change. "
                            + "The last valid server waypoint state remains active, the edited file was left untouched, "
                            + "and player waypoint writes are disabled until the files are valid again.",
                    e
            );
        }
    }

    private void broadcastSnapshot() {
        SnapshotPayload payload = new SnapshotPayload(snapshot());
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(player, SnapshotPayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
