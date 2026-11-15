package net.spindle.selftest;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.Spindle;
import net.spindle.SpindleBlocks;
import net.spindle.entity.BroodNodeEntity;
import net.spindle.entity.BroodmotherEntity;
import net.spindle.entity.SpindleEntity;
import net.spindle.world.FarLands;
import net.spindle.world.HiveEvents;
import net.spindle.world.Hivemind;
import net.spindle.world.SpindleWorldState;
import net.spindle.world.StalkerDirector;

import java.util.List;

/** World generation and story tests. Run on a normal (not flat) world, one after another. */
final class WorldTests {
	static List<SelfTest.Scenario> all() {
		return List.of(new FarLandsTest(), new StalkerSpawnTest(), new HiveGenTest(), new HiveEnterTest(), new BroodmotherTest(),
				new AfterTheEndTest());
	}

	static int countSolid(ServerWorld w, int x0, int z0, int size, int y0, int y1) {
		int n = 0;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x < x0 + size; x++) for (int z = z0; z < z0 + size; z++) for (int y = y0; y <= y1; y++) {
			if (!w.getBlockState(m.set(x, y, z)).getCollisionShape(w, m).isEmpty()) n++;
		}
		return n;
	}

	static Hivemind hive(ServerWorld w) {
		Hivemind h = Hivemind.of(w);
		if (h == null) throw new IllegalStateException("no Hivemind in this world");
		return h;
	}

	/** Past 1024 the terrain should tower into the sky; inside it shouldn't. */
	static class FarLandsTest extends SelfTest.Scenario {
		FarLandsTest() {
			super("far_lands", 5);
		}

		@Override
		void place() {
			o = new BlockPos(1040, 0, 8);
		}

		@Override
		void setup() {
			Scenes.prepare(world, 960, 0, 1120, 32);
		}

		@Override
		String check() {
			int far = countSolid(world, 1060, 8, 16, 150, 250);
			int near = countSolid(world, 960, 8, 16, 220, 250);
			int edge = countSolid(world, 1025, 8, 2, 70, 140);
			boolean ok = far > 3000 && near < 200 && edge > 100;
			return (ok ? "PASS" : "FAIL") + String.format(" solid blocks y150-250 just past the edge: %d, high up just inside: %d, cliff at the edge: %d/284", far, near, edge);
		}
	}

	/** A lone player at night with nothing shadowing them gets a Spindle, somewhere they can't see it. */
	static class StalkerSpawnTest extends SelfTest.Scenario {
		StalkerSpawnTest() {
			super("stalker_spawns", 600);
		}

		@Override
		void place() {
			o = new BlockPos(200, 0, -300);
		}

		@Override
		void setup() {
			BlockPos sp = world.getSpawnPos();
			Scenes.prepare(world, sp.getX() - 80, sp.getZ() - 80, sp.getX() + 80, sp.getZ() + 80);
			o = new BlockPos(sp.getX(), Scenes.groundY(world, sp.getX(), sp.getZ()), sp.getZ());
			// Somewhere dry.
			for (int r = 0; r < 80 && !world.getFluidState(o.down()).isEmpty(); r += 8) {
				for (int a = 0; a < 8; a++) {
					int x = sp.getX() + (int) (Math.cos(a * Math.PI / 4) * r), z = sp.getZ() + (int) (Math.sin(a * Math.PI / 4) * r);
					BlockPos p = new BlockPos(x, Scenes.groundY(world, x, z), z);
					if (world.getFluidState(p.down()).isEmpty()) {
						o = p;
						break;
					}
				}
			}
			SelfTest.Scenario.note(this, "player at " + o.toShortString() + " on " + world.getBlockState(o.down()).getBlock().getName().getString());
			victim = TestPlayer.spawn(world, "t_stalker", Vec3d.ofBottomCenter(o), 0f);
			StalkerDirector.minDelay = 0;
			StalkerDirector.extraDelay = 0;
			Scenes.cmd(world.getServer(), "gamerule doMobSpawning true");
		}

		@Override
		String check() {
			for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> e.squaredDistanceTo(victim) < 70 * 70)) {
				double d = s.distanceTo(victim);
				boolean seen = victim.canSee(s);
				spindle = s;
				return (d >= 20 && !seen ? "PASS" : "FAIL") + String.format(" one arrived at t=%d, %.0f blocks away, visible to the player: %s, fixated: %s",
						t, d, seen, s.getTarget() == victim);
			}
			if (t % 100 == 0) SelfTest.Scenario.note(this, "t=" + t + " " + net.spindle.world.StalkerDirector.lastNote);
			return null;
		}

		@Override
		void cleanup() {
			Scenes.cmd(world.getServer(), "gamerule doMobSpawning false");
			StalkerDirector.minDelay = 2400;
			StalkerDirector.extraDelay = 3600;
			super.cleanup();
		}
	}

	/** The Hivemind is where the seed says: a hollow heart, a hivemass body. */
	static class HiveGenTest extends SelfTest.Scenario {
		HiveGenTest() {
			super("hivemind_generates", 5);
		}

		@Override
		void place() {
			o = hive(world).center;
		}

		@Override
		void setup() {
			Hivemind h = hive(world);
			Scenes.prepare(world, h.bounds.getMinX(), h.bounds.getMinZ(), h.bounds.getMaxX(), h.bounds.getMaxZ());
		}

		@Override
		String check() {
			Hivemind h = hive(world);
			BlockState heart = world.getBlockState(BlockPos.ofFloored(h.throne.c()));
			int mass = 0, slab = 0;
			BlockPos.Mutable m = new BlockPos.Mutable();
			for (int x = -60; x <= 60; x += 2) for (int z = -60; z <= 60; z += 2) for (int y = -40; y <= 40; y += 2) {
				slab++;
				if (world.getBlockState(m.set(h.center.getX() + x, h.center.getY() + y, h.center.getZ() + z)).isOf(SpindleBlocks.HIVEMASS)) mass++;
			}
			double depth = Math.max(Math.abs(h.center.getX()), Math.abs(h.center.getZ())) - FarLands.EDGE;
			boolean ok = heart.isAir() && mass > slab / 5 && depth >= 200 && depth <= 500;
			return (ok ? "PASS" : "FAIL") + String.format(" at %s (%.0f blocks into the Far Lands), heart is %s, hivemass in %d/%d samples, %d chambers, %d tunnels",
					h.center.toShortString(), depth, heart.getBlock().getName().getString(), mass, slab, h.chambers.size(), h.tunnels.size());
		}
	}

	/** Walking in: the hive fills with Spindles, the Broodmother is there, and the player gets παράσιτος. */
	static class HiveEnterTest extends SelfTest.Scenario {
		HiveEnterTest() {
			super("hivemind_enter", 200);
		}

		@Override
		void place() {
			o = BlockPos.ofFloored(hive(world).chambers.get(0).c());
		}

		@Override
		void setup() {
			Hivemind h = hive(world);
			BlockPos floor = net.spindle.world.SpaceFinder.floorBelow(world, o, 20);
			victim = TestPlayer.spawn(world, "t_parasite", Vec3d.ofBottomCenter(floor == null ? o : floor), 0f);
			victim.getAbilities().invulnerable = true;
		}

		@Override
		String check() {
			if (t < 60) return null;
			var adv = world.getServer().getAdvancementLoader().get(HiveEvents.ADV_PARASITE);
			Hivemind h = hive(world);
			// Fabric refuses to give advancements to fake players, so check that the hive tried.
			boolean granted = adv != null && (victim.getAdvancementTracker().getProgress(adv).isDone()
					|| HiveEvents.lastNote.startsWith("grant spindle:parasite"));
			if (!granted && t < 180) return null;
			return (granted && spindles() >= 30 ? "PASS" : "FAIL") + " hive: " + HiveEvents.lastNote + String.format(", advancement granted: %s, Spindles living in the hive: %d, Broodmothers: %d, player inside: %s",
					granted, spindles(), world.getEntitiesByType(Spindle.BROODMOTHER, e -> true).size(), h.isInside(victim.getPos()));
		}

		int spindles() {
			return world.getEntitiesByType(Spindle.SPINDLE, SpindleEntity::isHiveBound).size();
		}
	}

	/** The fight: it wakes, grows nodes, attacks; strip its nodes, kill it; everything dies with it. */
	static class BroodmotherTest extends SelfTest.Scenario {
		BroodmotherEntity mother;
		int nodesAtStart = -1, hurtAt = -1, dyingAt = -1;
		float hpBefore;

		BroodmotherTest() {
			super("broodmother_fight", 1400);
		}

		@Override
		void place() {
			o = BlockPos.ofFloored(hive(world).throne.c());
		}

		@Override
		void setup() {
			List<? extends BroodmotherEntity> list = world.getEntitiesByType(Spindle.BROODMOTHER, e -> true);
			mother = list.isEmpty() ? null : list.get(0);
			// Put the hive's Spindles to sleep for good so they don't kill our player before the boss does anything.
			for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> true)) s.discard();
			Hivemind h = hive(world);
			Vec3d at = h.throne.c().add(12, 0, 0);
			BlockPos floor = net.spindle.world.SpaceFinder.floorBelow(world, BlockPos.ofFloored(at), 30);
			Vec3d stand = floor == null ? at : Vec3d.ofBottomCenter(floor);
			victim = TestPlayer.spawn(world, "t_mother", stand, 90f);
			victim.patrol(stand.add(0, 0, -6), stand.add(0, 0, 6));
		}

		@Override
		String check() {
			if (mother == null) return "FAIL no Broodmother";
			if (nodesAtStart < 0 && t == 40) nodesAtStart = mother.livingNodes();
			if (hurtAt < 0 && victim.getHealth() < victim.getMaxHealth()) hurtAt = t;
			if (victim.isDead() || victim.getHealth() < 6) {
				victim.setHealth(victim.getMaxHealth()); // keep the test going
			}
			if (t == 400) {
				hpBefore = mother.getHealth();
				mother.damage(world.getDamageSources().playerAttack(victim), 100f); // shielded: should take about 10
				float shielded = hpBefore - mother.getHealth();
				mother.debugExpose();
				SelfTest.Scenario.note(this, String.format("shielded hit took %.1f", shielded));
			}
			if (t > 420 && t < 600 && mother.isExposed() && !mother.isDying()) {
				mother.damage(world.getDamageSources().playerAttack(victim), 400f);
			}
			if (dyingAt < 0 && mother.isDying()) dyingAt = t;
			if (dyingAt > 0 && mother.isRemoved()) {
				SpindleWorldState st = SpindleWorldState.get(world.getServer());
				int left = world.getEntitiesByType(Spindle.SPINDLE, e -> true).size();
				boolean ok = nodesAtStart >= 3 && hurtAt > 0 && st.broodmotherSlain && left == 0;
				return (ok ? "PASS" : "FAIL") + String.format(" nodes grown: %d, first hurt the player at t=%d, slams %d grabs %d brood %d pulses %d, %s, died at t=%d, slain flag: %s, Spindles left: %d",
						nodesAtStart, hurtAt, mother.statSlams, mother.statGrabs, mother.statBrood, mother.statPulses, notes, dyingAt, st.broodmotherSlain, left);
			}
			return null;
		}

		@Override
		String status() {
			if (mother == null) return "";
			return String.format("hp %.0f phase %d exposed %s nodes %d attack %d | victim hp %.0f", mother.getHealth(), mother.getPhase(),
					mother.isExposed(), mother.livingNodes(), mother.getAttack(), victim.getHealth());
		}
	}

	/** Afterwards nothing comes back: a lone player at night, spawn delay zero, no Spindle arrives. */
	static class AfterTheEndTest extends SelfTest.Scenario {
		AfterTheEndTest() {
			super("none_come_back", 300);
		}

		@Override
		void place() {
			o = new BlockPos(-300, 0, 200);
		}

		@Override
		void setup() {
			Scenes.prepare(world, -380, 120, -220, 280);
			o = new BlockPos(-300, Scenes.groundY(world, -300, 200), 200);
			victim = TestPlayer.spawn(world, "t_after", Vec3d.ofBottomCenter(o), 0f);
			StalkerDirector.minDelay = 0;
			StalkerDirector.extraDelay = 0;
			Scenes.cmd(world.getServer(), "gamerule doMobSpawning true");
			// One more tries to exist the old-fashioned way.
			SpindleEntity s = Scenes.spawnSpindle(world, o.getX() + 20.5, o.getY(), o.getZ() + 0.5, 0f);
			HiveEvents.onEntityLoad(s, world);
		}

		@Override
		String check() {
			int n = world.getEntitiesByType(Spindle.SPINDLE, e -> e.isAlive()).size();
			if (n > 0 && t > 5) return "FAIL a Spindle exists after the Broodmother died";
			return null;
		}

		@Override
		String atTimeout() {
			return "PASS no Spindle came in 15 seconds with the spawn delay at zero";
		}
	}

	private WorldTests() {
	}
}
