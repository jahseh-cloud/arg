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
import net.spindle.entity.SpindleLegState;

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
	protected void setupTransforms(SpindleEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw,
								   float tickDelta, float scale) {
		if (entity.isHanging()) {
			// Upside down on the ceiling: mirror the whole model about a horizontal plane.
			float pivot = (float) SpindleEntity.HANG_PIVOT;
			matrices.translate(0f, pivot, 0f);
			matrices.scale(1f, -1f, 1f);
			matrices.translate(0f, -pivot, 0f);
		}
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
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
		RenderLayer cutout = RenderLayer.getEntityCutoutNoCull(TEXTURE);
		Vec3d base = entity.getLerpedPos(tickDelta);
		float fold = MathHelper.lerp(tickDelta, entity.legs.prevVisualFold, entity.legs.visualFold);
		float bodyYawDeg = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);
		// Where the core actually is this frame (the body bobs, rides the terrain and folds).
		boolean hanging = entity.isHanging();
		Vec3d core = base.add(SpindleLegState.mirror(
				SpindleLegState.modelToWorld(0, this.getModel().coreBottomY, 1, bodyYawDeg), hanging));
		float time = entity.age + tickDelta;
		int overlay = OverlayTexture.DEFAULT_UV;

		seg.visible = true;

		// Two short tendrils always hang from the underside of the core and sway.
		float yawRad = bodyYawDeg * MathHelper.RADIANS_PER_DEGREE;
		for (int i = 0; i < (hanging ? 0 : 2); i++) {
			double side = i == 0 ? 0.22 : -0.22;
			Vec3d start = core.add(Math.cos(yawRad) * side, 0.05, Math.sin(yawRad) * side);
			double len = (1.2 + 0.25 * Math.sin(time * 0.07 + i * 2)) * (1.0 - fold * 0.6);
			Vec3d end = start.add(Math.sin(time * 0.05 + i) * 0.35, -len, Math.cos(time * 0.043 + i * 3) * 0.35);
			drawChain(List.of(start, end), base, matrices, vertexConsumers.getBuffer(cutout), seg, light, overlay,
					time + i * 17, 0.12, 0.9f, 0.35f);
		}

		// The reaching / searching probe.
		if (entity.isTendrilActive() && entity.clientTip != null && entity.prevClientTip != null) {
			Vec3d tip = entity.prevClientTip.lerp(entity.clientTip, tickDelta);
			List<Vec3d> controls = new ArrayList<>();
			controls.add(core);
			controls.addAll(entity.clientAnchors);
			controls.add(tip);
			renderProbe(entity, controls, base, matrices, vertexConsumers, cutout, light, overlay, time);
		}

		seg.visible = false;
	}

	/**
	 * The probe: a smooth, ribbed mechanical cable following the body's bends (Catmull-Rom spline),
	 * ending in a head with a glowing lens and three petals that breathe while searching and flare
	 * open when it finds you.
	 */
	private void renderProbe(SpindleEntity entity, List<Vec3d> controls, Vec3d base, MatrixStack matrices,
							 VertexConsumerProvider vcp, RenderLayer cutout, int light, int overlay, float time) {
		SpindleModel model = this.getModel();
		boolean searching = entity.isTendrilSearching();
		boolean alert = entity.isTendrilAlert();
		List<Vec3d> pts = spline(controls, 0.3);
		if (pts.size() < 2) return;

		// Tiny mechanical tremor while searching; more whip when reaching.
		double amp = alert ? 0.05 : (searching ? 0.035 : 0.18);
		int n = pts.size();
		for (int i = 1; i < n - 1; i++) {
			double u = i / (double) (n - 1);
			double env = Math.sin(Math.PI * u);
			pts.set(i, pts.get(i).add(Math.sin(i * 0.9 + time * 0.5) * amp * env,
					Math.cos(i * 0.7 + time * 0.37) * amp * env, Math.sin(i * 1.3 - time * 0.41) * amp * env));
		}

		VertexConsumer vc = vcp.getBuffer(cutout);
		model.probeSeg.visible = model.probeRing.visible = model.probeHead.visible = model.probePetal.visible = true;
		double arc = 0, nextRing = 0.6;
		for (int i = 0; i < n - 1; i++) {
			Vec3d a = pts.get(i), b = pts.get(i + 1);
			float u = i / (float) (n - 1);
			drawSegment(a, b, base, matrices, vc, model.probeSeg, light, overlay, MathHelper.lerp(u, 1.35f, 0.75f), -1);
			arc += a.distanceTo(b);
			if (arc >= nextRing && i < n - 3) {
				nextRing += 0.75;
				drawPart(b, b.subtract(a), base, matrices, vc, model.probeRing, light, overlay, MathHelper.lerp(u, 1.2f, 0.8f), 0, 0);
			}
		}

		// Head, pointing where it looks (or along the cable when just reaching).
		Vec3d tip = pts.get(n - 1);
		Vec3d dir = searching || alert ? entity.getTendrilLook() : tip.subtract(pts.get(n - 2));
		if (dir.lengthSquared() < 1e-6) dir = tip.subtract(pts.get(n - 2));
		dir = dir.normalize();
		Vec3d headStart = tip.subtract(dir.multiply(0.12));
		drawPart(headStart, dir, base, matrices, vc, model.probeHead, light, overlay, 1f, 0, 0);

		float open = alert ? 1.05f : (searching ? 0.35f + 0.18f * MathHelper.sin(time * 0.12f) : 0.08f);
		for (int k = 0; k < 3; k++) {
			float roll = k * (float) (Math.PI * 2 / 3) + (searching ? time * 0.01f : 0f);
			drawPetal(headStart, dir, base, matrices, vc, model.probePetal, light, overlay, roll, open);
		}
		model.probeSeg.visible = model.probeRing.visible = model.probeHead.visible = model.probePetal.visible = false;

		// Lens (dark glass), then its glow and a soft halo in the emissive layer.
		model.probeLens.visible = true;
		Vec3d lensPos = headStart.add(dir.multiply(6.0 / 16.0));
		drawPart(lensPos, dir, base, matrices, vcp.getBuffer(cutout), model.probeLens, light, overlay, 1f, 0, 0);
		if (searching || alert) {
			float pulse = alert ? 0.85f + 0.15f * MathHelper.sin(time * 2.1f) : 0.6f + 0.25f * MathHelper.sin(time * 0.25f);
			VertexConsumer glow = vcp.getBuffer(RenderLayer.getEyes(SpindleCoreFeature.GLOW));
			drawPart(lensPos.add(dir.multiply(0.01)), dir, base, matrices, glow, model.probeLens, 0xF000F0, overlay, 1.05f, 0, colorGray(pulse));
			drawPart(lensPos.add(dir.multiply(0.02)), dir, base, matrices, glow, model.probeLens, 0xF000F0, overlay,
					alert ? 3.2f : 2.4f, 0, colorGray(pulse * 0.22f));
		}
		model.probeLens.visible = false;
	}

	/** Places a model part at {@code at}, its +Z axis pointing along {@code dir}. */
	private static void drawPart(Vec3d at, Vec3d dir, Vec3d base, MatrixStack matrices, VertexConsumer vc, ModelPart part,
								 int light, int overlay, float scale, float roll, int color) {
		matrices.push();
		matrices.translate(at.x - base.x, at.y - base.y, at.z - base.z);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) Math.atan2(dir.x, dir.z)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotation((float) -Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z))));
		if (roll != 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotation(roll));
		matrices.scale(scale, scale, scale);
		part.render(matrices, vc, light, overlay, color == 0 ? -1 : color);
		matrices.pop();
	}

	private static void drawPetal(Vec3d headStart, Vec3d dir, Vec3d base, MatrixStack matrices, VertexConsumer vc,
								  ModelPart petal, int light, int overlay, float roll, float open) {
		matrices.push();
		matrices.translate(headStart.x - base.x, headStart.y - base.y, headStart.z - base.z);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) Math.atan2(dir.x, dir.z)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotation((float) -Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z))));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotation(roll));
		matrices.translate(0, 2.3 / 16.0, 4.5 / 16.0);                      // on the rim, near the front
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(-open));        // flare outward
		petal.render(matrices, vc, light, overlay);
		matrices.pop();
	}

	/** Uniform Catmull-Rom spline through the control points, sampled roughly every {@code step} blocks. */
	private static List<Vec3d> spline(List<Vec3d> c, double step) {
		List<Vec3d> out = new ArrayList<>();
		int m = c.size();
		if (m == 2) {
			double len = c.get(0).distanceTo(c.get(1));
			int k = Math.max(2, (int) Math.ceil(len / step));
			for (int j = 0; j <= k; j++) out.add(c.get(0).lerp(c.get(1), j / (double) k));
			return out;
		}
		for (int i = 0; i < m - 1; i++) {
			Vec3d p0 = c.get(Math.max(0, i - 1)), p1 = c.get(i), p2 = c.get(i + 1), p3 = c.get(Math.min(m - 1, i + 2));
			int k = Math.max(2, (int) Math.ceil(p1.distanceTo(p2) / step));
			for (int j = (i == 0 ? 0 : 1); j <= k; j++) {
				double t = j / (double) k, t2 = t * t, t3 = t2 * t;
				out.add(p1.multiply(2)
						.add(p2.subtract(p0).multiply(t))
						.add(p0.multiply(2).subtract(p1.multiply(5)).add(p2.multiply(4)).subtract(p3).multiply(t2))
						.add(p1.multiply(3).subtract(p0).subtract(p2.multiply(3)).add(p3).multiply(t3))
						.multiply(0.5));
			}
			if (out.size() > 160) break;
		}
		return out;
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

	private static void drawSegment(Vec3d a, Vec3d b, Vec3d base, MatrixStack matrices, VertexConsumer vc, ModelPart seg,
									int light, int overlay, float thickness, int color) {
		Vec3d d = b.subtract(a);
		double len = d.length();
		if (len < 1e-4) return;
		matrices.push();
		matrices.translate(a.x - base.x, a.y - base.y, a.z - base.z);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) Math.atan2(d.x, d.z)));
		matrices.multiply(RotationAxis.POSITIVE_X.rotation((float) -Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
		matrices.scale(thickness, thickness, (float) len);
		seg.render(matrices, vc, light, overlay, color);
		matrices.pop();
	}

	private static int colorGray(float v) {
		int c = MathHelper.clamp((int) (v * 255f), 0, 255);
		return 0xFF000000 | (c << 16) | (c << 8) | c;
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
