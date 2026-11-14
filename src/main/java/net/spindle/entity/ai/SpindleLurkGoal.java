package net.spindle.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.SpindleSounds;
import net.spindle.entity.SpaceMap;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.EnumSet;

/**
 * Underground ambush: it picks a spot on the target's likely route with a ceiling 3-7 blocks up, gets
 * there unseen, folds, climbs up and hangs upside down from the ceiling. When the target walks
 * underneath, it drops on them and the hunt begins.
 */
public class SpindleLurkGoal extends Goal {
	private enum Phase { GO, CLIMB, HANG }

	private final SpindleEntity spindle;
	private Phase phase = Phase.GO;
	@Nullable private BlockPos floorCell;
	private double hangY;
	private int ticks;
	private int cooldown = 40;
	private final ArrayDeque<Vec3d> trail = new ArrayDeque<>();

	public SpindleLurkGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		if (spindle.isHanging()) { // resuming after another goal interrupted us
			phase = Phase.HANG;
			return true;
		}
		LivingEntity target = spindle.getTarget();
		if (target != null && spindle.age % 10 == 0) {
			trail.addLast(target.getPos());
			while (trail.size() > 5) trail.removeFirst();
		}
		if (--cooldown > 0) return false;
		cooldown = 40;
		if (spindle.getMode() != SpindleEntity.Mode.STALK || target == null) return false;
		if (spindle.getTensionFraction() < 0.3f || spindle.isWatched()) return false;
		if (spindle.getWorld().isSkyVisible(target.getBlockPos())) return false; // underground / indoors only
		if (spindle.distanceTo(target) > 40) return false;
		return pickSpot(target);
	}

	private boolean pickSpot(LivingEntity target) {
		SpaceMap map = SpaceMap.build(spindle.getWorld(), target.getBlockPos(), 24, 4000, spindle.age);
		if (map == null) return false;
		Vec3d predicted = SpindleStalkGoal.predict(target, trail, 60);
		BlockPos best = null;
		double bestScore = -1e9;
		for (int i = 0; i < 200; i++) {
			BlockPos c = map.random(spindle.getRandom());
			int d = map.distance(c);
			if (d < 6 || d > 22) continue;
			int h = SpaceMap.ceilingHeight(spindle.getWorld(), c);
			if (h < 3 || h > 7) continue;
			Vec3d cv = Vec3d.ofBottomCenter(c);
			double toRoute = Math.sqrt(Math.pow(cv.x - predicted.x, 2) + Math.pow(cv.z - predicted.z, 2));
			double score = -toRoute * 1.5 + (8 - map.openness(c)) * 0.5;
			if (spindle.getWorld().getLightLevel(c.up(h - 1)) < 5) score += 4;
			if (score <= bestScore) continue;
			Vec3d hangSpot = cv.add(0, h - 1.0, 0);
			if (!spindle.isSpotHidden(cv) || !spindle.isSpotHidden(hangSpot)) continue;
			bestScore = score;
			best = c;
		}
		if (best == null) return false;
		floorCell = best;
		hangY = best.getY() + SpaceMap.ceilingHeight(spindle.getWorld(), best) - SpindleEntity.MIN_HEIGHT;
		phase = Phase.GO;
		return true;
	}

	@Override
	public boolean shouldContinue() {
		if (spindle.getMode() != SpindleEntity.Mode.STALK || spindle.getTarget() == null) return false;
		return ticks < 2400;
	}

	@Override
	public void start() {
		ticks = 0;
		if (phase == Phase.GO && floorCell != null) spindle.travelTo(Vec3d.ofBottomCenter(floorCell), 1.2);
	}

	@Override
	public void stop() {
		// Dropped by being hurt, going to hunt, or giving up. If another goal just borrowed control
		// (snuffing a light from up there), canStart() resumes the hang.
		if (spindle.getMode() != SpindleEntity.Mode.STALK || spindle.getTarget() == null || ticks >= 2400) {
			spindle.setHanging(false);
		}
		cooldown = 300;
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		ticks++;
		LivingEntity target = spindle.getTarget();
		if (target == null) return;
		if (spindle.age % 10 == 0) {
			trail.addLast(target.getPos());
			while (trail.size() > 5) trail.removeFirst();
		}

		switch (phase) {
			case GO -> {
				if (floorCell == null) return;
				Vec3d c = Vec3d.ofBottomCenter(floorCell);
				if (spindle.getPos().squaredDistanceTo(c) < 1.2 * 1.2) {
					if (spindle.isWatched()) return; // never climb while someone's looking
					spindle.getNavigation().stop();
					spindle.setPosition(c.x, spindle.getY(), c.z);
					spindle.setHanging(true);
					phase = Phase.CLIMB;
				} else if (spindle.getNavigation().isIdle()) {
					if (!spindle.travelTo(c, 1.2)) ticks = 2400; // can't get there
				}
			}
			case CLIMB -> {
				spindle.setVelocity(Vec3d.ZERO);
				double y = Math.min(hangY, spindle.getY() + 0.25);
				spindle.setPosition(spindle.getX(), y, spindle.getZ());
				if (y >= hangY - 1e-3) {
					phase = Phase.HANG;
					spindle.playSound(SpindleSounds.SHIFT, 0.4f, 0.4f);
				}
			}
			case HANG -> {
				spindle.setVelocity(Vec3d.ZERO);
				spindle.getNavigation().stop();
				double dx = target.getX() - spindle.getX();
				double dz = target.getZ() - spindle.getZ();
				boolean beneath = dx * dx + dz * dz < 3.0 * 3.0 && target.getY() < spindle.getY()
						&& spindle.hasLineOfSight(spindle.getCorePos(), target.getEyePos());
				if (beneath) {
					drop(dx, dz);
				} else if (spindle.distanceTo(target) > 36 && ticks > 600) {
					ticks = 2400; // they went elsewhere; come down and follow
				}
			}
		}
	}

	private void drop(double dx, double dz) {
		spindle.setHanging(false);
		spindle.setVelocity(dx * 0.15, -0.3, dz * 0.15);
		spindle.velocityModified = true;
		spindle.startHunt();
	}
}
