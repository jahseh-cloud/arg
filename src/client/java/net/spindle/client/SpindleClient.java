package net.spindle.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.Entity;
import net.spindle.SpindleNetworking;
import net.spindle.entity.SpindleEntity;
import net.spindle.Spindle;

public class SpindleClient implements ClientModInitializer {
	public static final EntityModelLayer LAYER = new EntityModelLayer(Spindle.id("spindle"), "main");

	@Override
	public void onInitializeClient() {
		EntityModelLayerRegistry.registerModelLayer(LAYER, SpindleModel::getTexturedModelData);
		EntityRendererRegistry.register(Spindle.SPINDLE, SpindleRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(SpindleNetworking.TendrilTrailPayload.ID, (payload, context) -> {
			if (context.client().world == null) return;
			Entity e = context.client().world.getEntityById(payload.entityId());
			if (e instanceof SpindleEntity spindle) spindle.clientAnchors = payload.anchors();
		});
	}
}
