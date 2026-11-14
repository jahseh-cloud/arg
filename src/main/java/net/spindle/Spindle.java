package net.spindle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.spindle.entity.SpindleChunkLoader;
import net.spindle.entity.SpindleEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Spindle implements ModInitializer {
	public static final String MOD_ID = "spindle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final EntityType<SpindleEntity> SPINDLE = Registry.register(
			Registries.ENTITY_TYPE,
			id("spindle"),
			EntityType.Builder.create(SpindleEntity::new, SpawnGroup.MONSTER)
					.dimensions(SpindleEntity.STANDING_WIDTH, SpindleEntity.STANDING_HEIGHT)
					.eyeHeight(SpindleEntity.STANDING_HEIGHT - 0.6f)
					.maxTrackingRange(16) // chunks; it needs to be visible from far away while stalking
					.build("spindle"));

	public static final Item SPINDLE_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			id("spindle_spawn_egg"),
			new SpawnEggItem(SPINDLE, 0x1c1a1e, 0x7a1f14, new Item.Settings()));

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		SpindleSounds.init();
		SpindleNetworking.init();
		SpindleDebug.init();
		FabricDefaultAttributeRegistry.register(SPINDLE, SpindleEntity.createSpindleAttributes());
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(SPINDLE_SPAWN_EGG));
		// After a restart, reload wherever each Spindle was so it keeps going without anyone nearby.
		ServerWorldEvents.LOAD.register((server, world) -> SpindleChunkLoader.get(world).wakeAll(world));
		LOGGER.info("Something tall is walking around.");
	}
}
