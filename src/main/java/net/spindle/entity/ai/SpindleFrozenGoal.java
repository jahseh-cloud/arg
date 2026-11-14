package net.spindle.entity.ai;

import net.minecraft.entity.ai.goal.Goal;
import net.spindle.entity.SpindleEntity;

import java.util.EnumSet;

/** While a player sees it in the light, it cannot move. At all. */
public class SpindleFrozenGoal extends Goal {
	private final SpindleEntity spindle;
	private float lockedYaw;

	public SpindleFrozenGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		return spindle.isFrozen();
	}

	@Override
	public boolean shouldContinue() {
		return spindle.isFrozen();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void start() {
		spindle.getNavigation().stop();
		lockedYaw = spindle.bodyYaw;
	}

	@Override
	public void tick() {
		spindle.getNavigation().stop();
		spindle.setVelocity(0, Math.min(0, spindle.getVelocity().y), 0);
		spindle.setBodyYaw(lockedYaw);
		spindle.setHeadYaw(lockedYaw);
		spindle.setYaw(lockedYaw);
	}
}
