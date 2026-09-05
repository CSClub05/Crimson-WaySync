# Crimson WaySync

## What It Is

Crimson WaySync is a Fabric mod for Minecraft Java Edition that lets players on the same server share minimap waypoints automatically.

When a player creates, edits, or deletes a waypoint, other players using Crimson WaySync can receive the same update without having to manually copy coordinates or recreate the waypoint themselves.

## Supported Minecraft and Minimap Environments

Crimson WaySync is designed for Minecraft Java Edition using Fabric.

Supported minimap mods:

- Xaero's Minimap
- VoxelMap

Crimson WaySync must be installed **on the server and on participating players' clients**. Players also need one of the supported minimap mods to view and manage synchronized waypoints.

## Server Configuration and Use

Server owners can install Crimson WaySync on their Fabric server and use its configuration file to enable optional features such as Discord notifications.

Waypoint data is preserved between server restarts.

Server owners who have access to the server files can also make changes directly to the shared waypoint collection. Those changes can then be reflected for connected players.

## Discord Notifications

Server owners can optionally configure a Discord webhook. If no webhook is configured, waypoint synchronization can still be used normally.

When enabled, Crimson WaySync can send a Discord message when a player manually:

- Creates a waypoint
- Edits a waypoint
- Deletes a waypoint

Changes made directly to the server's waypoint files do not send Discord notifications.

## Authorship

Crimson WaySync is made by **CSClub05**.

Crimson WaySync is released under the **MIT License**.
