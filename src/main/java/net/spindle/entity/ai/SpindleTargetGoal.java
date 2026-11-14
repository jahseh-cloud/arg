package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/** Picks the most vulnerable player: alone, in the dark, underground, hurt. Sticks with its choice. */
public class SpindleTargetGoal extends Goal {
	private final SpindleEntity spindle;
	private int cooldown;
	@Nullable private PlayerEntity pick;

	public SpindleTargetGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.TARGET));
	}

	@Override
	public boolean canStart() {
		if (--cooldown > 0) return false;
		cooldown = 20;
		if (spindle.getMode() == SpindleEntity.Mode.HUNT && spindle.getTarget() != null && spindle.getTarget().isAlive()) {
			return false; // committed
		}
		PlayerEntity best = null;
		double bestScore = -1e9;
		LivingEntity current = spindle.getTarget();
		for (PlayerEntity p : spindle.getWorld().getPlayers()) {
			if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
			double dist = spindle.distanceTo(p);
			if (dist > 80) continue;
			double score = -dist * 0.3;
			if (spindle.countPlayersNear(p, 20) <= 1) score += 30;
			if (spindle.isInDarkness(p)) score += 20;
			if (!spindle.getWorld().isSkyVisible(p.getBlockPos())) score += 15;
			score += (p.getMaxHealth() - p.getHealth()) * 2.0;
			if (p == current) score += 30; // fixation
			if (score > bestScore) {
				bestScore = score;
				best = p;
			}
		}
		pick = best;
		return best != null && best != current;
	}

	@Override
	public void start() {
		spindle.setTarget(pick);
	}

	@Override
	public boolean shouldContinue() {
		return false;
	}
}
