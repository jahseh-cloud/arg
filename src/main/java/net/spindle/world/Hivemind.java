package net.spindle.world;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.spindle.Spindle;
import net.spindle.SpindleBlocks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Hivemind: one per world, 200-500 blocks into the Far Lands. A huge lumpy blob of hivemass
 * honeycombed with chambers joined by tunnels, a few ways in from outside, and one vast chamber at
 * its heart where the Broodmother is. Its whole layout comes from the world seed, so it generates
 * the same way chunk by chunk and the server always knows where everything is.
 */
public final class Hivemind {
	public record Sphere(Vec3d c, double r) {}

	public record Tunnel(Vec3d a, Vec3d b, double r) {}

	private static final Map<Long, Hivemind> CACHE = new ConcurrentHashMap<>();

	public final BlockPos center;
	public final Sphere throne;
	public final List<Sphere> lobes = new ArrayList<>();
	public final List<Sphere> chambers = new ArrayList<>();
	public final List<Tunnel> tunnels = new ArrayList<>();
	public final List<Vec3d> entrances = new ArrayList<>();
	public final BlockBox bounds;
	private final SimplexNoiseSampler skin;
	private final SimplexNoiseSampler wobble;

	private Hivemind(long seed) {
		java.util.Random r = new java.util.Random(seed);
		int side = r.nextInt(4);
		int along = r.nextInt(1201) - 600;
		int depth = 200 + r.nextInt(301);
		int far = FarLands.EDGE + depth;
		int y = 104;
		center = switch (side) {
			case 0 -> new BlockPos(far, y, along);
			case 1 -> new BlockPos(-far, y, along);
			case 2 -> new BlockPos(along, y, far);
			default -> new BlockPos(along, y, -far);
		};
		Vec3d c = Vec3d.ofCenter(center);
		Random noiseRandom = Random.create(seed * 31 + 7);
		skin = new SimplexNoiseSampler(noiseRandom);
		wobble = new SimplexNoiseSampler(noiseRandom);

		// Body: a big central lobe and several bulges.
		lobes.add(new Sphere(c, 64));
		for (int i = 0; i < 7; i++) {
			Vec3d dir = randomDir(r, 0.45);
			double dist = 32 + r.nextDouble() * 22;
			lobes.add(new Sphere(c.add(dir.multiply(dist)), 30 + r.nextDouble() * 16));
		}

		// The heart.
		throne = new Sphere(c, 30);

		// Chambers, scattered through the body, not overlapping each other or the heart.
		int tries = 0;
		while (chambers.size() < 18 && tries++ < 600) {
			Vec3d dir = randomDir(r, 0.35);
			double dist = 38 + r.nextDouble() * 38;
			double rad = 7 + r.nextDouble() * 6;
			Vec3d p = c.add(dir.multiply(dist));
			if (outerDistance(p) > -rad - 6) continue;
			boolean ok = true;
			for (Sphere s : chambers) if (s.c.distanceTo(p) < s.r + rad + 5) ok = false;
			if (throne.c.distanceTo(p) < throne.r + rad + 6) ok = false;
			if (ok) chambers.add(new Sphere(p, rad));
		}

		// Tunnels: each chamber joins the nearest thing already connected to the heart.
		List<Sphere> connected = new ArrayList<>();
		connected.add(throne);
		List<Sphere> remaining = new ArrayList<>(chambers);
		remaining.sort((a, b) -> Double.compare(a.c.distanceTo(c), b.c.distanceTo(c)));
		for (Sphere ch : remaining) {
			Sphere best = throne;
			double bd = Double.MAX_VALUE;
			for (Sphere s : connected) {
				double d = s.c.distanceTo(ch.c) - s.r;
				if (d < bd) {
					bd = d;
					best = s;
				}
			}
			tunnels.add(new Tunnel(ch.c, best.c, 2.6 + r.nextDouble() * 1.0));
			connected.add(ch);
		}
		// A few loops so it isn't a pure tree.
		for (int i = 0; i < 4 && chambers.size() > 3; i++) {
			Sphere a = chambers.get(r.nextInt(chambers.size()));
			Sphere b = chambers.get(r.nextInt(chambers.size()));
			if (a != b && a.c.distanceTo(b.c) < 45) tunnels.add(new Tunnel(a.c, b.c, 2.4));
		}

		// Ways in: one facing the world's center, and two more, each from a chamber out past the skin.
		List<Vec3d> outward = new ArrayList<>();
		outward.add(new Vec3d(-c.x, 0, -c.z).normalize());
		outward.add(randomDir(r, 0.0));
		outward.add(randomDir(r, 0.0));
		for (Vec3d dir : outward) {
			Sphere from = throne;
			double best = -Double.MAX_VALUE;
			for (Sphere s : chambers) {
				double score = s.c.subtract(c).normalize().dotProduct(dir);
				if (score > best) {
					best = score;
					from = s;
				}
			}
			Vec3d out = new Vec3d(from.c.x, from.c.y - from.r * 0.4, from.c.z).add(dir.multiply(130));
			out = new Vec3d(out.x, from.c.y - from.r * 0.4, out.z);
			tunnels.add(new Tunnel(new Vec3d(from.c.x, from.c.y - from.r * 0.4, from.c.z), out, 3.6));
			entrances.add(c.add(dir.multiply(110)));
		}

		int reach = 125;
		bounds = new BlockBox(center.getX() - reach, Math.max(FarLands.MIN_Y, center.getY() - 100), center.getZ() - reach,
				center.getX() + reach, Math.min(FarLands.MAX_Y + 40, center.getY() + 100), center.getZ() + reach);
	}

	private static Vec3d randomDir(java.util.Random r, double flatten) {
		double a = r.nextDouble() * Math.PI * 2;
		double y = (r.nextDouble() * 2 - 1) * (1 - flatten);
		double h = Math.sqrt(Math.max(0, 1 - y * y));
		return new Vec3d(Math.cos(a) * h, y, Math.sin(a) * h);
	}

	public static Hivemind of(long seed) {
		return CACHE.computeIfAbsent(seed, Hivemind::new);
	}

	public static long seedFrom(NoiseConfig config) {
		return config.getOrCreateRandomDeriver(Spindle.id("hivemind")).split(0, 0, 0).nextLong();
	}

	@Nullable
	public static Hivemind of(ServerWorld world) {
		if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return null;
		if (!(world.getChunkManager().getChunkGenerator() instanceof net.minecraft.world.gen.chunk.NoiseChunkGenerator ncg)
				|| !ncg.getSettings().value().defaultBlock().isOf(net.minecraft.block.Blocks.STONE)) return null;
		return of(seedFrom(world.getChunkManager().getNoiseConfig()));
	}

	/** Signed distance to the outside of the blob (negative inside). */
	public double outerDistance(Vec3d p) {
		double d = Double.MAX_VALUE;
		for (Sphere s : lobes) d = Math.min(d, p.distanceTo(s.c) - s.r);
		return d + 7.0 * skin.sample(p.x / 24.0, p.y / 24.0, p.z / 24.0) + 2.0 * skin.sample(p.x / 6.0, p.y / 6.0, p.z / 6.0);
	}

	/** Is this point hollow (a chamber, the heart or a tunnel)? */
	public boolean isCarved(Vec3d p) {
		if (inChamber(p, throne, 0.45)) return true;
		for (Sphere s : chambers) if (inChamber(p, s, 0.55)) return true;
		double w = 0.7 * wobble.sample(p.x / 9.0, p.y / 9.0, p.z / 9.0);
		for (Tunnel t : tunnels) {
			if (distToSegment(p, t.a, t.b) < t.r + w) return true;
		}
		return false;
	}

	/** A sphere with a flattened floor. */
	private boolean inChamber(Vec3d p, Sphere s, double floor) {
		if (p.y < s.c.y - s.r * floor) return false;
		double n = 1.5 * wobble.sample(p.x / 7.0, p.y / 7.0, p.z / 7.0);
		return p.distanceTo(s.c) < s.r + n;
	}

	private static double distToSegment(Vec3d p, Vec3d a, Vec3d b) {
		Vec3d ab = b.subtract(a);
		double t = ab.lengthSquared() < 1e-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dotProduct(ab) / ab.lengthSquared()));
		return p.distanceTo(a.add(ab.multiply(t)));
	}

	/** What the Hivemind puts at this block: hivemass, air, or null for "leave the terrain alone". */
	@Nullable
	public BlockState blockAt(int x, int y, int z) {
		if (!bounds.contains(x, y, z)) return null;
		Vec3d p = new Vec3d(x + 0.5, y + 0.5, z + 0.5);
		if (isCarved(p)) return net.minecraft.block.Blocks.AIR.getDefaultState();
		if (outerDistance(p) < 0) return SpindleBlocks.HIVEMASS.getDefaultState();
		return null;
	}

	public boolean touches(ChunkPos cp) {
		return cp.getEndX() >= bounds.getMinX() && cp.getStartX() <= bounds.getMaxX()
				&& cp.getEndZ() >= bounds.getMinZ() && cp.getStartZ() <= bounds.getMaxZ();
	}

	public void apply(Chunk chunk) {
		ChunkPos cp = chunk.getPos();
		if (!touches(cp)) return;
		BlockPos.Mutable m = new BlockPos.Mutable();
		int y0 = Math.max(bounds.getMinY(), chunk.getBottomY()), y1 = Math.min(bounds.getMaxY(), chunk.getTopY() - 1);
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = cp.getStartX() + lx, z = cp.getStartZ() + lz;
				if (x < bounds.getMinX() || x > bounds.getMaxX() || z < bounds.getMinZ() || z > bounds.getMaxZ()) continue;
				for (int y = y0; y <= y1; y++) {
					BlockState s = blockAt(x, y, z);
					if (s != null) chunk.setBlockState(m.set(x, y, z), s, false);
				}
			}
		}
	}

	/** Is this position inside the Hivemind (in its body or its hollows)? */
	public boolean isInside(Vec3d p) {
		return bounds.contains(BlockPos.ofFloored(p)) && outerDistance(p) < 2;
	}
}
