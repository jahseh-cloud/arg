package net.spindle.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.spindle.Spindle;
import net.spindle.entity.BroodNodeEntity;
import net.spindle.entity.BroodmotherEntity;
import net.spindle.entity.SpindleLegState;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the Broodmother entirely procedurally: a churning clump of black masses, tentacles running to
 * every wall of the chamber it's in, jointed legs bursting out of the floor, cables to its nodes, and
 * the tentacle it attacks with.
 */
public class BroodmotherRenderer extends EntityRenderer<BroodmotherEntity> {
	public static final EntityModelLayer LAYER = new EntityModelLayer(Spindle.id("broodmother"), "main");
	private static final Identifier TEXTURE = Spindle.id("textures/entity/spindle.png");

	private final ModelPart mass, seg, leg;

	public BroodmotherRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		ModelPart root = ctx.getPart(LAYER);
		mass = root.getChild("mass");
		seg = root.getChild("seg");
		leg = root.getChild("leg");
		this.shadowRadius = 0f;
	}

	public static TexturedModelData getTexturedModelData() {
		ModelData data = new ModelData();
		ModelPartData r = data.getRoot();
		r.addChild("mass", ModelPartBuilder.create().uv(136, 0).cuboid(-5f, -3.5f, -5f, 10f, 7f, 10f), ModelTransform.NONE);
		r.addChild("seg", ModelPartBuilder.create().uv(200, 24).cuboid(-1f, -1f, 0f, 2f, 2f, 16f), ModelTransform.NONE);
		r.addChild("leg", ModelPartBuilder.create().uv(0, 64).cuboid(-2f, -2f, 0f, 4f, 4f, 16f), ModelTransform.NONE);
		return TexturedModelData.of(data, 256, 512);
	}

	@Override
	public Identifier getTexture(BroodmotherEntity entity) {
		return TEXTURE;
	}

	@Override
	public boolean shouldRender(BroodmotherEntity entity, net.minecraft.client.render.Frustum frustum, double x, double y, double z) {
		return entity.squaredDistanceTo(x, y, z) < 200 * 200;
	}

	// ---------------------------------------------------------------- where the walls and floor are

	private static final class Anchors {
		final List<Vec3d> walls = new ArrayList<>();
		final List<Vec3d> floor = new ArrayList<>();
		int builtAt = -1000;
	}

	private final java.util.Map<Integer, Anchors> anchors = new java.util.HashMap<>();

	private Anchors anchorsFor(BroodmotherEntity e) {
		Anchors a = anchors.computeIfAbsent(e.getId(), k -> new Anchors());
		if (e.clientAge - a.builtAt < 200 && !a.walls.isEmpty()) return a;
		a.builtAt = e.clientAge;
		a.walls.clear();
		a.floor.clear();
		World world = e.getWorld();
		Vec3d c = e.getMassCenter();
		for (int i = 0; i < 120; i++) {
			Vec3d dir = BroodmotherEntity.fibonacci(i, 120);
			BlockHitResult hit = world.raycast(new RaycastContext(c, c.add(dir.multiply(BroodmotherEntity.ARENA)),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, e));
			if (hit.getType() != HitResult.Type.BLOCK) continue;
			Vec3d p = hit.getPos();
			if (dir.y < -0.55) {
				if (a.floor.size() < 22) a.floor.add(p);
			} else if (a.walls.size() < 44) {
				a.walls.add(p);
			}
		}
		if (anchors.size() > 8) anchors.keySet().removeIf(id -> world.getEntityById(id) == null);
		return a;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void render(BroodmotherEntity e, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
		Vec3d base = e.getLerpedPos(tickDelta);
		float time = e.clientAge + tickDelta;
		int dying = e.getDyingTicks();
		float die = MathHelper.clamp(dying / 140f, 0f, 1f);
		Vec3d c = e.getMassCenter().subtract(e.getPos()).add(base);
		if (dying > 0) c = c.add((SpindleCoreFeature.noise((int) time, 3) - 0.5) * die, (SpindleCoreFeature.noise((int) time, 4) - 0.5) * die,
				(SpindleCoreFeature.noise((int) time, 5) - 0.5) * die);
		int overlay = OverlayTexture.getUv(0f, e.hurtTime > 0);
		RenderLayer black = RenderLayer.getEntityCutoutNoCull(TEXTURE);
		Anchors an = anchorsFor(e);
		boolean exposed = e.isExposed();

		mass.visible = seg.visible = leg.visible = true;

		// Body: a clump of slowly churning masses.
		List<float[]> lumps = lumps(time, die);
		VertexConsumer vc = vcp.getBuffer(black);
		for (float[] l : lumps) drawLump(matrices, vc, c, base, l, light, overlay, -1);

		// Tentacles to the walls.
		vc = vcp.getBuffer(black);
		for (int i = 0; i < an.walls.size(); i++) {
			Vec3d w = an.walls.get(i);
			Vec3d dir = w.subtract(c).normalize();
			Vec3d start = c.add(dir.multiply(3.2));
			double len = start.distanceTo(w);
			Vec3d mid = start.lerp(w, 0.5).add(0, -len * 0.12 - 1.5, 0)
					.add(Math.sin(time * 0.03 + i) * 0.8, Math.sin(time * 0.041 + i * 2) * 0.6, Math.cos(time * 0.037 + i) * 0.8);
			if (dying > 0) mid = mid.add(0, -die * 6, 0);
			List<Vec3d> pts = SpindleRenderer.spline(List.of(start, mid, w), 0.6);
			float thick = 5.5f - (i % 4) * 0.8f;
			drawRope(matrices, vc, base, pts, seg, light, overlay, thick, thick * 0.45f, time + i * 13, 0.15);
		}

		// Legs bursting out of the floor, twitching.
		for (int i = 0; i < an.floor.size(); i++) {
			Vec3d f = an.floor.get(i);
			Vec3d toC = new Vec3d(c.x - f.x, 0, c.z - f.z);
			if (toC.lengthSquared() < 1e-4) continue;
			toC = toC.normalize();
			float twitch = SpindleCoreFeature.noise((int) (time / 6f), i * 41 + e.getId()) < 0.15f ? 1f : 0f;
			double reachOut = 3.5 + Math.sin(time * 0.05 + i) * 0.6 + twitch * 1.2;
			Vec3d tip = f.add(toC.multiply(reachOut)).add(0, 6.5 + Math.sin(time * 0.07 + i * 1.7) * 0.8 + twitch * 1.5 - die * 4, 0);
			Vec3d side = new Vec3d(-toC.z, 0, toC.x);
			Vec3d knee = SpindleLegState.solveKnee(f, tip, 5.5, 6.0, new Vec3d(0, 1, 0), side, 0.6 * (i % 2 == 0 ? 1 : -1));
			SpindleRenderer.drawSegment(f, knee, base, matrices, vc, leg, light, overlay, 2.4f, -1);
			SpindleRenderer.drawSegment(knee, tip, base, matrices, vc, leg, light, overlay, 1.8f, -1);
			SpindleRenderer.drawSegment(tip, tip.add(toC.multiply(1.2)).add(0, -0.8, 0), base, matrices, vc, seg, light, overlay, 1.2f, -1);
		}

		// Cables to its nodes.
		List<BroodNodeEntity> nodes = e.getWorld().getEntitiesByClass(BroodNodeEntity.class, new Box(c, c).expand(BroodmotherEntity.ARENA), n -> n.isAlive());
		for (BroodNodeEntity n : nodes) {
			Vec3d np = n.getLerpedPos(tickDelta).add(0, 0.7, 0);
			Vec3d start = c.add(np.subtract(c).normalize().multiply(3.0));
			Vec3d mid = start.lerp(np, 0.5).add(0, -2.5, 0);
			drawRope(matrices, vc, base, SpindleRenderer.spline(List.of(start, mid, np), 0.6), seg, light, overlay, 2.2f, 1.6f, time, 0.1);
		}

		// The attacking tentacle.
		int attack = e.getAttack();
		if (attack != BroodmotherEntity.ATTACK_NONE && attack != BroodmotherEntity.ATTACK_PULSE) {
			Vec3d target = e.getAttackTarget();
			Vec3d start = c.add(0, -2.5, 0);
			int t = e.getAttackTicks();
			Vec3d tip;
			if (attack == BroodmotherEntity.ATTACK_SLAM_WINDUP) {
				tip = target.add(0, 7 + Math.sin(time * 0.6) * 0.5, 0);
			} else if (attack == BroodmotherEntity.ATTACK_GRAB_REACH) {
				tip = start.lerp(target, MathHelper.clamp(1f - t / 18f, 0f, 1f));
			} else {
				tip = target;
			}
			Vec3d mid = start.lerp(tip, 0.5).add(0, attack == BroodmotherEntity.ATTACK_SLAM_WINDUP ? 6 : 2, 0);
			drawRope(matrices, vc, base, SpindleRenderer.spline(List.of(start, mid, tip), 0.5), seg, light, overlay, 7f, 3.5f, time, 0.25);
		}

		// The corruption: glows faintly, blazes when its core is exposed, and tears like a bad signal.
		float glow = exposed ? 0.75f + 0.25f * MathHelper.sin(time * 0.5f) : 0.35f;
		float flick = SpindleCoreFeature.noise((int) time, e.getId() * 7);
		if (flick < 0.05f) glow *= 0.15f;
		if (dying > 0) glow = (SpindleCoreFeature.noise((int) time, 9) < 0.5f ? 1f : 0.1f) * (1f - die * 0.6f);
		int gcol = exposed ? colorRGB(1f * glow, 0.25f * glow, 0.25f * glow) : SpindleRenderer.colorGray(glow);
		int slot = (int) (time / 3f);
		Identifier glitch = SpindleCoreFeature.GLITCH[(int) (SpindleCoreFeature.noise(slot, e.getId()) * 3)];
		VertexConsumer glowVc = vcp.getBuffer(RenderLayer.getEyes(glitch));
		for (float[] l : lumps) drawLump(matrices, glowVc, c, base, l, 0xF000F0, OverlayTexture.DEFAULT_UV, gcol);

		float tearChance = dying > 0 ? 0.5f : (exposed ? 0.12f : 0.04f);
		if (SpindleCoreFeature.noise((int) (time / 2f), e.getId() * 13) < tearChance) {
			float shift = 0.25f + 0.5f * SpindleCoreFeature.noise((int) (time / 2f), 77);
			VertexConsumer gv = vcp.getBuffer(RenderLayer.getEntityTranslucentEmissive(SpindleCoreFeature.GHOST));
			for (float[] l : lumps) {
				drawLump(matrices, gv, c.add(shift, 0, 0), base, l, 0xF000F0, OverlayTexture.DEFAULT_UV, 0x50FF1040);
				drawLump(matrices, gv, c.add(-shift, 0, 0), base, l, 0xF000F0, OverlayTexture.DEFAULT_UV, 0x5010F0FF);
			}
		}

		mass.visible = seg.visible = leg.visible = false;
		super.render(e, yaw, tickDelta, matrices, vcp, light);
	}

	private static int colorRGB(float r, float g, float b) {
		return 0xFF000000 | (MathHelper.clamp((int) (r * 255), 0, 255) << 16) | (MathHelper.clamp((int) (g * 255), 0, 255) << 8)
				| MathHelper.clamp((int) (b * 255), 0, 255);
	}

	/** Each lump: offset x, y, z (blocks), scale, yaw, pitch. */
	private static List<float[]> lumps(float time, float die) {
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < 14; i++) {
			Vec3d d = BroodmotherEntity.fibonacci(i, 14);
			float r = i == 0 ? 0f : 2.2f + 0.4f * MathHelper.sin(time * 0.02f + i);
			float scale = (i == 0 ? 9f : 4.5f + 1.5f * MathHelper.sin(time * 0.03f + i * 2.1f)) * (1f - die * 0.5f);
			out.add(new float[]{(float) d.x * r, (float) d.y * r * 0.8f, (float) d.z * r, scale,
					time * 0.004f * (i % 2 == 0 ? 1 : -1) + i, MathHelper.sin(time * 0.01f + i) * 0.4f});
		}
		return out;
	}

	private void drawLump(MatrixStack matrices, VertexConsumer vc, Vec3d c, Vec3d base, float[] l, int light, int overlay, int color) {
		matrices.push();
		matrices.translate(c.x - base.x + l[0], c.y - base.y + l[1], c.z - base.z + l[2]);
		matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotation(l[4]));
		matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotation(l[5]));
		matrices.scale(l[3], l[3] * 0.8f, l[3]);
		mass.render(matrices, vc, light, overlay, color);
		matrices.pop();
	}

	/** A thick rope through the points, tapering, with a slow writhe. */
	private static void drawRope(MatrixStack matrices, VertexConsumer vc, Vec3d base, List<Vec3d> pts, ModelPart part, int light,
								 int overlay, float thickStart, float thickEnd, float time, double writhe) {
		int n = pts.size();
		for (int i = 0; i < n - 1; i++) {
			float u = i / (float) Math.max(1, n - 1);
			double env = Math.sin(Math.PI * u);
			Vec3d a = pts.get(i).add(Math.sin(i * 0.8 + time * 0.09) * writhe * env, Math.cos(i * 0.6 + time * 0.07) * writhe * env, 0);
			Vec3d b = pts.get(i + 1).add(Math.sin((i + 1) * 0.8 + time * 0.09) * writhe * env, Math.cos((i + 1) * 0.6 + time * 0.07) * writhe * env, 0);
			SpindleRenderer.drawSegment(a, b, base, matrices, vc, part, light, overlay, MathHelper.lerp(u, thickStart, thickEnd), -1);
		}
	}
}
