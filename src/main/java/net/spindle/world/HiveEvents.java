package net.spindle.world;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.Spindle;
import net.spindle.entity.BroodmotherEntity;
import net.spindle.entity.SpindleChunkLoader;
import net.spindle.entity.SpindleEntity;

/** Everything that happens around the Hivemind: populating it, the advancement, and the ending. */
public final class HiveEvents {
	public static final Identifier ADV_PARASITE = Spindle.id("parasite");
	public static final Identifier ADV_SIGNAL_LOST = Spindle.id("signal_lost");

	/** Last thing the hive logic did, for the self-test. */
	public static String lastNote = "never ticked";

	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		lastNote = "ticked";
		ServerWorld world = server.getOverworld();
		Hivemind hive = Hivemind.of(world);
		if (hive == null) return;
		SpindleWorldState state = SpindleWorldState.get(server);
		Vec3d c = Vec3d.ofCenter(hive.center);
		FarLands fl = FarLands.of(Hivemind.seedFrom(world.getChunkManager().getNoiseConfig()));
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isSpectator()) continue;
			settleOasis(world, fl, state, p.getBlockX(), p.getBlockZ());
			double d = p.getPos().distanceTo(c);
			if (d < 150 && !state.hivePopulated && !state.broodmotherSlain) populate(world, hive, state);
			if (hive.isInside(p.getPos())) {
				lastNote = "inside: " + p.getName().getString();
				grant(p, ADV_PARASITE);
			}
		}
	}

	/** When someone first comes near an oasis, its village is put in. */
	public static void settleOasis(ServerWorld world, FarLands fl, SpindleWorldState state, int x, int z) {
		if (!FarLands.isFarLands(x, z)) return;
		FarLands.Oasis o = fl.oasisNear(x, z);
		if (o == null) return;
		long key = BlockPos.asLong(o.x(), 0, o.z());
		if (state.villages.contains(key)) return;
		int ccx = o.x() >> 4, ccz = o.z() >> 4;
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			if (!world.isChunkLoaded(ccx + dx, ccz + dz)) return;
		}
		state.villages.add(key);
		state.markDirty();
		int y = fl.oasisGround(o.x(), o.z(), world.getSeaLevel()) + 1;
		var server = world.getServer();
		server.getCommandManager().executeWithPrefix(server.getCommandSource().withLevel(4).withSilent().withWorld(world),
				"place structure minecraft:village_plains " + o.x() + " " + y + " " + o.z());
		lastNote = "village at " + o.x() + " " + y + " " + o.z();
		Spindle.LOGGER.info("Settled the oasis at {} {}", o.x(), o.z());
	}

	public static void grant(ServerPlayerEntity player, Identifier id) {
		AdvancementEntry adv = player.getServer().getAdvancementLoader().get(id);
		if (adv == null) {
			Spindle.LOGGER.warn("Advancement {} is missing", id);
			return;
		}
		if (!player.getAdvancementTracker().getProgress(adv).isDone()) {
			boolean ok = player.getAdvancementTracker().grantCriterion(adv, "done");
			lastNote = "grant " + id + " -> " + ok + " criteria " + adv.value().criteria().keySet();
			Spindle.LOGGER.info("Granting {} to {}: {}", id, player.getName().getString(), ok);
		}
	}

	/** Fills every chamber with sleeping Spindles and wakes the Broodmother in the heart. */
	public static void populate(ServerWorld world, Hivemind hive, SpindleWorldState state) {
		state.hivePopulated = true;
		state.markDirty();
		java.util.Random r = new java.util.Random(hive.center.asLong());
		int count = 0;
		for (Hivemind.Sphere ch : hive.chambers) {
			int n = 2 + r.nextInt(3) + (ch.r() > 10 ? 1 : 0);
			for (int i = 0; i < n; i++) {
				double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * ch.r() * 0.5;
				BlockPos p = BlockPos.ofFloored(ch.c().x + Math.cos(a) * rad, ch.c().y, ch.c().z + Math.sin(a) * rad);
				BlockPos floor = SpaceFinder.floorBelow(world, p, (int) ch.r() + 2);
				if (floor == null) continue;
				SpindleEntity s = Spindle.SPINDLE.create(world);
				if (s == null) continue;
				s.refreshPositionAndAngles(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, r.nextFloat() * 360f, 0f);
				s.setHiveBound(true);
				world.spawnEntity(s);
				count++;
			}
		}
		// The heart: some on the floor around the Broodmother, and the Broodmother itself.
		for (int i = 0; i < 10; i++) {
			double a = r.nextDouble() * Math.PI * 2, rad = hive.throne.r() * (0.55 + r.nextDouble() * 0.3);
			BlockPos p = BlockPos.ofFloored(hive.throne.c().x + Math.cos(a) * rad, hive.throne.c().y, hive.throne.c().z + Math.sin(a) * rad);
			BlockPos floor = SpaceFinder.floorBelow(world, p, (int) hive.throne.r() + 2);
			if (floor == null) continue;
			SpindleEntity s = Spindle.SPINDLE.create(world);
			if (s == null) continue;
			s.refreshPositionAndAngles(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, r.nextFloat() * 360f, 0f);
			s.setHiveBound(true);
			world.spawnEntity(s);
			count++;
		}
		BroodmotherEntity mother = Spindle.BROODMOTHER.create(world);
		if (mother != null) {
			Vec3d c = hive.throne.c();
			mother.refreshPositionAndAngles(c.x, c.y - 4, c.z, 0f, 0f);
			mother.setPersistent();
			world.spawnEntity(mother);
		}
		Spindle.LOGGER.info("The Hivemind stirs: {} Spindles", count);
	}

	/** The Broodmother is dead. Every Spindle anywhere dies with it, and they never come back. */
	public static void endOfTheHive(MinecraftServer server) {
		SpindleWorldState state = SpindleWorldState.get(server);
		state.broodmotherSlain = true;
		state.markDirty();
		for (ServerWorld world : server.getWorlds()) {
			for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> true)) s.collapse();
			SpindleChunkLoader.get(world).forgetAll();
		}
	}

	/** Spindles saved in chunks nobody has loaded since the Broodmother died collapse as soon as they load. */
	public static void onEntityLoad(Entity entity, ServerWorld world) {
		if (entity instanceof SpindleEntity s && SpindleWorldState.get(world.getServer()).broodmotherSlain) {
			s.collapse();
		}
	}

	private HiveEvents() {
	}
}
