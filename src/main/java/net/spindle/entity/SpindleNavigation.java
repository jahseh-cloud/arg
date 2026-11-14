package net.spindle.entity;

import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.SpiderNavigation;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;

/**
 * Plans every route as if the Spindle were folded (1 wide, 2 tall), so it never has to fail first to
 * discover that a tunnel fits. The entity then folds/unfolds along the way based on actual headroom.
 */
public class SpindleNavigation extends SpiderNavigation {
	public SpindleNavigation(SpindleEntity spindle, World world) {
		super(spindle, world);
	}

	@Override
	protected PathNodeNavigator createPathNodeNavigator(int range) {
		this.nodeMaker = new FoldedNodeMaker();
		this.nodeMaker.setCanEnterOpenDoors(true);
		return new PathNodeNavigator(this.nodeMaker, range);
	}

	static class FoldedNodeMaker extends LandPathNodeMaker {
		@Override
		public void init(ChunkCache cachedWorld, MobEntity entity) {
			super.init(cachedWorld, entity);
			this.entityBlockXSize = 1;
			this.entityBlockYSize = 2;
			this.entityBlockZSize = 1;
		}
	}
}
