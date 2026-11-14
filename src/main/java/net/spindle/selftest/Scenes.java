package net.spindle.selftest;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.spindle.Spindle;
import net.spindle.entity.SpindleEntity;

/**
 * Little test arenas, shared by the automated tests and the screenshot harness.
 * Every builder takes an origin "o" at ground level (the first air block above the ground)
 * and builds toward +X (east). Coordinates in comments are relative to the origin.
 */
public final class Scenes {
	public static final BlockState AIR = Blocks.AIR.getDefaultState();
	public static final BlockState STONE = Blocks.STONE.getDefaultState();

	public static void cmd(MinecraftServer server, String command) {
		server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command);
	}

	/** Loads (generating if needed) every chunk touching the area, right now. */
	public static void prepare(ServerWorld world, int x1, int z1, int x2, int z2) {
		for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
			for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
				world.getChunk(cx, cz);
			}
		}
	}

	public static int groundY(ServerWorld world, int x, int z) {
		world.getChunk(x >> 4, z >> 4);
		return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	public static void fill(ServerWorld world, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					world.setBlockState(m.set(o.getX() + x, o.getY() + y, o.getZ() + z), state);
				}
			}
		}
	}

	public static void set(ServerWorld world, BlockPos o, int x, int y, int z, BlockState state) {
		world.setBlockState(o.add(x, y, z), state);
	}

	/** A solid block of material with the inside hollowed out (the bounds are the outer shell). */
	public static void box(ServerWorld world, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, BlockState wall) {
		fill(world, o, x1, y1, z1, x2, y2, z2, wall);
		fill(world, o, x1 + 1, y1, z1 + 1, x2 - 1, y2 - 1, z2 - 1, AIR);
	}

	public static SpindleEntity spawnSpindle(ServerWorld world, double x, double y, double z, float yaw) {
		SpindleEntity s = Spindle.SPINDLE.create(world);
		if (s == null) throw new IllegalStateException("could not create a Spindle");
		s.refreshPositionAndAngles(x, y, z, yaw, 0f);
		world.spawnEntity(s);
		return s;
	}

	// ---------------------------------------------------------------- arenas

	/** A 1 wide, 2 high tunnel through a stone block: mouth at x=10, dead end at x=40, along z=0. */
	public static void tunnel(ServerWorld world, BlockPos o, BlockState wall) {
		fill(world, o, 10, 0, -2, 42, 3, 2, wall);
		fill(world, o, 10, 0, 0, 40, 1, 0, AIR);
	}

	/** Wooden house, 7x7 inside and 4 high, with a closed oak door in the west wall at (20, 0, 0). */
	public static void doorHouse(ServerWorld world, BlockPos o) {
		box(world, o, 20, 0, -4, 28, 4, 4, Blocks.OAK_PLANKS.getDefaultState());
		BlockPos door = o.add(20, 0, 0);
		world.setBlockState(door, AIR);
		world.setBlockState(door.up(), AIR);
		MinecraftServer server = world.getServer();
		cmd(server, String.format("setblock %d %d %d minecraft:oak_door[half=lower,facing=east]", door.getX(), door.getY(), door.getZ()));
		cmd(server, String.format("setblock %d %d %d minecraft:oak_door[half=upper,facing=east]", door.getX(), door.getY() + 1, door.getZ()));
	}

	/** Obsidian bunker, 3x3 inside and 3 high, sealed except for one glass block at eye level at (20, 1, 0). */
	public static void bunker(ServerWorld world, BlockPos o) {
		box(world, o, 20, 0, -2, 24, 3, 2, Blocks.OBSIDIAN.getDefaultState());
		set(world, o, 20, 1, 0, Blocks.GLASS.getDefaultState());
	}

	/** Dirt hut, 3x3 inside and 3 high, no openings at all. */
	public static void dirtHut(ServerWorld world, BlockPos o) {
		box(world, o, 20, 0, -2, 24, 3, 2, Blocks.DIRT.getDefaultState());
	}

	/**
	 * Stone house, 9x7 inside and 4 high, open doorway at (20, 0..1, 0), and an inner wall at x=25
	 * (gap at z=2..3) so the east half can't be seen from the doorway.
	 */
	public static void partitionHouse(ServerWorld world, BlockPos o, BlockState wall) {
		box(world, o, 20, 0, -4, 30, 4, 4, wall);
		fill(world, o, 20, 0, 0, 20, 1, 0, AIR);
		fill(world, o, 25, 0, -3, 25, 3, 1, wall);
	}

	/** Scattered stone pillars (3x3, 7 tall) to hide behind, around (40, 0, 0). */
	public static void pillars(ServerWorld world, BlockPos o) {
		int[][] at = {{14, -18}, {22, 16}, {30, -30}, {34, 26}, {48, -22}, {56, 18}, {62, -8}, {70, 6}, {40, -44}, {44, 40}, {8, 6}, {76, -26}};
		for (int[] p : at) fill(world, o, p[0] - 1, 0, p[1] - 1, p[0] + 1, 6, p[1] + 1, STONE);
	}

	/** A patch of glowstone set into the ground around the origin, so whatever stands there is lit. */
	public static void glowPad(ServerWorld world, BlockPos o) {
		fill(world, o, -4, -1, -4, 4, -1, 4, Blocks.GLOWSTONE.getDefaultState());
	}

	/** A dark covered hall: 41 long (x 0..40), 5 wide (z -2..2), with the ceiling "height" blocks up. */
	public static void hall(ServerWorld world, BlockPos o, int height, BlockState wall) {
		fill(world, o, -1, 0, -3, 41, height, 3, wall);
		fill(world, o, 0, 0, -2, 40, height - 1, 2, AIR);
	}

	/** A corridor like a mansion hallway: 3 wide (z -1..1), "height" high, x 0..30, open at the west end. */
	public static void corridor(ServerWorld world, BlockPos o, int height, BlockState wall) {
		fill(world, o, 0, 0, -2, 31, height, 2, wall);
		fill(world, o, 0, 0, -1, 30, height - 1, 1, AIR);
	}

	/** Rough ground: a staircase of 1-block steps rising east, then a 3-block ledge. */
	public static void steps(ServerWorld world, BlockPos o) {
		for (int i = 0; i < 5; i++) fill(world, o, 6 + i * 2, 0, -6, 7 + i * 2, i, 6, STONE);
		fill(world, o, 16, 0, -6, 24, 4, 6, STONE);
	}

	private Scenes() {
	}
}
