package net.spindle;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.spindle.entity.SpindleEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Operator commands for testing:
 *   /spindle debug          toggle a readout of what the nearest Spindle is doing (action bar)
 *   /spindle hunt           the nearest Spindle hunts you right now
 *   /spindle calm           the nearest Spindle drops everything and wanders
 *   /spindle tension <0-100>  set how close the nearest Spindle is to hunting
 */
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
						}))
						.then(CommandManager.literal("hunt").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
							SpindleEntity s = nearest(ctx.getSource(), p);
							if (s == null) return 0;
							if (p.isCreative() || p.isSpectator()) {
								ctx.getSource().sendFeedback(() -> Text.literal("It ignores Creative and Spectator players. Switch to Survival first."), false);
								return 0;
							}
							s.calmDown();
							s.noticeTarget(p);
							s.startHunt();
							ctx.getSource().sendFeedback(() -> Text.literal("It's coming."), false);
							return 1;
						}))
						.then(CommandManager.literal("calm").executes(ctx -> {
							SpindleEntity s = nearest(ctx.getSource(), ctx.getSource().getPlayerOrThrow());
							if (s == null) return 0;
							s.calmDown();
							ctx.getSource().sendFeedback(() -> Text.literal("Calmed."), false);
							return 1;
						}))
						.then(CommandManager.literal("hivemind").executes(ctx -> {
							net.spindle.world.Hivemind hive = net.spindle.world.Hivemind.of(ctx.getSource().getServer().getOverworld());
							if (hive == null) return 0;
							net.minecraft.util.math.BlockPos c = hive.center;
							var state = net.spindle.world.SpindleWorldState.get(ctx.getSource().getServer());
							ctx.getSource().sendFeedback(() -> Text.literal("The Hivemind is centered at " + c.getX() + " " + c.getY() + " " + c.getZ()
									+ (state.broodmotherSlain ? " (the Broodmother is dead)" : "")), false);
							return 1;
						}))
						.then(CommandManager.literal("stalker").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
							SpindleEntity s = net.spindle.world.StalkerDirector.spawnFor(p.getServerWorld(), p);
							ctx.getSource().sendFeedback(() -> Text.literal(s == null ? "Nowhere hidden to put one." : "One is watching you now."), false);
							return s == null ? 0 : 1;
						}))
						.then(CommandManager.literal("expose").executes(ctx -> {
							int n = 0;
							for (net.spindle.entity.BroodmotherEntity m : ctx.getSource().getWorld().getEntitiesByType(Spindle.BROODMOTHER, e -> true)) {
								m.wake();
								m.debugExpose();
								n++;
							}
							int found = n;
							ctx.getSource().sendFeedback(() -> Text.literal(found == 0 ? "No Broodmother loaded." : "Nodes destroyed."), false);
							return n;
						}))
						.then(CommandManager.literal("tension")
								.then(CommandManager.argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
									SpindleEntity s = nearest(ctx.getSource(), ctx.getSource().getPlayerOrThrow());
									if (s == null) return 0;
									int pct = IntegerArgumentType.getInteger(ctx, "percent");
									s.setTensionFraction(pct / 100f);
									ctx.getSource().sendFeedback(() -> Text.literal("Tension set to " + pct + "%"), false);
									return 1;
								})))));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (WATCHERS.isEmpty() || server.getTicks() % 10 != 0) return;
			for (UUID id : WATCHERS) {
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
				if (p == null) continue;
				SpindleEntity s = nearest(p.getServerWorld(), p);
				if (s == null) {
					p.sendMessage(Text.literal("no spindle within 256").formatted(Formatting.GRAY), true);
				} else {
					p.sendMessage(Text.literal(s.debugLine(p)).formatted(Formatting.RED), true);
				}
			}
		});
	}

	@Nullable
	private static SpindleEntity nearest(ServerCommandSource source, ServerPlayerEntity player) {
		SpindleEntity s = nearest(player.getServerWorld(), player);
		if (s == null) source.sendFeedback(() -> Text.translatable("commands.spindle.none"), false);
		return s;
	}

	@Nullable
	private static SpindleEntity nearest(ServerWorld world, ServerPlayerEntity player) {
		SpindleEntity best = null;
		double bestD = 256 * 256;
		for (SpindleEntity s : world.getEntitiesByType(Spindle.SPINDLE, e -> e.isAlive())) {
			double d = s.squaredDistanceTo(player);
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return best;
	}

	private SpindleDebug() {
	}
}
