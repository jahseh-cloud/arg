package net.spindle.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;

/**
 * The corruption. A glowing layer of dead pixels, scanlines and missing-texture squares that swaps
 * between variants every few frames (so it crawls), flickers out at random, and now and then the whole
 * creature splits into red/cyan ghost copies like a broken video signal.
 */
public class SpindleCoreFeature extends FeatureRenderer<SpindleEntity, SpindleModel> {
	static final Identifier[] GLITCH = {
			Spindle.id("textures/entity/spindle_glitch_0.png"),
			Spindle.id("textures/entity/spindle_glitch_1.png"),
			Spindle.id("textures/entity/spindle_glitch_2.png")
	};
	static final Identifier GHOST = Spindle.id("textures/entity/spindle_ghost.png");

	public SpindleCoreFeature(FeatureRendererContext<SpindleEntity, SpindleModel> context) {
		super(context);
	}

	/** Deterministic noise in [0, 1) for an integer time slot and entity, so every viewer sees the same glitch. */
	static float noise(int slot, int salt) {
		int h = slot * 0x27d4eb2d ^ salt * 0x165667b1;
		h ^= h >>> 15;
		h *= 0x2c1b3c6d;
		h ^= h >>> 12;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	/** Which glitch texture is showing right now. */
	static Identifier currentGlitch(SpindleEntity entity, float animationProgress) {
		int slot = (int) (animationProgress / 3f);
		return GLITCH[(int) (noise(slot, entity.getId()) * GLITCH.length)];
	}

	/** 0..1 strength of the "signal tearing" effect right now (mostly 0). */
	static float tear(SpindleEntity entity, float animationProgress) {
		int slot = (int) (animationProgress / 2f);
		float chance = switch (entity.getSyncedMode()) {
			case HUNT -> 0.14f;
			case STALK -> 0.05f;
			default -> 0.08f;
		};
		if (entity.hurtTime > 0) chance = 0.6f;
		return noise(slot, entity.getId() * 31 + 7) < chance ? 0.5f + noise(slot, entity.getId() * 17) * 0.5f : 0f;
	}

	@Override
	public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, SpindleEntity entity,
					   float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
		float g = MathHelper.lerp(tickDelta, entity.prevGlow, entity.glow);
		float intensity = 0.55f + 0.45f * g;
		float flicker = noise((int) animationProgress, entity.getId() * 3 + 1);
		if (flicker < 0.07f) intensity *= 0.1f;          // drops out
		else if (flicker > 0.95f) intensity = 1f;         // spikes
		if (entity.isDead()) intensity *= 1f - Math.min(1f, entity.deathTime / 20f);
		int c = MathHelper.clamp((int) (intensity * 255f), 0, 255);
		int color = 0xFF000000 | (c << 16) | (c << 8) | c;
		this.getContextModel().render(matrices, vertexConsumers.getBuffer(RenderLayer.getEyes(currentGlitch(entity, animationProgress))),
				0xF000F0, OverlayTexture.DEFAULT_UV, color);

		float tear = tear(entity, animationProgress);
		if (tear > 0f) {
			int slot = (int) (animationProgress / 2f);
			float shift = (0.05f + 0.12f * tear) * (noise(slot, entity.getId() + 99) < 0.5f ? 1f : -1f);
			float lift = (noise(slot, entity.getId() + 5) - 0.5f) * 0.06f;
			int alpha = (int) (70 + 80 * tear);
			ghost(matrices, vertexConsumers, shift, lift, (alpha << 24) | 0xFF1040);
			ghost(matrices, vertexConsumers, -shift, -lift, (alpha << 24) | 0x10F0FF);
		}
	}

	private void ghost(MatrixStack matrices, VertexConsumerProvider vertexConsumers, float dx, float dy, int argb) {
		matrices.push();
		matrices.translate(dx, dy, 0f);
		this.getContextModel().render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(GHOST)),
				0xF000F0, OverlayTexture.DEFAULT_UV, argb);
		matrices.pop();
	}
}
