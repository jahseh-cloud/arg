package net.spindle.entity.ai;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpaceMap;
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

		if (!spindle.getWorld().isSkyVisible(spindle.getBlockPos())) {
			// Indoors / underground: pick by walking distance, so it actually gets away instead of
			// "fleeing" to a spot on the other side of a wall.
			SpaceMap map = SpaceMap.build(spindle.getWorld(), spindle.getBlockPos(), 32, 5000, spindle.age);
			spot = null;
			if (map != null) {
				double best = -1e9;
				for (int i = 0; i < 120; i++) {
					BlockPos c = map.random(spindle.getRandom());
					Vec3d cv = Vec3d.ofBottomCenter(c);
					double nearest = nearestPlayerDistance(cv);
					if (nearest < 14) continue;
					double score = nearest * 0.4 + map.distance(c) * 0.1;
					if (spindle.getWorld().getLightLevel(c) < 5) score += 6;
					if (score <= best || spindle.isSpotVisibleToAnyone(cv)) continue;
					best = score;
					spot = cv;
				}
			}
		} else {
			spot = spindle.findSpot(spindle.getPos(), 16, 40, 20, c -> {
				double nearest = nearestPlayerDistance(c);
				if (nearest < 24) return -1e9;
				double score = nearest * 0.3;
				if (!spindle.isSpotVisibleToAnyone(c)) score += 20;
				return score;
			});
		}
		if (spot != null) {
			boolean ok = spindle.travelTo(spot, 1.5);
			if (!ok) repathTimer = 5;
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
