package net.spindle.entity;

import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;

/**
 * Ground navigation (no blind wall-climbing, which is what got it wedged against walls and ceilings),
 * planned as if the Spindle were folded (1 wide, 2 tall) so it never has to fail first to discover
 * that a gap fits. It opens wooden doors on the way. The entity folds/unfolds by actual headroom.
 */
public class SpindleNavigation extends MobNavigation {
	public SpindleNavigation(SpindleEntity spindle, World world) {
		super(spindle, world);
		this.setCanPathThroughDoors(true);
	}

	@Override
	protected PathNodeNavigator createPathNodeNavigator(int range) {
		this.nodeMaker = new FoldedNodeMaker();
		this.nodeMaker.setCanEnterOpenDoors(true);
		this.nodeMaker.setCanOpenDoors(true);
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
