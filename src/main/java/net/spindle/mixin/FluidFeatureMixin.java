package net.spindle.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.feature.LakeFeature;
import net.minecraft.world.gen.feature.SpringFeature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import net.spindle.world.FarLands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No springs or lakes in the Far Lands: they'd pour out of every hole in the walls. */
@Mixin({SpringFeature.class, LakeFeature.class})
public abstract class FluidFeatureMixin {
	@Inject(method = "generate", at = @At("HEAD"), cancellable = true)
	private void spindle$dry(FeatureContext<?> context, CallbackInfoReturnable<Boolean> cir) {
		BlockPos p = context.getOrigin();
		if (FarLands.isFarLands(p.getX(), p.getZ())) cir.setReturnValue(false);
	}
}
