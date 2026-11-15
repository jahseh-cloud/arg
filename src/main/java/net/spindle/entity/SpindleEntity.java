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
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.DamageTypeTags;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.spindle.SpindleSounds;
import net.spindle.entity.ai.SpindleDigGoal;
import net.spindle.entity.ai.SpindleFrozenGoal;
import net.spindle.entity.ai.SpindleHuntGoal;
import net.spindle.entity.ai.SpindleLurkGoal;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * The Spindle.
 *
 * Rules of its behaviour:
 *  - It cannot move while a player is looking at it AND it is standing in light.
 *    In the dark, or when it is within arm's reach of you, looking does not save you.
 *  - Because of that it hates light: it reaches out with its tendrils and snuffs torches.
 *  - It stalks first. Tension builds while it shadows you (faster if you're alone, in the dark,
 *    underground, at night). When tension peaks it hunts.
 *  - While hunting it has to actually find you: it follows what it can see and hear. Walking is heard
 *    nearby, sprinting from further away, sneaking and standing still are silent. Break line of sight
 *    quietly and it goes to where it last knew you were and searches, sending its probe into buildings.
 *  - It hunts the most vulnerable player, avoids groups, and retreats when badly hurt.
 *  - Its body rides as high as the space allows: 5.6 blocks tall in the open, hunched under a ceiling,
 *    folded flat to squeeze through a 1x2 tunnel. The hitbox is the narrow column under its body.
 *  - Its tendrils reach through doors and windows (breaking glass), grab players and drag them out.
 */
public class SpindleEntity extends HostileEntity {
	public static final float WIDTH = 0.9f;
	public static final float MAX_HEIGHT = 5.6f;
	public static final float MIN_HEIGHT = 1.4f;
	public static final float HUNT_THRESHOLD = 7200f;
	/** Mirror plane height when hanging upside down (half the folded hitbox). */
	public static final double HANG_PIVOT = MIN_HEIGHT / 2.0;

	private static final TrackedData<Float> HEIGHT = DataTracker.registerData(SpindleEntity.class, TrackedDataHandlerRegistry.FLOAT);
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
	private int attackCooldown;
	private int unreachableTicks;
	private boolean pathBlocked;
	private int crouchTicks;
	private float lastShiftSoundHeight = MAX_HEIGHT;
	private EntityDimensions dims;
	private float dimsHeight = -1f;
	private final List<StolenItem> stolen = new ArrayList<>();
	public final TendrilController tendril = new TendrilController(this);
	// senses
	private final Map<UUID, Vec3d> playerPos = new HashMap<>();
	private final Map<UUID, Float> playerSpeed = new HashMap<>();
	@Nullable private Vec3d lastKnownPos;
	private Vec3d lastKnownVel = Vec3d.ZERO;
	private int lostTicks;
	private int noiseTicks;
	private boolean sawTarget;
	// movement / stuck recovery
	@Nullable private Vec3d destination;
	private final ArrayDeque<Vec3d> recentPositions = new ArrayDeque<>();
	private int stuckChecks;
	private int stuckLevel;
	// memory of where the probe has already looked and which ways in it used (expires)
	public final List<Memory> searchedSpots = new ArrayList<>();
	public final List<Memory> usedEntries = new ArrayList<>();
	public record Memory(Vec3d pos, int expiresAt) {}
	/** Counters for /spindle debug and the automated tests. */
	public int statAttacks, statGrabs, statBlocksDug, statProbeSearches, statProbeSpots;
	/** Free-form note goals leave for /spindle debug and the tests. */
	public String debugNote = "";

	// ---------------------------------------------------------------- client state
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
		builder.add(HEIGHT, MAX_HEIGHT);
		builder.add(MODE, 0);
		builder.add(HANGING, false);
		builder.add(TENDRIL_FLAGS, 0);
		builder.add(TENDRIL_TIP, new Vector3f());
		builder.add(TENDRIL_LOOK, new Vector3f(0, 0, 1));
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new SpindleFrozenGoal(this));
		this.goalSelector.add(1, new LongDoorInteractGoal(this, true));
		this.goalSelector.add(2, new SpindleRetreatGoal(this));
		this.goalSelector.add(3, new SpindleTendrilGoal(this));
		this.goalSelector.add(4, new SpindleDigGoal(this));
		this.goalSelector.add(5, new SpindleHuntGoal(this));
		this.goalSelector.add(6, new SpindleSnuffLightGoal(this));
		this.goalSelector.add(7, new SpindleLurkGoal(this));
		this.goalSelector.add(8, new SpindleStalkGoal(this));
		this.goalSelector.add(9, new SpindleTravelGoal(this));
		this.goalSelector.add(10, new WanderAroundFarGoal(this, 0.7));
		this.targetSelector.add(1, new SpindleTargetGoal(this));
	}

	@Override
	protected EntityNavigation createNavigation(World world) {
		return new SpindleNavigation(this, world);
	}

	// ================================================================ body height / posture

	@Override
	public EntityDimensions getBaseDimensions(EntityPose pose) {
		float h = getBodyHeight();
		if (dims == null || dimsHeight != h) {
			dims = EntityDimensions.changing(WIDTH, h).withEyeHeight(Math.max(0.9f, h - 0.6f));
			dimsHeight = h;
		}
		return dims;
	}

	@Override
	public void onTrackedDataSet(TrackedData<?> data) {
		if (HEIGHT.equals(data)) {
			this.calculateDimensions();
		}
		super.onTrackedDataSet(data);
	}

	/** Current height of the hitbox: from 1.4 (folded flat) to 5.6 (standing tall). */
	public float getBodyHeight() {
		return this.dataTracker.get(HEIGHT);
	}

	public static float foldOf(float height) {
		return MathHelper.clamp((MAX_HEIGHT - height) / (MAX_HEIGHT - MIN_HEIGHT), 0f, 1f);
	}

	/** 0 = standing tall, 1 = folded flat. */
	public float getFold() {
		return foldOf(getBodyHeight());
	}

	public boolean isCrouched() {
		return getBodyHeight() < 2.6f;
	}

	/** Height of the core (where tendrils come out) above the feet, for a given body height. */
	public static double coreHeightFor(float height) {
		return MathHelper.lerp(foldOf(height), 4.55, 0.6);
	}

	public double getCoreHeight() {
		if (isHanging()) return 2 * HANG_PIVOT - coreHeightFor(MIN_HEIGHT);
		return coreHeightFor(getBodyHeight());
	}

	public Vec3d getCorePos() {
		return getPos().add(0, getCoreHeight(), 0);
	}

	private void setBodyHeight(float h) {
		h = MathHelper.clamp((float) Math.floor(h * 5f + 0.01f) / 5f, MIN_HEIGHT, MAX_HEIGHT);
		if (Math.abs(h - getBodyHeight()) < 0.01f) return;
		this.dataTracker.set(HEIGHT, h);
		EntityAttributeInstance step = getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT);
		double stepHeight = h >= 3.0f ? 2.0 : 1.0;
		if (step != null && step.getBaseValue() != stepHeight) step.setBaseValue(stepHeight);
		if (Math.abs(h - lastShiftSoundHeight) > 1.9f) {
			lastShiftSoundHeight = h;
			if (this.age > 5) playSound(SpindleSounds.SHIFT, 0.6f, 0.45f);
		}
	}

	/** Goals call this to keep it folded flat for a while (digging, squeezing past something). */
	public void forceCrouch(int ticks) {
		crouchTicks = Math.max(crouchTicks, ticks);
	}

	/**
	 * How tall it could be at this spot: the height of the free column above it, less a margin for
	 * the body. Between MIN_HEIGHT and MAX_HEIGHT.
	 */
	public float clearHeightAt(Vec3d p) {
		double top = p.y + MAX_HEIGHT + 0.5;
		Box column = new Box(p.x - 0.42, p.y + 1.0, p.z - 0.42, p.x + 0.42, top, p.z + 0.42);
		double ceiling = top;
		for (VoxelShape shape : getWorld().getBlockCollisions(this, column)) {
			double min = shape.getMin(Direction.Axis.Y);
			if (min > p.y + 0.9 && min < ceiling) ceiling = min;
		}
		return (float) MathHelper.clamp(ceiling - p.y - 0.4, MIN_HEIGHT, MAX_HEIGHT);
	}

	/** Ducks before it reaches a low ceiling on its path, rises again once there's room. */
	private void updatePosture() {
		if (crouchTicks > 0) crouchTicks--;
		if (this.age % 2 != 0) return;
		float current = getBodyHeight();
		float want;
		if (isHanging() || crouchTicks > 0) {
			want = MIN_HEIGHT;
		} else {
			want = clearHeightAt(getPos());
			Path path = getNavigation().getCurrentPath();
			if (path != null && !path.isFinished()) {
				int start = path.getCurrentNodeIndex();
				for (int i = start; i < Math.min(path.getLength(), start + 5); i++) {
					want = Math.min(want, clearHeightAt(Vec3d.ofBottomCenter(path.getNode(i).getBlockPos())));
				}
				if (this.horizontalCollision && current > MIN_HEIGHT + 0.1f) {
					// Snagged on something the look-ahead missed: drop flat and squeeze past.
					crouchTicks = 30;
					want = MIN_HEIGHT;
				}
			}
		}
		if (want < current - 0.01f) {
			setBodyHeight(want);
		} else if (want > current + 0.39f) {
			setBodyHeight(Math.min(want, current + 0.4f));
		}
	}

	// ================================================================ physics

	@Override
	public boolean isClimbing() {
		return false;
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
	public boolean isPushable() {
		return false;
	}

	@Override
	public void pushAwayFrom(Entity entity) {
	}

	@Override
	protected void pushAway(Entity entity) {
	}

	@Override
	public boolean isPushedByFluids() {
		return false;
	}

	@Override
	public void slowMovement(BlockState state, Vec3d multiplier) {
		// Cobwebs, berry bushes and powder snow don't slow it.
	}

	@Override
	public boolean startRiding(Entity entity, boolean force) {
		return false; // no trapping it in a boat or minecart
	}

	@Override
	/**
	 * It doesn't belong to this world, and the world can't hurt it: no suffocation, fire, fall, cactus,
	 * freezing, dripstone, stray arrows... Only players, mobs actually fighting it, explosions, the void
	 * and /kill can.
	 */
	public boolean isInvulnerableTo(DamageSource source) {
		if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.isInvulnerableTo(source);
		Entity attacker = source.getAttacker();
		if (attacker == null) return !source.isIn(DamageTypeTags.IS_EXPLOSION) || super.isInvulnerableTo(source);
		if (attacker instanceof PlayerEntity) return super.isInvulnerableTo(source);
		if (attacker instanceof MobEntity m && m.getTarget() == this) return super.isInvulnerableTo(source);
		if (source.isIn(DamageTypeTags.IS_EXPLOSION)) return super.isInvulnerableTo(source);
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
			serverTick((ServerWorld) getWorld());
		} else {
			clientTick();
		}
	}

	/** Screenshot harness only: hold whatever pose it's put in (posture and tendril still update). */
	public boolean posed;
	/** Lives in the Hivemind: sleeps until someone comes near, never leaves, doesn't keep chunks loaded. */
	private boolean hiveBound;

	public void setHiveBound(boolean v) {
		hiveBound = v;
	}

	public boolean isHiveBound() {
		return hiveBound;
	}

	/** Dies with the Broodmother: falls apart into ink and static. No drops. */
	public void collapse() {
		if (!(getWorld() instanceof ServerWorld world) || isRemoved()) return;
		world.spawnParticles(net.minecraft.particle.ParticleTypes.SQUID_INK, getX(), getY() + getBodyHeight() / 2, getZ(),
				40, 1.2, getBodyHeight() / 2, 1.2, 0.05);
		world.playSound(null, getX(), getY(), getZ(), SpindleSounds.DEATH, net.minecraft.sound.SoundCategory.HOSTILE, 2f, 0.3f);
		tendril.forceRelease();
		SpindleChunkLoader.get(world).forget(getUuid());
		discard();
	}

	private void serverTick(ServerWorld world) {
		if (hiveBound && this.age % 20 == 0) {
			PlayerEntity near = world.getClosestPlayer(getX(), getY(), getZ(), 40,
					e -> e instanceof PlayerEntity p && !p.isSpectator() && !p.isCreative());
			boolean asleep = near == null && getTarget() == null && recentDamage <= 0;
			if (asleep != isAiDisabled()) setAiDisabled(asleep);
		}
		if (hiveBound && isAiDisabled()) return;
		if (posed) {
			if (this.age % 20 == 0) SpindleChunkLoader.get(world).update(world, getUuid(), getChunkPos());
			updatePosture();
			tendril.tick(world);
			this.dataTracker.set(MODE, mode.ordinal());
			return;
		}
		samplePlayers(world);
		if (this.age % 2 == 0) updateWatchers(world);
		if (this.age % 20 == 0 && isAlive() && !hiveBound) SpindleChunkLoader.get(world).update(world, getUuid(), getChunkPos());
		recentDamage = Math.max(0f, recentDamage - 0.05f);
		if (attackCooldown > 0) attackCooldown--;
		updateMode(world);
		updateAwareness();
		if (mode == Mode.HUNT && pathBlocked && isTracking() && !frozen) unreachableTicks++;
		else if (mode != Mode.HUNT) {
			unreachableTicks = 0;
			pathBlocked = false;
		}
		if (isHanging() && mode != Mode.STALK) setHanging(false); // drops down to hunt / flee
		updatePosture();
		tickStuck();
		tickMemory();
		tendril.tick(world);
		tickStolenItems(world);

		if (this.age % 4 == 0) swatMobsInTheWay(world);
		if (mode == Mode.STALK && !watched && getTarget() != null && distanceTo(getTarget()) < 26
				&& getRandom().nextInt(900) == 0) {
			playSound(SpindleSounds.PROBE_MUMBLE, 0.35f, 0.6f + getRandom().nextFloat() * 0.15f);
		}
		if (mode == Mode.RETREAT && !watched && this.age % 20 == 0) heal(1.0f);
		else if (this.age % 100 == 0) heal(1.0f);

		this.dataTracker.set(MODE, mode.ordinal());
	}

	private void clientTick() {
		prevGlow = glow;
		float targetGlow = switch (getSyncedMode()) {
			case HUNT -> 0.2f;      // it goes dark when it hunts
			case STALK -> 0.5f;
			case RETREAT -> 0.35f;
			case WANDER -> 0.85f;
		};
		if (isDead()) targetGlow = 0f;
		glow += (targetGlow - glow) * 0.05f;

		if (attackAnimTicks > 0) attackAnimTicks--;
		legs.tick(this);

		Vec3d core = getCorePos();
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

	// ================================================================ perception: who is watching it

	private void updateWatchers(ServerWorld world) {
		boolean seen = false;
		double nearest = Double.MAX_VALUE;
		float h = getBodyHeight();
		Vec3d[] points = {
				getCorePos(),
				getPos().add(0, h * 0.5, 0),
				getPos().add(0, h * 0.95, 0)
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

	/** Whether any player has a line of sight to this spot (ignoring which way they face and how dark it is). */
	public boolean isSpotVisibleToAnyone(Vec3d spot) {
		return spotExposure(spot, false);
	}

	/**
	 * Whether it could stand at this spot without being noticed: no player has a line of sight to it,
	 * or it's dark there and they're too far away to make it out.
	 */
	public boolean isSpotHidden(Vec3d spot) {
		return !spotExposure(spot, true);
	}

	private boolean spotExposure(Vec3d spot, boolean darknessHides) {
		World world = getWorld();
		Vec3d low = spot.add(0, 1.0, 0);
		Vec3d high = spot.add(0, coreHeightFor(clearHeightAt(spot)), 0);
		boolean dark = darknessHides && world.getLightLevel(BlockPos.ofFloored(low)) < 4;
		for (PlayerEntity player : world.getPlayers()) {
			if (player.isSpectator() || !player.isAlive()) continue;
			double d2 = player.squaredDistanceTo(spot);
			if (d2 > 100 * 100) continue;
			if (dark && d2 > 10 * 10) continue;
			Vec3d eye = player.getEyePos();
			if (hasLineOfSight(eye, low) || hasLineOfSight(eye, high)) return true;
		}
		return false;
	}

	/** Whether the spot is inside any player's field of view (and they have a line of sight to it). */
	public boolean isSpotInSomeonesView(Vec3d spot) {
		Vec3d mid = spot.add(0, 1.5, 0);
		Vec3d high = spot.add(0, coreHeightFor(clearHeightAt(spot)), 0);
		for (PlayerEntity player : getWorld().getPlayers()) {
			if (player.isSpectator() || !player.isAlive()) continue;
			if (player.squaredDistanceTo(spot) > 100 * 100) continue;
			if (playerSeesAny(player, mid, high)) return true;
		}
		return false;
	}

	public boolean isInDarkness(Entity entity) {
		return getWorld().getLightLevel(entity.getBlockPos()) < 7;
	}

	public boolean isEnclosed(Vec3d pos) {
		return SpaceMap.hasRoof(getWorld(), BlockPos.ofFloored(pos));
	}

	public int countPlayersNear(Entity center, double radius) {
		int n = 0;
		for (PlayerEntity p : getWorld().getPlayers()) {
			if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
			if (p.squaredDistanceTo(center) <= radius * radius) n++;
		}
		return n;
	}

	/** Random standable ground position in a ring around center. */
	@Nullable
	public Vec3d randomGroundNear(Vec3d center, double minR, double maxR) {
		World world = getWorld();
		for (int attempt = 0; attempt < 6; attempt++) {
			double ang = getRandom().nextDouble() * Math.PI * 2;
			double r = minR + getRandom().nextDouble() * (maxR - minR);
			int x = MathHelper.floor(center.x + Math.cos(ang) * r);
			int z = MathHelper.floor(center.z + Math.sin(ang) * r);
			int cy = MathHelper.floor(center.y);
			// Nearest level first, so in caves and buildings it stays on the same floor.
			for (int i = 0; i <= 16; i++) {
				int dy = (i % 2 == 0) ? -(i / 2) : (i + 1) / 2;
				BlockPos p = new BlockPos(x, cy + dy, z);
				if (SpaceMap.standable(world, p) && world.getFluidState(p).isEmpty()) return Vec3d.ofBottomCenter(p);
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

	// ================================================================ senses: finding its prey

	private void samplePlayers(ServerWorld world) {
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.squaredDistanceTo(this) > 128 * 128) continue;
			Vec3d now = p.getPos();
			Vec3d before = playerPos.put(p.getUuid(), now);
			float moved = 0f;
			if (before != null) {
				double dx = now.x - before.x, dz = now.z - before.z;
				moved = (float) Math.sqrt(dx * dx + dz * dz);
				if (moved > 3f) moved = 0f; // teleported
			}
			playerSpeed.put(p.getUuid(), Math.max(moved, playerSpeed.getOrDefault(p.getUuid(), 0f) * 0.8f));
		}
		if (this.age % 200 == 0) {
			playerPos.keySet().removeIf(id -> world.getPlayerByUuid(id) == null);
			playerSpeed.keySet().removeIf(id -> world.getPlayerByUuid(id) == null);
		}
	}

	/** Horizontal speed in blocks per tick, as this Spindle observed it (walking ~0.22, sprinting ~0.28, sneaking ~0.07). */
	public float getPlayerSpeed(PlayerEntity player) {
		return playerSpeed.getOrDefault(player.getUuid(), 0f);
	}

	public boolean canSeeTarget(LivingEntity target) {
		Vec3d eye = target.getEyePos();
		Vec3d mid = target.getPos().add(0, target.getHeight() * 0.5, 0);
		Vec3d core = getCorePos();
		return hasLineOfSight(core, eye) || hasLineOfSight(core, mid) || hasLineOfSight(getEyePos(), eye);
	}

	private void updateAwareness() {
		if (noiseTicks > 0) noiseTicks--;
		LivingEntity target = getTarget();
		if (target == null) {
			lastKnownPos = null;
			lostTicks = 0;
			return;
		}
		if (mode != Mode.HUNT) {
			// While it shadows someone it always has a rough sense of where they are.
			lastKnownPos = target.getPos();
			lastKnownVel = Vec3d.ZERO;
			lostTicks = 0;
			return;
		}
		double dist = distanceTo(target);
		boolean sensed = dist < 3.0 || noiseTicks > 0;
		if (!sensed && target instanceof PlayerEntity p && !p.isSneaking()) {
			float speed = getPlayerSpeed(p);
			sensed = (speed > 0.1f && dist < 12) || (speed > 0.24f && dist < 24);
		}
		if (!sensed && dist < 72) {
			if ((this.age + getId()) % 2 == 0) sawTarget = canSeeTarget(target);
			sensed = sawTarget;
		}
		if (sensed) {
			Vec3d now = target.getPos();
			if (lastKnownPos != null && lostTicks == 0) {
				lastKnownVel = lastKnownVel.multiply(0.7).add(now.subtract(lastKnownPos).multiply(0.3));
			}
			lastKnownPos = now;
			lostTicks = 0;
		} else {
			lostTicks++;
		}
	}

	/** True while it knows where its target is right now (with a short grace period). */
	public boolean isTracking() {
		return lostTicks < 15;
	}

	public int getLostTicks() {
		return lostTicks;
	}

	@Nullable
	public Vec3d getLastKnownPos() {
		return lastKnownPos;
	}

	public Vec3d getLastKnownVel() {
		return lastKnownVel;
	}

	/** Something told it exactly where this entity is (its probe saw them, they hit it, ...). */
	public void noticeTarget(LivingEntity entity) {
		if (entity instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return;
		if (mode != Mode.RETREAT) setTarget(entity);
		lastKnownPos = entity.getPos();
		lostTicks = 0;
		noiseTicks = Math.max(noiseTicks, 20);
	}

	/** Tests only: it last knew its target was at pos, and has since lost them. */
	public void debugLoseTargetAt(Vec3d pos) {
		lastKnownPos = pos;
		lastKnownVel = Vec3d.ZERO;
		lostTicks = 30;
		sawTarget = false;
		noiseTicks = 0;
	}

	/** The player made a loud noise nearby (broke a block). */
	public void hearNoise(PlayerEntity player) {
		if (getTarget() == player) noiseTicks = Math.max(noiseTicks, 30);
	}

	/** It lost its prey: back to shadowing, but still wound up. */
	public void loseTrail() {
		if (mode != Mode.HUNT) return;
		mode = Mode.STALK;
		tension = HUNT_THRESHOLD * 0.4f;
		huntTicks = 0;
		unreachableTicks = 0;
		pathBlocked = false;
		relocateRequested = true;
		tendril.forceRelease();
		getNavigation().stop();
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

		if (hiveBound && (mode == Mode.WANDER || mode == Mode.STALK) && dist < 48) {
			// In the nest they don't stalk. They swarm.
			startHunt();
			return;
		}
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
				boolean giveUp = dist > 72
						|| (exposedDay && huntTicks > 200)
						|| (group >= 3 && huntTicks > 100)
						|| huntTicks > 1800;
				if (giveUp) endHunt();
			}
			default -> {}
		}
	}

	public void startHunt() {
		if (mode == Mode.RETREAT) return;
		if (mode != Mode.HUNT) {
			playSound(SpindleSounds.HISS, 2.0f, 0.35f);
			LivingEntity target = getTarget();
			if (target != null) lastKnownPos = target.getPos();
			lostTicks = 0;
			unreachableTicks = 0;
		pathBlocked = false;
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
		unreachableTicks = 0;
		pathBlocked = false;
		clearStuck();
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
			if (isAiDisabled() && hiveBound) setAiDisabled(false);
			if (isHanging()) setHanging(false);
			if (frozen) gazeDamage += amount;
			tendril.onOwnerDamaged(amount);
			if (source.getAttacker() instanceof PlayerEntity player && !player.isCreative() && !player.isSpectator()
					&& mode != Mode.RETREAT) {
				noticeTarget(player);
				startHunt();
			}
		}
		return result;
	}

	@Override
	public boolean tryAttack(Entity target) {
		getWorld().sendEntityStatus(this, (byte) 4);
		attackCooldown = 24;
		boolean hit = super.tryAttack(target);
		if (hit) statAttacks++;
		return hit;
	}

	public boolean canMeleeNow() {
		return attackCooldown <= 0;
	}

	public boolean isInMeleeReach(LivingEntity target) {
		double dx = target.getX() - getX();
		double dz = target.getZ() - getZ();
		double reach = MathHelper.lerp(getFold(), 3.3, 2.6);
		double dy = target.getY() - getY();
		return dx * dx + dz * dz <= reach * reach && dy > -2.0 && dy < getBodyHeight() + 0.5;
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

	/**
	 * Anything that isn't a player and is in its way (close, or ahead of where it's walking) gets hit.
	 * It doesn't go after mobs, it just doesn't go around them.
	 */
	private void swatMobsInTheWay(ServerWorld world) {
		if (frozen || isHanging() || !canMeleeNow() || !tendril.isIdle()) return;
		Vec3d vel = getVelocity();
		Vec3d dir = new Vec3d(vel.x, 0, vel.z);
		boolean moving = dir.lengthSquared() > 0.0004;
		if (moving) dir = dir.normalize();
		double reach = MathHelper.lerp(getFold(), 3.3, 2.4);
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(reach, 1.0, reach),
				e -> e != this && e.isAlive() && !(e instanceof PlayerEntity) && !(e instanceof SpindleEntity)
						&& !(e instanceof net.minecraft.entity.decoration.ArmorStandEntity) && !e.isInvulnerable())) {
			Vec3d to = new Vec3d(e.getX() - getX(), 0, e.getZ() - getZ());
			double d = to.length();
			boolean inPath = d < 1.6 + e.getWidth() / 2 || (moving && d > 0.01 && to.multiply(1 / d).dotProduct(dir) > 0.35);
			if (!inPath || !isInMeleeReach(e) || !canSee(e)) continue;
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		if (best != null) {
			getLookControl().lookAt(best, 30f, 30f);
			if (tryAttack(best)) {
				Vec3d away = new Vec3d(best.getX() - getX(), 0, best.getZ() - getZ());
				if (away.lengthSquared() > 1e-4) {
					away = away.normalize();
					best.addVelocity(away.x * 0.9, 0.35, away.z * 0.9);
					best.velocityModified = true;
				}
			}
		}
	}

	// ================================================================ lights

	public static boolean isSnuffable(BlockState state) {
		if (state.getBlock() instanceof AbstractTorchBlock) return state.getLuminance() >= 8; // not redstone torches
		if (state.getBlock() instanceof LanternBlock) return true;
		if (state.isOf(Blocks.JACK_O_LANTERN)) return true;
		if (state.getBlock() instanceof AbstractCandleBlock) return state.contains(AbstractCandleBlock.LIT) && state.get(AbstractCandleBlock.LIT);
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

	/**
	 * Stolen items turn up later when nobody is looking: on the owner's bed if they have one,
	 * otherwise somewhere close behind them.
	 */
	private void tickStolenItems(ServerWorld world) {
		if (stolen.isEmpty()) return;
		for (StolenItem s : stolen) s.age++;
		if (this.age % 100 != 0) return;

		stolen.removeIf(s -> {
			if (s.age < 1200) return false;
			ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(s.owner);
			if (owner == null || owner.getWorld() != world) return false; // keep it until they're back
			Vec3d drop = null;
			BlockPos bed = owner.getSpawnPointPosition();
			RegistryKey<World> dim = owner.getSpawnPointDimension();
			if (bed != null && dim == world.getRegistryKey() && world.isChunkLoaded(bed)) {
				Vec3d onBed = Vec3d.ofCenter(bed).add(0, 0.6, 0);
				if (!isSpotVisibleToAnyone(onBed.subtract(0, 1.0, 0)) || s.age > 12000) drop = onBed;
			} else {
				for (int i = 0; i < 8 && drop == null; i++) {
					Vec3d c = randomGroundNear(owner.getPos(), 4, 10);
					if (c != null && (s.age > 12000 || !isSpotInSomeonesView(c))) drop = c.add(0, 0.3, 0);
				}
			}
			if (drop == null) return false;
			ItemEntity item = new ItemEntity(world, drop.x, drop.y, drop.z, s.stack, 0, 0, 0);
			item.setNeverDespawn();
			world.spawnEntity(item);
			return true;
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

	public int getTendrilFlags() {
		return this.dataTracker.get(TENDRIL_FLAGS);
	}

	public boolean isTendrilActive() {
		return (getTendrilFlags() & TENDRIL_ACTIVE) != 0;
	}

	public boolean isTendrilAlert() {
		return (getTendrilFlags() & TENDRIL_ALERT) != 0;
	}

	public boolean isTendrilSearching() {
		return (getTendrilFlags() & TENDRIL_SEARCHING) != 0;
	}

	public Vec3d getTendrilTip() {
		return new Vec3d(this.dataTracker.get(TENDRIL_TIP));
	}

	public Vec3d getTendrilLook() {
		return new Vec3d(this.dataTracker.get(TENDRIL_LOOK));
	}

	/** The probe came back empty. It's frustrated: it gets closer to just hunting. */
	public void onSearchFailed() {
		if (mode == Mode.STALK) tension = Math.min(HUNT_THRESHOLD, tension + 900f);
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

	public void setTensionFraction(float fraction) {
		tension = MathHelper.clamp(fraction, 0f, 1f) * HUNT_THRESHOLD;
	}

	/** For commands and tests: drop everything and go back to wandering. */
	public void calmDown() {
		tendril.forceRelease();
		setHanging(false);
		setTarget(null);
		mode = Mode.WANDER;
		tension = 0;
		huntTicks = 0;
		unreachableTicks = 0;
		pathBlocked = false;
		clearStuck();
		getNavigation().stop();
	}

	public boolean isWatched() {
		return watched;
	}

	/** Watched, and the watcher can actually make it out: it's lit, or they're close. */
	public boolean isNoticed() {
		return watched && (lit || nearestWatcherDist < 10);
	}

	public boolean isFrozen() {
		return frozen;
	}

	public boolean consumeRelocateRequest() {
		boolean r = relocateRequested;
		relocateRequested = false;
		return r;
	}

	/**
	 * The hunt goal reports what its latest path said: true = it can't walk to the target,
	 * false = it can. Null = no verdict yet (still walking to the closest spot); keeps the last one.
	 */
	public void markUnreachable(@Nullable Boolean unreachable) {
		if (unreachable == null) return;
		pathBlocked = unreachable;
		if (!unreachable) unreachableTicks = 0;
	}

	/** Seconds-ish it has been unable to walk to its target, counted in ticks. */

	public int getUnreachableTicks() {
		if (mode != Mode.HUNT) return 0;
		return unreachableTicks;
	}

	public double getSpeedFactor() {
		return 1.0 - 0.2 * getFold();
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
	 * Every second: if it's trying to get somewhere but hasn't moved in 3 seconds, escalate:
	 * 1 = fold flat and re-plan, 2 = back off and come at it from somewhere else, 3 = dig (SpindleDigGoal).
	 */
	private void tickStuck() {
		if (this.age % 20 != 0) return;
		boolean busy = frozen || isHanging() || !tendril.isIdle() || unreachableTicks > 0;
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
			stuckLevel = 0;
			return;
		}
		stuckChecks++;
		if (stuckLevel == 0) {
			stuckLevel = 1;
			forceCrouch(60);
			getNavigation().startMovingTo(destination.x, destination.y, destination.z, getSpeedFactor());
		} else if (stuckLevel == 1 && stuckChecks >= 4) {
			stuckLevel = 2;
			Vec3d away = randomGroundNear(getPos(), 4, 9);
			if (away != null) getNavigation().startMovingTo(away.x, away.y, away.z, 1.2);
		} else if (stuckLevel == 2 && stuckChecks >= 7) {
			if (canGrief()) {
				stuckLevel = 3;
			} else {
				destination = null; // give up on that spot; goals will pick another
				clearStuck();
			}
		}
	}

	/**
	 * Where it should tunnel to, or null if it shouldn't dig: it has been stuck for a long time, or it
	 * is hunting someone it can't walk to (walled in, sealed room).
	 */
	@Nullable
	public Vec3d getDigDestination() {
		if (!canGrief() || frozen || isHanging()) return null;
		if (stuckLevel >= 3 && destination != null) return destination;
		if (mode == Mode.HUNT && unreachableTicks >= 100) {
			LivingEntity target = getTarget();
			if (target == null) return null;
			Vec3d dest = isTracking() ? target.getPos() : lastKnownPos;
			if (dest == null || squaredDistanceTo(dest) > 40 * 40) return null;
			// Up a pillar under the open sky: there's nothing to dig through.
			World world = getWorld();
			if (dest.y > getY() + 2.5 && world.isSkyVisible(BlockPos.ofFloored(dest).up())
					&& world.isSkyVisible(getBlockPos().up(2))) return null;
			return dest;
		}
		return null;
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

	public String debugLine(Entity viewer) {
		String dest = destination == null ? "-" : String.format("%.0fm", Math.sqrt(squaredDistanceTo(destination)));
		String sense = mode != Mode.HUNT ? "" : (isTracking() ? " tracking" : " LOST " + lostTicks);
		return String.format("%s %d%%%s | h%.1f%s%s | stuck %d unreach %d | dest %s | probe %s | %.0fm %s",
				mode, (int) (getTensionFraction() * 100), sense,
				getBodyHeight(), isHanging() ? " hanging" : "", frozen ? " FROZEN" : "",
				stuckLevel, unreachableTicks, dest, tendril.getState(), Math.sqrt(squaredDistanceTo(viewer)), debugNote);
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
			setBodyHeight(MIN_HEIGHT);
			setVelocity(Vec3d.ZERO);
		} else {
			crouchTicks = Math.max(crouchTicks, 20);
		}
	}

	// ================================================================ sounds

	@Nullable
	@Override
	public SoundEvent getAmbientSound() {
		// Silent while stalking. You only hear it when it's already hunting.
		return mode == Mode.HUNT ? SpindleSounds.HISS : null;
	}

	@Override
	public SoundEvent getHurtSound(DamageSource source) {
		return SpindleSounds.HURT;
	}

	@Override
	public SoundEvent getDeathSound() {
		return SpindleSounds.DEATH;
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
		nbt.putFloat("BodyHeight", getBodyHeight());
		nbt.putBoolean("Hanging", isHanging());
		if (posed) nbt.putBoolean("Posed", true);
		if (hiveBound) nbt.putBoolean("HiveBound", true);
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
		if (nbt.contains("BodyHeight")) {
			float h = MathHelper.clamp(nbt.getFloat("BodyHeight"), MIN_HEIGHT, MAX_HEIGHT);
			lastShiftSoundHeight = h;
			this.dataTracker.set(HEIGHT, h);
		}
		if (nbt.getBoolean("Hanging")) setHanging(true);
		posed = nbt.getBoolean("Posed");
		hiveBound = nbt.getBoolean("HiveBound");
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
