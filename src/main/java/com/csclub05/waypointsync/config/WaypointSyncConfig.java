package com.csclub05.waypointsync.config;

public final class WaypointSyncConfig {
    public int version = 1;
    public Discord discord = new Discord();
    public Storage storage = new Storage();

    public static final class Discord {
        public String webhookUrl = "";

        private void normalize() {
            if (webhookUrl == null) {
                webhookUrl = "";
            } else {
                webhookUrl = webhookUrl.trim();
            }
        }
    }

    public static final class Storage {
        public boolean watchForExternalChanges = true;
        public int fallbackCheckSeconds = 5;

        private void normalize() {
            if (fallbackCheckSeconds < 1) {
                fallbackCheckSeconds = 1;
            }
        }
    }

    public void normalize() {
        if (discord == null) {
            discord = new Discord();
        }
        if (storage == null) {
            storage = new Storage();
        }

        discord.normalize();
        storage.normalize();
    }
}
