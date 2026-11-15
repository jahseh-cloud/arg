package net.spindle.mixin;

import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.math.ChunkPos;
import net.spindle.selftest.SelfTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Self-test only: who asks for chunks at the very end of a run. */
@Mixin(ChunkTicketManager.class)
public abstract class TicketWatchMixin {
	@Inject(method = "addTicketWithLevel(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V", at = @At("HEAD"))
	private void spindle$watch(ChunkTicketType<?> type, ChunkPos pos, int level, Object argument, CallbackInfo ci) {
		if (SelfTest.watchTickets && type == ChunkTicketType.UNKNOWN) SelfTest.ticketAdded(pos);
	}
}
