package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import net.spindle.entity.TendrilController;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/**
 * Stands still and uses the tendril.
 *  - Hunting, knows where you are, but can't walk to you (or you're a few blocks off): reaches
 *    straight in and grabs, through a window or door if it has to.
 *  - Hunting and it lost you somewhere enclosed: sends a searching probe in around the last place
 *    it knew you were.
 *  - Late in the stalking phase, with you indoors and out of its sight: it probes the building.
 */
public class SpindleTendrilGoal extends Goal {
	private final SpindleEntity spindle;
	@Nullable private List<Vec3d> route;
	@Nullable private Vec3d searchCenter;
	private int checkTimer;

	public SpindleTendrilGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (!spindle.tendril.isReady() || spindle.isFrozen() || spindle.isHanging()) return false;
		if (--checkTimer > 0) return false;
		checkTimer = 10;
		route = null;
		searchCenter = null;

		LivingEntity target = spindle.getTarget();
		if (target == null || !target.isAlive()) return false;
		Vec3d core = spindle.getCorePos();
		SpindleEntity.Mode mode = spindle.getMode();

		if (mode == SpindleEntity.Mode.HUNT) {
			if (!spindle.isTracking()) {
				Vec3d lastKnown = spindle.getLastKnownPos();
				if (lastKnown == null || spindle.getLostTicks() < 25) return false;
				if (!spindle.isEnclosed(lastKnown) || core.distanceTo(lastKnown) > 14) return false;
				Vec3d vel = spindle.getLastKnownVel();
				searchCenter = lastKnown.add(vel.x * 20, 0.5, vel.z * 20)
						.add(spindle.getRandom().nextGaussian() * 2.0, 0, spindle.getRandom().nextGaussian() * 2.0);
				return true;
			}
			double dist = core.distanceTo(target.getPos());
			if (dist > TendrilController.MAX_LENGTH) return false;
			// Mostly it just runs you down. It reaches when it can't get to you, when it's squeezed
			// into a tunnel and slow, or now and then to keep you guessing.
			boolean want = spindle.getUnreachableTicks() > 2
					|| (dist > 5 && spindle.isCrouched() && spindle.getRandom().nextFloat() < 0.12f)
					|| (dist > 6 && spindle.getRandom().nextFloat() < 0.03f);
			if (!want) return false;
			route = spindle.tendril.planRoute(target);
			return route != null;
		}

		if (mode == SpindleEntity.Mode.STALK && spindle.getTensionFraction() > 0.6f && !spindle.isWatched()) {
			double dist = core.distanceTo(target.getPos());
			if (dist > 30 || !spindle.isEnclosed(target.getPos()) || spindle.canSeeTarget(target)) return false;
			// It only knows roughly where they are.
			searchCenter = target.getPos().add(spindle.getRandom().nextGaussian() * 2.5, 0.5, spindle.getRandom().nextGaussian() * 2.5);
			return true;
		}
		return false;
	}

	@Override
	public void start() {
		spindle.getNavigation().stop();
		LivingEntity target = spindle.getTarget();
		if (searchCenter != null) {
			if (!spindle.tendril.beginSearch(searchCenter)) checkTimer = 100; // no way in from here; the hunt goal moves it closer
		} else if (target != null && route != null) {
			spindle.tendril.begin(target, route);
		}
	}

	@Override
	public boolean shouldContinue() {
		return !spindle.tendril.isIdle() && spindle.getMode() != SpindleEntity.Mode.RETREAT;
	}

	@Override
	public void stop() {
		spindle.tendril.forceRelease();
		route = null;
		searchCenter = null;
	}

	@Override
	public void tick() {
		spindle.getNavigation().stop();
		Vec3d first = spindle.tendril.getFirstWaypoint();
		if (first != null) spindle.getLookControl().lookAt(first.x, first.y, first.z, 20f, 20f);
	}
}
