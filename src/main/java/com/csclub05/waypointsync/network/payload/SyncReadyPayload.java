package com.csclub05.waypointsync.network.payload;

import com.csclub05.waypointsync.WaypointSync;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SyncReadyPayload(int protocolVersion) implements CustomPayload {
    public static final Id<SyncReadyPayload> ID =
            new Id<>(Identifier.of(WaypointSync.MOD_ID, "sync_ready"));
    public static final PacketCodec<RegistryByteBuf, SyncReadyPayload> CODEC =
            PacketCodec.of(SyncReadyPayload::write, SyncReadyPayload::new);

    private SyncReadyPayload(RegistryByteBuf buf) {
        this(buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(protocolVersion);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
