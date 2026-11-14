package net.spindle.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;

import java.util.ArrayList;
import java.util.List;

public class SpindleRenderer extends MobEntityRenderer<SpindleEntity, SpindleModel> {
	private static final Identifier TEXTURE = Spindle.id("textures/entity/spindle.png");

	public SpindleRenderer(EntityRendererFactory.Context context) {
		super(context, new SpindleModel(context.getPart(SpindleClient.LAYER)), 1.8f);
		this.addFeature(new SpindleCoreFeature(this));
	}

	@Override
	public Identifier getTexture(SpindleEntity entity) {
		return TEXTURE;
	}

	@Override
	protected float getLyingAngle(SpindleEntity entity) {
		return 0f; // it doesn't tip over when it dies; it just folds up
	}

	@Override
	public void render(SpindleEntity entity, float yaw, float tickDelta, MatrixStack matrices,
					   VertexConsumerProvider vertexConsumers, int light) {
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
		renderTendrils(entity, tickDelta, matrices, vertexConsumers, light);
	}

	private void renderTendrils(SpindleEntity entity, float tickDelta, MatrixStack matrices,
								VertexConsumerProvider vertexConsumers, int light) {
		ModelPart seg = this.getModel().tendril;
		VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
		Vec3d base = entity.getLerpedPos(tickDelta);
		float fold = MathHelper.lerp(tickDelta, entity.prevFoldProgress, entity.foldProgress);
		double coreY = MathHelper.lerp(fold, SpindleEntity.CORE_HEIGHT_STANDING, SpindleEntity.CORE_HEIGHT_FOLDED);
		Vec3d core = base.add(0, coreY, 0);
		float time = entity.age + tickDelta;
		int overlay = OverlayTexture.DEFAULT_UV;

		seg.visible = true;

		// Two short tendrils always hang from the core and sway.
		float bodyYaw = MathHelper.lerp(tickDelta, entity.prevBodyYaw, entity.bodyYaw) * MathHelper.RADIANS_PER_DEGREE;
		for (int i = 0; i < 2; i++) {
			double side = i == 0 ? 0.3 : -0.3;
			Vec3d start = core.add(Math.cos(bodyYaw) * side, -0.1, Math.sin(bodyYaw) * side);
			double len = (1.2 + 0.25 * Math.sin(time * 0.07 + i * 2)) * (1.0 - fold * 0.6);
			Vec3d end = start.add(Math.sin(time * 0.05 + i) * 0.35, -len, Math.cos(time * 0.043 + i * 3) * 0.35);
			drawChain(List.of(start, end), base, matrices, vc, seg, light, overlay, time + i * 17, 0.12, 0.9f, 0.35f);
		}

		// The reaching tendril.
		if (entity.isTendrilActive() && entity.clientTip != null && entity.prevClientTip != null) {
			Vec3d tip = entity.prevClientTip.lerp(entity.clientTip, tickDelta);
			int flags = entity.getTendrilFlags();
			List<Vec3d> points = new ArrayList<>();
			points.add(core);
			if ((flags & SpindleEntity.TENDRIL_HAS_MID) != 0 && (flags & SpindleEntity.TENDRIL_PAST_MID) != 0) {
				points.add(entity.getTendrilMid());
			}
			points.add(tip);
			drawChain(points, base, matrices, vc, seg, light, overlay, time, 0.35, 1.5f, 0.45f);
		}

		seg.visible = false;
	}

	/**
	 * Draws a writhing chain of segments through the given points.
	 * The wiggle is zero at both ends so it stays attached to the core and the tip.
	 */
	private static void drawChain(List<Vec3d> controls, Vec3d base, MatrixStack matrices, VertexConsumer vc, ModelPart seg,
								  int light, int overlay, float time, double wiggle, float thickBase, float thickTip) {
		double total = 0;
		for (int i = 0; i < controls.size() - 1; i++) total += controls.get(i).distanceTo(controls.get(i + 1));
		if (total < 0.05) return;

		int n = MathHelper.clamp((int) Math.ceil(total / 0.4), 3, 96);
		Vec3d overall = controls.get(controls.size() - 1).subtract(controls.get(0));
		if (overall.lengthSquared() < 1e-6) overall = new Vec3d(0, -1, 0);
		overall = overall.normalize();
		Vec3d perp1 = overall.crossProduct(new Vec3d(0, 1, 0));
		if (perp1.lengthSquared() < 1e-4) perp1 = new Vec3d(1, 0, 0);
		perp1 = perp1.normalize();
		Vec3d perp2 = overall.crossProduct(perp1).normalize();

		Vec3d[] pts = new Vec3d[n + 1];
		for (int i = 0; i <= n; i++) {
			double u = i / (double) n;
			Vec3d p = pointAlong(controls, u * total);
			double env = Math.sin(Math.PI * u);
			double s = u * total;
			double w1 = Math.sin(s * 2.3 - time * 0.45) * wiggle * env;
			double w2 = Math.cos(s * 1.7 - time * 0.31) * wiggle * env;
			pts[i] = p.add(perp1.multiply(w1)).add(perp2.multiply(w2));
		}

		for (int i = 0; i < n; i++) {
			Vec3d a = pts[i];
			Vec3d d = pts[i + 1].subtract(a);
			double len = d.length();
			if (len < 1e-4) continue;
			float u = i / (float) n;
			float th = MathHelper.lerp(u, thickBase, thickTip);
			matrices.push();
			matrices.translate(a.x - base.x, a.y - base.y, a.z - base.z);
			float yaw = (float) Math.atan2(d.x, d.z);
			float pitch = (float) -Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
			matrices.multiply(RotationAxis.POSITIVE_X.rotation(pitch));
			matrices.scale(th, th, (float) len * 1.08f);
			seg.render(matrices, vc, light, overlay);
			matrices.pop();
		}
	}

	private static Vec3d pointAlong(List<Vec3d> controls, double dist) {
		for (int i = 0; i < controls.size() - 1; i++) {
			Vec3d a = controls.get(i);
			Vec3d b = controls.get(i + 1);
			double l = a.distanceTo(b);
			if (dist <= l || i == controls.size() - 2) {
				return l < 1e-6 ? a : a.lerp(b, MathHelper.clamp(dist / l, 0.0, 1.0));
			}
			dist -= l;
		}
		return controls.get(controls.size() - 1);
	}
}
