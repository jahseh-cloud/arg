package net.spindle.entity.ai;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/** Badly hurt, driven off, or done hunting: it goes somewhere far and dark where nobody can see it. */
public class SpindleRetreatGoal extends Goal {
	private final SpindleEntity spindle;
	@Nullable private Vec3d spot;
	private int repathTimer;

	public SpindleRetreatGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return spindle.getMode() == SpindleEntity.Mode.RETREAT;
	}

	@Override
	public boolean shouldContinue() {
		return canStart();
	}

	@Override
	public void start() {
		repathTimer = 0;
		spot = null;
	}

	@Override
	public void tick() {
		if (--repathTimer > 0 && spot != null) return;
		repathTimer = 40;

		boolean hidden = !spindle.isSpotVisibleToAnyone(spindle.getPos()) && nearestPlayerDistance(spindle.getPos()) > 32;
		if (hidden && spindle.getNavigation().isIdle()) return; // stay put, out of sight

		spot = spindle.findSpot(spindle.getPos(), 16, 40, 20, c -> {
			double nearest = nearestPlayerDistance(c);
			if (nearest < 24) return -1e9;
			double score = nearest * 0.3;
			if (!spindle.isSpotVisibleToAnyone(c)) score += 20;
			return score;
		});
		if (spot != null) {
			boolean ok = spindle.getNavigation().startMovingTo(spot.x, spot.y, spot.z, 1.5 * spindle.getSpeedFactor());
			if (!ok && !spindle.isFolded()) {
				spindle.requestFold();
				repathTimer = 2;
			}
		}
	}

	private double nearestPlayerDistance(Vec3d pos) {
		double best = Double.MAX_VALUE;
		for (PlayerEntity p : spindle.getWorld().getPlayers()) {
			if (p.isSpectator()) continue;
			best = Math.min(best, Math.sqrt(p.squaredDistanceTo(pos)));
		}
		return best;
	}
}
