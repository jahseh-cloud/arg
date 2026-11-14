package net.spindle;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.spindle.entity.SpindleEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** /spindle debug : shows what the nearest Spindle is thinking in your action bar. */
public final class SpindleDebug {
	private static final Set<UUID> WATCHERS = new HashSet<>();

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("spindle")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("debug").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
							boolean on = WATCHERS.add(p.getUuid());
							if (!on) WATCHERS.remove(p.getUuid());
							ctx.getSource().sendFeedback(() -> Text.literal("Spindle debug " + (on ? "on" : "off")), false);
							return 1;
						}))));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (WATCHERS.isEmpty() || server.getTicks() % 10 != 0) return;
			for (UUID id : WATCHERS) {
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
				if (p == null) continue;
				ServerWorld world = p.getServerWorld();
				SpindleEntity nearest = null;
				double best = 256 * 256;
				for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> true)) {
					double d = s.squaredDistanceTo(p);
					if (d < best) {
						best = d;
						nearest = s;
					}
				}
				if (nearest == null) {
					p.sendMessage(Text.literal("no spindle within 256").formatted(Formatting.GRAY), true);
					continue;
				}
				p.sendMessage(Text.literal(nearest.debugLine(p)).formatted(Formatting.RED), true);
			}
		});
	}

	private SpindleDebug() {
	}
}
