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
 * Stands still and sends a tendril in: through windows, doors, gaps, up pillars, down holes.
 * Used when the target can't be reached on foot, when they're hiding indoors, or as a mid-range grab.
 */
public class SpindleTendrilGoal extends Goal {
	private final SpindleEntity spindle;
	@Nullable private List<Vec3d> route;
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

		LivingEntity target = spindle.getTarget();
		if (target == null || !target.isAlive()) return false;
		double dist = spindle.getCorePos().distanceTo(target.getPos());
		if (dist > TendrilController.MAX_LENGTH) return false;

		SpindleEntity.Mode mode = spindle.getMode();
		boolean hunting = mode == SpindleEntity.Mode.HUNT;
		boolean indoors = !spindle.getWorld().isSkyVisible(target.getBlockPos()) && dist > 4;
		boolean lateStalk = mode == SpindleEntity.Mode.STALK && spindle.getTensionFraction() > 0.6f
				&& !spindle.isWatched() && indoors;

		boolean want = (hunting && spindle.getUnreachableTicks() > 2)
				|| (hunting && indoors)
				|| (hunting && dist > 5 && spindle.getRandom().nextFloat() < 0.15f)
				|| lateStalk;
		if (!want) return false;

		route = spindle.tendril.planRoute(target);
		return route != null;
	}

	@Override
	public void start() {
		spindle.getNavigation().stop();
		LivingEntity target = spindle.getTarget();
		if (target != null && route != null) spindle.tendril.begin(target, route);
	}

	@Override
	public boolean shouldContinue() {
		return !spindle.tendril.isIdle() && spindle.getMode() != SpindleEntity.Mode.RETREAT;
	}

	@Override
	public void stop() {
		spindle.tendril.forceRelease();
		route = null;
	}

	@Override
	public void tick() {
		spindle.getNavigation().stop();
		Vec3d first = spindle.tendril.getFirstWaypoint();
		if (first != null) spindle.getLookControl().lookAt(first.x, first.y, first.z, 20f, 20f);
	}
}
