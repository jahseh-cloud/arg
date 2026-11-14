package net.spindle.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;

/** The core's glow. Bright when it wanders, dim when it stalks, almost gone when it hunts. */
public class SpindleCoreFeature extends FeatureRenderer<SpindleEntity, SpindleModel> {
	private static final Identifier GLOW = Spindle.id("textures/entity/spindle_glow.png");

	public SpindleCoreFeature(FeatureRendererContext<SpindleEntity, SpindleModel> context) {
		super(context);
	}

	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, SpindleEntity entity,
					   float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
		float g = MathHelper.lerp(tickDelta, entity.prevGlow, entity.glow);
		g *= 0.8f + 0.2f * MathHelper.sin(animationProgress * 0.13f) * MathHelper.sin(animationProgress * 0.031f);
		int c = MathHelper.clamp((int) (g * 255f), 0, 255);
		int color = 0xFF000000 | (c << 16) | (c << 8) | c;
		VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEyes(GLOW));
		this.getContextModel().render(matrices, vc, 0xF000F0, OverlayTexture.DEFAULT_UV, color);
	}
}
