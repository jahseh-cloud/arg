package net.spindle.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.spindle.Spindle;

public class SpindleClient implements ClientModInitializer {
	public static final EntityModelLayer LAYER = new EntityModelLayer(Spindle.id("spindle"), "main");

	@Override
	public void onInitializeClient() {
		EntityModelLayerRegistry.registerModelLayer(LAYER, SpindleModel::getTexturedModelData);
		EntityRendererRegistry.register(Spindle.SPINDLE, SpindleRenderer::new);
	}
}
