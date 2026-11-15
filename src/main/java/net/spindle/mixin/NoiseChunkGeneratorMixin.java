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
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Right after a chunk's base terrain is made: Far Lands past 1024, and the Hivemind where it is. */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
	@Shadow
	public abstract RegistryEntry<ChunkGeneratorSettings> getSettings();

	/**
	 * Hooks the finished noise step (after vanilla has unlocked the chunk's sections), so our writes
	 * don't collide with vanilla's section locks.
	 */
	@Inject(method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;)Ljava/util/concurrent/CompletableFuture;",
			at = @At("RETURN"), cancellable = true)
	private void spindle$farLands(Blender blender, NoiseConfig noiseConfig, StructureAccessor structures, Chunk chunk,
								  CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
		ChunkGeneratorSettings settings = getSettings().value();
		BlockState stone = settings.defaultBlock();
		if (!stone.isOf(Blocks.STONE)) return; // overworld-style terrain only (not the Nether, the End, or flat worlds)
		if (!FarLands.touches(chunk.getPos())) return;
		int seaLevel = settings.seaLevel();
		cir.setReturnValue(cir.getReturnValue().thenApply(c -> {
			long seed = Hivemind.seedFrom(noiseConfig);
			FarLands.of(seed).apply(c, seaLevel, stone, noiseConfig.getMultiNoiseSampler());
			Hivemind.of(seed).apply(c);
			return c;
		}));
	}
}
