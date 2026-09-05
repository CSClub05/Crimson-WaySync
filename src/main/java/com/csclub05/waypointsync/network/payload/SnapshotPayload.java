package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.WaypointSync;
import com.csclub05.waypointsync.model.Waypoint;
import com.csclub05.waypointsync.model.WaypointSnapshot;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SnapshotPayload(long revision, List<Waypoint> waypoints) implements CustomPayload {
    private static final int MAX_WAYPOINTS = 100_000;

    public static final Id<SnapshotPayload> ID =
            new Id<>(Identifier.of(WaypointSync.MOD_ID, "snapshot"));
    public static final PacketCodec<RegistryByteBuf, SnapshotPayload> CODEC =
            PacketCodec.of(SnapshotPayload::write, SnapshotPayload::new);

    public SnapshotPayload {
        waypoints = List.copyOf(waypoints);
    }

    public SnapshotPayload(WaypointSnapshot snapshot) {
        this(snapshot.revision(), snapshot.waypoints());
    }

    private SnapshotPayload(RegistryByteBuf buf) {
        this(readRevision(buf), readWaypoints(buf));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeLong(revision);
        buf.writeVarInt(waypoints.size());
        for (Waypoint waypoint : waypoints) {
            WaypointPacketCodec.write(buf, waypoint);
        }
    }

    private static long readRevision(RegistryByteBuf buf) {
        return buf.readLong();
    }

    private static List<Waypoint> readWaypoints(RegistryByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_WAYPOINTS) {
            throw new IllegalArgumentException("Invalid waypoint snapshot size: " + size);
        }

        List<Waypoint> waypoints = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            waypoints.add(WaypointPacketCodec.read(buf));
        }
        return waypoints;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
