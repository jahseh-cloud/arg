package net.spindle.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.Chunk;
import net.spindle.SpindleBlocks;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Far Lands, pulled in to 1024 blocks from the world's center by the Spindle.
 *
 * <p>Built the way the Beta Far Lands looked: the terrain noise "overflows", so along the axis
 * pointing away from the world the terrain stops changing and stretches into long straight tunnels
 * and ledges, while across it and up it the values jump around between lattice points, giving the
 * flat striped wall. Where both axes overflow (the corners) it becomes pillars.
 *
 * <p>It gets stranger the further in you go: the tunnels get shorter and more chaotic, the stripes
 * shear diagonally, chunks of terrain lose their footing and float, whole sections hang upside down
 * from the sky, and the Spindle's hivemass starts showing up in the stone.
 *
 * <p>Every so often there's an oasis: a valley where the world forgot to break, flat plains with a
 * village in it (the village is placed when someone first gets close, see {@link HiveEvents}).
 */
public final class FarLands {
	public static final int EDGE = 1024;
	public static final int MIN_Y = 0;
	public static final int MAX_Y = 255;
	/** How far in it takes to get fully strange. */
	public static final double STRANGE_DEPTH = 700;

	/** Plains, for oases. Set when the server starts. */
	@Nullable
	public static volatile RegistryEntry<Biome> plains;

	private static final Map<Long, FarLands> CACHE = new ConcurrentHashMap<>();

	private final long seed;
	private final SimplexNoiseSampler region;
	private final SimplexNoiseSampler hills;
	private final Map<Long, Oasis> oases = new ConcurrentHashMap<>();
	@Nullable
	private volatile BlockPos hiveCenter;
	private volatile boolean hiveLooked;

	public record Oasis(int x, int z, int radius, int height) {
		public static final int RING = 40;

		public double dist(int px, int pz) {
			return Math.sqrt((double) (px - x) * (px - x) + (double) (pz - z) * (pz - z));
		}
	}

	private FarLands(long seed) {
		this.seed = seed;
		Random r = Random.create(seed ^ 0x5F4A11D5L);
		region = new SimplexNoiseSampler(r);
		hills = new SimplexNoiseSampler(r);
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

	/** 0 at the edge, 1 when it's as strange as it gets. */
	public static double strangeness(int x, int z) {
		int past = Math.max(Math.abs(x), Math.abs(z)) - EDGE;
		return MathHelper.clamp(past / STRANGE_DEPTH, 0, 1);
	}

	// ---------------------------------------------------------------- lattice noise

	private double lattice(long i, long j, long k, long salt) {
		long h = seed * 0x9E3779B97F4A7C15L + salt;
		h ^= i * 0xC2B2AE3D27D4EB4FL;
		h = Long.rotateLeft(h, 29) * 0x165667B19E3779F9L;
		h ^= j * 0x27D4EB2F165667C5L;
		h = Long.rotateLeft(h, 31) * 0x9E3779B97F4A7C15L;
		h ^= k * 0x94D049BB133111EBL;
		h ^= h >>> 32;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
	}

	/** Random values on a grid, blended linearly in between: the blocky look of the Beta noise breaking. */
	private double cell(double a, double b, double c, long salt) {
		long i = MathHelper.floor(a), j = MathHelper.floor(b), k = MathHelper.floor(c);
		double fa = a - i, fb = b - j, fc = c - k;
		double v000 = lattice(i, j, k, salt), v100 = lattice(i + 1, j, k, salt);
		double v010 = lattice(i, j + 1, k, salt), v110 = lattice(i + 1, j + 1, k, salt);
		double v001 = lattice(i, j, k + 1, salt), v101 = lattice(i + 1, j, k + 1, salt);
		double v011 = lattice(i, j + 1, k + 1, salt), v111 = lattice(i + 1, j + 1, k + 1, salt);
		return MathHelper.lerp3(fa, fb, fc, v000, v100, v010, v110, v001, v101, v011, v111);
	}

	// ---------------------------------------------------------------- terrain

	/** Positive = solid. */
	public double density(int x, int y, int z) {
		int px = Math.abs(x) - EDGE, pz = Math.abs(z) - EDGE;
		int past = Math.max(px, pz);
		double s = MathHelper.clamp(past / STRANGE_DEPTH, 0, 1);
		double wobble = region.sample(x / 160.0, z / 160.0); // -1..1, slow

		// Upside-down sections deep in: terrain hanging from the sky.
		double yy = y;
		if (s > 0.55 && wobble > 0.55 - (s - 0.55) * 0.6) yy = 250 - y;

		double d;
		if (px > 0 && pz > 0) {
			// Corner Far Lands: both axes overflow and everything turns into striped pillars.
			double stretch = MathHelper.lerp(s, 260.0, 50.0);
			d = cell(x / 6.0, z / 6.0, yy / stretch, 11) + 0.5 * cell(x / 3.0, z / 3.0, yy / 5.0, 12);
		} else {
			// Edge Far Lands: along u (away from the world) the terrain hardly changes, so it runs in
			// long straight tunnels and ledges; across the face it's wide flat strata.
			int u = px > pz ? px : pz;
			int v = px > pz ? z : x;
			double tunnel = MathHelper.lerp(s, 600.0, 60.0);
			double shear = s < 0.3 ? 0 : (s - 0.3) * 1.4 * Math.signum(wobble + 0.01);
			// Deep in, the world tears: blocky columns slipped up and down against each other.
			if (s > 0.45) yy += lattice(Math.floorDiv(v, 8), 7, Math.floorDiv(u, 16), 24) * 60.0 * (s - 0.45);
			double vv = v + yy * shear;
			double chaos = MathHelper.lerp(s * s, 1.0, 0.45); // strata get shorter and choppier
			double ys = 9.0 - 4.0 * s; // strata get thinner deeper in
			d = cell(vv / (32.0 * chaos), yy / ys, u / tunnel, 21)
					+ 0.5 * cell(vv / (16.0 * chaos), yy / (ys * 0.6), u / tunnel, 22)
					+ 0.25 * cell(vv / 6.0, yy / 2.5, u / (tunnel * 0.5), 23);
		}

		// The ground hardly matters: the strata go all the way up. Deeper in, even less.
		double grip = MathHelper.lerp(s, 1.0, 0.3);
		d += (60.0 - yy) / 400.0 * grip;
		d -= Math.max(0, y - 238) / 10.0; // thins out just under the top of the world
		return d + 0.16;
	}

	/** What a solid Far Lands block is made of. */
	private BlockState material(int x, int y, int z, double s, BlockState stone) {
		if (s < 0.35) return stone;
		// Whole lattice cells go wrong: the Spindle's matter, growing in the stone.
		double c = lattice(Math.floorDiv(x, 4), Math.floorDiv(y, 8), Math.floorDiv(z, 4), 31) * 0.5 + 0.5;
		double hive = (s - 0.35) * 0.09;
		BlockPos hc = hiveCenter();
		if (hc != null) {
			double dh = Math.sqrt(hc.getSquaredDistance(x, hc.getY(), z));
			if (dh < 400) hive += (1 - dh / 400) * 0.12; // thickens close to the Hivemind
		}
		if (c < hive) return SpindleBlocks.HIVEMASS.getDefaultState();
		return stone;
	}

	@Nullable
	private BlockPos hiveCenter() {
		if (!hiveLooked) {
			try {
				hiveCenter = Hivemind.of(seed).center;
			} catch (RuntimeException e) {
				hiveCenter = null;
			}
			hiveLooked = true;
		}
		return hiveCenter;
	}

	// ---------------------------------------------------------------- oases

	private static final int OASIS_CELL = 384;

	@Nullable
	public Oasis oasisInCell(int cx, int cz) {
		long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
		Oasis o = oases.get(key);
		if (o != null) return o.radius() > 0 ? o : null;
		double roll = lattice(cx, 0, cz, 41) * 0.5 + 0.5;
		int x = cx * OASIS_CELL + 96 + (int) ((lattice(cx, 1, cz, 42) * 0.5 + 0.5) * 192);
		int z = cz * OASIS_CELL + 96 + (int) ((lattice(cx, 2, cz, 43) * 0.5 + 0.5) * 192);
		int radius = 45 + (int) ((lattice(cx, 3, cz, 44) * 0.5 + 0.5) * 30);
		int past = Math.max(Math.abs(x), Math.abs(z)) - EDGE;
		boolean ok = roll < 0.3 && past > radius + Oasis.RING + 60;
		BlockPos hc = hiveCenter();
		if (ok && hc != null && Math.hypot(hc.getX() - x, hc.getZ() - z) < 320) ok = false;
		o = ok ? new Oasis(x, z, radius, 0) : new Oasis(x, z, 0, 0);
		oases.put(key, o);
		return ok ? o : null;
	}

	/** The oasis this column is in or near (including its ring), if any. */
	@Nullable
	public Oasis oasisNear(int x, int z) {
		int cx = Math.floorDiv(x, OASIS_CELL), cz = Math.floorDiv(z, OASIS_CELL);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				Oasis o = oasisInCell(cx + dx, cz + dz);
				if (o != null && o.dist(x, z) < o.radius() + Oasis.RING) return o;
			}
		}
		return null;
	}

	/** The first oasis found going out along +x from the edge, for tests and screenshots. */
	@Nullable
	public Oasis firstOasisEast() {
		for (int cx = Math.floorDiv(EDGE, OASIS_CELL); cx < 40; cx++) {
			for (int cz = -2; cz <= 1; cz++) {
				Oasis o = oasisInCell(cx, cz);
				if (o != null && Math.abs(o.z()) < o.x() - EDGE) return o;
			}
		}
		return null;
	}

	/** Height of the gentle ground in an oasis. */
	public int oasisGround(int x, int z, int seaLevel) {
		return seaLevel + 4 + (int) Math.round(2.5 * hills.sample(x / 48.0, z / 48.0));
	}

	// ---------------------------------------------------------------- writing chunks

	public void apply(Chunk chunk, int seaLevel, BlockState stone, @Nullable MultiNoiseUtil.MultiNoiseSampler sampler) {
		ChunkPos cp = chunk.getPos();
		BlockPos.Mutable m = new BlockPos.Mutable();
		BlockState air = Blocks.AIR.getDefaultState();
		BlockState water = Blocks.WATER.getDefaultState();
		int top = Math.min(MAX_Y, chunk.getTopY() - 1);
		int bottom = Math.max(MIN_Y, chunk.getBottomY());
		boolean anyOasis = false;
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = cp.getStartX() + lx, z = cp.getStartZ() + lz;
				if (!isFarLands(x, z)) continue;
				double s = strangeness(x, z);
				Oasis o = oasisNear(x, z);
				double od = o == null ? 1e9 : o.dist(x, z);
				int ground = o == null ? 0 : oasisGround(x, z, seaLevel);
				if (o != null) anyOasis = true;
				for (int y = bottom; y <= top; y++) {
					BlockState state;
					if (o != null && od < o.radius()) {
						state = y <= ground ? stone : air; // the world as it should be
					} else {
						double d = density(x, y, z);
						if (o != null) {
							// The ring around an oasis: the walls slope down into the valley.
							double t = 1 - (od - o.radius()) / Oasis.RING;
							if (y <= ground) d = Math.max(d, 0.1);
							else d -= t * t * 2.5;
						}
						state = d > 0 ? material(x, y, z, s, stone) : (y < seaLevel ? water : air);
					}
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
		RegistryEntry<Biome> p = plains;
		if (anyOasis && p != null && sampler != null) {
			// Oases are plains, whatever the climate around them says.
			chunk.populateBiomes((qx, qy, qz, noise) -> {
				int x = (qx << 2) + 2, z = (qz << 2) + 2;
				Oasis o = isFarLands(x, z) ? oasisNear(x, z) : null;
				if (o != null && o.dist(x, z) < o.radius() + 6) return p;
				return chunk.getBiomeForNoiseGen(qx, qy, qz);
			}, sampler);
		}
	}
}
