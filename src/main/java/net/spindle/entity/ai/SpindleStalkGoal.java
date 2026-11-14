package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Keeps a distance band from its target that shrinks as tension builds.
 * Prefers spots nobody can see, behind the target, in the dark.
 * Occasionally (on purpose) picks a spot where the target CAN see it if they turn around: a glimpse.
 * The moment it's looked at and the player looks away, it relocates.
 */
public class SpindleStalkGoal extends Goal {
	private final SpindleEntity spindle;
	private int repathTimer;
	@Nullable private Vec3d spot;
	private boolean hurry;
	private int failures;

	public SpindleStalkGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return spindle.getMode() == SpindleEntity.Mode.STALK && spindle.getTarget() != null;
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
		spot = null;
	}

	@Override
	public void tick() {
		LivingEntity target = spindle.getTarget();
		if (target == null) return;

		float t = spindle.getTensionFraction();
		double minD = MathHelper.lerp(t, 36.0, 12.0);
		double maxD = MathHelper.lerp(t, 52.0, 22.0);
		boolean exposedDay = spindle.getWorld().isDay() && spindle.getWorld().isSkyVisible(target.getBlockPos())
				&& !spindle.isInDarkness(target);
		if (exposedDay) {
			minD = 44;
			maxD = 60;
		}
		if (spindle.countPlayersNear(target, 16) >= 3) {
			minD = Math.max(minD, 40);
			maxD = Math.max(maxD, 56);
		}

		boolean relocate = spindle.consumeRelocateRequest();
		double dist = spindle.distanceTo(target);
		boolean outOfBand = dist < minD - 4 || dist > maxD + 6;
		repathTimer--;

		if (relocate || repathTimer <= 0 || (spindle.getNavigation().isIdle() && outOfBand)) {
			pickSpot(target, minD, maxD, relocate, t);
		}

		if (spot != null && !spindle.getNavigation().isIdle()) {
			// keep moving
		} else if (spot != null && spindle.squaredDistanceTo(spot) > 4.0) {
			moveToSpot();
		}

		if (spindle.getNavigation().isIdle()) {
			spindle.getLookControl().lookAt(target, 10f, 10f);
		}
	}

	private void pickSpot(LivingEntity target, double minD, double maxD, boolean relocate, float tension) {
		repathTimer = 60 + spindle.getRandom().nextInt(80);
		hurry = relocate;
		boolean glimpse = !relocate && tension > 0.15f && tension < 0.85f && spindle.getRandom().nextFloat() < 0.12f;
		Vec3d targetPos = target.getPos();
		Vec3d look = target.getRotationVec(1.0f);
		Vec3d lookFlat = new Vec3d(look.x, 0, look.z).normalize();

		spot = spindle.findSpot(targetPos, minD, maxD, 18, candidate -> {
			Vec3d toSpot = candidate.subtract(targetPos);
			Vec3d toSpotFlat = new Vec3d(toSpot.x, 0, toSpot.z).normalize();
			double facing = lookFlat.dotProduct(toSpotFlat); // 1 = right in front of them
			boolean visible = spindle.isSpotVisibleToAnyone(candidate);
			if (glimpse) {
				// Visible if they turn, but not currently in front of them.
				if (!visible || facing > 0.3) return -1e9;
				return 10 - Math.abs(facing);
			}
			if (visible) return -1e9;
			double score = 10;
			score -= candidate.distanceTo(spindle.getPos()) * 0.08;
			score += (1 - facing) * 3; // prefer behind them
			if (spindle.getWorld().getLightLevel(net.minecraft.util.math.BlockPos.ofFloored(candidate)) < 5) score += 4;
			return score;
		});
		if (spot != null) moveToSpot();
	}

	private void moveToSpot() {
		if (spot == null) return;
		double speed = (hurry ? 1.6 : 1.0) * spindle.getSpeedFactor();
		boolean ok = spindle.getNavigation().startMovingTo(spot.x, spot.y, spot.z, speed);
		if (!ok) {
			failures++;
			if (!spindle.isFolded()) {
				spindle.requestFold();
				repathTimer = 2;
			} else if (failures > 3) {
				failures = 0;
				spot = null;
				repathTimer = 0;
			}
		} else {
			failures = 0;
		}
	}
}
