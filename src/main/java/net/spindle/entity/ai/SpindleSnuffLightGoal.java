package net.spindle.entity.ai;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.spindle.entity.SpindleEntity;
import net.spindle.entity.TendrilController;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Light is the only thing that can pin it in place, so it removes light.
 * While nobody is watching, it reaches out and puts out torches, lanterns, candles and campfires,
 * preferring the ones behind its target.
 */
public class SpindleSnuffLightGoal extends Goal {
	private final SpindleEntity spindle;
	private int cooldown;
	@Nullable private BlockPos light;

	public SpindleSnuffLightGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
	}

	@Override
	public boolean canStart() {
		if (--cooldown > 0) return false;
		cooldown = 20;
		if (!spindle.canGrief() || spindle.isWatched() || !spindle.tendril.isReady()) return false;
		SpindleEntity.Mode mode = spindle.getMode();
		boolean active = mode == SpindleEntity.Mode.HUNT
				|| (mode == SpindleEntity.Mode.STALK && spindle.getTensionFraction() > 0.25f);
		if (!active) return false;
		LivingEntity target = spindle.getTarget();
		if (target == null) return false;

		light = findLight(target);
		return light != null;
	}

	@Nullable
	private BlockPos findLight(LivingEntity target) {
		World world = spindle.getWorld();
		Vec3d core = spindle.getCorePos();
		Vec3d look = target.getRotationVec(1.0f);
		BlockPos best = null;
		double bestScore = -1e9;
		BlockPos[] centers = {target.getBlockPos(), spindle.getBlockPos()};
		for (BlockPos center : centers) {
			for (BlockPos p : BlockPos.iterate(center.add(-8, -4, -8), center.add(8, 5, 8))) {
				BlockState st = world.getBlockState(p);
				if (!SpindleEntity.isSnuffable(st)) continue;
				Vec3d c = Vec3d.ofCenter(p);
				if (c.distanceTo(core) > TendrilController.MAX_LENGTH - 1) continue;
				Vec3d toLight = c.subtract(target.getEyePos()).normalize();
				double score = -look.dotProduct(toLight) * 5; // behind them is better
				score -= c.distanceTo(core) * 0.2;
				if (score > bestScore) {
					bestScore = score;
					best = p.toImmutable();
				}
			}
		}
		return best;
	}

	@Override
	public void start() {
		spindle.getNavigation().stop();
		if (light == null || !spindle.tendril.beginTouch(light)) {
			light = null;
		}
		cooldown = 60 + spindle.getRandom().nextInt(100);
	}

	@Override
	public boolean shouldContinue() {
		return light != null && !spindle.tendril.isIdle() && !spindle.isFrozen();
	}

	@Override
	public void tick() {
		spindle.getNavigation().stop();
		if (light != null) spindle.getLookControl().lookAt(light.getX() + 0.5, light.getY() + 0.5, light.getZ() + 0.5, 20f, 20f);
	}

	@Override
	public void stop() {
		light = null;
	}
}
