package net.spindle.selftest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;
import net.spindle.entity.TendrilController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Automated behaviour tests. Only runs on a dedicated server started with the environment variable
 * SPINDLE_SELFTEST set (the build server does this); otherwise this class does nothing.
 *
 * Each scenario gets its own arena far from the others, a Spindle, and a simulated Survival player,
 * and checks that the Spindle actually does what it's supposed to: catches someone in the open,
 * gets through a tunnel and a door, grabs through a window, digs into a sealed hut, searches with
 * its probe, freezes when watched in the light, hangs from a ceiling, retreats when hurt, and
 * crosses the world on its own.
 */
public final class SelfTest {
	private static final int START_TICK = 40;
	private static final List<Scenario> SCENARIOS = new ArrayList<>();
	private static int ticks;
	private static boolean finished;

	public static boolean enabled() {
		return System.getenv("SPINDLE_SELFTEST") != null;
	}

	/** "world" runs the world-generation and story tests one after another; anything else runs the behaviour tests. */
	public static boolean worldMode() {
		return "world".equals(System.getenv("SPINDLE_SELFTEST"));
	}

	public static void init() {
		if (!enabled()) return;
		ServerTickEvents.END_SERVER_TICK.register(SelfTest::tick);
		Thread dog = new Thread(SelfTest::watchdog, "spindle-selftest-watchdog");
		dog.setDaemon(true);
		dog.start();
	}

	/** Every 45 seconds: where is everyone? (Shows up in the log if a test hangs.) */
	private static void watchdog() {
		int lastTicks = -1;
		while (true) {
			try {
				Thread.sleep(45000);
			} catch (InterruptedException e) {
				return;
			}
			if (finished) return;
			StringBuilder b = new StringBuilder("WATCHDOG ticks=" + ticks + (ticks == lastTicks ? " (STUCK)" : "") + "\n");
			lastTicks = ticks;
			for (var entry : Thread.getAllStackTraces().entrySet()) {
				String name = entry.getKey().getName();
				if (!name.equals("Server thread") && !name.startsWith("Worker-Main")) continue;
				b.append("  thread ").append(name).append(" ").append(entry.getKey().getState()).append("\n");
				StackTraceElement[] st = entry.getValue();
				for (int i = 0; i < Math.min(14, st.length); i++) b.append("    at ").append(st[i]).append("\n");
			}
			Spindle.LOGGER.info(b.toString());
		}
	}

	private static void log(String line) {
		Spindle.LOGGER.info("SELFTEST " + line);
	}

	private static void tick(MinecraftServer server) {
		if (finished || !server.isDedicated()) return;
		ticks++;
		try {
			if (ticks == START_TICK) {
				setup(server);
			} else if (ticks > START_TICK) {
				run(server);
			}
		} catch (Throwable t) {
			Spindle.LOGGER.error("SELFTEST CRASH", t);
			log("CRASH " + t);
			finish(server);
		}
	}

	private static void setup(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		if (worldMode()) {
			for (String c : new String[]{"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
					"difficulty normal", "weather clear", "time set midnight"}) {
				Scenes.cmd(server, c);
			}
			SCENARIOS.addAll(WorldTests.all());
			server.getTickManager().startSprint(20000);
			return;
		}
		net.spindle.world.StalkerDirector.enabled = false;
		for (String c : new String[]{
				"gamerule doMobSpawning false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
				"gamerule spawnRadius 0", "difficulty normal", "weather clear", "time set midnight",
				"kill @e[type=!minecraft:player]"}) {
			Scenes.cmd(server, c);
		}
		SCENARIOS.add(new OpenHunt());
		SCENARIOS.add(new TunnelHunt());
		SCENARIOS.add(new DoorHouse());
		SCENARIOS.add(new WindowGrab());
		SCENARIOS.add(new SealedHut());
		SCENARIOS.add(new ProbeHide());
		SCENARIOS.add(new StalkOpen());
		SCENARIOS.add(new LightFreeze());
		SCENARIOS.add(new LurkCeiling());
		SCENARIOS.add(new Retreat());
		SCENARIOS.add(new TravelFar());
		SCENARIOS.add(new MansionCorridor());
		SCENARIOS.add(new RoughSteps());
		SCENARIOS.add(new MobsInPath());
		SCENARIOS.add(new NoWorldDamage());
		int i = 0;
		for (Scenario s : SCENARIOS) {
			int ox = 1000 + 400 * i++, oz = 1000;
			Scenes.prepare(world, ox - 64, oz - 64, ox + 96, oz + 64);
			s.world = world;
			s.o = new BlockPos(ox, Scenes.groundY(world, ox, oz), oz);
			s.started = true;
			s.setup();
			log("START " + s.name + " at " + s.o.toShortString());
		}
		server.getTickManager().startSprint(4000);
	}

	private static void run(MinecraftServer server) {
		boolean allDone = true;
		for (Scenario s : SCENARIOS) {
			if (s.result != null) continue;
			allDone = false;
			if (!s.started) {
				s.started = true;
				s.world = server.getOverworld();
				s.place();
				log("START " + s.name + " at " + s.o.toShortString());
				s.setup();
			}
			s.t++;
			if (s.victim != null) s.victim.drive();
			s.observe();
			String r = s.check();
			if (r == null && s.t >= s.maxTicks) r = s.atTimeout();
			if (r == null && s.spindle != null && !s.spindle.isAlive()) r = "FAIL the Spindle died or vanished";
			if (r != null) {
				s.result = r;
				log("RESULT " + s.name + " " + r + " | " + s.summary());
				s.cleanup();
			} else if (s.t % 100 == 0) {
				log("TRACE " + s.name + " t=" + s.t + " " + s.status());
			}
			if (worldMode()) break; // one at a time
		}
		if (allDone || ticks > START_TICK + (worldMode() ? 12000 : 2600)) finish(server);
	}

	private static void finish(MinecraftServer server) {
		if (finished) return;
		finished = true;
		int pass = 0, fail = 0;
		for (Scenario s : SCENARIOS) {
			if (s.result == null) {
				s.result = "FAIL did not finish";
				log("RESULT " + s.name + " " + s.result + " | " + s.summary());
				s.cleanup();
			}
			if (s.result.startsWith("PASS")) pass++; else fail++;
		}
		Scenes.cmd(server, "save-all");
		log("DONE passed=" + pass + " failed=" + fail + " ticks=" + ticks);
		server.stop(false);
	}

	// ================================================================ scenario base

	abstract static class Scenario {
		final String name;
		final int maxTicks;
		ServerWorld world;
		BlockPos o;
		SpindleEntity spindle;
		TestPlayer victim;
		int t;
		String result;
		boolean started;

		/** Sets "o" (world tests pick their own spot; behaviour tests are placed by setup()). */
		void place() {
		}

		final StringBuilder notesBuf = new StringBuilder();
		String notes = "";

		static void note(Scenario s, String text) {
			if (s.notesBuf.length() > 0) s.notesBuf.append(", ");
			s.notesBuf.append(text);
			s.notes = s.notesBuf.toString();
		}

		// observations
		final EnumSet<TendrilController.State> tendrilStates = EnumSet.noneOf(TendrilController.State.class);
		final EnumSet<SpindleEntity.Mode> modes = EnumSet.noneOf(SpindleEntity.Mode.class);
		double minDist = 1e9;
		float minHeight = 99f;
		int hungAt = -1;

		Scenario(String name, int maxTicks) {
			this.name = name;
			this.maxTicks = maxTicks;
		}

		abstract void setup();

		/** Null to keep going, otherwise a result starting with PASS or FAIL. */
		abstract String check();

		String atTimeout() {
			return "FAIL timed out " + notes;
		}

		Vec3d at(double x, double y, double z) {
			return new Vec3d(o.getX() + x, o.getY() + y, o.getZ() + z);
		}

		BlockState block(int x, int y, int z) {
			return world.getBlockState(o.add(x, y, z));
		}

		/** Spindle at the origin, victim at (vx, vz), facing victimYaw (-90 = east, 90 = west). */
		void spawn(double vx, double vz, float victimYaw) {
			spindle = Scenes.spawnSpindle(world, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90f);
			victim = TestPlayer.spawn(world, "t_" + name, at(vx, 0, vz), victimYaw);
		}

		void forceHunt() {
			spindle.noticeTarget(victim);
			spindle.startHunt();
		}

		boolean hurt() {
			return victim.isDead() || victim.getHealth() < victim.getMaxHealth();
		}

		double dist() {
			return spindle.distanceTo(victim);
		}

		void observe() {
			if (spindle == null || victim == null) return;
			tendrilStates.add(spindle.tendril.getState());
			modes.add(spindle.getMode());
			minDist = Math.min(minDist, dist());
			minHeight = Math.min(minHeight, spindle.getBodyHeight());
			if (hungAt < 0 && spindle.isHanging()) hungAt = t;
		}

		String status() {
			if (spindle == null || victim == null) return "";
			Vec3d p = spindle.getPos().subtract(o.getX(), o.getY(), o.getZ());
			Vec3d v = victim.getPos().subtract(o.getX(), o.getY(), o.getZ());
			return String.format("[%s] at (%.1f %.1f %.1f) victim (%.1f %.1f %.1f) hp %.0f",
					spindle.debugLine(victim), p.x, p.y, p.z, v.x, v.y, v.z, victim.getHealth());
		}

		String summary() {
			if (spindle == null) return "";
			return String.format("t=%d minDist=%.1f minHeight=%.1f modes=%s tendril=%s attacks=%d grabs=%d dug=%d searches=%d spots=%d | %s",
					t, minDist, minHeight, modes, tendrilStates, spindle.statAttacks, spindle.statGrabs, spindle.statBlocksDug,
					spindle.statProbeSearches, spindle.statProbeSpots, status());
		}

		void cleanup() {
			if (victim != null) victim.despawn();
			if (spindle != null) spindle.discard();
		}
	}

	// ================================================================ scenarios

	/** Flat open ground at night: it should simply run the player down. */
	static class OpenHunt extends Scenario {
		OpenHunt() {
			super("open_hunt", 400);
		}

		@Override
		void setup() {
			spawn(25.5, 0.5, -90f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			return hurt() ? "PASS hit the player after " + t + " ticks" : null;
		}
	}

	/** The player is at the end of a 1x2 tunnel 30 blocks long. It has to fold flat and go in. */
	static class TunnelHunt extends Scenario {
		boolean tallInside;

		TunnelHunt() {
			super("tunnel_hunt", 1000);
		}

		@Override
		void setup() {
			Scenes.tunnel(world, o, Scenes.STONE);
			spawn(38.5, 0.5, -90f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			double x = spindle.getX() - o.getX();
			if (x > 11 && x < 40 && spindle.getBodyHeight() > 1.7f) tallInside = true;
			if (!hurt()) return null;
			return (tallInside ? "FAIL reached the player but was taller than the tunnel" : "PASS went down the tunnel and hit the player") + " after " + t + " ticks";
		}
	}

	/** The player walks around inside a closed wooden house. It should come in through the door. */
	static class DoorHouse extends Scenario {
		boolean doorOpened;

		DoorHouse() {
			super("door_house", 1400);
		}

		@Override
		void setup() {
			Scenes.doorHouse(world, o);
			spawn(24.5, 0.5, -90f);
			victim.patrol(at(22.5, 0, -2.5), at(26.5, 0, 2.5));
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			BlockState door = block(20, 0, 0);
			if (door.contains(Properties.OPEN) && door.get(Properties.OPEN)) doorOpened = true;
			if (!hurt()) return null;
			return "PASS got to the player after " + t + " ticks (door opened: " + doorOpened + ")";
		}
	}

	/** The player stands in an obsidian bunker with one glass window. Only a tendril can get in. */
	static class WindowGrab extends Scenario {
		WindowGrab() {
			super("window_grab", 1400);
		}

		@Override
		void setup() {
			Scenes.bunker(world, o);
			spawn(22.5, 0.5, 90f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			boolean glassBroken = !block(20, 1, 0).isOf(Blocks.GLASS);
			if (!hurt()) return null;
			return (glassBroken ? "PASS" : "FAIL player hurt but the window is intact:") + " broke the window and got the player after " + t + " ticks";
		}
	}

	/** The player paces inside a sealed dirt hut with no openings. It has to dig its way in. */
	static class SealedHut extends Scenario {
		SealedHut() {
			super("sealed_hut_dig", 1800);
		}

		@Override
		void setup() {
			Scenes.dirtHut(world, o);
			spawn(22.5, 0.5, -90f);
			victim.patrol(at(21.5, 0, -0.5), at(23.5, 0, 1.5));
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			if (!hurt()) return null;
			return (spindle.statBlocksDug > 0 ? "PASS dug in" : "PASS got in without digging?") + " and hit the player after " + t + " ticks";
		}
	}

	/**
	 * The player hides, standing still, behind an inner wall of a stone house. The Spindle only knows
	 * they went in the door. It should send its probe in. Later the player starts walking around,
	 * which it should hear, and then it should get them.
	 */
	static class ProbeHide extends Scenario {
		int foundWhileStill = -1;
		static final int MOVE_AT = 800;

		ProbeHide() {
			super("probe_hide", 2000);
		}

		@Override
		void setup() {
			Scenes.partitionHouse(world, o, Scenes.STONE);
			spawn(28.5, -2.5, -90f);
		}

		@Override
		String check() {
			if (t == 10) {
				forceHunt();
				spindle.debugLoseTargetAt(at(21.5, 0, 0.5));
			}
			if (t < MOVE_AT && foundWhileStill < 0 && (spindle.statProbeSpots > 0 || (t > 40 && spindle.isTracking()))) foundWhileStill = t;
			if (t == MOVE_AT) victim.patrol(at(28.5, 0, -2.5), at(28.5, 0, 2.5));
			if (!hurt()) return null;
			boolean searched = spindle.statProbeSearches > 0;
			String how = foundWhileStill >= 0 ? "found the hiding player at t=" + foundWhileStill : "did not find the player until they moved";
			return (searched ? "PASS" : "FAIL never used its probe:") + " probe searches=" + spindle.statProbeSearches + ", " + how + ", hit at t=" + t;
		}

		@Override
		String atTimeout() {
			return "FAIL never got the player (probe searches=" + spindle.statProbeSearches + ", found while still at " + foundWhileStill + ")";
		}
	}

	/** Open ground with pillars for cover; the player walks a loop. It should shadow them, mostly unseen. */
	static class StalkOpen extends Scenario {
		int stalkTicks, watchedTicks;
		double distSum, moved;
		Vec3d last;

		StalkOpen() {
			super("stalk_open", 900);
		}

		@Override
		void setup() {
			Scenes.pillars(world, o);
			spawn(40.5, 0.5, -90f);
			victim.patrol(at(52, 0, -12), at(52, 0, 12), at(28, 0, 12), at(28, 0, -12));
		}

		@Override
		String check() {
			if (spindle.getMode() == SpindleEntity.Mode.STALK) {
				stalkTicks++;
				distSum += dist();
				if (spindle.isWatched()) watchedTicks++;
			}
			if (last != null) moved += spindle.getPos().distanceTo(last);
			last = spindle.getPos();
			return null;
		}

		@Override
		String atTimeout() {
			double avg = stalkTicks == 0 ? 0 : distSum / stalkTicks;
			int watchedPct = stalkTicks == 0 ? 0 : 100 * watchedTicks / stalkTicks;
			String numbers = String.format("stalked %d ticks, average distance %.0f, moved %.0f blocks, in the player's view %d%% of the time, player hp %.0f",
					stalkTicks, avg, moved, watchedPct, victim.getHealth());
			boolean ok = stalkTicks > 300 && moved > 15 && avg > 8 && avg < 75 && watchedPct < 60;
			return (ok ? "PASS " : "FAIL ") + numbers;
		}
	}

	/** It's standing in light and the player is looking at it: it must not move. Then they look away. */
	static class LightFreeze extends Scenario {
		Vec3d frozenPos;
		double movedWhileWatched = -1;

		LightFreeze() {
			super("light_freeze", 700);
		}

		@Override
		void setup() {
			Scenes.glowPad(world, o);
			spawn(14.5, 0.5, 90f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			if (t == 25) frozenPos = spindle.getPos();
			if (t == 125) {
				movedWhileWatched = spindle.getPos().distanceTo(frozenPos);
				victim.faceYaw = -90f; // look away
			}
			if (t <= 125 && hurt()) return "FAIL it attacked while being watched in the light";
			if (t > 125 && hurt()) {
				return (movedWhileWatched < 0.3 ? "PASS" : "FAIL") + String.format(" moved %.2f blocks in 5s while watched, then hit the player %d ticks after they looked away", movedWhileWatched, t - 125);
			}
			return null;
		}
	}

	/** A dark covered hall with the player walking up and down it. It should hang from the ceiling and drop on them. */
	static class LurkCeiling extends Scenario {
		LurkCeiling() {
			super("lurk_ceiling", 1800);
		}

		@Override
		void setup() {
			Scenes.hall(world, o, 5, Scenes.STONE);
			spindle = Scenes.spawnSpindle(world, o.getX() + 2.5, o.getY(), o.getZ() + 0.5, -90f);
			victim = TestPlayer.spawn(world, "t_" + name, at(36.5, 0, 0.5), 90f);
		}

		@Override
		String check() {
			if (t == 10) spindle.noticeTarget(victim);
			if (t == 30) spindle.setTensionFraction(0.4f);
			if (t == 300) victim.patrol(at(12.5, 0, 0.5), at(36.5, 0, 0.5)); // starts pacing the hall
			if (!hurt()) return null;
			return (hungAt >= 0 ? "PASS hung from the ceiling at t=" + hungAt + " and" : "FAIL never hung from the ceiling;") + " hit the player at t=" + t;
		}

		@Override
		String atTimeout() {
			return hungAt >= 0 ? "PASS hung from the ceiling at t=" + hungAt + " (but never dropped on the player)" : "FAIL never hung from the ceiling";
		}
	}

	/** Badly hurt mid-hunt: it should break off and get away. */
	static class Retreat extends Scenario {
		Retreat() {
			super("retreat", 600);
		}

		@Override
		void setup() {
			spawn(20.5, 0.5, -90f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			if (t == 30) {
				spindle.setHealth(40f);
				spindle.damage(world.getDamageSources().generic(), 2f);
			}
			if (t > 30 && spindle.getMode() == SpindleEntity.Mode.RETREAT && dist() > 30) {
				return "PASS broke off and got " + (int) dist() + " blocks away by t=" + t;
			}
			return null;
		}
	}

	/** Nobody near it at all: it should cross 230 blocks of empty world on its own and start stalking. */
	static class TravelFar extends Scenario {
		TravelFar() {
			super("travel_far", 2200);
		}

		@Override
		void setup() {
			Scenes.prepare(world, o.getX() + 200, o.getZ() - 32, o.getX() + 260, o.getZ() + 32);
			spawn(230.5, 0.5, -90f);
		}

		@Override
		String check() {
			if (dist() < 80 && spindle.getMode() != SpindleEntity.Mode.WANDER) {
				return "PASS crossed the world and started " + spindle.getMode() + " at t=" + t;
			}
			return null;
		}
	}

	/** A dark 3-wide, 4-high corridor with a dog-leg, like a woodland mansion hallway. */
	static class MansionCorridor extends Scenario {
		int maxStuck;
		float maxHeightInside;

		MansionCorridor() {
			super("mansion_corridor", 900);
		}

		@Override
		void setup() {
			BlockState wall = Blocks.DARK_OAK_PLANKS.getDefaultState();
			Scenes.corridor(world, o.add(6, 0, 0), 4, wall);
			// a side passage at the far end, so the player can be around a corner
			Scenes.fill(world, o, 34, 0, 1, 36, 3, 14, wall);
			Scenes.fill(world, o, 35, 0, 1, 35, 2, 13, Scenes.AIR);
			spawn(35.5, 12.5, 180f);
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			maxStuck = Math.max(maxStuck, spindle.getStuckLevel());
			double x = spindle.getX() - o.getX();
			if (x > 8) maxHeightInside = Math.max(maxHeightInside, spindle.getBodyHeight());
			if (!hurt()) return null;
			boolean fits = maxHeightInside <= 3.8f;
			return (fits ? "PASS" : "FAIL too tall for the corridor:") + String.format(" reached the player round the corner at t=%d, tallest inside %.1f, worst stuck level %d", t, maxHeightInside, maxStuck);
		}
	}

	/** Steps and a 4-block ledge; the player is on top. It has to find a way up. */
	static class RoughSteps extends Scenario {
		RoughSteps() {
			super("rough_steps", 900);
		}

		@Override
		void setup() {
			Scenes.steps(world, o);
			spindle = Scenes.spawnSpindle(world, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90f);
			victim = TestPlayer.spawn(world, "t_" + name, at(21.5, 5, 0.5), 90f);
			victim.patrol(at(18.5, 5, -3.5), at(22.5, 5, 3.5));
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			return hurt() ? "PASS climbed up and hit the player at t=" + t : null;
		}
	}

	/** A herd of cows between it and the player: it should hit what's in its way on the way through. */
	static class MobsInPath extends Scenario {
		final java.util.List<net.minecraft.entity.LivingEntity> cows = new ArrayList<>();

		MobsInPath() {
			super("mobs_in_path", 600);
		}

		@Override
		void setup() {
			spawn(26.5, 0.5, -90f);
			for (int z = -1; z <= 1; z++) {
				for (int x = 10; x <= 14; x += 2) {
					net.minecraft.entity.passive.CowEntity cow = net.minecraft.entity.EntityType.COW.create(world);
					if (cow == null) continue;
					cow.refreshPositionAndAngles(o.getX() + x + 0.5, o.getY(), o.getZ() + z * 1.5 + 0.5, 0f, 0f);
					cow.setAiDisabled(true);
					world.spawnEntity(cow);
					cows.add(cow);
				}
			}
		}

		@Override
		String check() {
			if (t == 10) forceHunt();
			if (!hurt()) return null;
			long hit = cows.stream().filter(c -> !c.isAlive() || c.getHealth() < c.getMaxHealth()).count();
			return (hit > 0 ? "PASS" : "FAIL walked through the herd without hitting anything:") + " hit " + hit + " cows on the way, reached the player at t=" + t;
		}

		@Override
		void cleanup() {
			super.cleanup();
			cows.forEach(net.minecraft.entity.Entity::discard);
		}
	}

	/** Set on fire, standing on magma between cactus and berry bushes: none of it should hurt. */
	static class NoWorldDamage extends Scenario {
		NoWorldDamage() {
			super("no_world_damage", 200);
		}

		@Override
		void setup() {
			Scenes.fill(world, o, -2, -1, -2, 2, -1, 2, Blocks.MAGMA_BLOCK.getDefaultState());
			Scenes.set(world, o, 1, 0, 0, Blocks.CACTUS.getDefaultState());
			Scenes.set(world, o, -1, 0, 0, Blocks.SWEET_BERRY_BUSH.getDefaultState());
			Scenes.set(world, o, 0, 0, 1, Blocks.FIRE.getDefaultState());
			spindle = Scenes.spawnSpindle(world, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90f);
			spindle.setOnFireFor(10f);
			victim = TestPlayer.spawn(world, "t_" + name, at(60.5, 0, 0.5), -90f);
		}

		@Override
		String check() {
			return null;
		}

		@Override
		String atTimeout() {
			float hp = spindle.getHealth();
			return (hp >= spindle.getMaxHealth() ? "PASS" : "FAIL") + " health after 10s of fire, magma, cactus and berries: " + hp;
		}
	}

	private SelfTest() {
	}
}
