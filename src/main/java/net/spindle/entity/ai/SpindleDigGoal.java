package net.spindle.entity.ai;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Last resort: it folds flat and digs its own 1x2 tunnel toward where it wants to go, cracking blocks
 * one at a time (you can see and hear it coming through). Stairs up or down if needed.
 * Used when it's hunting someone it can't walk to and can't reach with a tendril (a sealed room),
 * or when it has been properly stuck for a while.
 * Won't dig containers, obsidian-hard blocks, or next to lava/water. Stops as soon as a normal path
 * exists again. Needs the mobGriefing gamerule.
 */
public class SpindleDigGoal extends Goal {
	private static final float MAX_HARDNESS = 20f;
	private final SpindleEntity spindle;
	@Nullable private BlockPos digging;
	private float progress;
	@Nullable private BlockPos stepTarget;
	private int stepTicks;
	private final List<BlockPos> toClear = new ArrayList<>();
	private int ticks;
	private int failures;
	private int restTicks;

	public SpindleDigGoal(SpindleEntity spindle) {
		this.spindle = spindle;
		this.setControls(EnumSet.of(Control.MOVE, Control.LOOK, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		if (restTicks > 0) {
			restTicks--;
			return false;
		}
		return spindle.getDigDestination() != null && spindle.tendril.isIdle();
	}

	@Override
	public boolean shouldContinue() {
		return spindle.getDigDestination() != null && ticks < 1200 && failures <= 3;
	}

	@Override
	public void start() {
		ticks = 0;
		failures = 0;
		digging = null;
		stepTarget = null;
		toClear.clear();
		spindle.getNavigation().stop();
		spindle.forceCrouch(20);
	}

	@Override
	public void stop() {
		if (digging != null) spindle.getWorld().setBlockBreakingInfo(spindle.getId(), digging, -1);
		digging = null;
		spindle.clearStuck();
		if (failures > 3 || ticks >= 1200) {
			// Nothing it can get through here. Don't keep hammering at it.
			spindle.markUnreachable(false);
			restTicks = 200;
		}
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		ticks++;
		spindle.forceCrouch(10);
		Vec3d dest = spindle.getDigDestination();
		if (dest == null) return;
		ServerWorld world = (ServerWorld) spindle.getWorld();

		// Every 2 seconds: is there a normal way now?
		if (ticks % 40 == 0) {
			Path path = spindle.getNavigation().findPathTo(dest.x, dest.y, dest.z, 1);
			if (path != null && path.reachesTarget()) {
				spindle.clearStuck();
				spindle.markUnreachable(false);
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
			float hardness = Math.max(0f, st.getHardness(world, digging));
			progress += 1f / (20f + hardness * 12f);
			world.setBlockBreakingInfo(spindle.getId(), digging, Math.min(9, (int) (progress * 10f)));
			if (ticks % 5 == 0) {
				world.playSound(null, digging, st.getSoundGroup().getHitSound(), SoundCategory.HOSTILE, 0.9f, 0.6f);
			}
			if (progress >= 1f) {
				world.setBlockBreakingInfo(spindle.getId(), digging, -1);
				world.breakBlock(digging, true, spindle);
				spindle.statBlocksDug++;
				digging = null;
			}
			return;
		}

		while (!toClear.isEmpty()) {
			BlockPos next = toClear.remove(0);
			if (blocks(world, next)) {
				digging = next;
				progress = 0;
				return;
			}
		}

		if (stepTarget != null) {
			Vec3d c = Vec3d.ofBottomCenter(stepTarget);
			double dx = spindle.getX() - c.x, dz = spindle.getZ() - c.z;
			if (dx * dx + dz * dz < 0.3 * 0.3 || ++stepTicks > 40) {
				stepTarget = null;
			} else {
				spindle.getMoveControl().moveTo(c.x, c.y, c.z, 0.8);
				return;
			}
		}

		planNextStep(world, dest);
	}

	private void planNextStep(ServerWorld world, Vec3d dest) {
		BlockPos here = spindle.getBlockPos();
		Vec3d d = dest.subtract(spindle.getPos());
		double ax = Math.abs(d.x), az = Math.abs(d.z);
		Direction primary = ax > az ? (d.x > 0 ? Direction.EAST : Direction.WEST) : (d.z > 0 ? Direction.SOUTH : Direction.NORTH);
		Direction secondary = ax > az ? (d.z > 0 ? Direction.SOUTH : Direction.NORTH) : (d.x > 0 ? Direction.EAST : Direction.WEST);
		boolean above = d.y > 1.2, below = d.y < -1.2;
		boolean near = ax < 1.2 && az < 1.2;
		Direction[] order = near
				? new Direction[]{primary, secondary, primary.getOpposite(), secondary.getOpposite()}
				: new Direction[]{primary, secondary};

		for (Direction dir : order) {
			BlockPos front = here.offset(dir);
			List<BlockPos> cells = new ArrayList<>();
			BlockPos target;
			if (above && blocks(world, front)) {
				// Step up onto the block in front.
				target = front.up();
				cells.add(here.up(2));
				cells.add(front.up());
				cells.add(front.up(2));
			} else if (below) {
				target = front.down();
				cells.add(front.up());
				cells.add(front);
				cells.add(front.down());
			} else if (above && near) {
				continue; // need something to climb on; try another side
			} else {
				target = front;
				cells.add(front);
				cells.add(front.up());
			}
			boolean ok = true;
			for (BlockPos c : cells) {
				if (!diggable(world, c)) {
					ok = false;
					break;
				}
			}
			if (ok) {
				toClear.clear();
				toClear.addAll(cells);
				stepTarget = target;
				stepTicks = 0;
				failures = 0;
				return;
			}
		}
		failures++;
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
