package net.spindle.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.Chunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Far Lands, pulled in to 1024 blocks from the world's center by the Spindle.
 *
 * Past |x| or |z| = 1024 the overworld's terrain is replaced with what the real Far Lands look like:
 * terrain stretched into sheer walls that reach for the sky, cut through by long horizontal strata
 * and tunnels, flooded below sea level. It starts with a cliff at the boundary and never ends.
 * Applied right after the base terrain of each chunk is generated, so grass, caves, ores and trees
 * are still added by vanilla on top.
 */
public final class FarLands {
	public static final int EDGE = 1024;
	public static final int MIN_Y = 0;
	public static final int MAX_Y = 255;

	private static final Map<Long, FarLands> CACHE = new ConcurrentHashMap<>();

	private final SimplexNoiseSampler walls;
	private final SimplexNoiseSampler strata;
	private final SimplexNoiseSampler detail;

	private FarLands(long seed) {
		Random r = Random.create(seed ^ 0x5F4A11D5L);
		walls = new SimplexNoiseSampler(r);
		strata = new SimplexNoiseSampler(r);
		detail = new SimplexNoiseSampler(r);
	}

	public static FarLands of(long seed) {
		return CACHE.computeIfAbsent(seed, FarLands::new);
	}

	public static boolean isFarLands(int x, int z) {
		return Math.max(Math.abs(x), Math.abs(z)) > EDGE;
	}

	/** True if any part of the chunk is in the Far Lands. */
	public static boolean touches(ChunkPos pos) {
		int maxAbsX = Math.max(Math.abs(pos.getStartX()), Math.abs(pos.getEndX()));
		int maxAbsZ = Math.max(Math.abs(pos.getStartZ()), Math.abs(pos.getEndZ()));
		return Math.max(maxAbsX, maxAbsZ) > EDGE;
	}

	/** Positive = solid. */
	public double density(int x, int y, int z) {
		double region = strata.sample(x / 80.0, z / 80.0);
		double d = walls.sample(x / 22.0, y / 150.0, z / 22.0)
				+ 0.45 * MathHelper.sin((float) (y * 0.42 + region * 5.0))
				+ 0.18 * detail.sample(x / 7.0, y / 9.0, z / 7.0)
				+ (72.0 - y) / 135.0;
		// The boundary is a cliff.
		int past = Math.max(Math.abs(x), Math.abs(z)) - EDGE;
		if (past < 24) d += (24 - past) / 24.0 * 0.85;
		return d - 0.12;
	}

	public void apply(Chunk chunk, int seaLevel, BlockState stone) {
		ChunkPos cp = chunk.getPos();
		BlockPos.Mutable m = new BlockPos.Mutable();
		BlockState air = Blocks.AIR.getDefaultState();
		BlockState water = Blocks.WATER.getDefaultState();
		int top = Math.min(MAX_Y, chunk.getTopY() - 1);
		int bottom = Math.max(MIN_Y, chunk.getBottomY());
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = cp.getStartX() + lx, z = cp.getStartZ() + lz;
				if (!isFarLands(x, z)) continue;
				for (int y = bottom; y <= top; y++) {
					BlockState state = density(x, y, z) > 0 ? stone : (y < seaLevel ? water : air);
					m.set(x, y, z);
					if (chunk.getBlockState(m) != state) chunk.setBlockState(m, state, false);
				}
				// Clear anything vanilla put above our ceiling in this column.
				for (int y = top + 1; y < chunk.getTopY(); y++) {
					m.set(x, y, z);
					if (!chunk.getBlockState(m).isAir()) chunk.setBlockState(m, air, false);
				}
			}
		}
	}
}
