package net.spindle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.spindle.entity.SpindleChunkLoader;
import net.spindle.entity.BroodNodeEntity;
import net.spindle.entity.BroodmotherEntity;
import net.spindle.entity.SpindleEntity;
import net.spindle.world.HiveEvents;
import net.spindle.world.StalkerDirector;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.spindle.selftest.SelfTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Spindle implements ModInitializer {
	public static final String MOD_ID = "spindle";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final EntityType<SpindleEntity> SPINDLE = Registry.register(
			Registries.ENTITY_TYPE,
			id("spindle"),
			EntityType.Builder.create(SpindleEntity::new, SpawnGroup.MONSTER)
					.dimensions(SpindleEntity.WIDTH, SpindleEntity.MAX_HEIGHT)
					.eyeHeight(SpindleEntity.MAX_HEIGHT - 0.6f)
					.makeFireImmune()
					.maxTrackingRange(16) // chunks; it needs to be visible from far away while stalking
					.build("spindle"));

	public static final EntityType<BroodmotherEntity> BROODMOTHER = Registry.register(
			Registries.ENTITY_TYPE,
			id("broodmother"),
			EntityType.Builder.create(BroodmotherEntity::new, SpawnGroup.MONSTER)
					.dimensions(9.0f, 8.0f)
					.makeFireImmune()
					.maxTrackingRange(16)
					.build("broodmother"));

	public static final EntityType<BroodNodeEntity> BROOD_NODE = Registry.register(
			Registries.ENTITY_TYPE,
			id("brood_node"),
			EntityType.Builder.create(BroodNodeEntity::new, SpawnGroup.MONSTER)
					.dimensions(1.4f, 1.4f)
					.makeFireImmune()
					.maxTrackingRange(10)
					.build("brood_node"));

	public static final Item SPINDLE_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			id("spindle_spawn_egg"),
			new SpawnEggItem(SPINDLE, 0x050507, 0xff00ff, new Item.Settings()));

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		SpindleSounds.init();
		SpindleBlocks.init();
		SpindleNetworking.init();
		SpindleDebug.init();
		FabricDefaultAttributeRegistry.register(SPINDLE, SpindleEntity.createSpindleAttributes());
		FabricDefaultAttributeRegistry.register(BROODMOTHER, BroodmotherEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(BROOD_NODE, BroodNodeEntity.createAttributes());
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(SPINDLE_SPAWN_EGG));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(SpindleBlocks.HIVEMASS_ITEM));

		// After a restart, reload wherever each Spindle was so it keeps going without anyone nearby.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				net.spindle.entity.SpindleChunkLoader.releaseAll());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> net.spindle.entity.SpindleChunkLoader.serverStarting());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server ->
				net.spindle.world.FarLands.plains = server.getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME)
						.entryOf(net.minecraft.world.biome.BiomeKeys.PLAINS));
		ServerWorldEvents.LOAD.register((server, world) -> SpindleChunkLoader.get(world).wakeAll(world));
		// A Spindle keeps its surroundings loaded from the moment it exists.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof SpindleEntity s && !s.isHiveBound()) SpindleChunkLoader.keepLoaded(world, entity.getChunkPos());
			HiveEvents.onEntityLoad(entity, world);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			StalkerDirector.tick(server);
			HiveEvents.tick(server);
		});
		// Breaking blocks is loud: a Spindle hunting you nearby hears it.
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerWorld serverWorld) || player.isCreative() || player.isSpectator()) return;
			for (SpindleEntity spindle : serverWorld.getEntitiesByType(SPINDLE, s -> s.squaredDistanceTo(player) < 24 * 24)) {
				spindle.hearNoise(player);
			}
		});

		SelfTest.init();
		LOGGER.info("Something tall is walking around.");
	}
}
