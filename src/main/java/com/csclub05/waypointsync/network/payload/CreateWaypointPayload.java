package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.model.Waypoint;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CreateWaypointPayload(long expectedRevision, Waypoint waypoint) implements CustomPayload {
    public static final Id<CreateWaypointPayload> ID =
            new Id<>(Identifier.of(WaypointSync.MOD_ID, "create_waypoint"));
    public static final PacketCodec<RegistryByteBuf, CreateWaypointPayload> CODEC =
            PacketCodec.of(CreateWaypointPayload::write, CreateWaypointPayload::new);

    private CreateWaypointPayload(RegistryByteBuf buf) {
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
