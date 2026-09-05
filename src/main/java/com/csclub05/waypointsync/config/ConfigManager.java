package com.csclub05.waypointsync.config;

import com.csclub05.waypointsync.WaypointSync;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve(WaypointSync.MOD_ID);
    private static final Path FILE = DIRECTORY.resolve("config.json");

    private ConfigManager() {
    }

    public static Path directory() {
        return DIRECTORY;
    }

    public static Path file() {
        return FILE;
    }

    public static WaypointSyncConfig load() {
        try {
            Files.createDirectories(DIRECTORY);

            if (Files.notExists(FILE)) {
                WaypointSyncConfig defaults = normalizedDefaults();
                writeFirstRunConfig(defaults);
                WaypointSync.LOGGER.info("Created Crimson WaySync configuration at {}.", FILE);
                return defaults;
            }

            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            WaypointSyncConfig config = GSON.fromJson(json, WaypointSyncConfig.class);
            if (config == null) {
                throw new JsonParseException("Configuration file contained no configuration object.");
            }

            config.normalize();
            return config;
        } catch (Exception e) {
            // A malformed or unreadable administrator-owned config must never be replaced automatically.
            // Falling back in memory keeps the server usable while preserving the exact file for correction.
            WaypointSync.LOGGER.error(
                    "Could not load {}. The existing configuration was left untouched and safe defaults will be used in memory for this server session.",
                    FILE,
                    e
            );
            return normalizedDefaults();
        }
    }

    private static WaypointSyncConfig normalizedDefaults() {
        WaypointSyncConfig defaults = new WaypointSyncConfig();
        defaults.normalize();
        return defaults;
    }

    private static void writeFirstRunConfig(WaypointSyncConfig config) throws IOException {
        try {
            Files.writeString(
                    FILE,
                    GSON.toJson(config) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another initializer/process created the file between the existence check and CREATE_NEW.
            // Never truncate it; load() on the next server start will read the administrator-owned file.
        }
    }
}
