package net.spindle.client;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.spindle.entity.SpindleLegState;
import net.spindle.entity.SpindleEntity;

/**
 * Model space: units are pixels (1/16 block), +Y is DOWN, the ground is at y = 24, the front is -Z.
 *
 * Legs are two separate bones (not parented) so each one can be placed exactly by a small
 * two-bone IK solver every frame: the feet stay planted and the knees ride high above the body.
 * When the Spindle folds, its legs telescope down to a fraction of their length.
 */
public class SpindleModel extends SinglePartEntityModel<SpindleEntity> {
	private static final int LEGS_PER_SIDE = 3;
	private static final float UPPER_LEN = 84f;
	private static final float LOWER_LEN = 120f;
	private static final float ARM_UPPER_LEN = 30f;
	private static final float ARM_LOWER_LEN = 36f;
	private static final float[] HIP_Z = {-9f, 0f, 9f};

	private final ModelPart root;
	private final ModelPart body;
	private final ModelPart[] upper = new ModelPart[LEGS_PER_SIDE * 2];
	private final ModelPart[] lower = new ModelPart[LEGS_PER_SIDE * 2];
	private final ModelPart[] armUpper = new ModelPart[2];
	private final ModelPart[] armLower = new ModelPart[2];
	public final ModelPart tendril;
	public final ModelPart probeSeg, probeRing, probeHead, probeLens, probePetal;

	private float tickDelta;
	/** Model-space Y of the underside of the core, from the last setAngles call (used by the renderer). */
	public float coreBottomY = -48f;

	public SpindleModel(ModelPart root) {
		this.root = root;
		this.body = root.getChild("body");
		for (int i = 0; i < upper.length; i++) {
			upper[i] = root.getChild("leg" + i + "_upper");
			lower[i] = root.getChild("leg" + i + "_lower");
		}
		for (int i = 0; i < 2; i++) {
			armUpper[i] = root.getChild("arm" + i + "_upper");
			armLower[i] = root.getChild("arm" + i + "_lower");
		}
		this.tendril = root.getChild("tendril");
		this.probeSeg = root.getChild("probe_seg");
		this.probeRing = root.getChild("probe_ring");
		this.probeHead = root.getChild("probe_head");
		this.probeLens = root.getChild("probe_lens");
		this.probePetal = root.getChild("probe_petal");
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData data = new ModelData();
		ModelPartData r = data.getRoot();

		ModelPartData body = r.addChild("body", ModelPartBuilder.create()
						.uv(0, 0).cuboid(-9f, -5f, -13f, 18f, 10f, 26f)          // thorax plate
						.uv(88, 0).cuboid(-2f, -9f, -10f, 4f, 4f, 20f)           // spine ridge
						.uv(176, 0).cuboid(-7f, -4f, 13f, 14f, 9f, 14f),         // hanging rear sac
				ModelTransform.pivot(0f, -60f, 0f));
		body.addChild("core", ModelPartBuilder.create()
						.uv(136, 0).cuboid(-5f, 0f, -5f, 10f, 7f, 10f),          // cracked core, glows
				ModelTransform.pivot(0f, 5f, 1f));

		for (int i = 0; i < LEGS_PER_SIDE * 2; i++) {
			r.addChild("leg" + i + "_upper", ModelPartBuilder.create()
							.uv(0, 64).cuboid(-2f, -2f, 0f, 4f, 4f, UPPER_LEN, new Dilation(0f)),
					ModelTransform.NONE);
			r.addChild("leg" + i + "_lower", ModelPartBuilder.create()
							.uv(0, 160).cuboid(-1.5f, -1.5f, 0f, 3f, 3f, LOWER_LEN)
							.uv(0, 290).cuboid(-0.5f, -0.5f, LOWER_LEN, 1f, 1f, 8f), // needle tip
					ModelTransform.NONE);
		}
		for (int i = 0; i < 2; i++) {
			r.addChild("arm" + i + "_upper", ModelPartBuilder.create()
							.uv(180, 64).cuboid(-1.5f, -1.5f, 0f, 3f, 3f, ARM_UPPER_LEN),
					ModelTransform.NONE);
			r.addChild("arm" + i + "_lower", ModelPartBuilder.create()
							.uv(180, 100).cuboid(-1f, -1f, 0f, 2f, 2f, ARM_LOWER_LEN)
							.uv(176, 24).cuboid(-1.5f, -0.5f, ARM_LOWER_LEN, 3f, 1f, 6f), // hooked tip
					ModelTransform.NONE);
		}
		// Single tendril segment, 1 block long. Not drawn with the body; the renderer chains copies of it.
		r.addChild("tendril", ModelPartBuilder.create()
						.uv(200, 24).cuboid(-1f, -1f, 0f, 2f, 2f, 16f),
				ModelTransform.NONE);

		// Probe parts (mechanical reaching/searching tendril). Drawn piece by piece by the renderer.
		r.addChild("probe_seg", ModelPartBuilder.create().uv(0, 300).cuboid(-1f, -1f, 0f, 2f, 2f, 16f), ModelTransform.NONE);
		r.addChild("probe_ring", ModelPartBuilder.create().uv(40, 300).cuboid(-1.6f, -1.6f, -0.75f, 3.2f, 3.2f, 1.5f), ModelTransform.NONE);
		r.addChild("probe_head", ModelPartBuilder.create().uv(56, 300).cuboid(-2.5f, -2.5f, 0f, 5f, 5f, 6f), ModelTransform.NONE);
		r.addChild("probe_lens", ModelPartBuilder.create().uv(80, 300).cuboid(-1.8f, -1.8f, 0f, 3.6f, 3.6f, 0.6f), ModelTransform.NONE);
		r.addChild("probe_petal", ModelPartBuilder.create().uv(96, 300).cuboid(-0.75f, -0.4f, 0f, 1.5f, 0.8f, 5f), ModelTransform.NONE);

		return TexturedModelData.of(data, 256, 512);
	}

	@Override
	public ModelPart getPart() {
		return root;
	}

	@Override
	public void animateModel(SpindleEntity entity, float limbAngle, float limbDistance, float tickDelta) {
		this.tickDelta = tickDelta;
	}

	@Override
	public void setAngles(SpindleEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
		tendril.visible = false;
		probeSeg.visible = probeRing.visible = probeHead.visible = probeLens.visible = probePetal.visible = false;

		float fold = MathHelper.lerp(tickDelta, entity.prevFoldProgress, entity.foldProgress);
		float legScale = MathHelper.lerp(fold, 1.0f, 0.3f);
		float speed = Math.min(1.0f, limbDistance * 1.6f);
		float attack = entity.attackAnimTicks > 0 ? (entity.attackAnimTicks - tickDelta) / 12f : 0f;
		attack = MathHelper.clamp(attack, 0f, 1f);
		float strike = MathHelper.sin(attack * (float) Math.PI);

		// Body: hangs high between the legs at the average height of its feet, tilts with the ground, breathes.
		SpindleLegState legs = entity.legs;
		float groundOff = MathHelper.lerp(tickDelta, legs.prevGroundOffset, legs.groundOffset);
		float tiltPitch = MathHelper.lerp(tickDelta, legs.prevPitch, legs.pitch);
		float tiltRoll = MathHelper.lerp(tickDelta, legs.prevRoll, legs.roll);
		float breathe = MathHelper.sin(animationProgress * 0.06f) * 1.2f;
		float stepBob = Math.abs(MathHelper.sin(limbAngle * 0.6f)) * 2f * speed;
		float bodyY = MathHelper.lerp(fold, -60f, 6f) - groundOff * 16f + breathe + stepBob + strike * 6f;
		body.pivotY = bodyY;
		coreBottomY = bodyY + 12f; // core part: pivot +5, 7 tall
		body.pitch = strike * 0.35f + MathHelper.sin(animationProgress * 0.04f) * 0.03f - tiltPitch;
		body.roll = -tiltRoll;
		body.yaw = 0f;

		float l1 = UPPER_LEN * legScale;
		float l2 = LOWER_LEN * legScale;
		Vec3d base = entity.getLerpedPos(tickDelta);
		float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);

		for (int i = 0; i < SpindleLegState.LEGS; i++) {
			float s = i < 3 ? 1f : -1f;
			int k = i % 3;
			float hipX = s * 8f;
			float hipZ = HIP_Z[k];
			// Hips follow the body's tilt a little so legs stay attached.
			float hipY = bodyY + 3f + hipZ * tiltPitch - hipX * tiltRoll;

			float fx, fy, fz;
			if (legs.isReady()) {
				Vec3d footWorld = legs.prevFoot[i].lerp(legs.foot[i], tickDelta);
				Vec3d m = SpindleLegState.worldToModel(footWorld.subtract(base), bodyYaw, entity.isHanging());
				fx = (float) m.x;
				fy = (float) m.y;
				fz = (float) m.z;
			} else {
				float radius = SpindleLegState.restRadius(fold);
				fx = SpindleLegState.restModelX(i, radius);
				fy = 24f;
				fz = SpindleLegState.restModelZ(i, radius);
			}
			solveLeg(upper[i], lower[i], hipX, hipY, hipZ, fx, fy, fz, l1, l2, legScale);
		}

		// Front arms: tucked under the body, thrown forward to strike, raised while the tendril is out.
		boolean reaching = entity.isTendrilActive();
		for (int side = 0; side < 2; side++) {
			float s = side == 0 ? 1f : -1f;
			float hipX = s * 5f, hipY = bodyY + 4f, hipZ = -12f;
			float tx = s * 7f, ty = bodyY + 26f * legScale, tz = -24f * legScale;
			if (reaching) {
				tx = s * 16f;
				ty = bodyY + 6f;
				tz = -40f * legScale;
			}
			tx = MathHelper.lerp(strike, tx, s * 3f);
			ty = MathHelper.lerp(strike, ty, 22f - groundOff * 16f);
			tz = MathHelper.lerp(strike, tz, -56f * legScale);
			ty += MathHelper.sin(animationProgress * 0.08f + side) * 1.5f;
			solveLeg(armUpper[side], armLower[side], hipX, hipY, hipZ, tx, ty, tz,
					ARM_UPPER_LEN * legScale, ARM_LOWER_LEN * legScale, legScale);
		}
	}

	/**
	 * Two-bone IK in model space. Bones are modelled along +Z, so: yaw aims the leg horizontally and
	 * pitch (positive = up) raises it. The knee always bends upward like a harvestman's.
	 */
	private static void solveLeg(ModelPart up, ModelPart low, float hx, float hy, float hz,
								 float fx, float fy, float fz, float l1, float l2, float scale) {
		float dx = fx - hx;
		float dy = fy - hy; // positive = foot is below the hip
		float dz = fz - hz;
		float h = MathHelper.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.atan2(dx, dz);
		float dist = MathHelper.sqrt(h * h + dy * dy);
		dist = MathHelper.clamp(dist, Math.abs(l1 - l2) + 0.01f, l1 + l2 - 0.01f);

		float below = (float) Math.atan2(dy, h);
		float hipAngle = (float) Math.acos(MathHelper.clamp((l1 * l1 + dist * dist - l2 * l2) / (2f * l1 * dist), -1f, 1f));
		float kneeAngle = (float) Math.acos(MathHelper.clamp((l1 * l1 + l2 * l2 - dist * dist) / (2f * l1 * l2), -1f, 1f));

		float upperPitch = hipAngle - below;
		float lowerPitch = upperPitch - ((float) Math.PI - kneeAngle);

		up.pivotX = hx;
		up.pivotY = hy;
		up.pivotZ = hz;
		up.yaw = yaw;
		up.pitch = upperPitch;
		up.roll = 0f;
		up.zScale = scale;

		float horiz = l1 * MathHelper.cos(upperPitch);
		low.pivotX = hx + horiz * MathHelper.sin(yaw);
		low.pivotY = hy - l1 * MathHelper.sin(upperPitch);
		low.pivotZ = hz + horiz * MathHelper.cos(yaw);
		low.yaw = yaw;
		low.pitch = lowerPitch;
		low.roll = 0f;
		low.zScale = scale;
	}
}
