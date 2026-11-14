package net.spindle.entity.ai;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Nobody nearby? It doesn't sit and wait. It walks across the world toward the most isolated player,
 * keeping its own chunks loaded as it goes. When it gets within range, stalking takes over.
 */
public class SpindleTravelGoal extends Goal {
	private final SpindleEntity spindle;
	@Nullable private PlayerEntity quarry;
	private int repathTimer;
	private int checkTimer;

	public SpindleTravelGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (spindle.getMode() != SpindleEntity.Mode.WANDER || spindle.getTarget() != null) return false;
		if (--checkTimer > 0) return false;
		checkTimer = 20; // (goal checks only run every other tick)
		quarry = pickQuarry();
		return quarry != null;
	}

	@Override
	public boolean shouldContinue() {
		return spindle.getMode() == SpindleEntity.Mode.WANDER && spindle.getTarget() == null
				&& quarry != null && quarry.isAlive() && !quarry.isRemoved() && quarry.getWorld() == spindle.getWorld();
	}

	@Override
	public void start() {
		repathTimer = 0;
	}

	@Override
	public void stop() {
		quarry = null;
		spindle.getNavigation().stop();
	}

	@Override
	public void tick() {
		if (quarry == null) return;
		if (--repathTimer > 0 && !spindle.getNavigation().isIdle()) return;
		repathTimer = 60;

		Vec3d here = spindle.getPos();
		Vec3d toward = quarry.getPos().subtract(here);
		Vec3d flat = new Vec3d(toward.x, 0, toward.z);
		double dist = flat.length();
		if (dist < 1) return;
		double hop = Math.min(28.0, dist);
		Vec3d aim = here.add(flat.multiply(hop / dist));
		Vec3d spot = spindle.randomGroundNear(new Vec3d(aim.x, here.y, aim.z), 0, 6);
		if (spot == null) spot = spindle.randomGroundNear(new Vec3d(aim.x, quarry.getY(), aim.z), 0, 8);
		if (spot == null) return;
		boolean ok = spindle.travelTo(spot, 1.1);
		if (!ok) repathTimer = 5;
	}

	@Nullable
	private PlayerEntity pickQuarry() {
		PlayerEntity best = null;
		double bestScore = -1e9;
		for (PlayerEntity p : spindle.getWorld().getPlayers()) {
			if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
			double score = spindle.countPlayersNear(p, 48) <= 1 ? 100 : 0;
			score -= Math.sqrt(p.squaredDistanceTo(spindle)) * 0.01;
			if (score > bestScore) {
				bestScore = score;
				best = p;
			}
		}
		return best;
	}
}
