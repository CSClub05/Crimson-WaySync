package com.csclub05.waypointsync.storage;

import com.csclub05.waypointsync.WaypointSync;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaypointFileWatcher implements AutoCloseable {
    private static final long DEBOUNCE_MILLIS = 200L;

    private final Path root;
    private final Runnable onChange;
    private final WatchService watchService;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WaypointSync-FileWatcher-Debounce");
        thread.setDaemon(true);
        return thread;
    });

    private final Object debounceLock = new Object();
    private ScheduledFuture<?> pendingNotification;
    private Thread thread;

    public WaypointFileWatcher(Path root, Runnable onChange) throws IOException {
        this.root = root;
        this.onChange = onChange;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursively(root);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        thread = Thread.ofPlatform()
                .daemon(true)
                .name("WaypointSync-FileWatcher")
                .start(this::watchLoop);
    }

    @Override
    public void close() {
        running.set(false);
        synchronized (debounceLock) {
            if (pendingNotification != null) {
                pendingNotification.cancel(false);
                pendingNotification = null;
            }
        }
        debounceExecutor.shutdownNow();
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();
                Path directory = directories.get(key);
                boolean relevant = false;

                if (directory != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            relevant = true;
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                        Path changed = directory.resolve(pathEvent.context());

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                            registerRecursively(changed);
                            relevant = true;
                            continue;
                        }

                        String fileName = changed.getFileName().toString();
                        if (fileName.endsWith(".txt") || event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            relevant = true;
                        }
                    }
                }

                if (!key.reset()) {
                    directories.remove(key);
                }

                if (relevant) {
                    scheduleNotification();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (Exception e) {
                WaypointSync.LOGGER.error("Waypoint file watcher failed while processing an event.", e);
            }
        }
    }

    private void scheduleNotification() {
        synchronized (debounceLock) {
            if (!running.get()) {
                return;
            }
            if (pendingNotification != null) {
                pendingNotification.cancel(false);
            }
            pendingNotification = debounceExecutor.schedule(() -> {
                synchronized (debounceLock) {
                    pendingNotification = null;
                }
                if (running.get()) {
                    onChange.run();
                }
            }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void registerRecursively(Path start) throws IOException {
        if (Files.notExists(start)) {
            return;
        }

        try (var stream = Files.walk(start)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                WatchKey key = directory.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                directories.put(key, directory);
            }
        }
    }
}
