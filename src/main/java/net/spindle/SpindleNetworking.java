package net.spindle;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class SpindleNetworking {
	/** The bends in a tendril's body (world positions between the core and the tip). */
	public record TendrilTrailPayload(int entityId, List<Vec3d> anchors) implements CustomPayload {
		public static final Id<TendrilTrailPayload> ID = new Id<>(Spindle.id("tendril_trail"));
		public static final PacketCodec<RegistryByteBuf, TendrilTrailPayload> CODEC =
				CustomPayload.codecOf(TendrilTrailPayload::write, TendrilTrailPayload::read);

		private static TendrilTrailPayload read(PacketByteBuf buf) {
			int id = buf.readVarInt();
			int n = Math.min(buf.readVarInt(), 256);
			List<Vec3d> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) list.add(new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()));
			return new TendrilTrailPayload(id, list);
		}

		private void write(PacketByteBuf buf) {
			buf.writeVarInt(entityId);
			buf.writeVarInt(anchors.size());
			for (Vec3d v : anchors) {
				buf.writeDouble(v.x);
				buf.writeDouble(v.y);
				buf.writeDouble(v.z);
			}
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(TendrilTrailPayload.ID, TendrilTrailPayload.CODEC);
	}

	public static void sendTrail(Entity entity, List<Vec3d> anchors) {
		TendrilTrailPayload payload = new TendrilTrailPayload(entity.getId(), List.copyOf(anchors));
		for (ServerPlayerEntity player : PlayerLookup.tracking(entity)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private SpindleNetworking() {
	}
}
