package com.csclub05.waypointsync.discord;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.model.Waypoint;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DiscordWebhookService {
    private static final Gson GSON = new Gson();
    private static final int DISCORD_CONTENT_LIMIT = 2_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final URI webhookUri;
    private final HttpClient httpClient;

    public DiscordWebhookService(String webhookUrl) {
        this.webhookUri = validateWebhookUrl(webhookUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        if (webhookUri == null) {
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                WaypointSync.LOGGER.warn(
                        "Discord webhook configuration is invalid. Discord notifications are disabled for this server session."
                );
            }
        } else {
            WaypointSync.LOGGER.info("Discord waypoint notifications are enabled.");
        }
    }

    public boolean isConfigured() {
        return webhookUri != null;
    }

    public void waypointCreated(String playerName, Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        send(safePlayerName(playerName) + " created a new waypoint called " + waypoint.name()
                + " at " + waypoint.displayCoordinates()
                + " in " + waypoint.dimension());
    }

    public void waypointDeleted(String playerName, Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        send(safePlayerName(playerName) + " deleted a waypoint called " + waypoint.name()
                + " at " + waypoint.displayCoordinates()
                + " in " + waypoint.dimension());
    }

    public void waypointEdited(String playerName, Waypoint oldWaypoint, Waypoint newWaypoint) {
        Objects.requireNonNull(oldWaypoint, "oldWaypoint");
        Objects.requireNonNull(newWaypoint, "newWaypoint");
        send(safePlayerName(playerName) + " edited a waypoint called " + oldWaypoint.name()
                + " at " + oldWaypoint.displayCoordinates()
                + " in " + oldWaypoint.dimension()
                + ". The waypoint is now called " + newWaypoint.name()
                + " at " + newWaypoint.displayCoordinates()
                + " in " + newWaypoint.dimension());
    }

    private void send(String content) {
        if (!isConfigured()) {
            return;
        }

        String boundedContent = boundDiscordContent(content);
        Map<String, Object> bodyObject = Map.of(
                "content", boundedContent,
                "allowed_mentions", Map.of("parse", List.of())
        );
        String body = GSON.toJson(bodyObject);

        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        // sendAsync keeps Discord latency completely off the Minecraft server thread. Notification
        // delivery is intentionally downstream from persistence; a failure here never rolls back a waypoint.
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        WaypointSync.LOGGER.warn(
                                "Could not send Discord waypoint notification ({}). Waypoint synchronization was not affected.",
                                error.getClass().getSimpleName()
                        );
                        return;
                    }

                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        WaypointSync.LOGGER.warn(
                                "Discord waypoint notification returned HTTP status {}. Waypoint synchronization was not affected.",
                                status
                        );
                    }
                });
    }

    static URI validateWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }

        final URI uri;
        try {
            uri = URI.create(webhookUrl.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return null;
        }

        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!(host.equals("discord.com")
                || host.endsWith(".discord.com")
                || host.equals("discordapp.com")
                || host.endsWith(".discordapp.com"))) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith("/api/webhooks/")) {
            return null;
        }

        String[] parts = path.split("/");
        // Expected minimum: /api/webhooks/{id}/{token}
        if (parts.length < 5 || parts[3].isBlank() || parts[4].isBlank()) {
            return null;
        }

        return uri;
    }

    static String boundDiscordContent(String content) {
        String value = content == null ? "" : content;
        if (value.length() <= DISCORD_CONTENT_LIMIT) {
            return value;
        }

        // Discord's content limit is UTF-16-code-unit based closely enough for Java String length,
        // but avoid cutting through a surrogate pair at the boundary.
        int end = DISCORD_CONTENT_LIMIT - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private static String safePlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return "Unknown player";
        }
        return playerName;
    }
}
