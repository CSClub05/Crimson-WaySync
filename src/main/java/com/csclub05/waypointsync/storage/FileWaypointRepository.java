package com.csclub05.waypointsync.storage;

import com.csclub05.waypointsync.model.Waypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class FileWaypointRepository {
    private final Path root;
    private final XaeroWaypointCodec codec = new XaeroWaypointCodec();

    public FileWaypointRepository(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Creates starter files only when the waypoint storage root itself has never existed.
     * Existing storage is never regenerated or normalized during startup.
     */
    public synchronized void ensureFirstRunLayout() throws WaypointStorageException {
        if (Files.exists(root)) {
            return;
        }

        try {
            Files.createDirectories(root);
            createFirstRunFile("minecraft:overworld");
            createFirstRunFile("minecraft:the_nether");
            createFirstRunFile("minecraft:the_end");
        } catch (IOException e) {
            throw new WaypointStorageException("Could not create first-run waypoint storage.", e);
        }
    }

    public synchronized List<Waypoint> loadAll() throws WaypointStorageException {
        List<Waypoint> result = new ArrayList<>();

        for (Path file : listWaypointFiles()) {
            String dimension = dimensionFor(file);
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new WaypointStorageException("Could not read waypoint file " + file, e);
            }

            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("sets:")) {
                    continue;
                }
                if (!trimmed.startsWith("waypoint:")) {
                    // Preserve compatible/unknown metadata. Only waypoint-prefixed lines are parsed.
                    continue;
                }
                result.add(codec.decode(trimmed, dimension, i + 1));
            }
        }

        return List.copyOf(result);
    }

    /**
     * Saves a set of dimensions only if the waypoint storage still matches the fingerprint that the
     * caller last accepted. This prevents an in-game mutation from silently overwriting an administrator's
     * external file edit that has not yet been processed by the file watcher.
     */
    public synchronized String saveDimensionsIfUnchanged(
            Map<String, List<Waypoint>> completeState,
            Set<String> dimensions,
            String expectedFingerprint
    ) throws WaypointStorageException {
        String before = fingerprint();
        if (!before.equals(expectedFingerprint)) {
            throw new WaypointStorageConflictException(
                    "Waypoint storage changed on disk before the player mutation could be saved."
            );
        }

        if (dimensions.isEmpty()) {
            return before;
        }

        Map<Path, PreparedWrite> prepared = new LinkedHashMap<>();
        try {
            for (String dimension : dimensions.stream().sorted().toList()) {
                Path target = pathForDimension(dimension);
                Files.createDirectories(target.getParent());

                List<Waypoint> waypoints = completeState.getOrDefault(dimension, List.of());
                List<String> output = buildOutput(target, waypoints);
                Path temp = target.resolveSibling(target.getFileName() + ".waypointsync-" + UUID.randomUUID() + ".tmp");
                Files.write(temp, output, StandardCharsets.UTF_8);
                prepared.put(target, new PreparedWrite(temp, Files.exists(target) ? Files.readAllBytes(target) : null));
            }

            // Re-check after preparing temporary files. Temporary files do not participate in fingerprinting,
            // so any difference here came from a real external change.
            String immediatelyBeforeCommit = fingerprint();
            if (!immediatelyBeforeCommit.equals(expectedFingerprint)) {
                throw new WaypointStorageConflictException(
                        "Waypoint storage changed on disk while a player mutation was being prepared."
                );
            }

            List<Path> committed = new ArrayList<>();
            try {
                for (Map.Entry<Path, PreparedWrite> entry : prepared.entrySet()) {
                    replaceAtomically(entry.getValue().temp(), entry.getKey());
                    committed.add(entry.getKey());
                }
            } catch (IOException commitFailure) {
                rollback(committed, prepared);
                throw commitFailure;
            }

            return fingerprint();
        } catch (WaypointStorageException e) {
            cleanupTemps(prepared);
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            cleanupTemps(prepared);
            throw new WaypointStorageException("Could not save waypoint storage transaction.", e);
        }
    }

    public synchronized String fingerprint() throws WaypointStorageException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : listWaypointFiles()) {
                Path relative = root.relativize(file);
                digest.update(relative.toString().getBytes(StandardCharsets.UTF_8));
                FileTime modified = Files.getLastModifiedTime(file);
                digest.update(Long.toString(modified.toMillis()).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new WaypointStorageException("Could not fingerprint waypoint storage.", e);
        }
    }

    private List<String> buildOutput(Path target, List<Waypoint> waypoints) throws IOException {
        List<String> preserved = Files.exists(target)
                ? readPreservedLines(target)
                : new ArrayList<>(List.of(XaeroWaypointCodec.HEADER));

        if (preserved.stream().noneMatch(line -> line.trim().equals(XaeroWaypointCodec.HEADER))) {
            preserved.add(0, XaeroWaypointCodec.HEADER);
        }

        List<String> output = new ArrayList<>(preserved);
        if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) {
            output.add("");
        }
        for (Waypoint waypoint : waypoints) {
            output.add(codec.encode(waypoint));
        }
        return output;
    }

    private void rollback(List<Path> committed, Map<Path, PreparedWrite> prepared) {
        for (int i = committed.size() - 1; i >= 0; i--) {
            Path target = committed.get(i);
            PreparedWrite write = prepared.get(target);
            try {
                if (write.originalBytes() == null) {
                    Files.deleteIfExists(target);
                } else {
                    Path rollbackTemp = target.resolveSibling(
                            target.getFileName() + ".waypointsync-rollback-" + UUID.randomUUID() + ".tmp"
                    );
                    Files.write(rollbackTemp, write.originalBytes());
                    replaceAtomically(rollbackTemp, target);
                }
            } catch (IOException ignored) {
                // The caller will keep the in-memory state unchanged and log the transaction failure.
                // A subsequent disk reload will decide whether writes remain enabled.
            }
        }
    }

    private void cleanupTemps(Map<Path, PreparedWrite> prepared) {
        for (PreparedWrite write : prepared.values()) {
            try {
                Files.deleteIfExists(write.temp());
            } catch (IOException ignored) {
            }
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<String> readPreservedLines(Path file) throws IOException {
        List<String> preserved = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.trim().startsWith("waypoint:")) {
                preserved.add(line);
            }
        }

        while (!preserved.isEmpty() && preserved.get(preserved.size() - 1).isBlank()) {
            preserved.remove(preserved.size() - 1);
        }
        return preserved;
    }

    private void createFirstRunFile(String dimension) throws IOException {
        Path file = pathForDimension(dimension);
        Files.createDirectories(file.getParent());
        if (Files.notExists(file)) {
            Files.writeString(
                    file,
                    XaeroWaypointCodec.HEADER + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private List<Path> listWaypointFiles() throws WaypointStorageException {
        if (Files.notExists(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        } catch (IOException e) {
            throw new WaypointStorageException("Could not enumerate waypoint files under " + root, e);
        }
    }

    public Map<String, List<Waypoint>> groupByDimension(List<Waypoint> waypoints) {
        Map<String, List<Waypoint>> grouped = new LinkedHashMap<>();
        for (Waypoint waypoint : waypoints) {
            grouped.computeIfAbsent(waypoint.dimension(), ignored -> new ArrayList<>()).add(waypoint);
        }
        return grouped;
    }

    private Path pathForDimension(String dimension) {
        int split = dimension.indexOf(':');
        if (split <= 0 || split == dimension.length() - 1) {
            throw new IllegalArgumentException("Invalid dimension identifier: " + dimension);
        }

        String namespace = dimension.substring(0, split);
        String path = dimension.substring(split + 1);

        Path namespaceRoot = root.resolve(namespace).normalize();
        Path resolved = namespaceRoot.resolve(path + ".txt").normalize();
        if (!resolved.startsWith(namespaceRoot)) {
            throw new IllegalArgumentException("Dimension path escapes waypoint storage: " + dimension);
        }
        return resolved;
    }

    private String dimensionFor(Path file) throws WaypointStorageException {
        Path relative = root.relativize(file);
        if (relative.getNameCount() < 2) {
            throw new WaypointStorageException(
                    "Waypoint files must be stored as <namespace>/<dimension>.txt: " + file
            );
        }

        String namespace = relative.getName(0).toString();
        Path tail = relative.subpath(1, relative.getNameCount());
        String path = tail.toString().replace('\\', '/');
        path = path.substring(0, path.length() - ".txt".length());
        return namespace + ":" + path;
    }

    private record PreparedWrite(Path temp, byte[] originalBytes) {
    }
}
