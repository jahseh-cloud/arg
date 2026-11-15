package net.spindle.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.spindle.world.FarLands;
import net.spindle.world.Hivemind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Right after a chunk's base terrain is made: Far Lands past 1024, and the Hivemind where it is. */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
	@Shadow
	public abstract RegistryEntry<ChunkGeneratorSettings> getSettings();

	@Inject(method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
			at = @At("RETURN"))
	private void spindle$farLands(Blender blender, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk,
								  int minimumCellY, int cellHeight, CallbackInfoReturnable<Chunk> cir) {
		ChunkGeneratorSettings settings = getSettings().value();
		BlockState stone = settings.defaultBlock();
		if (!stone.isOf(Blocks.STONE)) return; // overworld-style terrain only (not the Nether, the End, or flat worlds)
		if (!FarLands.touches(chunk.getPos())) return;
		long seed = Hivemind.seedFrom(noiseConfig);
		FarLands.of(seed).apply(chunk, settings.seaLevel(), stone);
		Hivemind.of(seed).apply(chunk);
	}
}
