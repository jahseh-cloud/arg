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
	private static final float UPPER_LEN = SpindleLegState.UPPER_LEN;
	private static final float LOWER_LEN = SpindleLegState.LOWER_LEN;
	private static final Vec3d MODEL_UP = new Vec3d(0, -1, 0);
	private static final float ARM_UPPER_LEN = 30f;
	private static final float ARM_LOWER_LEN = 36f;

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

		SpindleLegState legs = entity.legs;
		float fold = legs.isReady() ? MathHelper.lerp(tickDelta, legs.prevVisualFold, legs.visualFold) : entity.getFold();
		float legScale = SpindleLegState.legScale(fold);
		float attack = entity.attackAnimTicks > 0 ? (entity.attackAnimTicks - tickDelta) / 12f : 0f;
		attack = MathHelper.clamp(attack, 0f, 1f);
		float strike = MathHelper.sin(attack * (float) Math.PI);
		boolean dead = entity.isDead();

		// Body: hangs high between the legs at the average height of its feet, tilts with the ground, breathes.
		float groundOff = MathHelper.lerp(tickDelta, legs.prevGroundOffset, legs.groundOffset);
		float tiltPitch = MathHelper.lerp(tickDelta, legs.prevPitch, legs.pitch);
		float tiltRoll = MathHelper.lerp(tickDelta, legs.prevRoll, legs.roll);
		float breathe = dead ? 0f : MathHelper.sin(animationProgress * 0.06f) * 1.2f * (1f - fold * 0.6f);
		float bodyY = SpindleLegState.bodyModelY(fold) - groundOff * 16f + breathe + strike * 6f * legScale;
		body.pivotY = bodyY;
		coreBottomY = bodyY + 12f; // core part: pivot +5, 7 tall
		body.pitch = strike * 0.35f + (dead ? 0f : MathHelper.sin(animationProgress * 0.04f) * 0.03f) - tiltPitch;
		body.roll = -tiltRoll;
		body.yaw = 0f;

		float l1 = UPPER_LEN * legScale;
		float l2 = LOWER_LEN * legScale;
		Vec3d base = entity.getLerpedPos(tickDelta);
		float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);

		for (int i = 0; i < SpindleLegState.LEGS; i++) {
			float s = i < 3 ? 1f : -1f;
			int k = i % 3;
			float hipX = s * SpindleLegState.HIP_X;
			float hipZ = SpindleLegState.HIP_Z[k];
			// Hips follow the body's tilt a little so legs stay attached.
			float hipY = bodyY + 3f + hipZ * tiltPitch - hipX * tiltRoll;

			Vec3d footModel;
			float kneeTilt = 0f;
			if (legs.isReady()) {
				Vec3d footWorld = legs.prevFoot[i].lerp(legs.foot[i], tickDelta);
				footModel = SpindleLegState.worldToModel(footWorld.subtract(base), bodyYaw, entity.isHanging());
				kneeTilt = MathHelper.lerp(tickDelta, legs.prevTilt[i], legs.tilt[i]);
			} else {
				float radius = SpindleLegState.restRadius(fold);
				footModel = new Vec3d(SpindleLegState.restModelX(i, radius), 24f, SpindleLegState.restModelZ(i, radius));
			}
			poseLeg(upper[i], lower[i], new Vec3d(hipX, hipY, hipZ), footModel, l1, l2,
					SpindleLegState.sideHintModel(i), kneeTilt, legScale);
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
			ty = MathHelper.lerp(strike, ty, Math.min(22f, bodyY + 60f * legScale));
			tz = MathHelper.lerp(strike, tz, -56f * legScale);
			if (!dead) ty += MathHelper.sin(animationProgress * 0.08f + side) * 1.5f;
			poseLeg(armUpper[side], armLower[side], new Vec3d(hipX, hipY, hipZ), new Vec3d(tx, ty, tz),
					ARM_UPPER_LEN * legScale, ARM_LOWER_LEN * legScale, new Vec3d(s, 0, 0), 0f, legScale);
		}
	}

	/**
	 * Two-bone IK. Bones are modelled along +Z; each is simply aimed along its segment
	 * (hip to knee, knee to foot). The knee comes from the same solver the leg planner uses.
	 */
	private static void poseLeg(ModelPart up, ModelPart low, Vec3d hip, Vec3d foot, float l1, float l2,
								Vec3d sideHint, float kneeTilt, float scale) {
		Vec3d knee = SpindleLegState.solveKnee(hip, foot, l1, l2, MODEL_UP, sideHint, kneeTilt);
		aim(up, hip, knee, scale);
		aim(low, knee, foot, scale);
	}

	private static void aim(ModelPart part, Vec3d from, Vec3d to, float scale) {
		Vec3d d = to.subtract(from);
		double len = d.length();
		part.pivotX = (float) from.x;
		part.pivotY = (float) from.y;
		part.pivotZ = (float) from.z;
		if (len > 1e-4) {
			part.yaw = (float) Math.atan2(d.x, d.z);
			part.pitch = (float) Math.asin(MathHelper.clamp(-d.y / len, -1.0, 1.0));
		}
		part.roll = 0f;
		part.zScale = scale;
	}
}
