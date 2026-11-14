package net.spindle.entity.ai;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Last resort when it's truly stuck: it folds up and digs its own 1x2 tunnel toward where it wants to go,
 * cracking blocks one at a time (you can see and hear the cracks). Stairs up or down if needed.
 * Won't dig containers, very hard blocks, or into lava/water. Stops as soon as a normal path exists again.
 */
public class SpindleDigGoal extends Goal {
	private static final float MAX_HARDNESS = 20f;
	private final SpindleEntity spindle;
	@Nullable private BlockPos digging;
	private float progress;
	@Nullable private BlockPos stepTarget;
	private final List<BlockPos> toClear = new ArrayList<>();
	private int ticks;
	private int failures;

	public SpindleDigGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		return spindle.getStuckLevel() >= 3 && spindle.getDestination() != null && spindle.canGrief()
				&& !spindle.isFrozen() && !spindle.isHanging();
	}

	@Override
	public boolean shouldContinue() {
		return canStart() && ticks < 900;
	}

	@Override
	public void start() {
		ticks = 0;
		failures = 0;
		digging = null;
		stepTarget = null;
		toClear.clear();
		spindle.getNavigation().stop();
		spindle.requestFold();
	}

	@Override
	public void stop() {
		if (digging != null) spindle.getWorld().setBlockBreakingInfo(spindle.getId(), digging, -1);
		digging = null;
		spindle.clearStuck();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		ticks++;
		spindle.requestFold();
		Vec3d dest = spindle.getDestination();
		if (dest == null) return;
		ServerWorld world = (ServerWorld) spindle.getWorld();

		// Every 2 seconds: is there a normal way now?
		if (ticks % 40 == 0) {
			var path = spindle.getNavigation().findPathTo(dest.x, dest.y, dest.z, 1);
			if (path != null && path.reachesTarget()) {
				spindle.clearStuck();
				spindle.travelTo(dest, 1.0);
				return;
			}
		}

		if (digging != null) {
			spindle.getLookControl().lookAt(Vec3d.ofCenter(digging));
			BlockState st = world.getBlockState(digging);
			if (st.getCollisionShape(world, digging).isEmpty()) {
				world.setBlockBreakingInfo(spindle.getId(), digging, -1);
				digging = null;
				return;
			}
			float hardness = st.getHardness(world, digging);
			progress += 1f / (8f + hardness * 6f);
			world.setBlockBreakingInfo(spindle.getId(), digging, (int) (progress * 10f) - 1);
			if (ticks % 4 == 0) {
				world.playSound(null, digging, st.getSoundGroup().getHitSound(), net.minecraft.sound.SoundCategory.HOSTILE, 0.8f, 0.6f);
			}
			if (progress >= 1f) {
				world.setBlockBreakingInfo(spindle.getId(), digging, -1);
				world.breakBlock(digging, true, spindle);
				digging = null;
			}
			return;
		}

		if (!toClear.isEmpty()) {
			BlockPos next = toClear.remove(0);
			if (blocks(world, next)) {
				digging = next;
				progress = 0;
			}
			return;
		}

		if (stepTarget != null) {
			Vec3d c = Vec3d.ofBottomCenter(stepTarget);
			if (spindle.getPos().squaredDistanceTo(c) < 0.35 * 0.35 || spindle.getBlockPos().equals(stepTarget)) {
				stepTarget = null;
			} else {
				spindle.getMoveControl().moveTo(c.x, c.y, c.z, 0.8);
				if (c.y > spindle.getY() + 0.5 && spindle.isOnGround()) spindle.getJumpControl().setActive();
				return;
			}
		}

		planNextStep(world, dest);
	}

	private void planNextStep(ServerWorld world, Vec3d dest) {
		BlockPos here = spindle.getBlockPos();
		Vec3d d = dest.subtract(spindle.getPos());
		Direction primary = Math.abs(d.x) > Math.abs(d.z)
				? (d.x > 0 ? Direction.EAST : Direction.WEST)
				: (d.z > 0 ? Direction.SOUTH : Direction.NORTH);
		Direction secondary = Math.abs(d.x) > Math.abs(d.z)
				? (d.z > 0 ? Direction.SOUTH : Direction.NORTH)
				: (d.x > 0 ? Direction.EAST : Direction.WEST);

		for (Direction dir : new Direction[]{primary, secondary}) {
			BlockPos front = here.offset(dir);
			List<BlockPos> cells = new ArrayList<>();
			BlockPos target;
			if (d.y > 1.5) {            // stairs up
				target = front.up();
				cells.add(here.up(2));
				cells.add(front.up());
				cells.add(front.up(2));
			} else if (d.y < -1.5) {    // stairs down
				target = front.down();
				cells.add(front);
				cells.add(front.down());
			} else {
				target = front;
				cells.add(front);
				cells.add(front.up());
			}
			if (cells.stream().allMatch(c -> diggable(world, c))) {
				toClear.clear();
				toClear.addAll(cells);
				stepTarget = target;
				return;
			}
		}
		if (++failures > 3) spindle.clearStuck(); // solid wall of obsidian or water: give up digging
	}

	private static boolean blocks(ServerWorld world, BlockPos pos) {
		return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	private static boolean diggable(ServerWorld world, BlockPos pos) {
		BlockState st = world.getBlockState(pos);
		if (st.getCollisionShape(world, pos).isEmpty()) return !nearDangerousFluid(world, pos);
		if (st.hasBlockEntity()) return false;
		float h = st.getHardness(world, pos);
		if (h < 0 || h > MAX_HARDNESS) return false;
		return !nearDangerousFluid(world, pos);
	}

	private static boolean nearDangerousFluid(ServerWorld world, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			FluidState f = world.getFluidState(pos.offset(dir));
			if (f.isIn(FluidTags.LAVA) || f.isIn(FluidTags.WATER)) return true;
		}
		return false;
	}
}
