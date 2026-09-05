package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.network.RegistryByteBuf;

final class WaypointPacketCodec {
    private WaypointPacketCodec() {
    }

    static void write(RegistryByteBuf buf, Waypoint waypoint) {
        buf.writeString(waypoint.name());
        buf.writeString(waypoint.initials());
        buf.writeInt(waypoint.x());
        buf.writeBoolean(waypoint.y() != null);
        if (waypoint.y() != null) {
            buf.writeInt(waypoint.y());
        }
        buf.writeInt(waypoint.z());
        buf.writeString(waypoint.dimension());
        buf.writeVarInt(waypoint.color());
        buf.writeBoolean(waypoint.disabled());
        buf.writeVarInt(waypoint.type());
        buf.writeString(waypoint.set());
        buf.writeBoolean(waypoint.rotateOnTeleport());
        buf.writeInt(waypoint.teleportYaw());
        buf.writeVarInt(waypoint.visibilityType());
        buf.writeBoolean(waypoint.destination());
    }

    static Waypoint read(RegistryByteBuf buf) {
        String name = buf.readString(512);
        String initials = buf.readString(32);
        int x = buf.readInt();
        Integer y = buf.readBoolean() ? buf.readInt() : null;
        int z = buf.readInt();
        String dimension = buf.readString(256);
        int color = buf.readVarInt();
        boolean disabled = buf.readBoolean();
        int type = buf.readVarInt();
        String set = buf.readString(256);
        boolean rotateOnTeleport = buf.readBoolean();
        int teleportYaw = buf.readInt();
        int visibilityType = buf.readVarInt();
        boolean destination = buf.readBoolean();

        return new Waypoint(
                name,
                initials,
                x,
                y,
                z,
                dimension,
                color,
                disabled,
                type,
                set,
                rotateOnTeleport,
                teleportYaw,
                visibilityType,
                destination
        );
    }
}
