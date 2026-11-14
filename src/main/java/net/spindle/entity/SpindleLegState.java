package net.spindle.entity;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.spindle.SpindleSounds;

/**
 * Client-side procedural walking.
 *
 * Feet: each foot is planted at a real spot in the world and stays there until the body has moved
 * far enough away from it; then that leg lifts and steps to a new spot on the actual ground (slopes,
 * stairs, cave floors). Neighbouring legs never step at the same time. In corridors and tunnels the
 * feet pull in or swing fore and aft so they never land inside a wall.
 *
 * Knees: normally they ride high above the body. Where there's a ceiling or a wall in the way, each
 * knee swings sideways around its hip-foot line until the whole leg is clear of solid blocks.
 *
 * Body: rides at the average height of its feet, tilts with the terrain, and sits as high as the
 * server says there's room for (see SpindleEntity#getBodyHeight).
 */
public class SpindleLegState {
	public static final int LEGS = 6;
	/** Bone lengths in pixels (1/16 block) at full size. */
	public static final float UPPER_LEN = 84f, LOWER_LEN = 120f;
	public static final float[] HIP_Z = {-9f, 0f, 9f};
	public static final float HIP_X = 8f;
	/** Resting foot direction of each leg, measured from straight ahead. */
	private static final float[] LEG_ANGLES = {
			(float) Math.toRadians(38), (float) Math.toRadians(88), (float) Math.toRadians(138)
	};
	/** Where each leg points instead when there's no room to the sides (tunnels). */
	private static final float[] TUNNEL_ANGLES = {
			(float) Math.toRadians(12), (float) Math.toRadians(160), (float) Math.toRadians(170)
	};
	private static final float[] TILT_STEPS = {0f, 0.4f, 0.8f, 1.2f, 1.5f};
	private static final float[] SHRINK_STEPS = {1f, 0.85f, 0.7f, 0.58f, 0.48f};

	public final Vec3d[] foot = new Vec3d[LEGS];
	public final Vec3d[] prevFoot = new Vec3d[LEGS];
	/** Each leg's own length scale: legs telescope shorter where there isn't room for them. */
	public final float[] scale = new float[LEGS];
	public final float[] prevScale = new float[LEGS];
	/** Fold used for the legs. Same as visualFold, except when hanging: legs spread out across the ceiling. */
	public float legFold, prevLegFold;
	/** How far each knee is swung sideways out of its upright plane, in radians. */
	public final float[] tilt = new float[LEGS];
	public final float[] prevTilt = new float[LEGS];
	private final float[] tiltTarget = new float[LEGS];
	private final Vec3d[] from = new Vec3d[LEGS];
	private final Vec3d[] to = new Vec3d[LEGS];
	private final float[] progress = new float[LEGS];
	private final boolean[] stepping = new boolean[LEGS];
	private float stepDuration = 8f;
	private float stepLift = 1f;

	public float groundOffset, prevGroundOffset;
	/** 0 = standing tall, 1 = folded flat. Follows the server-synced body height. */
	public float visualFold, prevVisualFold;
	public float pitch, prevPitch;
	public float roll, prevRoll;
	private boolean initialized;
	private float lastPlantFold;
	private boolean lastHanging;

	public boolean isReady() {
		return initialized;
	}

	// ---------------------------------------------------------------- shared geometry

	private static float scaleNow(float fold) {
		return legScale(fold);
	}

	public static float legScale(float fold) {
		return MathHelper.lerp(fold, 1.0f, 0.3f);
	}

	public static float restRadius(float fold) {
		return MathHelper.lerp(fold, 90f, 14f);
	}

	/** Model-space Y of the body's pivot for a given fold (before terrain offset and animation). */
	public static float bodyModelY(float fold) {
		return MathHelper.lerp(fold, -60f, 6f);
	}

	/** Resting foot position in model space (pixels; +Y down, ground at 24, front is -Z). */
	public static float restModelX(int leg, float radius) {
		return footModelX(leg, radius, LEG_ANGLES[leg % 3]);
	}

	public static float restModelZ(int leg, float radius) {
		return footModelZ(radius, LEG_ANGLES[leg % 3]);
	}

	private static float footModelX(int leg, float radius, float angle) {
		float s = leg < 3 ? 1f : -1f;
		return s * radius * MathHelper.sin(angle);
	}

	private static float footModelZ(float radius, float angle) {
		return -radius * MathHelper.cos(angle);
	}

	/** Which way a leg's knee swings when it has to tilt, in model space: front legs forward, the rest backward. */
	public static Vec3d sideHintModel(int leg) {
		return new Vec3d(0, 0, leg % 3 == 0 ? -1 : 1);
	}

	/** Converts a model-space point (pixels) to a world offset from the entity's feet, for a given body yaw. */
	public static Vec3d modelToWorld(double xm, double ym, double zm, float bodyYawDeg) {
		double lx = -xm / 16.0, ly = 1.501 - ym / 16.0, lz = zm / 16.0;
		double t = Math.toRadians(180.0 - bodyYawDeg);
		double c = Math.cos(t), s = Math.sin(t);
		return new Vec3d(lx * c + lz * s, ly, -lx * s + lz * c);
	}

	/** A model-space direction as a world direction. */
	public static Vec3d modelDirToWorld(Vec3d dir, float bodyYawDeg, boolean hanging) {
		double lx = -dir.x, ly = -dir.y, lz = dir.z;
		double t = Math.toRadians(180.0 - bodyYawDeg);
		double c = Math.cos(t), s = Math.sin(t);
		return new Vec3d(lx * c + lz * s, hanging ? -ly : ly, -lx * s + lz * c);
	}

	/** Upside down (hanging from a ceiling): the whole model is mirrored about a horizontal plane. */
	public static Vec3d mirror(Vec3d off, boolean hanging) {
		return hanging ? new Vec3d(off.x, 2 * SpindleEntity.HANG_PIVOT - off.y, off.z) : off;
	}

	public static Vec3d worldToModel(Vec3d d, float bodyYawDeg, boolean hanging) {
		return worldToModel(mirror(d, hanging), bodyYawDeg);
	}

	/** Inverse of {@link #modelToWorld}: world offset from the entity's feet -> model-space pixels. */
	public static Vec3d worldToModel(Vec3d d, float bodyYawDeg) {
		double t = Math.toRadians(180.0 - bodyYawDeg);
		double c = Math.cos(t), s = Math.sin(t);
		double lx = d.x * c - d.z * s;
		double lz = d.x * s + d.z * c;
		return new Vec3d(-lx * 16.0, (1.501 - d.y) * 16.0, lz * 16.0);
	}

	/**
	 * Knee position of a two-bone leg, in whatever space the inputs are in.
	 * With tilt 0 the knee points as far "up" as it can; tilt swings it around the hip-foot line
	 * toward sideHint (positive) or away from it (negative).
	 */
	public static Vec3d solveKnee(Vec3d hip, Vec3d foot, double l1, double l2, Vec3d up, Vec3d sideHint, double tilt) {
		Vec3d axis = foot.subtract(hip);
		double dist = axis.length();
		Vec3d a = dist < 1e-5 ? new Vec3d(-up.x, -up.y, -up.z) : axis.multiply(1.0 / dist);
		double d = MathHelper.clamp(dist, Math.abs(l1 - l2) + 0.01, l1 + l2 - 0.01);
		double along = (l1 * l1 - l2 * l2 + d * d) / (2.0 * d);
		double r = Math.sqrt(Math.max(0.0, l1 * l1 - along * along));
		Vec3d u = up.subtract(a.multiply(up.dotProduct(a)));
		if (u.lengthSquared() < 1e-6) u = sideHint.subtract(a.multiply(sideHint.dotProduct(a)));
		u = u.normalize();
		Vec3d w = sideHint.subtract(a.multiply(sideHint.dotProduct(a))).subtract(u.multiply(sideHint.dotProduct(u)));
		if (w.lengthSquared() < 1e-6) w = a.crossProduct(u);
		w = w.normalize();
		return hip.add(a.multiply(along)).add(u.multiply(r * Math.cos(tilt))).add(w.multiply(r * Math.sin(tilt)));
	}

	// ---------------------------------------------------------------- per tick

	public void tick(SpindleEntity e) {
		World world = e.getWorld();
		Vec3d pos = e.getPos();
		Vec3d vel = pos.subtract(e.prevX, e.prevY, e.prevZ);
		boolean hanging = e.isHanging();

		prevVisualFold = visualFold;
		float targetFold = (hanging || e.isDead()) ? 1f : e.getFold();
		visualFold += (targetFold - visualFold) * (e.isDead() ? 0.12f : 0.3f);
		if (!initialized) prevVisualFold = visualFold = targetFold;
		prevLegFold = legFold;
		float targetLegFold = hanging ? 0.55f : visualFold;
		legFold += (targetLegFold - legFold) * 0.3f;
		if (!initialized) prevLegFold = legFold = targetLegFold;
		float fold = legFold;
		float radius = restRadius(fold);
		float baseScale = scaleNow(fold);
		boolean low = fold > 0.5f;
		boolean hunting = e.getSyncedMode() == SpindleEntity.Mode.HUNT;

		stepDuration = low ? 4f : (hunting ? 5f : 8f);
		stepLift = MathHelper.lerp(fold, 1.1f, 0.3f);
		double stepDist = MathHelper.lerp(fold, 1.5, 0.45);
		Vec3d lead = new Vec3d(vel.x, 0, vel.z).multiply(stepDuration * 1.3);
		double l1 = UPPER_LEN * baseScale / 16.0, l2 = LOWER_LEN * baseScale / 16.0;
		double reach = (l1 + l2) * 0.96;
		float hipYm = bodyModelY(visualFold) + 3f - groundOffset * 16f;

		Vec3d[] hip = new Vec3d[LEGS];
		Vec3d[] home = new Vec3d[LEGS];
		Vec3d up = new Vec3d(0, hanging ? -1 : 1, 0);
		double ceilRef = pos.y + 2 * SpindleEntity.HANG_PIVOT;
		for (int i = 0; i < LEGS; i++) {
			float s = i < 3 ? 1f : -1f;
			hip[i] = pos.add(mirror(modelToWorld(s * HIP_X, hipYm, HIP_Z[i % 3], e.bodyYaw), hanging));
			Vec3d side = modelDirToWorld(sideHintModel(i), e.bodyYaw, false);
			home[i] = null;
			// First choice is the resting spot; then pulled in; then swung fore/aft (tunnels); then tucked under.
			for (int c = 0; c < 8 && home[i] == null; c++) {
				boolean swing = c == 2 || c == 3 || c == 5;
				float angle = swing ? TUNNEL_ANGLES[i % 3] : LEG_ANGLES[i % 3];
				float pull = switch (c) {
					case 0, 2 -> 1.0f;
					case 1 -> 0.8f;
					case 3 -> 0.75f;
					case 4 -> 0.6f;
					case 5 -> 0.5f;
					case 6 -> 0.4f;
					default -> 0.2f;
				};
				Vec3d off = modelToWorld(footModelX(i, radius * pull, angle), 24, footModelZ(radius * pull, angle), e.bodyYaw);
				double hx = pos.x + off.x + lead.x;
				double hz = pos.z + off.z + lead.z;
				// The floor at its own level first; failing that (a building in the way), the roof.
				for (int level = 0; level < (hanging || low ? 1 : 2) && home[i] == null; level++) {
					double gy = hanging ? ceilingAt(world, hx, hz, ceilRef)
							: level == 0 ? groundAt(world, hx, hz, pos.y, low) : highSurfaceAt(world, hx, hz, pos.y);
					if (Double.isNaN(gy)) continue;
					Vec3d cand = new Vec3d(hx, gy, hz);
					if (cand.distanceTo(hip[i]) > reach) continue;
					if (!legCanReach(world, hip[i], cand, l1, l2, up, side)) continue;
					home[i] = cand;
				}
			}
			if (home[i] == null) home[i] = new Vec3d(pos.x + lead.x, hanging ? ceilRef : pos.y, pos.z + lead.z);
		}
		boolean replantAll = Math.abs(fold - lastPlantFold) > 0.2f || hanging != lastHanging;
		if (replantAll) {
			lastPlantFold = fold;
			lastHanging = hanging;
		}

		boolean teleported = false;
		if (initialized) {
			for (int i = 0; i < LEGS; i++) {
				if (foot[i].squaredDistanceTo(home[i]) > 12 * 12) teleported = true;
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

		for (int i = 0; i < LEGS; i++) {
			prevFoot[i] = foot[i];
			prevTilt[i] = tilt[i];
			prevScale[i] = scale[i];
			if (scale[i] <= 0f) prevScale[i] = scale[i] = scaleNow(fold);
		}
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
			foot[i] = p.add(0, MathHelper.sin(t * (float) Math.PI) * stepLift * (hanging ? -1 : 1), 0);
			if (t >= 1f) {
				stepping[i] = false;
				foot[i] = to[i];
				if (!e.isDead()) footfall(e, to[i]);
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
			if (stepping[i] || e.isDead()) continue;
			// A foot that's out of reach (or the body just changed size) steps right away, no waiting its turn.
			boolean urgent = (replantAll && err[i] > 0.3) || (foot[i].distanceTo(hip[i]) > reach && err[i] > 0.5);
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
				double jy = hanging ? ceilingAt(world, j.x, j.z, ceilRef) : groundAt(world, j.x, j.z, pos.y, low);
				if (!Double.isNaN(jy)) startStep(i, new Vec3d(j.x, jy, j.z));
			}
		}

		// Knees: swing each one sideways, and if that's not enough telescope the leg shorter, until the
		// whole leg is clear of walls and ceilings.
		for (int i = 0; i < LEGS; i++) {
			Vec3d side = modelDirToWorld(sideHintModel(i), e.bodyYaw, false);
			float sign = tiltTarget[i] < 0 ? -1f : 1f;
			double span = hip[i].distanceTo(foot[i]);
			float chosenTilt = 0f, chosenScale = -1f;
			search:
			for (float shrink : SHRINK_STEPS) {
				float sc = baseScale * shrink;
				double a1 = UPPER_LEN * sc / 16.0, a2 = LOWER_LEN * sc / 16.0;
				if (a1 + a2 < span * 1.02) {
					// Can't get any shorter than this and still reach the foot.
					chosenScale = (float) (span * 1.03 / ((UPPER_LEN + LOWER_LEN) / 16.0));
					chosenTilt = tiltTarget[i];
					break;
				}
				for (float step : TILT_STEPS) {
					for (int k = 0; k < (step == 0f ? 1 : 2); k++) {
						float candidate = step * (k == 0 ? sign : -sign);
						Vec3d knee = solveKnee(hip[i], foot[i], a1, a2, up, side, candidate);
						if (!segmentBlocked(world, hip[i], knee, 0.7, 0) && !segmentBlocked(world, knee, foot[i], 0, 0.45)) {
							chosenTilt = candidate;
							chosenScale = sc;
							break search;
						}
					}
				}
			}
			if (chosenScale < 0) chosenScale = baseScale * SHRINK_STEPS[SHRINK_STEPS.length - 1];
			tiltTarget[i] = chosenTilt;
			tilt[i] += (chosenTilt - tilt[i]) * 0.35f;
			// Shrinking happens fast (no clipping), growing back slowly.
			scale[i] += (chosenScale - scale[i]) * (chosenScale < scale[i] ? 0.6f : 0.15f);
		}

		// Body rides at the average foot height and tilts with the ground.
		double sum = 0, front = 0, rear = 0, left = 0, right = 0;
		for (int i = 0; i < LEGS; i++) {
			double y = (stepping[i] ? to[i].y : foot[i].y) - pos.y;
			if (hanging) y = 2 * SpindleEntity.HANG_PIVOT - y; // measured in the mirrored frame
			sum += y;
			if (i % 3 == 0) front += y;
			if (i % 3 == 2) rear += y;
			if (i < 3) right += y; else left += y;
		}
		// It can sink toward low ground, but never rises above what the server said there's room for.
		float targetOffset = (float) MathHelper.clamp(sum / LEGS, -2.5, low ? 0.0 : 1.0);
		float span = Math.max(1f, radius / 16f * 1.4f);
		float targetPitch = (float) MathHelper.clamp(Math.atan2((front - rear) / 2.0, span), -0.3, 0.3);
		float targetRoll = (float) MathHelper.clamp(Math.atan2((right - left) / 3.0, span), -0.3, 0.3);
		if (low) {
			targetPitch *= 0.3f;
			targetRoll *= 0.3f;
		}
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
			case HUNT -> 0.8f;
			case STALK -> 0.06f;
			default -> 0.3f;
		};
		e.getWorld().playSound(at.x, at.y, at.z, SpindleSounds.STEP, SoundCategory.HOSTILE,
				volume, 0.5f + e.getRandom().nextFloat() * 0.2f, false);
	}

	// ---------------------------------------------------------------- world queries

	/**
	 * Height of the floor at (x, z) at about the given level: the block it would step onto if there's
	 * a ledge there, otherwise the first surface below. Returns NaN where a foot can't go (inside a wall).
	 */
	public static double groundAt(World world, double x, double z, double refY, boolean low) {
		int bx = MathHelper.floor(x), bz = MathHelper.floor(z), by = MathHelper.floor(refY + 0.01);
		BlockPos.Mutable m = new BlockPos.Mutable();
		m.set(bx, by, bz);
		if (isSolid(world, m)) {
			for (int y = by; y <= by + (low ? 1 : 2); y++) {
				double top = walkableTop(world, m, bx, y, bz);
				if (!Double.isNaN(top)) return top;
			}
			return Double.NaN;
		}
		for (int d = 1; d <= (low ? 3 : 10); d++) {
			double t = walkableTop(world, m, bx, by - d, bz);
			if (!Double.isNaN(t)) return t;
		}
		return Double.NaN;
	}

	/** The top of whatever stands at (x, z) above its feet: a roof, a wall top, a cliff edge. NaN if nothing. */
	public static double highSurfaceAt(World world, double x, double z, double refY) {
		int bx = MathHelper.floor(x), bz = MathHelper.floor(z), by = MathHelper.floor(refY + 0.01);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int y = by + 8; y > by; y--) {
			double t = walkableTop(world, m, bx, y, bz);
			if (!Double.isNaN(t)) return t;
		}
		return Double.NaN;
	}

	/** Can a leg get from this hip to this foot spot without passing through anything solid? */
	private static boolean legCanReach(World world, Vec3d hip, Vec3d foot, double l1, double l2, Vec3d up, Vec3d side) {
		double span = hip.distanceTo(foot);
		for (float f : SHRINK_STEPS) {
			if ((l1 + l2) * f < span * 1.02) break;
			for (float t : new float[]{0f, 0.8f, -0.8f, 1.5f, -1.5f}) {
				Vec3d knee = solveKnee(hip, foot, l1 * f, l2 * f, up, side, t);
				if (!segmentBlocked(world, hip, knee, 0.7, 0) && !segmentBlocked(world, knee, foot, 0, 0.45)) return true;
			}
		}
		return false;
	}

	private static double walkableTop(World world, BlockPos.Mutable m, int x, int y, int z) {
		m.set(x, y, z);
		BlockState st = world.getBlockState(m);
		if (st.isAir() || st.isIn(BlockTags.LEAVES)) return Double.NaN;
		VoxelShape shape = st.getCollisionShape(world, m);
		if (shape.isEmpty()) return Double.NaN;
		double top = y + shape.getMax(Direction.Axis.Y);
		m.set(x, y + 1, z);
		return isSolid(world, m) ? Double.NaN : top;
	}

	/** Underside of the ceiling above (x, z): a solid block with open space below it. */
	public static double ceilingAt(World world, double x, double z, double refTop) {
		int bx = MathHelper.floor(x), bz = MathHelper.floor(z), by = MathHelper.floor(refTop + 0.01);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int y = by - 1; y <= by + 3; y++) {
			m.set(bx, y, bz);
			BlockState st = world.getBlockState(m);
			if (st.isAir() || st.isIn(BlockTags.LEAVES)) continue;
			VoxelShape shape = st.getCollisionShape(world, m);
			if (shape.isEmpty()) continue;
			m.set(bx, y - 1, bz);
			if (isSolid(world, m)) continue;
			return y + shape.getMin(Direction.Axis.Y);
		}
		return Double.NaN;
	}

	/** Solid as far as legs are concerned. Leaves don't count: it wades through foliage. */
	private static boolean isSolid(World world, BlockPos pos) {
		BlockState st = world.getBlockState(pos);
		if (st.isAir() || st.isIn(BlockTags.LEAVES)) return false;
		VoxelShape shape = st.getCollisionShape(world, pos);
		return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) > 0.5;
	}

	/**
	 * Does the straight segment a-b pass through anything solid? The first skipStart and last skipEnd
	 * blocks of it are ignored (where it leaves the body / touches the ground).
	 */
	public static boolean segmentBlocked(World world, Vec3d a, Vec3d b, double skipStart, double skipEnd) {
		Vec3d d = b.subtract(a);
		double len = d.length();
		if (len - skipStart - skipEnd < 0.1) return false;
		int steps = Math.max(1, (int) Math.ceil((len - skipStart - skipEnd) / 0.33));
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int i = 0; i <= steps; i++) {
			double t = (skipStart + (len - skipStart - skipEnd) * i / steps) / len;
			double x = a.x + d.x * t, y = a.y + d.y * t, z = a.z + d.z * t;
			m.set(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
			BlockState st = world.getBlockState(m);
			if (st.isAir() || st.isIn(BlockTags.LEAVES)) continue;
			VoxelShape shape = st.getCollisionShape(world, m);
			if (shape.isEmpty()) continue;
			double lx = x - m.getX(), ly = y - m.getY(), lz = z - m.getZ();
			if (lx >= shape.getMin(Direction.Axis.X) && lx <= shape.getMax(Direction.Axis.X)
					&& ly >= shape.getMin(Direction.Axis.Y) && ly <= shape.getMax(Direction.Axis.Y)
					&& lz >= shape.getMin(Direction.Axis.Z) && lz <= shape.getMax(Direction.Axis.Z)) return true;
		}
		return false;
	}
}
