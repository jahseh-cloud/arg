package net.spindle.entity;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side logic for the Spindle's reaching tendril.
 *
 * The tendril travels from the Spindle's core along a route (optionally through one "opening" such as a
 * window, door or gap). Glass in the way is shattered, wooden doors/trapdoors/gates are pushed open.
 * When it reaches a player it either snatches the item in their hand or grabs them and drags them out.
 *
 * Breaking free: mash sneak, or hurt the Spindle while it's holding you. If you get dragged into a wall,
 * you get slammed and dropped.
 */
public class TendrilController {
	public enum State { IDLE, EXTENDING, HOLDING, RETRACTING }

	public static final double MAX_LENGTH = 16.0;
	private static final double EXTEND_SPEED = 0.85;
	private static final double RETRACT_SPEED = 1.3;
	private static final double PULL_SPEED = 0.38;

	private final SpindleEntity owner;
	private State state = State.IDLE;
	private final List<Vec3d> waypoints = new ArrayList<>();
	private int wpIndex;
	private Vec3d tip = Vec3d.ZERO;
	@Nullable private Vec3d mid;
	private boolean passedMid;
	@Nullable private LivingEntity target;
	@Nullable private BlockPos touchBlock;
	@Nullable private LivingEntity grabbed;
	private boolean pulledPastMid;
	private int extendTicks;
	private int holdTicks;
	private int sneakToggles;
	private boolean lastSneak;
	private int stuckTicks;
	private float ownerDamageWhileHolding;
	@Nullable private Vec3d lastGrabbedPos;
	private int cooldown;

	public TendrilController(SpindleEntity owner) {
		this.owner = owner;
	}

	public boolean isIdle() {
		return state == State.IDLE;
	}

	public boolean isReady() {
		return state == State.IDLE && cooldown <= 0;
	}

	public State getState() {
		return state;
	}

	@Nullable
	public Vec3d getFirstWaypoint() {
		return waypoints.isEmpty() ? null : waypoints.get(0);
	}

	// ================================================================ starting

	/** Tries to plan a route to the target. Returns null if there's no way in. */
	@Nullable
	public List<Vec3d> planRoute(LivingEntity target) {
		World world = owner.getWorld();
		Vec3d core = owner.getCorePos();
		Vec3d tgt = targetPoint(target);
		if (core.distanceTo(tgt) <= MAX_LENGTH && passable(core, tgt)) {
			return List.of(tgt);
		}
		Vec3d best = null;
		double bestLen = MAX_LENGTH;
		BlockPos tp = target.getBlockPos();
		for (BlockPos bp : BlockPos.iterate(tp.add(-5, -2, -5), tp.add(5, 3, 5))) {
			BlockState st = world.getBlockState(bp);
			if (!isOpeningCandidate(bp, st)) continue;
			Vec3d o = Vec3d.ofCenter(bp);
			double len = core.distanceTo(o) + o.distanceTo(tgt);
			if (len > bestLen) continue;
			if (passable(core, o) && passable(o, tgt)) {
				best = o;
				bestLen = len;
			}
		}
		return best == null ? null : List.of(best, tgt);
	}

	public void begin(LivingEntity target, List<Vec3d> route) {
		reset();
		this.target = target;
		this.waypoints.addAll(route);
		this.mid = route.size() > 1 ? route.get(0) : null;
		this.tip = owner.getCorePos();
		this.state = State.EXTENDING;
		owner.playSound(SoundEvents.BLOCK_SCULK_VEIN_PLACE, 1.2f, 0.5f);
	}

	/** Reach out and touch a block (used to snuff out lights). */
	public boolean beginTouch(BlockPos pos) {
		Vec3d c = Vec3d.ofCenter(pos);
		Vec3d core = owner.getCorePos();
		if (core.distanceTo(c) > MAX_LENGTH || !passable(core, c, pos)) return false;
		reset();
		this.touchBlock = pos.toImmutable();
		this.waypoints.add(c);
		this.tip = core;
		this.state = State.EXTENDING;
		return true;
	}

	private void reset() {
		waypoints.clear();
		wpIndex = 0;
		mid = null;
		passedMid = false;
		target = null;
		touchBlock = null;
		grabbed = null;
		pulledPastMid = false;
		extendTicks = 0;
		holdTicks = 0;
		sneakToggles = 0;
		lastSneak = false;
		stuckTicks = 0;
		ownerDamageWhileHolding = 0;
		lastGrabbedPos = null;
	}

	// ================================================================ ticking

	public void tick(ServerWorld world) {
		if (state == State.IDLE && cooldown > 0) cooldown--;
		switch (state) {
			case EXTENDING -> {
				if (!owner.isFrozen()) tickExtending(world);
			}
			case HOLDING -> tickHolding(world);
			case RETRACTING -> tickRetracting();
			default -> {}
		}
		owner.syncTendril(state != State.IDLE, tip, mid, passedMid);
	}

	private Vec3d currentGoal() {
		boolean last = wpIndex >= waypoints.size() - 1;
		if (last && target != null && target.isAlive()) {
			return targetPoint(target);
		}
		return waypoints.get(Math.min(wpIndex, waypoints.size() - 1));
	}

	private void tickExtending(ServerWorld world) {
		extendTicks++;
		double budget = EXTEND_SPEED;
		while (budget > 1e-4) {
			Vec3d goal = currentGoal();
			Vec3d dir = goal.subtract(tip);
			double dl = dir.length();
			if (dl < 0.05) {
				if (wpIndex < waypoints.size() - 1) {
					wpIndex++;
					passedMid = true;
					continue;
				}
				break;
			}
			double s = Math.min(0.25, Math.min(budget, dl));
			Vec3d next = tip.add(dir.multiply(s / dl));
			if (!interactAt(world, next)) {
				startRetract();
				return;
			}
			tip = next;
			budget -= s;
		}

		if (touchBlock != null) {
			if (tip.distanceTo(Vec3d.ofCenter(touchBlock)) < 0.6) {
				owner.snuffLight(touchBlock);
				startRetract();
			}
		} else if (target != null) {
			if (!target.isAlive() || owner.getCorePos().distanceTo(target.getPos()) > MAX_LENGTH + 4) {
				startRetract();
			} else if (target.getBoundingBox().expand(0.4).contains(tip)) {
				onContact(world, target);
				return;
			}
		}
		if (currentLength() > MAX_LENGTH + 2 || extendTicks > 100) startRetract();
	}

	private void onContact(ServerWorld world, LivingEntity victim) {
		owner.onTendrilContact();
		if (victim instanceof PlayerEntity player && !player.getMainHandStack().isEmpty() && owner.getRandom().nextFloat() < 0.3f) {
			// Snatch whatever they're holding. It turns up later.
			ItemStack taken = player.getMainHandStack().copy();
			player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			owner.addStolen(player.getUuid(), taken);
			world.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.HOSTILE, 1.5f, 0.5f);
			startRetract();
			return;
		}
		grabbed = victim;
		state = State.HOLDING;
		holdTicks = 0;
		lastGrabbedPos = victim.getPos();
		victim.damage(owner.getDamageSources().mobAttack(owner), 3.0f);
		world.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
				SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 1.2f, 0.6f);
	}

	private void tickHolding(ServerWorld world) {
		LivingEntity g = grabbed;
		if (g == null || !g.isAlive() || g.isRemoved()) {
			release(false);
			return;
		}
		if (g instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) {
			release(false);
			return;
		}
		holdTicks++;

		if (g instanceof PlayerEntity p) {
			boolean sneaking = p.isSneaking();
			if (sneaking && !lastSneak) sneakToggles++;
			lastSneak = sneaking;
		}
		if (sneakToggles >= 7 || ownerDamageWhileHolding >= 8f || holdTicks > 140) {
			release(true);
			return;
		}

		Vec3d pullTo;
		if (mid != null && !pulledPastMid) {
			pullTo = mid.subtract(0, g.getHeight() * 0.5, 0);
			if (g.getPos().distanceTo(pullTo) < 1.2) pulledPastMid = true;
		} else {
			pullTo = new Vec3d(owner.getX(), owner.getY() + 0.3, owner.getZ());
		}

		Vec3d dir = pullTo.subtract(g.getPos());
		double dl = dir.length();
		if ((mid == null || pulledPastMid) && dl < 1.6 + owner.getWidth() / 2.0) {
			// Dragged all the way in.
			owner.tryAttack(g);
			release(false);
			return;
		}

		Vec3d vel = dir.multiply(Math.min(PULL_SPEED, dl) / Math.max(dl, 1e-4));
		if (g.isOnGround()) vel = vel.add(0, 0.08, 0);
		g.setVelocity(vel);
		g.velocityModified = true;
		g.fallDistance = 0;

		if (lastGrabbedPos != null && g.getPos().squaredDistanceTo(lastGrabbedPos) < 0.0025) stuckTicks++;
		else stuckTicks = 0;
		lastGrabbedPos = g.getPos();
		if (stuckTicks > 12) {
			// Pulled into a wall: slammed against it and dropped.
			g.damage(owner.getDamageSources().mobAttack(owner), 4.0f);
			world.playSound(null, g.getX(), g.getY(), g.getZ(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.0f, 0.6f);
			release(false);
			return;
		}

		if (holdTicks % 20 == 0) g.damage(owner.getDamageSources().mobAttack(owner), 1.0f);

		tip = g.getPos().add(0, g.getHeight() * 0.55, 0);
		if (owner.getCorePos().distanceTo(tip) > MAX_LENGTH + 3) release(true);
	}

	private void tickRetracting() {
		Vec3d core = owner.getCorePos();
		double budget = RETRACT_SPEED;
		while (budget > 1e-4) {
			Vec3d goal = (mid != null && passedMid) ? mid : core;
			Vec3d dir = goal.subtract(tip);
			double dl = dir.length();
			if (dl < 0.1) {
				if (goal == mid) {
					passedMid = false;
					continue;
				}
				state = State.IDLE;
				tip = core;
				mid = null;
				return;
			}
			double s = Math.min(budget, dl);
			tip = tip.add(dir.multiply(s / dl));
			budget -= s;
		}
	}

	private void startRetract() {
		state = State.RETRACTING;
		grabbed = null;
		cooldown = 60 + owner.getRandom().nextInt(60);
	}

	private void release(boolean snapped) {
		if (snapped) {
			owner.playSound(SoundEvents.BLOCK_WEEPING_VINES_BREAK, 1.5f, 0.5f);
		}
		startRetract();
		cooldown = snapped ? 160 : 80;
		pulledPastMid = false;
	}

	public void forceRelease() {
		if (state == State.EXTENDING || state == State.HOLDING) startRetract();
	}

	public void onOwnerDamaged(float amount) {
		if (state == State.HOLDING) ownerDamageWhileHolding += amount;
	}

	private double currentLength() {
		Vec3d core = owner.getCorePos();
		if (mid != null && passedMid) return core.distanceTo(mid) + mid.distanceTo(tip);
		return core.distanceTo(tip);
	}

	// ================================================================ world interaction

	private static Vec3d targetPoint(LivingEntity target) {
		return target.getPos().add(0, target.getHeight() * 0.6, 0);
	}

	private boolean isBreakableGlass(BlockState st) {
		return owner.canGrief() && (st.isIn(ConventionalBlockTags.GLASS_BLOCKS) || st.isIn(ConventionalBlockTags.GLASS_PANES)
				|| st.isOf(Blocks.GLASS) || st.isOf(Blocks.GLASS_PANE));
	}

	private static boolean isOpenable(BlockState st) {
		Block b = st.getBlock();
		if (b instanceof DoorBlock) return DoorBlock.canOpenByHand(st);
		if (b instanceof TrapdoorBlock) return !st.isOf(Blocks.IRON_TRAPDOOR);
		return b instanceof FenceGateBlock;
	}

	private boolean isSolid(BlockPos pos) {
		World world = owner.getWorld();
		return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	private boolean isOpeningCandidate(BlockPos bp, BlockState st) {
		if (isBreakableGlass(st) || isOpenable(st)) return true;
		World world = owner.getWorld();
		if (!st.getCollisionShape(world, bp).isEmpty()) return false;
		// A gap in a wall: empty block with solid blocks on two opposite sides.
		return (isSolid(bp.east()) && isSolid(bp.west())) || (isSolid(bp.north()) && isSolid(bp.south()));
	}

	/** Called for every small step the tip takes. Returns false if the tendril is blocked. */
	private boolean interactAt(ServerWorld world, Vec3d p) {
		BlockPos bp = BlockPos.ofFloored(p);
		BlockState st = world.getBlockState(bp);
		if (st.isAir()) return true;
		if (isBreakableGlass(st)) {
			world.breakBlock(bp, false, owner);
			return true;
		}
		if (isOpenable(st)) {
			if (st.contains(Properties.OPEN) && !st.get(Properties.OPEN)) {
				if (st.getBlock() instanceof DoorBlock door) {
					door.setOpen(owner, world, st, bp, true);
				} else {
					world.setBlockState(bp, st.with(Properties.OPEN, true), Block.NOTIFY_ALL);
					world.playSound(null, bp, st.getBlock() instanceof FenceGateBlock
									? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN,
							SoundCategory.BLOCKS, 1.0f, 0.7f);
				}
			}
			return true;
		}
		return !pointInsideShape(world, bp, st, p);
	}

	private boolean pointInsideShape(World world, BlockPos bp, BlockState st, Vec3d p) {
		VoxelShape shape = st.getCollisionShape(world, bp);
		if (shape.isEmpty()) return false;
		double lx = p.x - bp.getX(), ly = p.y - bp.getY(), lz = p.z - bp.getZ();
		for (Box box : shape.getBoundingBoxes()) {
			if (box.contains(lx, ly, lz)) return true;
		}
		return false;
	}

	public boolean passable(Vec3d a, Vec3d b) {
		return passable(a, b, null);
	}

	/** Can the tendril get from a to b? Glass and wooden doors count as passable (it will deal with them). */
	private boolean passable(Vec3d a, Vec3d b, @Nullable BlockPos ignore) {
		World world = owner.getWorld();
		Vec3d d = b.subtract(a);
		double len = d.length();
		if (len < 1e-3) return true;
		int steps = (int) Math.ceil(len / 0.25);
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int i = 1; i < steps; i++) {
			Vec3d p = a.add(d.multiply(i / (double) steps));
			m.set(p.x, p.y, p.z);
			if (ignore != null && ignore.equals(m)) continue;
			BlockState st = world.getBlockState(m);
			if (st.isAir() || isBreakableGlass(st) || isOpenable(st)) continue;
			if (pointInsideShape(world, m, st, p)) return false;
		}
		return true;
	}
}
