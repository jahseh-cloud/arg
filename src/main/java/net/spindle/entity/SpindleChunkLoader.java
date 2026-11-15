package net.spindle.entity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every Spindle's surroundings loaded and ticking, even with no players nearby,
 * and remembers where they are so they wake up again after a server restart.
 */
public class SpindleChunkLoader extends PersistentState {
	/** Tickets expire on their own unless refreshed, so a dead/removed Spindle never leaks a loaded chunk. */
	public static final ChunkTicketType<ChunkPos> TICKET =
			ChunkTicketType.create("spindle", Comparator.comparingLong(ChunkPos::toLong), 60);
	/** radius 3 => the Spindle's chunk and its neighbours are fully entity-ticking. */
	public static final int RADIUS = 3;
	private static final String ID = "spindle_chunk_loader";
	private static final Type<SpindleChunkLoader> TYPE = new Type<>(SpindleChunkLoader::new, SpindleChunkLoader::fromNbt, null);

	private final Map<UUID, ChunkPos> spindles = new HashMap<>();

	public static SpindleChunkLoader get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, ID);
	}

	public static void keepLoaded(ServerWorld world, ChunkPos pos) {
		world.getChunkManager().addTicket(TICKET, pos, RADIUS, pos);
	}

	/** Called every second by each living Spindle. */
	public void update(ServerWorld world, UUID id, ChunkPos pos) {
		keepLoaded(world, pos);
		ChunkPos old = spindles.put(id, pos);
		if (!pos.equals(old)) markDirty();
	}

	public void forgetAll() {
		if (!spindles.isEmpty()) {
			spindles.clear();
			markDirty();
		}
	}

	public void forget(UUID id) {
		if (spindles.remove(id) != null) markDirty();
	}

	/** After a restart nothing is loaded yet: load each Spindle's last chunk so it can resume. */
	public void wakeAll(ServerWorld world) {
		for (ChunkPos pos : spindles.values()) keepLoaded(world, pos);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		NbtList list = new NbtList();
		spindles.forEach((id, pos) -> {
			NbtCompound c = new NbtCompound();
			c.putUuid("Id", id);
			c.putLong("Chunk", pos.toLong());
			list.add(c);
		});
		nbt.put("Spindles", list);
		return nbt;
	}

	private static SpindleChunkLoader fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		SpindleChunkLoader state = new SpindleChunkLoader();
		NbtList list = nbt.getList("Spindles", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound c = list.getCompound(i);
			state.spindles.put(c.getUuid("Id"), new ChunkPos(c.getLong("Chunk")));
		}
		return state;
	}
}
