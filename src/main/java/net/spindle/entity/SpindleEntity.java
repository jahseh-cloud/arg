package net.spindle.entity;

import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.SpiderNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.spindle.entity.ai.SpindleDigGoal;
import net.spindle.entity.ai.SpindleFrozenGoal;
import net.spindle.entity.ai.SpindleLurkGoal;
import net.spindle.entity.ai.SpindleHuntGoal;
import net.spindle.entity.ai.SpindleRetreatGoal;
import net.spindle.entity.ai.SpindleSnuffLightGoal;
import net.spindle.entity.ai.SpindleStalkGoal;
import net.spindle.entity.ai.SpindleTargetGoal;
import net.spindle.entity.ai.SpindleTendrilGoal;
import net.spindle.entity.ai.SpindleTravelGoal;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * The Spindle.
 *
 * Core rules of its behaviour:
 *  - It cannot move while a player is looking at it AND it is standing in light.
 *    In the dark, or when it is within arm's reach of you, looking does not save you.
 *  - Because of that it hates light: it reaches out with its tendrils and snuffs torches.
 *  - It stalks first. Tension builds while it watches you (faster if you're alone, in the dark,
 *    underground, at night). When tension peaks it hunts.
 *  - It hunts the most vulnerable player, avoids groups, and retreats when badly hurt.
 *  - It folds its legs down to fit through 1x2 gaps and unfolds to full height in the open.
 *  - Its tendrils reach through doors and windows (breaking glass), grab players and drag them out.
 */
public class SpindleEntity extends HostileEntity {
	public static final float STANDING_WIDTH = 2.4f;
	public static final float STANDING_HEIGHT = 5.6f;
	public static final float FOLDED_WIDTH = 0.9f;
	public static final float FOLDED_HEIGHT = 1.4f;
	public static final double CORE_HEIGHT_STANDING = 4.9;
	public static final double CORE_HEIGHT_FOLDED = 0.9;
	public static final float HUNT_THRESHOLD = 4800f;

	public static final EntityDimensions STANDING = EntityDimensions.changing(STANDING_WIDTH, STANDING_HEIGHT).withEyeHeight(STANDING_HEIGHT - 0.6f);
	public static final EntityDimensions FOLDED = EntityDimensions.changing(FOLDED_WIDTH, FOLDED_HEIGHT).withEyeHeight(1.1f);

	private static final TrackedData<Boolean> CLIMBING = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> FOLDED_FLAG = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> MODE = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> HANGING = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> TENDRIL_FLAGS = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Vector3f> TENDRIL_TIP = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	private static final TrackedData<Vector3f> TENDRIL_LOOK = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);

	public static final int TENDRIL_ACTIVE = 1, TENDRIL_SEARCHING = 2, TENDRIL_ALERT = 4;

	public enum Mode { WANDER, STALK, HUNT, RETREAT }

	// ---------------------------------------------------------------- server state
	private Mode mode = Mode.WANDER;
	private float tension;
	private int huntTicks;
	private int retreatTicks;
	private float recentDamage;
	private float gazeDamage;
	private boolean watched;
	private boolean wasWatched;
	private boolean frozen;
	private boolean lit;
	private double nearestWatcherDist = Double.MAX_VALUE;
	private boolean relocateRequested;
	private int foldHoldTicks;
	private int attackCooldown;
	private int unreachableTicks;
	private final List<StolenItem> stolen = new ArrayList<>();
	public final TendrilController tendril = new TendrilController(this);
	// movement / stuck recovery
	@Nullable private Vec3d destination;
	private final ArrayDeque<Vec3d> recentPositions = new ArrayDeque<>();
	private int stuckChecks;
	private int stuckLevel;
	// memory of where the probe has already looked and which ways in it used (expires)
	public final List<Memory> searchedSpots = new ArrayList<>();
	public final List<Memory> usedEntries = new ArrayList<>();
	public record Memory(Vec3d pos, int expiresAt) {}

	// ---------------------------------------------------------------- client state
	public float foldProgress, prevFoldProgress;
	public float glow = 0.8f, prevGlow = 0.8f;
	public int attackAnimTicks;
	public Vec3d clientTip, prevClientTip;
	/** Bends in the tendril's body, received by packet. */
	public List<Vec3d> clientAnchors = List.of();
	public final SpindleLegState legs = new SpindleLegState();

	public SpindleEntity(EntityType<? extends SpindleEntity> type, World world) {
		super(type, world);
		this.experiencePoints = 60;
		this.ignoreCameraFrustum = true; // the model is far bigger than the hitbox
	}

	public static DefaultAttributeContainer.Builder createSpindleAttributes() {
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 200.0)
				.add(EntityAttributes.GENERIC_ARMOR, 12.0)
				.add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, 4.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 11.0)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 1.5)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 96.0)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
				.add(EntityAttributes.GENERIC_STEP_HEIGHT, 2.0)
				.add(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 64.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(CLIMBING, false);
		builder.add(FOLDED_FLAG, false);
		builder.add(MODE, 0);
		builder.add(TENDRIL_FLAGS, 0);
		builder.add(HANGING, false);
		builder.add(TENDRIL_TIP, new Vector3f());
		builder.add(TENDRIL_LOOK, new Vector3f(0, 0, 1));
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new SpindleFrozenGoal(this));
		this.goalSelector.add(2, new SpindleDigGoal(this));
		this.goalSelector.add(2, new SpindleRetreatGoal(this));
		this.goalSelector.add(3, new SpindleTendrilGoal(this));
		this.goalSelector.add(4, new SpindleHuntGoal(this));
		this.goalSelector.add(5, new SpindleSnuffLightGoal(this));
		this.goalSelector.add(6, new SpindleLurkGoal(this));
		this.goalSelector.add(7, new SpindleStalkGoal(this));
		this.goalSelector.add(8, new SpindleTravelGoal(this));
		this.goalSelector.add(9, new WanderAroundFarGoal(this, 0.7));
		this.targetSelector.add(1, new SpindleTargetGoal(this));
	}

	@Override
	protected EntityNavigation createNavigation(World world) {
		return new SpindleNavigation(this, world);
	}

	// ================================================================ dimensions / folding

	@Override
	public EntityDimensions getBaseDimensions(EntityPose pose) {
		return isFolded() ? FOLDED : STANDING;
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		if (FOLDED_FLAG.equals(data)) {
			this.calculateDimensions();
		}
		super.onTrackedDataSet(data);
	}

	public boolean isFolded() {
		return this.dataTracker.get(FOLDED_FLAG);
	}

	private void setFolded(boolean folded) {
		if (folded == isFolded()) return;
		this.dataTracker.set(FOLDED_FLAG, folded);
		EntityAttributeInstance step = getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT);
		if (step != null) step.setBaseValue(folded ? 1.0 : 2.0);
		playSound(SoundEvents.BLOCK_CHAIN_PLACE, 0.6f, folded ? 0.5f : 0.4f);
	}

	/** Called by goals when they can't find a path at full height. */
	public void requestFold() {
		setFolded(true);
		foldHoldTicks = 60;
	}

	public boolean canStandAt(Vec3d pos) {
		return getWorld().isSpaceEmpty(this, STANDING.getBoxAt(pos).expand(0.1, 0.0, 0.1));
	}

	private boolean canFoldAt(Vec3d pos) {
		return getWorld().isSpaceEmpty(this, FOLDED.getBoxAt(pos));
	}

	private void updateFold() {
		if (foldHoldTicks > 0) foldHoldTicks--;
		if (this.age % 4 != 0) return;
		Path path = getNavigation().getCurrentPath();
		if (isFolded()) {
			if (!isHanging() && foldHoldTicks <= 0 && canStandAt(getPos()) && pathAheadAllowsStanding(path)) {
				setFolded(false);
			}
		} else if (path != null && !path.isFinished()) {
			// Approaching a gap it can't fit through at full height: fold before it gets there.
			for (int i = path.getCurrentNodeIndex(); i < Math.min(path.getLength(), path.getCurrentNodeIndex() + 3); i++) {
				Vec3d node = Vec3d.ofBottomCenter(path.getNode(i).getBlockPos());
				if (!canStandAt(node) && canFoldAt(node)) {
					requestFold();
					break;
				}
			}
		}
	}

	private boolean pathAheadAllowsStanding(@Nullable Path path) {
		if (path == null || path.isFinished()) return true;
		int start = path.getCurrentNodeIndex();
		for (int i = start; i < Math.min(path.getLength(), start + 6); i++) {
			PathNode node = path.getNode(i);
			if (!canStandAt(Vec3d.ofBottomCenter(node.getBlockPos()))) return false;
		}
		return true;
	}

	/** Mirror plane height when hanging upside down (half the folded hitbox). */
	public static final double HANG_PIVOT = FOLDED_HEIGHT / 2.0;

	public double getCoreHeight() {
		if (isHanging()) return 2 * HANG_PIVOT - CORE_HEIGHT_FOLDED;
		return isFolded() ? CORE_HEIGHT_FOLDED : CORE_HEIGHT_STANDING;
	}

	public Vec3d getCorePos() {
		return getPos().add(0, getCoreHeight(), 0);
	}

	// ================================================================ climbing / physics

	@Override
	public boolean isClimbing() {
		return this.dataTracker.get(CLIMBING);
	}

	@Override
	public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
		return false;
	}

	@Override
	public boolean cannotDespawn() {
		return true;
	}

	@Override
	public boolean shouldRender(double distance) {
		return distance < 160.0 * 160.0;
	}

	// ================================================================ tick

	@Override
	public void tick() {
		super.tick();
		if (!getWorld().isClient) {
			this.dataTracker.set(CLIMBING, this.horizontalCollision && !frozen);
			serverTick((ServerWorld) getWorld());
		} else {
			clientTick();
		}
	}

	private void serverTick(ServerWorld world) {
		if (this.age % 2 == 0) updateWatchers(world);
		if (this.age % 20 == 0 && isAlive()) SpindleChunkLoader.get(world).update(world, getUuid(), getChunkPos());
		recentDamage = Math.max(0f, recentDamage - 0.05f);
		if (attackCooldown > 0) attackCooldown--;
		updateMode(world);
		if (isHanging() && mode != Mode.STALK) setHanging(false); // drops down to hunt / flee
		updateFold();
		tickStuck();
		tickMemory();
		tendril.tick(world);
		tickStolenItems(world);

		if (mode == Mode.STALK && !watched && getTarget() != null && distanceTo(getTarget()) < 26
				&& getRandom().nextInt(900) == 0) {
			playSound(net.spindle.SpindleSounds.PROBE_MUMBLE, 0.35f, 0.6f + getRandom().nextFloat() * 0.15f);
		}
		if (mode == Mode.RETREAT && !watched && this.age % 20 == 0) heal(1.0f);
		else if (this.age % 100 == 0) heal(1.0f);

		this.dataTracker.set(MODE, mode.ordinal());
	}

	private void clientTick() {
		prevFoldProgress = foldProgress;
		foldProgress += ((isFolded() ? 1f : 0f) - foldProgress) * 0.18f;

		prevGlow = glow;
		float targetGlow = switch (getSyncedMode()) {
			case HUNT -> 0.2f;      // it goes dark when it hunts
			case STALK -> 0.5f;
			case RETREAT -> 0.35f;
			case WANDER -> 0.85f;
		};
		glow += (targetGlow - glow) * 0.05f;

		if (attackAnimTicks > 0) attackAnimTicks--;
		legs.tick(this);

		Vec3d core = getPos().add(0, MathHelper.lerp(foldProgress, CORE_HEIGHT_STANDING, CORE_HEIGHT_FOLDED), 0);
		Vec3d target = isTendrilActive() ? getTendrilTip() : core;
		if (!isTendrilActive()) clientAnchors = List.of();
		prevClientTip = clientTip == null ? target : clientTip;
		clientTip = clientTip == null ? target : clientTip.lerp(target, 0.6);
	}

	@Override
	public void handleStatus(byte status) {
		if (status == 4) {
			attackAnimTicks = 12;
		} else {
			super.handleStatus(status);
		}
	}

	// ================================================================ perception

	private void updateWatchers(ServerWorld world) {
		boolean seen = false;
		double nearest = Double.MAX_VALUE;
		Vec3d[] points = {
				getCorePos(),
				getPos().add(0, getHeight() * 0.5, 0),
				getPos().add(0, getHeight() * 0.95, 0)
		};
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.isSpectator() || !player.isAlive()) continue;
			double d2 = player.squaredDistanceTo(this);
			if (d2 > 96 * 96) continue;
			if (playerSeesAny(player, points)) {
				seen = true;
				nearest = Math.min(nearest, Math.sqrt(d2));
			}
		}
		watched = seen;
		nearestWatcherDist = nearest;
		lit = Math.max(world.getLightLevel(BlockPos.ofFloored(getCorePos())),
				world.getLightLevel(BlockPos.ofFloored(getPos().add(0, 0.5, 0)))) >= 7;

		boolean close = nearestWatcherDist < 6.5;
		frozen = watched && lit && mode != Mode.RETREAT && !(mode == Mode.HUNT && close);

		if (mode == Mode.STALK || mode == Mode.WANDER) {
			// Looked at in the dark: it backs off. Looked away: it moves somewhere else.
			if (wasWatched && !watched) relocateRequested = true;
			if (watched && !lit) relocateRequested = true;
		}
		wasWatched = watched;
	}

	/** True if the player is facing the point and has a clear line of sight to it (glass doesn't block sight). */
	public boolean playerSeesAny(PlayerEntity player, Vec3d... points) {
		Vec3d eye = player.getEyePos();
		Vec3d look = player.getRotationVec(1.0f);
		for (Vec3d pt : points) {
			Vec3d to = pt.subtract(eye);
			double len = to.length();
			if (len < 0.01) return true;
			if (to.dotProduct(look) / len < 0.55) continue;
			if (hasLineOfSight(eye, pt)) return true;
		}
		return false;
	}

	public boolean hasLineOfSight(Vec3d from, Vec3d to) {
		return getWorld().raycast(new RaycastContext(from, to, RaycastContext.ShapeType.VISUAL,
				RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
	}

	/** Whether any player could see this spot (ignoring which way they face). */
	public boolean isSpotVisibleToAnyone(Vec3d spot) {
		Vec3d low = spot.add(0, 1.0, 0);
		Vec3d high = spot.add(0, CORE_HEIGHT_STANDING, 0);
		for (PlayerEntity player : getWorld().getPlayers()) {
			if (player.isSpectator() || !player.isAlive()) continue;
			if (player.squaredDistanceTo(spot) > 100 * 100) continue;
			Vec3d eye = player.getEyePos();
			if (hasLineOfSight(eye, low) || hasLineOfSight(eye, high)) return true;
		}
		return false;
	}

	public boolean isInDarkness(Entity entity) {
		return getWorld().getLightLevel(entity.getBlockPos()) < 7;
	}

	public int countPlayersNear(Entity center, double radius) {
		int n = 0;
		for (PlayerEntity p : getWorld().getPlayers()) {
			if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
			if (p.squaredDistanceTo(center) <= radius * radius) n++;
		}
		return n;
	}

	/** Random standable ground position in a ring around center (folded size must fit). */
	@Nullable
	public Vec3d randomGroundNear(Vec3d center, double minR, double maxR) {
		World world = getWorld();
		for (int attempt = 0; attempt < 6; attempt++) {
			double ang = getRandom().nextDouble() * Math.PI * 2;
			double r = minR + getRandom().nextDouble() * (maxR - minR);
			int x = MathHelper.floor(center.x + Math.cos(ang) * r);
			int z = MathHelper.floor(center.z + Math.sin(ang) * r);
			int cy = MathHelper.floor(center.y);
			for (int dy = 6; dy >= -8; dy--) {
				BlockPos p = new BlockPos(x, cy + dy, z);
				if (!world.getBlockState(p.down()).isSideSolidFullSquare(world, p.down(), Direction.UP)) continue;
				if (!world.getFluidState(p).isEmpty()) continue;
				Vec3d v = Vec3d.ofBottomCenter(p);
				if (!world.isSpaceEmpty(FOLDED.getBoxAt(v))) continue;
				return v;
			}
		}
		return null;
	}

	/** Samples spots and returns the highest scoring one (score <= -1e8 means rejected). */
	@Nullable
	public Vec3d findSpot(Vec3d center, double minR, double maxR, int tries, ToDoubleFunction<Vec3d> score) {
		Vec3d best = null;
		double bestScore = -1e8;
		for (int i = 0; i < tries; i++) {
			Vec3d c = randomGroundNear(center, minR, maxR);
			if (c == null) continue;
			double s = score.applyAsDouble(c);
			if (s > bestScore) {
				bestScore = s;
				best = c;
			}
		}
		return best;
	}

	// ================================================================ mode / tension

	private boolean isValidTarget(@Nullable LivingEntity target) {
		if (target == null || !target.isAlive() || target.isRemoved()) return false;
		if (target instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return false;
		return target.getWorld() == getWorld() && squaredDistanceTo(target) < 96 * 96;
	}

	private void updateMode(ServerWorld world) {
		LivingEntity target = getTarget();
		boolean valid = isValidTarget(target);
		if (!valid && target != null) setTarget(null);

		float hp = getHealth() / getMaxHealth();
		if (mode != Mode.RETREAT && (hp < 0.3f || recentDamage > 30f || gazeDamage > 14f)) {
			startRetreat(1200);
		}

		if (mode == Mode.RETREAT) {
			retreatTicks--;
			if (retreatTicks <= 0 && hp > 0.6f) {
				mode = valid ? Mode.STALK : Mode.WANDER;
				gazeDamage = 0;
				relocateRequested = true;
			}
			return;
		}

		if (!valid) {
			mode = Mode.WANDER;
			tension = Math.max(0f, tension - 1f);
			return;
		}

		double dist = distanceTo(target);
		boolean dark = isInDarkness(target);
		boolean underground = !world.isSkyVisible(target.getBlockPos());
		boolean exposedDay = world.isDay() && !underground && !dark;
		int group = countPlayersNear(target, 16);
		boolean alone = countPlayersNear(target, 24) <= 1;

		switch (mode) {
			case WANDER -> {
				mode = Mode.STALK;
				relocateRequested = true;
			}
			case STALK -> {
				float rate = 1f;
				if (alone) rate *= 1.5f;
				if (dark) rate *= 2f;
				if (underground) rate *= 1.5f;
				if (world.isNight()) rate *= 1.5f;
				if (exposedDay) rate *= 0.2f;
				if (watched) rate *= 0.25f;
				if (group >= 3) rate = 0f;
				tension += rate;

				boolean opportunity = dist < 7 && !watched && !exposedDay && group < 3;
				if ((tension >= HUNT_THRESHOLD && !exposedDay && group < 3) || opportunity) {
					startHunt();
				}
			}
			case HUNT -> {
				if (!tendril.isSearching()) huntTicks++;
				boolean giveUp = dist > 64
						|| (exposedDay && huntTicks > 200)
						|| (group >= 3 && huntTicks > 100)
						|| huntTicks > 1600;
				if (giveUp) endHunt();
			}
			default -> {}
		}
	}

	public void startHunt() {
		if (mode == Mode.RETREAT) return;
		if (mode != Mode.HUNT) {
			playSound(SoundEvents.ENTITY_SPIDER_AMBIENT, 2.0f, 0.35f);
		}
		mode = Mode.HUNT;
		huntTicks = 0;
	}

	private void endHunt() {
		tension = 0;
		huntTicks = 0;
		startRetreat(300); // vanish for a while, then start over
	}

	private void startRetreat(int ticks) {
		setHanging(false);
		mode = Mode.RETREAT;
		retreatTicks = ticks;
		tendril.forceRelease();
		getNavigation().stop();
	}

	/** Called by the tendril when it touches a player: being touched ends the stalking phase. */
	public void onTendrilContact() {
		if (mode == Mode.STALK || mode == Mode.WANDER) startHunt();
	}

	// ================================================================ combat

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean result = super.damage(source, amount);
		if (result && !getWorld().isClient) {
			recentDamage += amount;
			if (isHanging()) setHanging(false);
			if (frozen) gazeDamage += amount;
			tendril.onOwnerDamaged(amount);
			if (source.getAttacker() instanceof PlayerEntity player && !player.isCreative() && mode != Mode.RETREAT) {
				setTarget(player);
				startHunt();
			}
		}
		return result;
	}

	@Override
	public boolean tryAttack(Entity target) {
		getWorld().sendEntityStatus(this, (byte) 4);
		attackCooldown = 24;
		return super.tryAttack(target);
	}

	public boolean canMeleeNow() {
		return attackCooldown <= 0;
	}

	public boolean isInMeleeReach(LivingEntity target) {
		double dx = target.getX() - getX();
		double dz = target.getZ() - getZ();
		double reach = getWidth() / 2.0 + 2.2;
		double dy = target.getY() - getY();
		return dx * dx + dz * dz <= reach * reach && dy > -1.5 && dy < getHeight() + 0.5;
	}

	@Override
	public void onDeath(DamageSource damageSource) {
		super.onDeath(damageSource);
		if (!getWorld().isClient) {
			for (StolenItem s : stolen) dropStack(s.stack);
			stolen.clear();
			tendril.forceRelease();
		}
	}

	// ================================================================ lights

	public static boolean isSnuffable(BlockState state) {
		if (state.getBlock() instanceof AbstractTorchBlock) return true;
		if (state.getBlock() instanceof LanternBlock) return true;
		if (state.isOf(Blocks.JACK_O_LANTERN)) return true;
		if (state.getBlock() instanceof AbstractCandleBlock && state.contains(AbstractCandleBlock.LIT) && state.get(AbstractCandleBlock.LIT)) return true;
		return state.getBlock() instanceof CampfireBlock && state.get(CampfireBlock.LIT);
	}

	public void snuffLight(BlockPos pos) {
		World world = getWorld();
		BlockState state = world.getBlockState(pos);
		if (!isSnuffable(state)) return;
		if (state.getBlock() instanceof AbstractCandleBlock) {
			AbstractCandleBlock.extinguish(null, state, world, pos);
		} else if (state.getBlock() instanceof CampfireBlock) {
			CampfireBlock.extinguish(this, world, pos, state);
			world.setBlockState(pos, state.with(CampfireBlock.LIT, false));
		} else {
			world.breakBlock(pos, true, this);
		}
	}

	public boolean canGrief() {
		return getWorld() instanceof ServerWorld sw && sw.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
	}

	// ================================================================ stolen items

	public void addStolen(UUID owner, ItemStack stack) {
		stolen.add(new StolenItem(owner, stack, 0));
	}

	/** Stolen items turn up later on the owner's bed. Nobody sees it happen. */
	private void tickStolenItems(ServerWorld world) {
		if (stolen.isEmpty()) return;
		for (StolenItem s : stolen) s.age++;
		if (this.age % 100 != 0 || watched) return;

		stolen.removeIf(s -> {
			if (s.age < 1200) return false;
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(s.owner);
			if (owner != null) {
				BlockPos bed = owner.getSpawnPointPosition();
				RegistryKey<World> dim = owner.getSpawnPointDimension();
				if (bed != null && dim == world.getRegistryKey() && world.isChunkLoaded(bed)) {
					Vec3d drop = Vec3d.ofCenter(bed).add(0, 0.6, 0);
					if (isSpotVisibleToAnyone(drop.subtract(0, 1.0, 0)) && s.age < 12000) return false; // wait until nobody is looking
					ItemEntity item = new ItemEntity(world, drop.x, drop.y, drop.z, s.stack, 0, 0, 0);
					item.setNeverDespawn();
					world.spawnEntity(item);
					return true;
				}
			}
			if (s.age > 6000) {
				dropStack(s.stack);
				return true;
			}
			return false;
		});
	}

	private static final class StolenItem {
		final UUID owner;
		final ItemStack stack;
		int age;

		StolenItem(UUID owner, ItemStack stack, int age) {
			this.owner = owner;
			this.stack = stack;
			this.age = age;
		}
	}

	// ================================================================ tendril sync

	public void syncTendril(boolean active, boolean searching, boolean alert, Vec3d tip, Vec3d look) {
		int flags = 0;
		if (active) flags |= TENDRIL_ACTIVE;
		if (searching) flags |= TENDRIL_SEARCHING;
		if (alert) flags |= TENDRIL_ALERT;
		this.dataTracker.set(TENDRIL_FLAGS, flags);
		if (active) {
			this.dataTracker.set(TENDRIL_TIP, tip.toVector3f());
			this.dataTracker.set(TENDRIL_LOOK, look.toVector3f());
		}
	}

	public boolean isTendrilAlert() {
		return (getTendrilFlags() & TENDRIL_ALERT) != 0;
	}

	public boolean isTendrilSearching() {
		return (getTendrilFlags() & TENDRIL_SEARCHING) != 0;
	}

	public Vec3d getTendrilLook() {
		return new Vec3d(this.dataTracker.get(TENDRIL_LOOK));
	}

	/** The probe came back empty. It's frustrated: it gets closer to just hunting. */
	public void onSearchFailed() {
		tension = Math.min(HUNT_THRESHOLD, tension + 600f);
	}

	public int getTendrilFlags() {
		return this.dataTracker.get(TENDRIL_FLAGS);
	}

	public boolean isTendrilActive() {
		return (getTendrilFlags() & TENDRIL_ACTIVE) != 0;
	}

	public Vec3d getTendrilTip() {
		return new Vec3d(this.dataTracker.get(TENDRIL_TIP));
	}


	// ================================================================ accessors for goals

	public Mode getMode() {
		return mode;
	}

	public Mode getSyncedMode() {
		int i = this.dataTracker.get(MODE);
		Mode[] values = Mode.values();
		return values[MathHelper.clamp(i, 0, values.length - 1)];
	}

	public float getTension() {
		return tension;
	}

	public float getTensionFraction() {
		return MathHelper.clamp(tension / HUNT_THRESHOLD, 0f, 1f);
	}

	public boolean isWatched() {
		return watched;
	}

	public boolean isFrozen() {
		return frozen;
	}

	public boolean consumeRelocateRequest() {
		boolean r = relocateRequested;
		relocateRequested = false;
		return r;
	}

	public void markUnreachable(boolean unreachable) {
		unreachableTicks = unreachable ? unreachableTicks + 1 : 0;
	}

	public int getUnreachableTicks() {
		return unreachableTicks;
	}

	public double getSpeedFactor() {
		return isFolded() ? 0.8 : 1.0;
	}

	// ================================================================ movement helpers / stuck recovery

	/** All goals move through here so the stuck detector knows where it's trying to go. */
	public boolean travelTo(Vec3d dest, double speed) {
		destination = dest;
		return getNavigation().startMovingTo(dest.x, dest.y, dest.z, speed * getSpeedFactor());
	}

	public void setDestination(@Nullable Vec3d dest) {
		destination = dest;
	}

	@Nullable
	public Vec3d getDestination() {
		return destination;
	}

	public int getStuckLevel() {
		return stuckLevel;
	}

	public void clearStuck() {
		stuckLevel = 0;
		stuckChecks = 0;
		recentPositions.clear();
	}

	/**
	 * Every second: if it's trying to get somewhere but hasn't moved a block in 3 seconds, escalate:
	 * 1 = fold and re-plan, 2 = back off and come at it from somewhere else, 3 = dig (SpindleDigGoal).
	 */
	private void tickStuck() {
		if (this.age % 20 != 0) return;
		boolean busy = frozen || isHanging() || !tendril.isIdle();
		if (destination == null || busy || squaredDistanceTo(destination) < 3.0 * 3.0) {
			if (destination != null && squaredDistanceTo(destination) < 3.0 * 3.0) destination = null;
			if (stuckLevel < 3) clearStuck();
			return;
		}
		recentPositions.addLast(getPos());
		while (recentPositions.size() > 4) recentPositions.removeFirst();
		if (recentPositions.size() < 4) return;
		double moved = recentPositions.getFirst().distanceTo(recentPositions.getLast());
		if (moved > 1.5) {
			stuckChecks = 0;
			if (stuckLevel < 3) stuckLevel = 0;
			return;
		}
		stuckChecks++;
		if (stuckLevel == 0 && stuckChecks >= 1) {
			stuckLevel = 1;
			requestFold();
			getNavigation().startMovingTo(destination.x, destination.y, destination.z, getSpeedFactor());
		} else if (stuckLevel == 1 && stuckChecks >= 4) {
			stuckLevel = 2;
			Vec3d away = randomGroundNear(getPos(), 4, 9);
			if (away != null) getNavigation().startMovingTo(away.x, away.y, away.z, 1.2);
		} else if (stuckLevel == 2 && stuckChecks >= 7) {
			stuckLevel = canGrief() ? 3 : 0;
			if (stuckLevel == 0) stuckChecks = 0;
		}
	}

	private void tickMemory() {
		if (this.age % 100 != 0) return;
		searchedSpots.removeIf(m -> m.expiresAt() < this.age);
		usedEntries.removeIf(m -> m.expiresAt() < this.age);
		while (searchedSpots.size() > 96) searchedSpots.remove(0);
	}

	public void rememberSearched(Vec3d pos) {
		searchedSpots.add(new Memory(pos, this.age + 6000));
	}

	public void rememberEntry(Vec3d pos) {
		usedEntries.add(new Memory(pos, this.age + 6000));
	}

	// ================================================================ hanging from cave ceilings

	public boolean isHanging() {
		return this.dataTracker.get(HANGING);
	}

	public void setHanging(boolean hanging) {
		if (hanging == isHanging()) return;
		this.dataTracker.set(HANGING, hanging);
		setNoGravity(hanging);
		if (hanging) {
			requestFold();
			foldHoldTicks = Integer.MAX_VALUE / 2;
			setVelocity(Vec3d.ZERO);
		} else {
			foldHoldTicks = 40;
		}
	}

	// ================================================================ sounds

	@Nullable
	@Override
	public SoundEvent getAmbientSound() {
		// Silent while stalking. You only hear it when it's already hunting.
		return mode == Mode.HUNT ? SoundEvents.ENTITY_SPIDER_AMBIENT : null;
	}

	@Override
	public SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_SPIDER_HURT;
	}

	@Override
	public SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_SPIDER_DEATH;
	}

	@Override
	public float getSoundPitch() {
		return 0.4f + getRandom().nextFloat() * 0.1f;
	}

	@Override
	public float getSoundVolume() {
		return 1.6f;
	}

	@Override
	public int getMinAmbientSoundDelay() {
		return 100;
	}

	@Override
	public void playStepSound(BlockPos pos, BlockState state) {
		// Footfalls are played per foot by SpindleLegState on the client.
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (getWorld() instanceof ServerWorld sw && reason.shouldDestroy()) {
			SpindleChunkLoader.get(sw).forget(getUuid());
		}
		super.remove(reason);
	}

	// ================================================================ save / load

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putFloat("Tension", tension);
		nbt.putString("Mode", mode.name());
		nbt.putInt("RetreatTicks", retreatTicks);
		nbt.putBoolean("Folded", isFolded());
		nbt.putBoolean("Hanging", isHanging());
		NbtList list = new NbtList();
		for (StolenItem s : stolen) {
			if (s.stack.isEmpty()) continue;
			NbtCompound c = new NbtCompound();
			c.putUuid("Owner", s.owner);
			c.putInt("Age", s.age);
			c.put("Item", s.stack.encode(getRegistryManager()));
			list.add(c);
		}
		nbt.put("Stolen", list);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		tension = nbt.getFloat("Tension");
		try {
			mode = Mode.valueOf(nbt.getString("Mode"));
		} catch (IllegalArgumentException e) {
			mode = Mode.WANDER;
		}
		retreatTicks = nbt.getInt("RetreatTicks");
		this.dataTracker.set(FOLDED_FLAG, nbt.getBoolean("Folded"));
		if (nbt.getBoolean("Hanging")) setHanging(true);
		stolen.clear();
		NbtList list = nbt.getList("Stolen", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound c = list.getCompound(i);
			UUID owner = c.getUuid("Owner");
			int age = c.getInt("Age");
			ItemStack.fromNbt(getRegistryManager(), c.get("Item"))
					.ifPresent(stack -> stolen.add(new StolenItem(owner, stack, age)));
		}
	}
}
