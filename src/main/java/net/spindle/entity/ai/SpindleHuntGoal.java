package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.spindle.entity.SpindleEntity;

import java.util.EnumSet;

/** Runs the target down. If it can't path to them, it folds; if it still can't, the tendril goal takes over. */
public class SpindleHuntGoal extends Goal {
	private final SpindleEntity spindle;
	private int repathTimer;

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
		spindle.markUnreachable(false);
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		LivingEntity target = spindle.getTarget();
		if (target == null) return;
		spindle.getLookControl().lookAt(target, 30f, 30f);

		if (--repathTimer <= 0) {
			repathTimer = 10;
			Path path = spindle.getNavigation().findPathTo(target, 0);
			boolean reaches = path != null && path.reachesTarget();
			if (reaches) {
				spindle.getNavigation().startMovingAlong(path, 1.35 * spindle.getSpeedFactor());
				spindle.markUnreachable(false);
			} else if (!spindle.isFolded()) {
				spindle.requestFold();
				repathTimer = 1;
			} else {
				spindle.markUnreachable(true);
				if (path != null) spindle.getNavigation().startMovingAlong(path, 1.2 * spindle.getSpeedFactor());
			}
		}

		if (spindle.canMeleeNow() && spindle.isInMeleeReach(target) && spindle.canSee(target)) {
			spindle.tryAttack(target);
		}
	}
}
