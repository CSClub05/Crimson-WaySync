package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record DeleteWaypointPayload(long expectedRevision, Waypoint waypoint) implements CustomPayload {
    public static final Id<DeleteWaypointPayload> ID =
            new Id<>(Identifier.of(WaypointSync.MOD_ID, "delete_waypoint"));
    public static final PacketCodec<RegistryByteBuf, DeleteWaypointPayload> CODEC =
            PacketCodec.of(DeleteWaypointPayload::write, DeleteWaypointPayload::new);

    private DeleteWaypointPayload(RegistryByteBuf buf) {
        this(buf.readLong(), WaypointPacketCodec.read(buf));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeLong(expectedRevision);
        WaypointPacketCodec.write(buf, waypoint);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
