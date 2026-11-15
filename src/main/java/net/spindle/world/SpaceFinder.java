package net.spindle.world;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.spindle.entity.SpaceMap;
import org.jetbrains.annotations.Nullable;

public final class SpaceFinder {
	/** The first standable spot at or below p, within maxDown blocks. */
	@Nullable
	public static BlockPos floorBelow(ServerWorld world, BlockPos p, int maxDown) {
		for (int dy = 0; dy <= maxDown; dy++) {
			BlockPos q = p.down(dy);
			if (SpaceMap.standable(world, q)) return q;
		}
		return null;
	}

	private SpaceFinder() {
	}
}
