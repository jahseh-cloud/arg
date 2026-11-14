package net.spindle.entity;

import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.SpiderNavigation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Plans every route as if the Spindle were folded (1 wide, 2 tall), so it never has to fail first to
 * discover that a tunnel fits. The entity then folds/unfolds along the way based on actual headroom.
 */
public class SpindleNavigation extends SpiderNavigation {
	private final SpindleEntity spindle;

	public SpindleNavigation(SpindleEntity spindle, World world) {
		super(spindle, world);
		this.spindle = spindle;
	}

	@Nullable
	@Override
	protected Path findPathToAny(Set<BlockPos> positions, int range, boolean useHeadPos, int distance, float followRange) {
		spindle.setPathingAsFolded(true);
		try {
			return super.findPathToAny(positions, range, useHeadPos, distance, followRange);
		} finally {
			spindle.setPathingAsFolded(false);
		}
	}
}
