package net.spindle.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.spindle.Spindle;
import net.spindle.entity.BroodNodeEntity;

/** A pulsing knot of corrupted matter. It glows so you can find it in the dark. */
public class BroodNodeRenderer extends EntityRenderer<BroodNodeEntity> {
	private static final Identifier TEXTURE = Spindle.id("textures/entity/spindle.png");
	private final ModelPart mass;

	public BroodNodeRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
		mass = ctx.getPart(BroodmotherRenderer.LAYER).getChild("mass");
		this.shadowRadius = 0f;
	}

	@Override
	public Identifier getTexture(BroodNodeEntity entity) {
		return TEXTURE;
	}

	@Override
	public void render(BroodNodeEntity e, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
		float time = e.age + tickDelta;
		float pulse = 0.85f + 0.15f * MathHelper.sin(time * 0.25f);
		int overlay = OverlayTexture.getUv(0f, e.hurtTime > 0);
		Identifier glitch = SpindleCoreFeature.GLITCH[(int) (SpindleCoreFeature.noise((int) (time / 3f), e.getId()) * 3)];
		mass.visible = true;
		for (int i = 0; i < 3; i++) {
			matrices.push();
			matrices.translate(0, 0.7 + 0.15 * MathHelper.sin(time * 0.1f + i), 0);
			matrices.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.03f * (i + 1) + i));
			matrices.multiply(RotationAxis.POSITIVE_X.rotation(i * 0.9f));
			float s = (1.4f - i * 0.3f) * pulse;
			matrices.scale(s, s, s);
			mass.render(matrices, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)), light, overlay, -1);
			int g = MathHelper.clamp((int) (255 * (0.7f + 0.3f * MathHelper.sin(time * 0.4f + i))), 0, 255);
			mass.render(matrices, vcp.getBuffer(RenderLayer.getEyes(glitch)), 0xF000F0, OverlayTexture.DEFAULT_UV,
					0xFF000000 | (g << 16) | (g / 3 << 8) | g);
			matrices.pop();
		}
		mass.visible = false;
		super.render(e, yaw, tickDelta, matrices, vcp, light);
	}
}
