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
 *  - Can't see you and you're indoors: sends in a searching probe that only knows roughly where you are.
 *  - Can see you (or you're up a pillar / down a hole): reaches straight in and grabs.
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
		if (!spindle.tendril.isReady() || spindle.isFrozen()) return false;
		if (--checkTimer > 0) return false;
		checkTimer = 10;
		route = null;
		searchCenter = null;

		LivingEntity target = spindle.getTarget();
		if (target == null || !target.isAlive()) return false;
		Vec3d core = spindle.getCorePos();
		double dist = core.distanceTo(target.getPos());
		if (dist > TendrilController.MAX_SEARCH_LENGTH - 4) return false;

		SpindleEntity.Mode mode = spindle.getMode();
		boolean hunting = mode == SpindleEntity.Mode.HUNT;
		boolean indoors = !spindle.getWorld().isSkyVisible(target.getBlockPos());
		boolean seesTarget = spindle.hasLineOfSight(core, target.getEyePos())
				|| spindle.hasLineOfSight(core, target.getPos().add(0, target.getHeight() * 0.5, 0));
		boolean lateStalk = mode == SpindleEntity.Mode.STALK && spindle.getTensionFraction() > 0.6f && !spindle.isWatched();

		if (indoors && !seesTarget && (hunting || lateStalk)) {
			// Only roughly where they went.
			searchCenter = target.getPos().add(spindle.getRandom().nextGaussian() * 2.5, 0.5, spindle.getRandom().nextGaussian() * 2.5);
			return true;
		}

		if (dist > TendrilController.MAX_LENGTH) return false;
		boolean want = (hunting && spindle.getUnreachableTicks() > 2)
				|| (hunting && dist > 5 && spindle.getRandom().nextFloat() < 0.15f)
				|| (lateStalk && indoors);
		if (!want) return false;
		route = spindle.tendril.planRoute(target);
		return route != null;
	}

	@Override
	public void start() {
		spindle.getNavigation().stop();
		LivingEntity target = spindle.getTarget();
		if (searchCenter != null) {
			if (!spindle.tendril.beginSearch(searchCenter)) checkTimer = 60; // no way in; try later
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
