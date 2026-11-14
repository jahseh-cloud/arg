package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpaceMap;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * The hunt.
 *
 * While it can see or hear its prey it runs them down. When it loses them it goes to where it last
 * knew they were, then checks a few spots further along the way they were heading. If that turns up
 * nothing it gives up and goes back to shadowing them.
 *
 * (Its tendril goal has priority over this one, so near buildings and in caves the probe search
 * happens in between these steps.)
 */
public class SpindleHuntGoal extends Goal {
	private final SpindleEntity spindle;
	private int repathTimer;
	private int searchStage;
	private int searchTicks;
	@Nullable private Vec3d searchPoint;

	public SpindleHuntGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		LivingEntity t = spindle.getTarget();
		return spindle.getMode() == SpindleEntity.Mode.HUNT && t != null && t.isAlive() && spindle.tendril.isIdle();
	}

	@Override
	public boolean shouldContinue() {
		return canStart();
	}

	@Override
	public void start() {
		repathTimer = 0;
	}

	@Override
	public void stop() {
		spindle.getNavigation().stop();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		LivingEntity target = spindle.getTarget();
		if (target == null) return;

		if (spindle.isTracking()) {
			chase(target);
		} else {
			search();
		}
	}

	private void chase(LivingEntity target) {
		searchStage = 0;
		searchTicks = 0;
		searchPoint = null;
		spindle.getLookControl().lookAt(target, 30f, 30f);

		if (--repathTimer <= 0) {
			repathTimer = spindle.squaredDistanceTo(target) < 100 ? 6 : 12;
			spindle.setDestination(target.getPos());
			Path path = spindle.getNavigation().findPathTo(target, 0);
			boolean reaches = path != null && path.reachesTarget();
			// "Unreachable" only counts once it has walked as close as it can get.
			boolean atEnd = true;
			if (path != null) {
				PathNode end = path.getEnd();
				atEnd = end == null || spindle.getBlockPos().getManhattanDistance(end.getBlockPos()) <= 2;
			}
			spindle.markUnreachable(!reaches && atEnd);
			if (path != null) spindle.getNavigation().startMovingAlong(path, (reaches ? 1.35 : 1.2) * spindle.getSpeedFactor());
		}

		if (spindle.canMeleeNow() && spindle.isInMeleeReach(target) && spindle.canSee(target)) {
			spindle.tryAttack(target);
		}
	}

	private void search() {
		Vec3d lastKnown = spindle.getLastKnownPos();
		if (lastKnown == null) {
			spindle.loseTrail();
			return;
		}
		spindle.markUnreachable(false);
		if (searchPoint == null) {
			searchPoint = lastKnown;
			searchStage = 0;
			searchTicks = 0;
			repathTimer = 0;
		}
		spindle.getLookControl().lookAt(searchPoint.x, searchPoint.y + 1.5, searchPoint.z, 20f, 20f);

		searchTicks++;
		boolean arrived = spindle.squaredDistanceTo(searchPoint) < 2.5 * 2.5;
		if (arrived || searchTicks > 160) {
			searchStage++;
			searchTicks = 0;
			if (searchStage > 3 || spindle.getLostTicks() > 700) {
				spindle.loseTrail();
				return;
			}
			searchPoint = nextSearchPoint(lastKnown);
			repathTimer = 0;
		}
		if (--repathTimer <= 0) {
			repathTimer = 20;
			if (!spindle.travelTo(searchPoint, 1.15)) searchTicks += 40;
		}
	}

	/** Further along the way the prey was heading when it vanished, with some scatter. */
	private Vec3d nextSearchPoint(Vec3d lastKnown) {
		Vec3d vel = spindle.getLastKnownVel();
		Vec3d dir = new Vec3d(vel.x, 0, vel.z);
		if (dir.lengthSquared() < 0.0004) {
			double a = spindle.getRandom().nextDouble() * Math.PI * 2;
			dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
		} else {
			dir = dir.normalize();
		}
		for (int attempt = 0; attempt < 5; attempt++) {
			double reach = 5.0 * searchStage - attempt;
			Vec3d base = lastKnown.add(dir.multiply(Math.max(2.0, reach)))
					.add((spindle.getRandom().nextDouble() - 0.5) * 6, 0, (spindle.getRandom().nextDouble() - 0.5) * 6);
			BlockPos snapped = SpaceMap.snap(spindle.getWorld(), BlockPos.ofFloored(base));
			if (snapped != null) return Vec3d.ofBottomCenter(snapped);
		}
		Vec3d fallback = spindle.randomGroundNear(lastKnown, 3, 8);
		return fallback != null ? fallback : lastKnown;
	}
}
