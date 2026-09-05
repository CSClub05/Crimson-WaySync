package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record EditWaypointPayload(long expectedRevision, Waypoint oldWaypoint, Waypoint newWaypoint) implements CustomPayload {
    public static final Id<EditWaypointPayload> ID =
            new Id<>(Identifier.of(WaypointSync.MOD_ID, "edit_waypoint"));
    public static final PacketCodec<RegistryByteBuf, EditWaypointPayload> CODEC =
            PacketCodec.of(EditWaypointPayload::write, EditWaypointPayload::new);

    private EditWaypointPayload(RegistryByteBuf buf) {
        this(buf.readLong(), WaypointPacketCodec.read(buf), WaypointPacketCodec.read(buf));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeLong(expectedRevision);
        WaypointPacketCodec.write(buf, oldWaypoint);
        WaypointPacketCodec.write(buf, newWaypoint);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
