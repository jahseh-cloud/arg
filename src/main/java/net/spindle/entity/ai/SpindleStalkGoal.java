package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpaceMap;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Stalking. Two styles depending on where the target is:
 *
 * OPEN GROUND: keeps a straight-line distance band that shrinks as tension builds, picks spots nobody
 * can see, prefers behind the target and in the dark, and sometimes deliberately allows a glimpse.
 *
 * CONFINED (caves, mansions, any enclosed space): builds a walking-distance map around the target and
 * keeps a much tighter band of PATH distance. It predicts where the target is heading and prefers
 * hidden spots that overlook that route: around the corner, in the side room, at the fork.
 */
public class SpindleStalkGoal extends Goal {
	private final SpindleEntity spindle;
	private int repathTimer;
	@Nullable private Vec3d spot;
	private boolean hurry;
	private int failures;
	@Nullable private SpaceMap map;
	private final ArrayDeque<Vec3d> targetTrail = new ArrayDeque<>();

	public SpindleStalkGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return spindle.getMode() == SpindleEntity.Mode.STALK && spindle.getTarget() != null && !spindle.isHanging();
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

		if (spindle.age % 10 == 0) {
			targetTrail.addLast(target.getPos());
			while (targetTrail.size() > 5) targetTrail.removeFirst();
		}

		boolean confined = !spindle.getWorld().isSkyVisible(target.getBlockPos());
		boolean relocate = spindle.consumeRelocateRequest();
		repathTimer--;

		if (confined) {
			tickConfined(target, relocate);
		} else {
			tickOpen(target, relocate);
		}

		if (spot != null && spindle.getNavigation().isIdle() && spindle.squaredDistanceTo(spot) > 4.0) {
			moveToSpot();
		}
		if (spindle.getNavigation().isIdle()) {
			spindle.getLookControl().lookAt(target, 10f, 10f);
		}
	}

	/** Where the target will be in about 2-3 seconds, from its recent movement. */
	public static Vec3d predict(LivingEntity target, ArrayDeque<Vec3d> trail, double ticksAhead) {
		if (trail.size() < 2) return target.getPos();
		Vec3d first = trail.getFirst();
		Vec3d last = trail.getLast();
		double ticks = (trail.size() - 1) * 10.0;
		Vec3d vel = last.subtract(first).multiply(1.0 / ticks);
		return target.getPos().add(vel.x * ticksAhead, 0, vel.z * ticksAhead);
	}

	// ---------------------------------------------------------------- confined spaces

	private void tickConfined(LivingEntity target, boolean relocate) {
		if (!relocate && repathTimer > 0) return;
		repathTimer = 40 + spindle.getRandom().nextInt(40);
		hurry = relocate;

		if (map == null || spindle.age - map.builtAt > 40 || target.getBlockPos().getSquaredDistance(map.origin) > 9) {
			map = SpaceMap.build(spindle.getWorld(), target.getBlockPos(), 30, 5000, spindle.age);
		}
		if (map == null || map.cells.size() < 10) {
			tickOpen(target, relocate); // not really a walkable space; fall back
			return;
		}

		float t = spindle.getTensionFraction();
		double minP = MathHelper.lerp(t, 14.0, 5.0);
		double maxP = MathHelper.lerp(t, 28.0, 12.0);
		if (spindle.countPlayersNear(target, 16) >= 3) {
			minP += 8;
			maxP += 10;
		}
		Vec3d predicted = predict(target, targetTrail, 50);
		Vec3d look = target.getRotationVec(1.0f);
		Vec3d targetPos = target.getPos();
		Vec3d self = spindle.getPos();

		// Cheap pass over a sample of cells.
		List<BlockPos> shortlist = new ArrayList<>();
		List<Double> scores = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			BlockPos c = map.random(spindle.getRandom());
			int d = map.distance(c);
			if (d < minP || d > maxP) continue;
			Vec3d cv = Vec3d.ofBottomCenter(c);
			double score = 10 - Math.abs(d - (minP + maxP) / 2) * 0.2;
			if (spindle.getWorld().getLightLevel(c) < 5) score += 4;
			score += (8 - map.openness(c)) * 0.4;               // alcoves, corridors, corners
			Vec3d toSpot = cv.subtract(targetPos).normalize();
			score += (1 - look.dotProduct(toSpot)) * 1.5;         // behind them
			score -= cv.distanceTo(self) * 0.04;                  // don't cross the whole cave to get there
			double toRoute = cv.distanceTo(predicted);
			if (toRoute < 8) score += (8 - toRoute) * 0.6;        // near where they're going
			shortlist.add(c);
			scores.add(score);
		}

		// Expensive pass on the best ~25: hidden from everyone, and does it overlook their route?
		Vec3d best = null;
		double bestScore = -1e9;
		for (int n = 0; n < 25 && !shortlist.isEmpty(); n++) {
			int bi = 0;
			for (int i = 1; i < scores.size(); i++) if (scores.get(i) > scores.get(bi)) bi = i;
			BlockPos c = shortlist.remove(bi);
			double score = scores.remove(bi);
			Vec3d cv = Vec3d.ofBottomCenter(c);
			if (spindle.isSpotVisibleToAnyone(cv)) continue;
			if (spindle.hasLineOfSight(cv.add(0, 1.2, 0), predicted.add(0, 1.0, 0))) score += 8; // ambush point
			if (score > bestScore) {
				bestScore = score;
				best = cv;
			}
		}
		if (best != null) {
			spot = best;
			moveToSpot();
		}
	}

	// ---------------------------------------------------------------- open ground

	private void tickOpen(LivingEntity target, boolean relocate) {
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
		double dist = spindle.distanceTo(target);
		boolean outOfBand = dist < minD - 4 || dist > maxD + 6;
		if (!(relocate || repathTimer <= 0 || (spindle.getNavigation().isIdle() && outOfBand))) return;

		repathTimer = 60 + spindle.getRandom().nextInt(80);
		hurry = relocate;
		boolean glimpse = !relocate && t > 0.15f && t < 0.85f && spindle.getRandom().nextFloat() < 0.12f;
		Vec3d targetPos = target.getPos();
		Vec3d look = target.getRotationVec(1.0f);
		Vec3d lookFlat = new Vec3d(look.x, 0, look.z).normalize();

		spot = spindle.findSpot(targetPos, minD, maxD, 18, candidate -> {
			Vec3d toSpot = candidate.subtract(targetPos);
			Vec3d toSpotFlat = new Vec3d(toSpot.x, 0, toSpot.z).normalize();
			double facing = lookFlat.dotProduct(toSpotFlat);
			boolean visible = spindle.isSpotVisibleToAnyone(candidate);
			if (glimpse) {
				if (!visible || facing > 0.3) return -1e9;
				return 10 - Math.abs(facing);
			}
			if (visible) return -1e9;
			double score = 10;
			score -= candidate.distanceTo(spindle.getPos()) * 0.08;
			score += (1 - facing) * 3;
			if (spindle.getWorld().getLightLevel(BlockPos.ofFloored(candidate)) < 5) score += 4;
			return score;
		});
		if (spot != null) moveToSpot();
	}

	private void moveToSpot() {
		if (spot == null) return;
		boolean ok = spindle.travelTo(spot, hurry ? 1.6 : 1.0);
		if (!ok) {
			if (++failures > 3) {
				failures = 0;
				spot = null;
				repathTimer = 0;
			}
		} else {
			failures = 0;
		}
	}
}
