package net.spindle.entity;

import net.minecraft.block.BlockState;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

/**
 * Client-side procedural walking. Each foot is planted at a real spot in the world and stays there
 * until the body has moved far enough away from it; then that leg lifts and steps to a new spot on
 * the actual ground (slopes, stairs, cave floors). Neighbouring legs never step at the same time.
 * The body rides at the average height of its feet and tilts with the terrain.
 */
public class SpindleLegState {
	public static final int LEGS = 6;
	public static final float[] LEG_ANGLES = {
			(float) Math.toRadians(38), (float) Math.toRadians(88), (float) Math.toRadians(138)
	};

	public final Vec3d[] foot = new Vec3d[LEGS];
	public final Vec3d[] prevFoot = new Vec3d[LEGS];
	private final Vec3d[] from = new Vec3d[LEGS];
	private final Vec3d[] to = new Vec3d[LEGS];
	private final float[] progress = new float[LEGS];
	private final boolean[] stepping = new boolean[LEGS];
	private float stepDuration = 8f;
	private float stepLift = 1f;

	public float groundOffset, prevGroundOffset;
	public float pitch, prevPitch;
	public float roll, prevRoll;
	private boolean initialized;
	private float lastPlantFold;
	private static final float[] HIP_Z = {-9f, 0f, 9f};
	private static final float LEG_REACH_PX = 84f + 120f;

	public boolean isReady() {
		return initialized;
	}

	/** Resting foot position in model space (pixels; +Y down, ground at 24, front is -Z). */
	public static float restModelX(int leg, float radius) {
		float s = leg < 3 ? 1f : -1f;
		return s * radius * MathHelper.sin(LEG_ANGLES[leg % 3]);
	}

	public static float restModelZ(int leg, float radius) {
		return -radius * MathHelper.cos(LEG_ANGLES[leg % 3]);
	}

	public static float restRadius(float fold) {
		return MathHelper.lerp(fold, 90f, 14f);
	}

	/** Converts a model-space point (pixels) to a world offset from the entity's feet, for a given body yaw. */
	public static Vec3d modelToWorld(double xm, double ym, double zm, float bodyYawDeg) {
		double lx = -xm / 16.0, ly = 1.501 - ym / 16.0, lz = zm / 16.0;
		double t = Math.toRadians(180.0 - bodyYawDeg);
		double c = Math.cos(t), s = Math.sin(t);
		return new Vec3d(lx * c + lz * s, ly, -lx * s + lz * c);
	}

	/** Inverse of {@link #modelToWorld}: world offset from the entity's feet -> model-space pixels. */
	public static Vec3d worldToModel(Vec3d d, float bodyYawDeg) {
		double t = Math.toRadians(180.0 - bodyYawDeg);
		double c = Math.cos(t), s = Math.sin(t);
		double lx = d.x * c - d.z * s;
		double lz = d.x * s + d.z * c;
		return new Vec3d(-lx * 16.0, (1.501 - d.y) * 16.0, lz * 16.0);
	}

	public void tick(SpindleEntity e) {
		World world = e.getWorld();
		Vec3d pos = e.getPos();
		Vec3d vel = pos.subtract(e.prevX, e.prevY, e.prevZ);
		float fold = e.foldProgress;
		float radius = restRadius(fold);
		boolean hunting = e.getSyncedMode() == SpindleEntity.Mode.HUNT;

		stepDuration = fold > 0.5f ? 4f : (hunting ? 5f : 8f);
		stepLift = MathHelper.lerp(fold, 1.1f, 0.3f);
		double stepDist = MathHelper.lerp(fold, 1.5, 0.45);
		Vec3d lead = new Vec3d(vel.x, 0, vel.z).multiply(stepDuration * 1.3);

		boolean folded = fold > 0.5f;
		float legScale = MathHelper.lerp(fold, 1.0f, 0.3f);
		double reach = LEG_REACH_PX * legScale / 16.0 * 0.96;
		Vec3d[] hip = new Vec3d[LEGS];
		Vec3d[] home = new Vec3d[LEGS];
		for (int i = 0; i < LEGS; i++) {
			float s = i < 3 ? 1f : -1f;
			float hipYm = MathHelper.lerp(fold, -60f, 6f) + 3f - groundOffset * 16f;
			hip[i] = pos.add(modelToWorld(s * 8f, hipYm, HIP_Z[i % 3], e.bodyYaw));
			// Pick the resting spot; if the ground there is out of reach (cliff, deep hole), pull the foot inward.
			Vec3d chosen = null;
			for (float pull : new float[]{1.0f, 0.8f, 0.6f, 0.45f}) {
				Vec3d off = modelToWorld(restModelX(i, radius * pull), 24, restModelZ(i, radius * pull), e.bodyYaw);
				double hx = pos.x + off.x + lead.x;
				double hz = pos.z + off.z + lead.z;
				Vec3d cand = new Vec3d(hx, groundAt(world, hx, hz, pos.y, folded), hz);
				if (chosen == null) chosen = cand;
				if (cand.distanceTo(hip[i]) <= reach) {
					chosen = cand;
					break;
				}
			}
			home[i] = chosen;
		}
		boolean replantAll = Math.abs(fold - lastPlantFold) > 0.25f;
		if (replantAll) lastPlantFold = fold;

		boolean teleported = false;
		if (initialized) {
			for (int i = 0; i < LEGS; i++) {
				if (foot[i].squaredDistanceTo(home[i]) > 10 * 10) teleported = true;
			}
		}
		if (!initialized || teleported) {
			for (int i = 0; i < LEGS; i++) {
				foot[i] = home[i];
				prevFoot[i] = home[i];
				stepping[i] = false;
			}
			initialized = true;
		}

		for (int i = 0; i < LEGS; i++) prevFoot[i] = foot[i];
		prevGroundOffset = groundOffset;
		prevPitch = pitch;
		prevRoll = roll;

		// Advance legs that are mid-step.
		for (int i = 0; i < LEGS; i++) {
			if (!stepping[i]) continue;
			progress[i] += 1f / stepDuration;
			float t = Math.min(1f, progress[i]);
			float eased = t * t * (3f - 2f * t);
			Vec3d p = from[i].lerp(to[i], eased);
			foot[i] = p.add(0, MathHelper.sin(t * (float) Math.PI) * stepLift, 0);
			if (t >= 1f) {
				stepping[i] = false;
				foot[i] = to[i];
				footfall(e, to[i]);
			}
		}

		// Start new steps, worst-placed legs first.
		Integer[] order = {0, 1, 2, 3, 4, 5};
		double[] err = new double[LEGS];
		for (int i = 0; i < LEGS; i++) {
			double dx = foot[i].x - home[i].x, dz = foot[i].z - home[i].z;
			err[i] = Math.sqrt(dx * dx + dz * dz) + Math.abs(foot[i].y - home[i].y) * 0.5;
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(err[b], err[a]));
		boolean idle = vel.horizontalLengthSquared() < 1e-4;
		for (int i : order) {
			if (stepping[i]) continue;
			// A foot that's out of reach (or we just folded/unfolded) steps right away, no waiting its turn.
			boolean urgent = replantAll || (foot[i].distanceTo(hip[i]) > reach && foot[i].distanceTo(home[i]) > 0.5);
			if (urgent) {
				startStep(i, home[i]);
				continue;
			}
			if (!canStep(i)) continue;
			if (err[i] > stepDist) {
				startStep(i, home[i]);
			} else if (idle && e.getRandom().nextFloat() < 0.003f) {
				// Idle twitch: lift a leg and set it back down a little off.
				Vec3d j = home[i].add((e.getRandom().nextFloat() - 0.5f) * 0.6, 0, (e.getRandom().nextFloat() - 0.5f) * 0.6);
				startStep(i, new Vec3d(j.x, groundAt(world, j.x, j.z, pos.y, folded), j.z));
			}
		}

		// Body rides at the average foot height and tilts with the ground.
		double sum = 0, front = 0, rear = 0, left = 0, right = 0;
		for (int i = 0; i < LEGS; i++) {
			double y = (stepping[i] ? to[i].y : foot[i].y) - pos.y;
			sum += y;
			if (i % 3 == 0) front += y;
			if (i % 3 == 2) rear += y;
			if (i < 3) right += y; else left += y;
		}
		float targetOffset = (float) MathHelper.clamp(sum / LEGS, -2.5, 2.5);
		float span = Math.max(1f, radius / 16f * 1.4f);
		float targetPitch = (float) MathHelper.clamp(Math.atan2((front - rear) / 2.0, span), -0.3, 0.3);
		float targetRoll = (float) MathHelper.clamp(Math.atan2((right - left) / 3.0, span), -0.3, 0.3);
		groundOffset += (targetOffset - groundOffset) * 0.25f;
		pitch += (targetPitch - pitch) * 0.2f;
		roll += (targetRoll - roll) * 0.2f;
	}

	private void startStep(int i, Vec3d target) {
		from[i] = foot[i];
		to[i] = target;
		progress[i] = 0f;
		stepping[i] = true;
	}

	private boolean canStep(int i) {
		int count = 0;
		for (boolean b : stepping) if (b) count++;
		if (count >= 3) return false;
		int side = i / 3, k = i % 3;
		if (k > 0 && stepping[side * 3 + k - 1]) return false;
		if (k < 2 && stepping[side * 3 + k + 1]) return false;
		return !stepping[(1 - side) * 3 + k];
	}

	private static void footfall(SpindleEntity e, Vec3d at) {
		float volume = switch (e.getSyncedMode()) {
			case HUNT -> 0.7f;
			case STALK -> 0.05f;
			default -> 0.25f;
		};
		e.getWorld().playSound(at.x, at.y, at.z, SoundEvents.ENTITY_SPIDER_STEP, SoundCategory.HOSTILE,
				volume, 0.4f + e.getRandom().nextFloat() * 0.15f, false);
	}

	/**
	 * Height of the walkable surface under (x, z): a solid top with open space above it.
	 * Standing (huge): the highest such surface from a few blocks above its feet down to 10 below, so feet
	 * go on roofs, ledges and tree tops rather than through them. Folded (in tunnels): the surface nearest
	 * its own height, so feet stay on the cave floor instead of climbing through the ceiling.
	 */
	public static double groundAt(World world, double x, double z, double refY, boolean folded) {
		int bx = MathHelper.floor(x), bz = MathHelper.floor(z), by = MathHelper.floor(refY);
		BlockPos.Mutable m = new BlockPos.Mutable();
		if (!folded) {
			for (int y = by + 4; y >= by - 10; y--) {
				double top = walkableTop(world, m, bx, y, bz);
				if (top != Double.NEGATIVE_INFINITY) return top;
			}
			return by - 10;
		}
		for (int d = 0; d <= 3; d++) {
			for (int sign : (d == 0 ? new int[]{1} : new int[]{-1, 1})) {
				double top = walkableTop(world, m, bx, by + d * sign, bz);
				if (top != Double.NEGATIVE_INFINITY) return top;
			}
		}
		return refY;
	}

	private static double walkableTop(World world, BlockPos.Mutable m, int x, int y, int z) {
		m.set(x, y, z);
		double top = surfaceTop(world, m);
		if (top == Double.NEGATIVE_INFINITY) return top;
		m.set(x, y + 1, z);
		return isSolid(world, m) ? Double.NEGATIVE_INFINITY : top;
	}

	private static double surfaceTop(World world, BlockPos pos) {
		BlockState st = world.getBlockState(pos);
		VoxelShape shape = st.getCollisionShape(world, pos);
		if (shape.isEmpty()) return Double.NEGATIVE_INFINITY;
		return pos.getY() + shape.getMax(Direction.Axis.Y);
	}

	private static boolean isSolid(World world, BlockPos pos) {
		VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
		return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) > 0.5;
	}
}
