package net.spindle.entity;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A walking-distance map of the space around a point, at the Spindle's folded size (1 wide, 2 tall).
 * Built by breadth-first search over standable cells, so "distance" means steps along tunnels and
 * corridors, not straight-line distance through rock and walls.
 */
public final class SpaceMap {
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final int[] STEP_DY = {0, 1, -1, -2, -3};

	public final BlockPos origin;
	public final int builtAt;
	private final Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
	public final List<BlockPos> cells = new ArrayList<>();

	private SpaceMap(BlockPos origin, int builtAt) {
		this.origin = origin;
		this.builtAt = builtAt;
		dist.defaultReturnValue(-1);
	}

	@Nullable
	public static SpaceMap build(World world, BlockPos near, int radius, int maxCells, int age) {
		BlockPos start = snap(world, near);
		if (start == null) return null;
		SpaceMap map = new SpaceMap(start, age);
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		map.dist.put(start.asLong(), 0);
		int r2 = radius * radius;
		while (!queue.isEmpty() && map.cells.size() < maxCells) {
			BlockPos cur = queue.poll();
			map.cells.add(cur);
			int d = map.dist.get(cur.asLong());
			for (Direction dir : HORIZONTAL) {
				BlockPos side = cur.offset(dir);
				for (int dy : STEP_DY) {
					BlockPos n = side.up(dy);
					if (dy == 1 && !isOpen(world, cur.up(2))) continue; // headroom to step up
					if (!standable(world, n)) continue;
					if (map.dist.containsKey(n.asLong())) break;
					if (n.getSquaredDistance(start) > r2) break;
					map.dist.put(n.asLong(), d + 1 + Math.max(0, -dy - 1));
					queue.add(n);
					break;
				}
			}
		}
		return map;
	}

	public int distance(BlockPos p) {
		return dist.get(p.asLong());
	}

	public boolean contains(BlockPos p) {
		return dist.containsKey(p.asLong());
	}

	public BlockPos random(Random random) {
		return cells.get(random.nextInt(cells.size()));
	}

	@Nullable
	public BlockPos nearest(Vec3d p) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (BlockPos c : cells) {
			double d = Vec3d.ofBottomCenter(c).squaredDistanceTo(p);
			if (d < bd) {
				bd = d;
				best = c;
			}
		}
		return best;
	}

	/** How many of the 8 surrounding columns are open floor. Low = alcove / corridor / corner. */
	public int openness(BlockPos p) {
		int n = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				for (int dy = -1; dy <= 1; dy++) {
					if (contains(p.add(dx, dy, dz))) {
						n++;
						break;
					}
				}
			}
		}
		return n;
	}

	/** Blocks of clear space above the floor cell before a solid ceiling, or -1 if none within 8. */
	public static int ceilingHeight(World world, BlockPos floorCell) {
		for (int h = 2; h <= 8; h++) {
			BlockPos p = floorCell.up(h);
			VoxelShape shape = world.getBlockState(p).getCollisionShape(world, p);
			if (!shape.isEmpty()) {
				return shape.getMin(Direction.Axis.Y) < 0.01 ? h : -1; // needs a flat underside
			}
		}
		return -1;
	}

	@Nullable
	public static BlockPos snap(World world, BlockPos p) {
		for (int dy : new int[]{0, -1, 1, -2, 2, -3}) {
			BlockPos q = p.up(dy);
			if (standable(world, q)) return q.toImmutable();
		}
		return null;
	}

	public static boolean standable(World world, BlockPos p) {
		if (!isOpen(world, p) || !isOpen(world, p.up())) return false;
		if (world.getFluidState(p).isIn(FluidTags.LAVA)) return false;
		BlockPos below = p.down();
		BlockState st = world.getBlockState(below);
		VoxelShape shape = st.getCollisionShape(world, below);
		return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.5;
	}

	private static boolean isOpen(World world, BlockPos p) {
		BlockState st = world.getBlockState(p);
		if (st.getBlock() instanceof net.minecraft.block.DoorBlock && net.minecraft.block.DoorBlock.canOpenByHand(st)) return true;
		return st.getCollisionShape(world, p).isEmpty();
	}
}
