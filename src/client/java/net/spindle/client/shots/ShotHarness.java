package net.spindle.client.shots;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;
import net.spindle.selftest.Scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Development only (runs when SPINDLE_SHOTS is set): stages scenes in a singleplayer world and saves
 * screenshots of them, so the model and animation can be checked without someone playing.
 */
public final class ShotHarness {
	private record Shot(String name, Vec3d camera, Supplier<Vec3d> lookAt, int delay, Supplier<Boolean> ready, Runnable atStart) {
		Shot(String name, Vec3d camera, Supplier<Vec3d> lookAt, int delay, Supplier<Boolean> ready) {
			this(name, camera, lookAt, delay, ready, null);
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();
	private static int ticks;
	private static int shotIndex = -1;
	private static int shotTicks;
	private static boolean built;
	private static SpindleEntity walker;
	private static Vec3d walkerGoal;
	private static int ground;

	public static void init() {
		if (System.getenv("SPINDLE_SHOTS") == null) return;
		ClientTickEvents.END_CLIENT_TICK.register(ShotHarness::tick);
	}

	private static void log(String s) {
		Spindle.LOGGER.info("SHOTS " + s);
	}

	private static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null || client.getServer() == null) return;
		MinecraftServer server = client.getServer();
		ticks++;
		client.options.hudHidden = true;
		if (ticks == 40) {
			server.execute(() -> build(server));
		}
		if (!built || ticks < 100) return;

		if (walker != null && walkerGoal != null && ticks % 10 == 0) {
			SpindleEntity w = walker;
			Vec3d g = walkerGoal;
			server.execute(() -> w.travelTo(g, 1.0));
		}

		if (shotIndex < 0 || shotTicks < 0) {
			shotIndex++;
			if (shotIndex >= SHOTS.size()) {
				log("DONE " + SHOTS.size());
				client.scheduleStop();
				return;
			}
			Shot s = SHOTS.get(shotIndex);
			teleport(server, s.camera, s.lookAt.get());
			shotTicks = 0;
			return;
		}
		Shot s = SHOTS.get(shotIndex);
		shotTicks++;
		if (shotTicks % 40 == 0) log("  " + s.name + " t=" + shotTicks + " | " + describe(client, s));
		if (shotTicks == 20 && s.atStart != null) server.execute(s.atStart);
		if (shotTicks % 10 == 0) teleport(server, s.camera, s.lookAt.get());
		boolean timeUp = shotTicks >= s.delay + 200;
		if (shotTicks >= s.delay && (s.ready.get() || timeUp)) {
			ScreenshotRecorder.saveScreenshot(client.runDirectory, s.name + ".png", client.getFramebuffer(), msg -> {});
			log("SAVED " + s.name + (timeUp ? " (timed out waiting)" : "") + " | " + describe(client, s));
			shotTicks = -1;
		}
	}

	/** What the client sees of the Spindle nearest the shot's target. */
	private static String describe(MinecraftClient client, Shot s) {
		Vec3d at = s.lookAt.get();
		SpindleEntity best = null;
		double bd = 40 * 40;
		for (Entity e : client.world.getEntities()) {
			if (e instanceof SpindleEntity sp && sp.squaredDistanceTo(at) < bd) {
				bd = sp.squaredDistanceTo(at);
				best = sp;
			}
		}
		if (best == null) return "no spindle near";
		StringBuilder b = new StringBuilder();
		b.append(String.format(java.util.Locale.ROOT, "client: h%.1f hanging=%s tendril=%s fold=%.2f legFold=%.2f feet:",
				best.getBodyHeight(), best.isHanging(), best.getTendrilFlags(), best.legs.visualFold, best.legs.legFold));
		for (int i = 0; i < 6 && best.legs.isReady(); i++) {
			Vec3d f = best.legs.foot[i].subtract(best.getPos());
			b.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f %.1f s%.2f)", f.x, f.y, f.z, best.legs.scale[i]));
		}
		MinecraftServer server = client.getServer();
		if (server != null) {
			SpindleEntity srv = (SpindleEntity) server.getOverworld().getEntity(best.getUuid());
			if (srv != null) b.append(" | server: ").append(srv.tendril.getState()).append(" hanging=").append(srv.isHanging())
					.append(" posed=").append(srv.posed);
		}
		return b.toString();
	}

	private static void teleport(MinecraftServer server, Vec3d cam, Vec3d target) {
		Vec3d d = target.subtract(cam);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		String c = String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", cam.x, cam.y - 1.62, cam.z, yaw, pitch);
		server.execute(() -> Scenes.cmd(server, c));
	}

	private static SpindleEntity posed(ServerWorld world, double x, double y, double z, float yaw) {
		SpindleEntity s = Scenes.spawnSpindle(world, x, y, z, yaw);
		s.posed = true;
		s.setAiDisabled(true);
		s.setPersistent();
		s.setBodyYaw(yaw);
		s.setHeadYaw(yaw);
		return s;
	}

	private static void build(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		for (String c : new String[]{"gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule doWeatherCycle false",
				"time set 6000", "weather clear", "difficulty normal", "gamemode spectator @a", "kill @e[type=spindle:spindle]",
				"effect give @a minecraft:night_vision infinite 0 true"}) {
			Scenes.cmd(server, c);
		}
		Scenes.prepare(world, -64, -64, 960, 64);
		ground = Scenes.groundY(world, 0, 0);
		BlockState plank = Blocks.SPRUCE_PLANKS.getDefaultState();
		BlockState dark = Blocks.DARK_OAK_PLANKS.getDefaultState();

		// 1. open field, a small house for scale
		BlockPos a = new BlockPos(0, ground, 0);
		Scenes.box(world, a, 6, 0, 4, 10, 4, 8, plank);
		SpindleEntity open = posed(world, 0.5, ground, 0.5, 30f);
		SHOTS.add(new Shot("01_open_field", v(a, 15, 4, -12), () -> open.getPos().add(0, 3, 0), 60, () -> true));
		SHOTS.add(new Shot("02_from_below", v(a, 2.5, 1.6, -3.5), () -> open.getCorePos(), 30, () -> true));

		// 2. walking across rough ground
		BlockPos b = new BlockPos(100, ground, 0);
		Scenes.steps(world, b.add(10, 0, 0));
		walker = Scenes.spawnSpindle(world, b.getX() + 0.5, ground, 0.5, -90f);
		walker.setPersistent();
		walkerGoal = v(b, 40, 0, 0.5);
		SHOTS.add(new Shot("03_walking_steps", v(b, 22, 6, -16), () -> walker.getPos().add(0, 3, 0), 20,
				() -> walker.getX() - b.getX() > 16));

		// 3. mansion corridor, near wall left off
		BlockPos c = new BlockPos(200, ground, 0);
		Scenes.corridor(world, c, 4, dark);
		Scenes.fill(world, c, 0, 0, -2, 31, 3, -2, Blocks.GLASS.getDefaultState());
		SpindleEntity corridor = posed(world, c.getX() + 15.5, ground, 0.5, -90f);
		SHOTS.add(new Shot("04_corridor_cutaway", v(c, 15.5, 2.5, -9), () -> corridor.getPos().add(0, 1.5, 0), 80, () -> true));
		SHOTS.add(new Shot("05_corridor_inside", v(c, 3.5, 1.7, 0.5), () -> corridor.getPos().add(0, 1.8, 0), 40, () -> true));

		// 4. 1x2 tunnel, cut open on the near side
		BlockPos d = new BlockPos(300, ground, 0);
		Scenes.tunnel(world, d, Blocks.STONE.getDefaultState());
		Scenes.fill(world, d, 10, 0, -2, 42, 3, -2, Blocks.GLASS.getDefaultState());
		Scenes.fill(world, d, 10, 0, -1, 40, 1, -1, Blocks.GLASS.getDefaultState());
		SpindleEntity tunnel = posed(world, d.getX() + 25.5, ground, 0.5, -90f);
		SHOTS.add(new Shot("06_tunnel_folded", v(d, 25.5, 2.2, -6), () -> tunnel.getPos().add(0, 0.8, 0), 80, () -> true));

		// 5. hanging from a hall ceiling, side open
		BlockPos e = new BlockPos(400, ground, 0);
		Scenes.hall(world, e, 5, Blocks.STONE.getDefaultState());
		Scenes.fill(world, e, -1, 0, -3, 41, 4, -3, Blocks.GLASS.getDefaultState());
		SpindleEntity hanging = posed(world, e.getX() + 20.5, ground, 0.5, -90f);
		hanging.setHanging(true);
		hanging.setPosition(e.getX() + 20.5, ground + 5 - SpindleEntity.MIN_HEIGHT, 0.5);
		SHOTS.add(new Shot("07_hanging_ceiling", v(e, 20.5, 2.0, -9), () -> hanging.getPos().add(0, 0.5, 0), 80, () -> true));

		// 6. probe searching a house through its window, near wall left off
		BlockPos f = new BlockPos(500, ground, 0);
		Scenes.box(world, f, 6, 0, -4, 14, 4, 4, plank);
		Scenes.fill(world, f, 7, 0, -4, 13, 3, -4, Blocks.GLASS.getDefaultState());
		Scenes.set(world, f, 6, 1, 0, Blocks.GLASS_PANE.getDefaultState());
		SpindleEntity prober = posed(world, f.getX() + 0.5, ground, 0.5, -90f);
		SHOTS.add(new Shot("08_probe_search", v(f, 8, 3.5, -12), () -> v(f, 7, 2, 0), 120, () -> prober.tendril.isSearching(),
				() -> log("probe started=" + prober.tendril.beginSearch(v(f, 10.5, 0.5, 1.5)))));

		// 7. standing on a staircase edge: legs at different heights
		BlockPos g = new BlockPos(600, ground, 0);
		Scenes.steps(world, g);
		SpindleEntity stairs = posed(world, g.getX() + 12.5, ground + 4, 0.5, -90f);
		SHOTS.add(new Shot("09_on_steps", v(g, 12.5, 4, -14), () -> stairs.getPos().add(0, 2.5, 0), 80, () -> true));

		built = true;
		log("BUILT ground=" + ground);
	}

	private static Vec3d v(BlockPos o, double x, double y, double z) {
		return new Vec3d(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	private ShotHarness() {
	}
}
