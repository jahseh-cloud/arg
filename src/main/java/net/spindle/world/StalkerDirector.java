package net.spindle.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spindles don't spawn like normal mobs. Each player gets one that finds them: when nobody is
 * shadowing a player, one eventually turns up 30-55 blocks away, somewhere they can't see, already
 * fixated on them. More likely at night and underground. Never in Peaceful, never once the
 * Broodmother is dead.
 */
public final class StalkerDirector {
	/** Ticks between a player's last stalker vanishing and the next one arriving (randomised on top). */
	public static int minDelay = 2400;
	public static int extraDelay = 3600;
	public static boolean enabled = true;
	private static final Map<UUID, Integer> NEXT = new HashMap<>();

	public static void tick(MinecraftServer server) {
		if (!enabled || server.getTicks() % 40 != 0) return;
		SpindleWorldState state = SpindleWorldState.get(server);
		if (state.broodmotherSlain) return;
		for (ServerWorld w : server.getWorlds()) for (ServerPlayerEntity player : List.copyOf(w.getPlayers())) {
			ServerWorld world = w;
			if (player.isCreative() || player.isSpectator() || !player.isAlive()) continue;
			if (world.getDifficulty() == Difficulty.PEACEFUL || !world.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING)) continue;
			if (world.getRegistryKey() == net.minecraft.world.World.END) continue;
			Hivemind hive = Hivemind.of(world);
			if (hive != null && player.getPos().distanceTo(Vec3d.ofCenter(hive.center)) < 200) continue; // the hive has its own

			if (isShadowed(world, player)) {
				NEXT.remove(player.getUuid());
				continue;
			}
			int now = server.getTicks();
			Integer due = NEXT.get(player.getUuid());
			if (due == null) {
				NEXT.put(player.getUuid(), now + minDelay + world.getRandom().nextInt(extraDelay + 1));
				continue;
			}
			if (now < due) continue;
			boolean dark = !world.isSkyVisible(player.getBlockPos()) || world.isNight();
			if (!dark && world.getRandom().nextFloat() < 0.7f) continue; // prefers night and caves
			if (spawnFor(world, player) != null) NEXT.remove(player.getUuid());
		}
	}

	/** Some Spindle is already near and interested in this player. */
	private static boolean isShadowed(ServerWorld world, ServerPlayerEntity player) {
		for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> e.isAlive() && e.squaredDistanceTo(player) < 160 * 160)) {
			if (s.getTarget() == player || s.squaredDistanceTo(player) < 96 * 96) return true;
		}
		return false;
	}

	/** Puts a Spindle somewhere hidden 30-55 blocks from the player, already stalking them. */
	@Nullable
	public static SpindleEntity spawnFor(ServerWorld world, ServerPlayerEntity player) {
		SpindleEntity s = Spindle.SPINDLE.create(world);
		if (s == null) return null;
		s.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), 0f, 0f);
		Vec3d spot = null;
		for (int i = 0; i < 12 && spot == null; i++) {
			Vec3d c = s.randomGroundNear(player.getPos(), 30, 55);
			if (c == null || !world.isChunkLoaded(BlockPos.ofFloored(c))) continue;
			if (!s.isSpotHidden(c) || s.isSpotInSomeonesView(c)) continue;
			s.refreshPositionAndAngles(c.x, c.y, c.z, 0f, 0f);
			if (!world.isSpaceEmpty(s, s.getBoundingBox())) continue;
			spot = c;
		}
		if (spot == null) return null;
		s.refreshPositionAndAngles(spot.x, spot.y, spot.z, world.getRandom().nextFloat() * 360f, 0f);
		world.spawnEntity(s);
		s.setTarget(player);
		Spindle.LOGGER.debug("A Spindle found {}", player.getName().getString());
		return s;
	}

	private StalkerDirector() {
	}
}
