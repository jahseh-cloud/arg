package net.spindle.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** World-wide story state, saved with the overworld. */
public class SpindleWorldState extends PersistentState {
	private static final Type<SpindleWorldState> TYPE = new Type<>(SpindleWorldState::new, SpindleWorldState::fromNbt, null);

	/** The Broodmother is dead: every Spindle is gone and none come back. */
	public boolean broodmotherSlain;
	/** The Hivemind's inhabitants have been spawned. */
	public boolean hivePopulated;
	/** Oases (by center) that have had their village put in. */
	public final java.util.Set<Long> villages = new java.util.HashSet<>();

	public static SpindleWorldState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "spindle_story");
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		nbt.putBoolean("BroodmotherSlain", broodmotherSlain);
		nbt.putBoolean("HivePopulated", hivePopulated);
		nbt.putLongArray("Villages", villages.stream().mapToLong(Long::longValue).toArray());
		return nbt;
	}

	private static SpindleWorldState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
		SpindleWorldState s = new SpindleWorldState();
		s.broodmotherSlain = nbt.getBoolean("BroodmotherSlain");
		s.hivePopulated = nbt.getBoolean("HivePopulated");
		for (long l : nbt.getLongArray("Villages")) s.villages.add(l);
		return s;
	}
}
