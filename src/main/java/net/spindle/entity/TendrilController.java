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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.spindle.SpindleNetworking;
import net.spindle.SpindleSounds;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side logic for the Spindle's tendril.
 *
 * Two ways of using it:
 *  - REACH: it can see you (or knows exactly where you are). The tendril goes straight in, through
 *    one window/door/gap if needed, and grabs.
 *  - SEARCH: you're hiding indoors and it only knows roughly where. A slow probe with an eye on the
 *    end snakes in through an opening and explores room by room, mumbling. The eye has a view cone
 *    and needs line of sight. Moving gets you noticed, sneaking much less, standing still almost never
 *    unless it's right on top of you. Walking/sprinting nearby is heard even out of sight and it
 *    comes to investigate. If it spots you it shrieks, pulls back, and lunges.
 *
 * The tendril's body is a polyline: core -> anchors (bends) -> tip. Anchors are synced to clients
 * by packet; the tip and look direction by tracked data.
 */
public class TendrilController {
	public enum State { IDLE, EXTENDING, SEARCHING, SPOTTED, HOLDING, RETRACTING }

	public static final double MAX_LENGTH = 16.0;
	public static final double MAX_SEARCH_LENGTH = 36.0;
	private static final double SEARCH_RADIUS = 14.0;
	private static final int MAX_SEARCH_NODES = 6000;
	private static final double PULL_SPEED = 0.38;

	private final SpindleEntity owner;
	private State state = State.IDLE;

	// body
	private final List<Vec3d> anchors = new ArrayList<>();
	private Vec3d tip = Vec3d.ZERO;
	private Vec3d look = new Vec3d(0, 0, 1);
	private boolean anchorsDirty;

	// extending
	private final List<Vec3d> goals = new ArrayList<>();
	private int goalIndex;
	private double extendSpeed = 0.85;
	@Nullable private LivingEntity target;
	@Nullable private BlockPos touchBlock;
	private int extendTicks;

	// searching
	@Nullable private SearchArea area;
	private final List<BlockPos> movePath = new ArrayList<>();
	@Nullable private BlockPos currentNode;
	private final List<Vec3d> lookedAt = new ArrayList<>();
	private int lookTicks;
	private float sweepBase;
	private int searchTicks;
	private int visits;
	private int maxVisits;
	@Nullable private Vec3d investigate;
	private int mumbleTimer;
	private final Map<UUID, Float> suspicion = new HashMap<>();
	@Nullable private PlayerEntity spotted;
	private int spottedTicks;

	// holding
	@Nullable private LivingEntity grabbed;
	private int pullIndex;
	private int holdTicks;
	private int sneakToggles;
	private boolean lastSneak;
	private int stuckTicks;
	private float ownerDamageWhileHolding;
	@Nullable private Vec3d lastGrabbedPos;

	private int cooldown;
	private int resyncTimer;

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

	public boolean isSearching() {
		return state == State.SEARCHING || state == State.SPOTTED;
	}

	@Nullable
	public Vec3d getFirstWaypoint() {
		if (!anchors.isEmpty()) return anchors.get(0);
		return goals.isEmpty() ? null : goals.get(0);
	}

	// ================================================================ starting: reach

	/** Plans a direct route to a target it can locate. Returns null if there's no way in. */
	@Nullable
	public List<Vec3d> planRoute(LivingEntity target) {
		Vec3d core = owner.getCorePos();
		Vec3d tgt = targetPoint(target);
		if (core.distanceTo(tgt) <= MAX_LENGTH && passable(core, tgt)) return List.of(tgt);
		Vec3d opening = findOpening(core, tgt, true, MAX_LENGTH);
		return opening == null ? null : List.of(opening, tgt);
	}

	public void begin(LivingEntity target, List<Vec3d> route) {
		reset();
		this.target = target;
		this.goals.addAll(route);
		this.extendSpeed = 0.85;
		this.tip = owner.getCorePos();
		this.state = State.EXTENDING;
		owner.playSound(SpindleSounds.REACH, 1.2f, 0.5f);
	}

	/** Reach out and touch a block (used to snuff out lights). */
	public boolean beginTouch(BlockPos pos) {
		Vec3d c = Vec3d.ofCenter(pos);
		Vec3d core = owner.getCorePos();
		if (core.distanceTo(c) > MAX_LENGTH || !passable(core, c, pos)) return false;
		reset();
		this.touchBlock = pos.toImmutable();
		this.goals.add(c);
		this.extendSpeed = 0.85;
		this.tip = core;
		this.state = State.EXTENDING;
		return true;
	}

	// ================================================================ starting: search

	/**
	 * Sends a searching probe into the enclosed space around {@code roughCenter}.
	 * Returns false if there's no way in.
	 */
	public boolean beginSearch(Vec3d roughCenter) {
		Vec3d core = owner.getCorePos();
		Vec3d entry = findOpening(core, roughCenter, false, MAX_LENGTH);
		if (entry == null) {
			// Already inside the same cave or building: no window needed, it just uncoils from where it stands.
			Vec3d toward = roughCenter.subtract(core);
			double len = toward.length();
			if (len > 2.5 && owner.isEnclosed(owner.getPos())) {
				Vec3d start = core.add(toward.multiply(1.5 / len));
				if (isTendrilPassable(BlockPos.ofFloored(start)) && passable(core, start)) entry = start;
			}
		}
		if (entry == null) return false;
		owner.rememberEntry(entry);
		SearchArea a = SearchArea.build(this, BlockPos.ofFloored(entry), roughCenter);
		if (a == null || a.size() < 6) return false;

		reset();
		this.area = a;
		this.goals.add(entry);
		this.extendSpeed = 0.45;
		this.tip = core;
		this.state = State.EXTENDING; // first crawl to the entry, then switch to SEARCHING
		this.maxVisits = 6 + owner.getRandom().nextInt(4);
		this.mumbleTimer = 10;
		owner.playSound(SpindleSounds.PROBE_SERVO, 1.0f, 0.8f);
		owner.statProbeSearches++;
		return true;
	}

	private void reset() {
		anchors.clear();
		anchorsDirty = true;
		goals.clear();
		goalIndex = 0;
		target = null;
		touchBlock = null;
		extendTicks = 0;
		area = null;
		movePath.clear();
		currentNode = null;
		lookedAt.clear();
		lookTicks = 0;
		searchTicks = 0;
		visits = 0;
		investigate = null;
		suspicion.clear();
		spotted = null;
		spottedTicks = 0;
		grabbed = null;
		pullIndex = 0;
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
		boolean paused = owner.isFrozen() && state != State.HOLDING && state != State.RETRACTING;
		if (!paused) {
			switch (state) {
				case EXTENDING -> tickExtending(world);
				case SEARCHING -> tickSearching(world);
				case SPOTTED -> tickSpotted(world);
				case HOLDING -> tickHolding(world);
				case RETRACTING -> tickRetracting();
				default -> {}
			}
		}
		boolean alert = state == State.SPOTTED || state == State.HOLDING || (state == State.EXTENDING && area != null && target != null);
		owner.syncTendril(state != State.IDLE, isSearching() || alert, alert, tip, look);
		if (state != State.IDLE && --resyncTimer <= 0) {
			anchorsDirty = true; // late joiners / players who just started tracking
			resyncTimer = 40;
		}
		if (anchorsDirty) {
			anchorsDirty = false;
			SpindleNetworking.sendTrail(owner, anchors);
		}
	}

	// ---------------------------------------------------------------- extending (reach / lunge / crawl to entry)

	private Vec3d currentGoal() {
		boolean last = goalIndex >= goals.size() - 1;
		if (last && target != null && target.isAlive()) return targetPoint(target);
		return goals.get(Math.min(goalIndex, goals.size() - 1));
	}

	private void tickExtending(ServerWorld world) {
		extendTicks++;
		double budget = extendSpeed;
		while (budget > 1e-4) {
			Vec3d goal = currentGoal();
			Vec3d dir = goal.subtract(tip);
			double dl = dir.length();
			if (dl < 0.05) {
				if (goalIndex < goals.size() - 1) {
					addAnchor(goal);
					goalIndex++;
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
			look = dir.multiply(1.0 / dl);
			tip = next;
			budget -= s;
		}

		if (area != null && target == null) {
			// Crawled to the entry: start searching.
			if (tip.distanceTo(goals.get(goals.size() - 1)) < 0.1) {
				addAnchor(tip);
				currentNode = area.nearestNode(tip);
				state = State.SEARCHING;
				startLooking();
			}
			return;
		}
		if (touchBlock != null) {
			if (tip.distanceTo(Vec3d.ofCenter(touchBlock)) < 0.6) {
				owner.snuffLight(touchBlock);
				startRetract();
			}
		} else if (target != null) {
			double limit = area != null ? MAX_SEARCH_LENGTH : MAX_LENGTH;
			if (!target.isAlive() || owner.getCorePos().distanceTo(target.getPos()) > limit + 4) {
				startRetract();
			} else if (target.getBoundingBox().expand(0.4).contains(tip)) {
				onContact(world, target);
				return;
			}
		}
		double limit = area != null ? MAX_SEARCH_LENGTH : MAX_LENGTH;
		if (currentLength() > limit + 2 || extendTicks > 160) startRetract();
	}

	// ---------------------------------------------------------------- searching

	private void tickSearching(ServerWorld world) {
		SearchArea a = area;
		if (a == null) {
			startRetract();
			return;
		}
		searchTicks++;

		if (--mumbleTimer <= 0) {
			mumbleTimer = 50 + owner.getRandom().nextInt(50);
			world.playSound(null, tip.x, tip.y, tip.z, SpindleSounds.PROBE_MUMBLE, SoundCategory.HOSTILE,
					0.9f, 0.85f + owner.getRandom().nextFloat() * 0.3f);
		}

		if (perceive(world)) return; // spotted someone

		if (!movePath.isEmpty()) {
			// Creep along the tree toward the next spot to look from.
			BlockPos next = movePath.get(0);
			Vec3d nc = Vec3d.ofCenter(next);
			Vec3d dir = nc.subtract(tip);
			double dl = dir.length();
			double speed = investigate != null ? 0.22 : 0.13;
			if (dl <= speed) {
				tip = nc;
				currentNode = next;
				movePath.remove(0);
				rebuildBodyFromTree();
				if (movePath.isEmpty()) startLooking();
			} else {
				Vec3d step = dir.multiply(speed / dl);
				tip = tip.add(step);
				Vec3d flatLook = dir.multiply(1 / dl);
				look = look.lerp(flatLook, 0.15).normalize();
			}
			return;
		}

		if (lookTicks > 0) {
			lookTicks--;
			// Slow, jerky sweep of the eye; pauses between turns.
			float t = (float) searchTicks;
			float sweep = MathHelper.sin(t * 0.05f) * 1.25f;
			if ((searchTicks / 12) % 3 == 0) sweep = MathHelper.sin((t - (t % 12)) * 0.05f) * 1.25f;
			float yaw = sweepBase + sweep;
			float pitch = -0.25f + MathHelper.sin(t * 0.031f) * 0.3f;
			look = new Vec3d(-MathHelper.sin(yaw) * MathHelper.cos(pitch), MathHelper.sin(pitch), MathHelper.cos(yaw) * MathHelper.cos(pitch));
			if (searchTicks % 24 == 0) {
				world.playSound(null, tip.x, tip.y, tip.z, SpindleSounds.PROBE_SERVO, SoundCategory.HOSTILE, 0.5f,
						0.8f + owner.getRandom().nextFloat() * 0.4f);
			}
			return;
		}

		// Done looking here: pick the next spot.
		visits++;
		if (visits > maxVisits || searchTicks > 1200) {
			owner.onSearchFailed();
			startRetract();
			return;
		}
		chooseNextSpot(a);
	}

	private void startLooking() {
		lookTicks = 40 + owner.getRandom().nextInt(40);
		lookedAt.add(tip);
		owner.rememberSearched(tip);
		if (area != null) {
			Vec3d toCenter = area.center.subtract(tip);
			sweepBase = (float) Math.atan2(-toCenter.x, toCenter.z);
		}
		investigate = null;
	}

	private void chooseNextSpot(SearchArea a) {
		BlockPos dest = null;
		if (investigate != null) {
			dest = a.nearestNode(investigate);
		}
		if (dest == null) {
			double best = -1e9;
			double baseLen = baseLength();
			for (int i = 0; i < 40; i++) {
				BlockPos n = a.randomNode(owner.getRandom());
				if (baseLen + a.depth(n) > MAX_SEARCH_LENGTH) continue;
				Vec3d c = Vec3d.ofCenter(n);
				double minSeen = 8;
				for (Vec3d v : lookedAt) minSeen = Math.min(minSeen, v.distanceTo(c));
				// Rooms it already cleared on earlier searches count too, so big buildings get worked through.
				for (SpindleEntity.Memory m : owner.searchedSpots) minSeen = Math.min(minSeen, m.pos().distanceTo(c) + 1.5);
				double score = minSeen * 2.0 + a.openness(n) * 0.25 - c.distanceTo(a.center) * 0.3
						+ owner.getRandom().nextDouble();
				if (score > best) {
					best = score;
					dest = n;
				}
			}
		}
		if (dest == null || currentNode == null) {
			owner.onSearchFailed();
			startRetract();
			return;
		}
		movePath.clear();
		movePath.addAll(a.treePath(currentNode, dest));
		if (movePath.isEmpty()) startLooking();
	}

	/** Body = core -> entry -> corners of the tree path from the entry to the current node. */
	private void rebuildBodyFromTree() {
		if (area == null || currentNode == null) return;
		Vec3d entry = anchors.isEmpty() ? null : anchors.get(0);
		List<Vec3d> pts = new ArrayList<>();
		if (entry != null) pts.add(entry);
		List<BlockPos> chain = area.pathFromRoot(currentNode);
		Vec3d prevDir = null;
		for (int i = 0; i < chain.size() - 1; i++) {
			Vec3d a = Vec3d.ofCenter(chain.get(i));
			Vec3d b = Vec3d.ofCenter(chain.get(i + 1));
			Vec3d d = b.subtract(a);
			boolean corner = prevDir == null || !d.equals(prevDir);
			if (corner && (pts.isEmpty() || pts.get(pts.size() - 1).squaredDistanceTo(a) > 0.04)) pts.add(a);
			prevDir = d;
		}
		anchors.clear();
		anchors.addAll(pts);
		anchorsDirty = true;
	}

	/** Eye + ears. Returns true if a player was spotted. */
	private boolean perceive(ServerWorld world) {
		for (PlayerEntity p : world.getPlayers()) {
			if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
			double d = p.getEyePos().distanceTo(tip);
			if (d > 14) continue;
			Vec3d chest = p.getPos().add(0, p.getHeight() * 0.6, 0);
			double moved = owner.getPlayerSpeed(p);

			if (d < 1.4) { // it bumped into you
				spot(world, p);
				return true;
			}

			boolean los = owner.hasLineOfSight(tip, p.getEyePos()) || owner.hasLineOfSight(tip, chest);
			Vec3d to = chest.subtract(tip).normalize();
			boolean inCone = to.dotProduct(look) > 0.62;
			float s = suspicion.getOrDefault(p.getUuid(), 0f);

			if (los && inCone) {
				float distF = (float) MathHelper.clamp(1.6 - d / 8.0, 0.2, 1.4);
				float moveF;
				if (moved < 0.02) moveF = 0.06f;
				else if (p.isSneaking()) moveF = 0.35f;
				else if (p.isSprinting()) moveF = 2.2f;
				else moveF = 1.0f;
				int light = world.getLightLevel(p.getBlockPos());
				float lightF = light >= 10 ? 1.5f : (light < 4 ? 0.6f : 1.0f);
				s += 0.08f * distF * moveF * lightF;
				if (s > 0.3f) {
					// "Did something move?" The eye stops and stares.
					look = look.lerp(to, 0.35).normalize();
					lookTicks = Math.max(lookTicks, 20);
					movePath.clear();
				}
			} else {
				s = Math.max(0f, s - 0.01f);
				// Ears: walking or sprinting nearby is heard through walls.
				if (d < 7 && moved > 0.08 && !p.isSneaking()) {
					investigate = p.getPos().add(owner.getRandom().nextGaussian(), 0.5, owner.getRandom().nextGaussian());
					if (los) look = look.lerp(to, 0.5).normalize();
					if (lookTicks > 0 && owner.getRandom().nextFloat() < 0.1f) lookTicks = 0;
				}
			}
			suspicion.put(p.getUuid(), s);
			if (s >= 1f) {
				spot(world, p);
				return true;
			}
		}
		return false;
	}

	private void spot(ServerWorld world, PlayerEntity p) {
		spotted = p;
		spottedTicks = 0;
		state = State.SPOTTED;
		owner.statProbeSpots++;
		owner.noticeTarget(p);
		look = p.getEyePos().subtract(tip).normalize();
		world.playSound(null, tip.x, tip.y, tip.z, SpindleSounds.PROBE_FOUND, SoundCategory.HOSTILE, 1.6f, 1.0f);
	}

	private void tickSpotted(ServerWorld world) {
		PlayerEntity p = spotted;
		if (p == null || !p.isAlive()) {
			state = State.SEARCHING;
			startLooking();
			return;
		}
		spottedTicks++;
		look = p.getEyePos().subtract(tip).normalize();
		if (spottedTicks < 9) {
			tip = tip.subtract(look.multiply(0.06)); // coils back
			return;
		}
		// Lunge.
		addAnchor(tip);
		target = p;
		goals.clear();
		goals.add(targetPoint(p));
		goalIndex = 0;
		extendSpeed = 1.15;
		extendTicks = 0;
		state = State.EXTENDING;
		owner.onTendrilContact();
	}

	// ---------------------------------------------------------------- contact / holding

	private void onContact(ServerWorld world, LivingEntity victim) {
		owner.noticeTarget(victim);
		owner.onTendrilContact();
		if (victim instanceof PlayerEntity player && !player.getMainHandStack().isEmpty() && owner.getRandom().nextFloat() < 0.3f) {
			ItemStack taken = player.getMainHandStack().copy();
			player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			owner.addStolen(player.getUuid(), taken);
			world.playSound(null, player.getX(), player.getY(), player.getZ(),
					SpindleSounds.STEAL, SoundCategory.HOSTILE, 1.5f, 0.5f);
			startRetract();
			return;
		}
		grabbed = victim;
		owner.statGrabs++;
		state = State.HOLDING;
		holdTicks = 0;
		pullIndex = anchors.size() - 1;
		lastGrabbedPos = victim.getPos();
		victim.damage(owner.getDamageSources().mobAttack(owner), 3.0f);
		world.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
				SpindleSounds.GRAB, SoundCategory.HOSTILE, 1.2f, 0.6f);
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
		if (sneakToggles >= 7 || ownerDamageWhileHolding >= 8f || holdTicks > 200) {
			release(true);
			return;
		}

		// Reel the victim back along the tendril's body: each bend in turn, then under the Spindle.
		Vec3d pullTo;
		if (pullIndex >= 0 && pullIndex < anchors.size()) {
			pullTo = anchors.get(pullIndex).subtract(0, g.getHeight() * 0.5, 0);
			if (g.getPos().distanceTo(pullTo) < 1.2) {
				pullIndex--;
				if (!anchors.isEmpty()) {
					anchors.remove(anchors.size() - 1); // the body shortens as it reels in
					anchorsDirty = true;
				}
			}
		} else {
			pullTo = new Vec3d(owner.getX(), owner.getY() + 0.3, owner.getZ());
			if (g.getPos().distanceTo(pullTo) < 1.6 + owner.getWidth() / 2.0) {
				owner.tryAttack(g);
				release(false);
				return;
			}
		}

		Vec3d dir = pullTo.subtract(g.getPos());
		double dl = dir.length();
		Vec3d vel = dir.multiply(Math.min(PULL_SPEED, dl) / Math.max(dl, 1e-4));
		if (g.isOnGround()) vel = vel.add(0, 0.08, 0);
		g.setVelocity(vel);
		g.velocityModified = true;
		g.fallDistance = 0;

		if (lastGrabbedPos != null && g.getPos().squaredDistanceTo(lastGrabbedPos) < 0.0025) stuckTicks++;
		else stuckTicks = 0;
		lastGrabbedPos = g.getPos();
		if (stuckTicks > 12) {
			g.damage(owner.getDamageSources().mobAttack(owner), 4.0f);
			world.playSound(null, g.getX(), g.getY(), g.getZ(), SpindleSounds.SLAM, SoundCategory.HOSTILE, 1.0f, 0.6f);
			release(false);
			return;
		}

		if (holdTicks % 20 == 0) g.damage(owner.getDamageSources().mobAttack(owner), 1.0f);

		tip = g.getPos().add(0, g.getHeight() * 0.55, 0);
		look = tip.subtract(anchors.isEmpty() ? owner.getCorePos() : anchors.get(anchors.size() - 1)).normalize();
		if (currentLength() > MAX_SEARCH_LENGTH + 4) release(true);
	}

	// ---------------------------------------------------------------- retracting

	private void tickRetracting() {
		Vec3d core = owner.getCorePos();
		double budget = 1.3;
		while (budget > 1e-4) {
			Vec3d goal = anchors.isEmpty() ? core : anchors.get(anchors.size() - 1);
			Vec3d dir = goal.subtract(tip);
			double dl = dir.length();
			if (dl < 0.1) {
				if (!anchors.isEmpty()) {
					anchors.remove(anchors.size() - 1);
					anchorsDirty = true;
					continue;
				}
				state = State.IDLE;
				tip = core;
				return;
			}
			double s = Math.min(budget, dl);
			tip = tip.add(dir.multiply(s / dl));
			look = dir.multiply(-1 / dl);
			budget -= s;
		}
	}

	private void startRetract() {
		state = State.RETRACTING;
		grabbed = null;
		spotted = null;
		area = null;
		movePath.clear();
		cooldown = 60 + owner.getRandom().nextInt(60);
	}

	private void release(boolean snapped) {
		if (snapped) owner.playSound(SpindleSounds.SNAP, 1.5f, 0.5f);
		startRetract();
		cooldown = snapped ? 160 : 80;
	}

	public void forceRelease() {
		if (state != State.IDLE && state != State.RETRACTING) startRetract();
	}

	public void onOwnerDamaged(float amount) {
		if (state == State.HOLDING) ownerDamageWhileHolding += amount;
	}

	// ---------------------------------------------------------------- body helpers

	private void addAnchor(Vec3d p) {
		anchors.add(p);
		anchorsDirty = true;
	}

	private double baseLength() {
		Vec3d core = owner.getCorePos();
		return anchors.isEmpty() ? 0 : core.distanceTo(anchors.get(0));
	}

	private double currentLength() {
		Vec3d prev = owner.getCorePos();
		double len = 0;
		for (Vec3d a : anchors) {
			len += prev.distanceTo(a);
			prev = a;
		}
		return len + prev.distanceTo(tip);
	}

	// ================================================================ world interaction

	private static Vec3d targetPoint(LivingEntity target) {
		return target.getPos().add(0, target.getHeight() * 0.6, 0);
	}

	/** A window, door, gap etc. near {@code toward} that the core can reach in a straight line. */
	@Nullable
	private Vec3d findOpening(Vec3d core, Vec3d toward, boolean needLineToTarget, double maxLen) {
		World world = owner.getWorld();
		Vec3d best = null;
		double bestLen = Double.MAX_VALUE;
		BlockPos tp = BlockPos.ofFloored(toward);
		for (BlockPos bp : BlockPos.iterate(tp.add(-6, -3, -6), tp.add(6, 3, 6))) {
			BlockState st = world.getBlockState(bp);
			if (!isOpeningCandidate(bp, st)) continue;
			Vec3d o = Vec3d.ofCenter(bp);
			double toOpening = core.distanceTo(o);
			if (toOpening > maxLen) continue;
			double len = toOpening + o.distanceTo(toward);
			if (needLineToTarget && len > maxLen) continue;
			if (!needLineToTarget) {
				// Searching: prefer a way in it hasn't already tried recently.
				for (SpindleEntity.Memory m : owner.usedEntries) if (m.pos().distanceTo(o) < 3) len += 8;
			}
			if (len >= bestLen) continue;
			if (!passable(core, o)) continue;
			if (needLineToTarget && !passable(o, toward)) continue;
			best = o;
			bestLen = len;
		}
		return best;
	}

	boolean isBreakableGlass(BlockState st) {
		return owner.canGrief() && (st.isIn(ConventionalBlockTags.GLASS_BLOCKS) || st.isIn(ConventionalBlockTags.GLASS_PANES)
				|| st.isOf(Blocks.GLASS) || st.isOf(Blocks.GLASS_PANE));
	}

	static boolean isOpenable(BlockState st) {
		Block b = st.getBlock();
		if (b instanceof DoorBlock) return DoorBlock.canOpenByHand(st);
		if (b instanceof TrapdoorBlock) return !st.isOf(Blocks.IRON_TRAPDOOR);
		return b instanceof FenceGateBlock;
	}

	/** Can the tendril occupy this block? (air-ish, or glass / a door it can deal with) */
	boolean isTendrilPassable(BlockPos pos) {
		World world = owner.getWorld();
		BlockState st = world.getBlockState(pos);
		if (st.isAir()) return true;
		if (isBreakableGlass(st) || isOpenable(st)) return true;
		VoxelShape shape = st.getCollisionShape(world, pos);
		return shape.isEmpty() || shape.getMax(Direction.Axis.Y) < 0.3; // carpets, slabs' gaps, etc.
	}

	private boolean isSolid(BlockPos pos) {
		World world = owner.getWorld();
		return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
	}

	private boolean isOpeningCandidate(BlockPos bp, BlockState st) {
		if (isBreakableGlass(st) || isOpenable(st)) return true;
		World world = owner.getWorld();
		if (!st.getCollisionShape(world, bp).isEmpty()) return false;
		return (isSolid(bp.east()) && isSolid(bp.west())) || (isSolid(bp.north()) && isSolid(bp.south()));
	}

	private boolean interactAt(ServerWorld world, Vec3d p) {
		BlockPos bp = BlockPos.ofFloored(p);
		BlockState st = world.getBlockState(bp);
		if (st.isAir()) return true;
		if (isBreakableGlass(st)) {
			world.breakBlock(bp, false, owner);
			return true;
		}
		if (isOpenable(st)) {
			openIfClosed(world, bp, st);
			return true;
		}
		return !pointInsideShape(world, bp, st, p);
	}

	private void openIfClosed(ServerWorld world, BlockPos bp, BlockState st) {
		if (!st.contains(Properties.OPEN) || st.get(Properties.OPEN)) return;
		if (st.getBlock() instanceof DoorBlock door) {
			door.setOpen(owner, world, st, bp, true);
		} else {
			world.setBlockState(bp, st.with(Properties.OPEN, true), Block.NOTIFY_ALL);
			world.playSound(null, bp, st.getBlock() instanceof FenceGateBlock
							? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN,
					SoundCategory.BLOCKS, 1.0f, 0.7f);
		}
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

	// ================================================================ search area

	/**
	 * Breadth-first tree over the enclosed air space reachable from the entry. Outdoor (sky-visible)
	 * blocks are excluded so the probe stays inside buildings and caves. Every node knows its parent,
	 * so the probe's body always follows the tree from the entry to wherever the tip is.
	 */
	static final class SearchArea {
		final Vec3d center;
		final BlockPos root;
		final Map<BlockPos, BlockPos> parent = new HashMap<>();
		final Map<BlockPos, Integer> depth = new HashMap<>();
		final List<BlockPos> nodes = new ArrayList<>();
		private final TendrilController ctl;

		private SearchArea(TendrilController ctl, BlockPos root, Vec3d center) {
			this.ctl = ctl;
			this.root = root;
			this.center = center;
		}

		@Nullable
		static SearchArea build(TendrilController ctl, BlockPos entry, Vec3d center) {
			World world = ctl.owner.getWorld();
			SearchArea a = new SearchArea(ctl, entry.toImmutable(), center);
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(a.root);
			a.parent.put(a.root, a.root);
			a.depth.put(a.root, 0);
			while (!queue.isEmpty() && a.nodes.size() < MAX_SEARCH_NODES) {
				BlockPos cur = queue.poll();
				a.nodes.add(cur);
				for (Direction dir : Direction.values()) {
					BlockPos n = cur.offset(dir);
					if (a.parent.containsKey(n)) continue;
					if (Vec3d.ofCenter(n).distanceTo(center) > SEARCH_RADIUS) continue;
					if (!ctl.isTendrilPassable(n)) continue;
					if (world.isSkyVisible(n)) continue; // stay indoors
					a.parent.put(n, cur);
					a.depth.put(n, a.depth.get(cur) + 1);
					queue.add(n);
				}
			}
			return a;
		}

		int size() {
			return nodes.size();
		}

		int depth(BlockPos p) {
			return depth.getOrDefault(p, 999);
		}

		BlockPos randomNode(net.minecraft.util.math.random.Random random) {
			return nodes.get(random.nextInt(nodes.size()));
		}

		@Nullable
		BlockPos nearestNode(Vec3d p) {
			BlockPos best = null;
			double bd = Double.MAX_VALUE;
			for (BlockPos n : nodes) {
				double d = Vec3d.ofCenter(n).squaredDistanceTo(p);
				if (d < bd) {
					bd = d;
					best = n;
				}
			}
			return best;
		}

		int openness(BlockPos p) {
			int n = 0;
			for (Direction d : Direction.values()) if (parent.containsKey(p.offset(d))) n++;
			return n;
		}

		List<BlockPos> pathFromRoot(BlockPos p) {
			ArrayList<BlockPos> chain = new ArrayList<>();
			BlockPos cur = p;
			int guard = 0;
			while (cur != null && guard++ < 4000) {
				chain.add(cur);
				BlockPos par = parent.get(cur);
				if (par == null || par.equals(cur)) break;
				cur = par;
			}
			java.util.Collections.reverse(chain);
			return chain;
		}

		/** Tree path between two nodes: back up to the common ancestor, then down. Excludes {@code from}. */
		List<BlockPos> treePath(BlockPos from, BlockPos to) {
			List<BlockPos> a = pathFromRoot(from);
			List<BlockPos> b = pathFromRoot(to);
			int common = 0;
			while (common < a.size() && common < b.size() && a.get(common).equals(b.get(common))) common++;
			List<BlockPos> out = new ArrayList<>();
			for (int i = a.size() - 2; i >= common - 1 && i >= 0; i--) out.add(a.get(i));
			for (int i = Math.max(common, 0); i < b.size(); i++) out.add(b.get(i));
			return out;
		}
	}
}
