package net.spindle.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.spindle.Spindle;
import net.spindle.SpindleSounds;
import net.spindle.world.HiveEvents;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Broodmother. A vast mass hanging in the heart of the Hivemind, held up by tentacles that run to
 * every wall, with legs bursting out of the floor around it. It never moves.
 *
 * The fight:
 *  - While any of its nodes (glowing growths on the chamber walls) are alive, it takes a tenth of the
 *    damage. Destroy them all and its core is exposed for 20 seconds; then they grow back.
 *  - It slams tentacles down where you stand (a ring on the ground warns you), grabs players and reels
 *    them into its mass (mash sneak to tear free), births Spindles out of the walls, and sends out
 *    static that blinds and sickens everyone.
 *  - Below two thirds of its health it gets faster and starts grabbing and pulsing; below a third it
 *    drags everyone toward itself.
 *  - When it dies, every Spindle in the world dies with it and none ever come back.
 */
public class BroodmotherEntity extends HostileEntity {
	public static final int ATTACK_NONE = 0, ATTACK_SLAM_WINDUP = 1, ATTACK_SLAM = 2, ATTACK_GRAB_REACH = 3, ATTACK_GRAB_HOLD = 4,
			ATTACK_PULSE = 5;
	public static final float ARENA = 46f;

	private static final TrackedData<Integer> PHASE = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> EXPOSED = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> AWAKE = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> ATTACK = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> ATTACK_TICKS = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Vector3f> ATTACK_TARGET = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	private static final TrackedData<Integer> DYING = DataTracker.registerData(BroodmotherEntity.class, TrackedDataHandlerRegistry.INTEGER);

	private final ServerBossBar bossBar = (ServerBossBar) new ServerBossBar(
			Text.translatable("entity.spindle.broodmother").formatted(Formatting.DARK_PURPLE),
			BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10).setDarkenSky(true).setThickenFog(true);

	private final List<UUID> nodes = new ArrayList<>();
	private final List<UUID> brood = new ArrayList<>();
	private int exposedTicks;
	private int regrowTicks;
	private int nodeWave;
	private int slamCooldown = 60, grabCooldown = 200, broodCooldown = 160, pulseCooldown = 300;
	@Nullable private UUID grabbed;
	private int grabSneaks;
	private boolean grabLastSneak;
	private int dyingTicks;
	/** Tests / debug: total damage actually taken, and attacks made. */
	public float statDamageTaken;
	public int statSlams, statGrabs, statBrood, statPulses;

	// client
	public int clientAge;

	public BroodmotherEntity(EntityType<? extends BroodmotherEntity> type, World world) {
		super(type, world);
		this.setNoGravity(true);
		this.experiencePoints = 1000;
		this.ignoreCameraFrustum = true;
		this.noClip = false;
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 1600.0)
				.add(EntityAttributes.GENERIC_ARMOR, 12.0)
				.add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, 6.0)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 14.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(PHASE, 1);
		builder.add(EXPOSED, false);
		builder.add(AWAKE, false);
		builder.add(ATTACK, ATTACK_NONE);
		builder.add(ATTACK_TICKS, 0);
		builder.add(ATTACK_TARGET, new Vector3f());
		builder.add(DYING, 0);
	}

	@Override
	protected void initGoals() {
		// No goals: it doesn't move. Everything happens in tick().
	}

	// ================================================================ it never moves

	@Override
	public void move(MovementType type, Vec3d movement) {
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
	public boolean cannotDespawn() {
		return true;
	}

	@Override
	public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
		return false;
	}

	@Override
	public boolean shouldRender(double distance) {
		return distance < 200 * 200;
	}

	@Override
	public boolean canUsePortals(boolean allowVehicles) {
		return false;
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.isInvulnerableTo(source);
		Entity attacker = source.getAttacker();
		if (attacker instanceof PlayerEntity || source.isIn(DamageTypeTags.IS_EXPLOSION)) return super.isInvulnerableTo(source);
		return true;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (getWorld().isClient || isDying()) return false;
		if (!isExposed()) amount *= 0.1f;
		if (getPhase() == 3 && isExposed()) amount *= 1.25f;
		if (source.getAttacker() instanceof PlayerEntity p && !isAwake()) wake();
		float before = getHealth();
		boolean r = super.damage(source, amount);
		if (r) statDamageTaken += before - getHealth();
		return r;
	}

	// ================================================================ state accessors

	public int getPhase() {
		return this.dataTracker.get(PHASE);
	}

	public boolean isExposed() {
		return this.dataTracker.get(EXPOSED);
	}

	public boolean isAwake() {
		return this.dataTracker.get(AWAKE);
	}

	public int getAttack() {
		return this.dataTracker.get(ATTACK);
	}

	public int getAttackTicks() {
		return this.dataTracker.get(ATTACK_TICKS);
	}

	public Vec3d getAttackTarget() {
		return new Vec3d(this.dataTracker.get(ATTACK_TARGET));
	}

	public int getDyingTicks() {
		return this.dataTracker.get(DYING);
	}

	public boolean isDying() {
		return getDyingTicks() > 0;
	}

	public Vec3d getMassCenter() {
		return getPos().add(0, getHeight() / 2.0, 0);
	}

	public int livingNodes() {
		return nodes.size();
	}

	private void setAttack(int kind, int ticks, Vec3d target) {
		this.dataTracker.set(ATTACK, kind);
		this.dataTracker.set(ATTACK_TICKS, ticks);
		this.dataTracker.set(ATTACK_TARGET, target.toVector3f());
	}

	// ================================================================ tick

	@Override
	public void tick() {
		super.tick();
		setVelocity(Vec3d.ZERO);
		if (getWorld().isClient) {
			clientAge++;
			return;
		}
		ServerWorld world = (ServerWorld) getWorld();
		if (this.age % 20 == 0) SpindleChunkLoader.get(world).update(world, getUuid(), getChunkPos());
		if (isDying()) {
			tickDying(world);
			return;
		}
		List<ServerPlayerEntity> arena = playersInArena(world);
		updateBossBar(world);
		if (!isAwake()) {
			if (!arena.isEmpty()) wake();
			return;
		}
		if (arena.isEmpty()) {
			// Everyone left or died: it heals slowly and waits.
			if (this.age % 20 == 0) heal(4f);
			setAttack(ATTACK_NONE, 0, Vec3d.ZERO);
			releaseGrab();
			return;
		}

		float hp = getHealth() / getMaxHealth();
		int phase = hp > 0.66f ? 1 : (hp > 0.33f ? 2 : 3);
		if (phase != getPhase()) {
			this.dataTracker.set(PHASE, phase);
			roar(world);
			if (phase >= 2) spawnBrood(world, 3);
		}

		tickNodes(world);
		tickAttacks(world, arena, phase);
		tickGrab(world);
		if (phase == 3) tickPull(arena);
		if (this.age % 10 == 0) {
			world.spawnParticles(ParticleTypes.SQUID_INK, getX(), getY() + getHeight() / 2, getZ(), 4, 3, 3, 3, 0.02);
		}
	}

	private List<ServerPlayerEntity> playersInArena(ServerWorld world) {
		List<ServerPlayerEntity> list = new ArrayList<>();
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
			if (p.squaredDistanceTo(getMassCenter()) < ARENA * ARENA) list.add(p);
		}
		return list;
	}

	private void updateBossBar(ServerWorld world) {
		bossBar.setPercent(getHealth() / getMaxHealth());
		bossBar.setColor(isExposed() ? BossBar.Color.RED : BossBar.Color.PURPLE);
		if (this.age % 10 != 0) return;
		for (ServerPlayerEntity p : world.getPlayers()) {
			boolean near = isAwake() && p.squaredDistanceTo(this) < 80 * 80;
			if (near) bossBar.addPlayer(p);
			else bossBar.removePlayer(p);
		}
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		bossBar.removePlayer(player);
	}

	public void wake() {
		if (isAwake()) return;
		this.dataTracker.set(AWAKE, true);
		if (getWorld() instanceof ServerWorld world) {
			roar(world);
			growNodes(world, 6);
		}
	}

	private void roar(ServerWorld world) {
		world.playSound(null, getX(), getY(), getZ(), SpindleSounds.PROBE_FOUND, SoundCategory.HOSTILE, 6f, 0.35f);
		world.playSound(null, getX(), getY(), getZ(), SpindleSounds.HISS, SoundCategory.HOSTILE, 6f, 0.25f);
	}

	// ================================================================ nodes (the shield)

	private void tickNodes(ServerWorld world) {
		nodes.removeIf(id -> {
			Entity e = world.getEntity(id);
			return e == null || !e.isAlive();
		});
		if (isExposed()) {
			if (--exposedTicks <= 0) {
				this.dataTracker.set(EXPOSED, false);
				regrowTicks = 100;
			}
		} else if (nodes.isEmpty()) {
			if (regrowTicks > 0) {
				if (--regrowTicks == 0) growNodes(world, Math.max(3, 6 - nodeWave));
			} else {
				this.dataTracker.set(EXPOSED, true);
				exposedTicks = 400;
				nodeWave++;
				world.playSound(null, getX(), getY(), getZ(), SpindleSounds.HURT, SoundCategory.HOSTILE, 6f, 0.3f);
			}
		}
	}

	/** Grows nodes on the chamber walls, spread out around it. */
	public void growNodes(ServerWorld world, int count) {
		Vec3d c = getMassCenter();
		int placed = 0;
		for (int i = 0; i < 64 && placed < count; i++) {
			Vec3d dir = fibonacci(i * 7 % 64, 64);
			if (dir.y < -0.5 || dir.y > 0.7) continue;
			BlockHitResult hit = world.raycast(new RaycastContext(c, c.add(dir.multiply(ARENA)), RaycastContext.ShapeType.COLLIDER,
					RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() != HitResult.Type.BLOCK) continue;
			Vec3d at = hit.getPos().subtract(dir.multiply(1.2));
			boolean tooClose = false;
			for (UUID id : nodes) {
				Entity e = world.getEntity(id);
				if (e != null && e.getPos().distanceTo(at) < 10) tooClose = true;
			}
			if (tooClose) continue;
			BroodNodeEntity node = Spindle.BROOD_NODE.create(world);
			if (node == null) continue;
			node.refreshPositionAndAngles(at.x, at.y - 0.6, at.z, 0f, 0f);
			node.setMother(getUuid());
			world.spawnEntity(node);
			nodes.add(node.getUuid());
			placed++;
		}
	}

	public static Vec3d fibonacci(int i, int n) {
		double phi = Math.PI * (3 - Math.sqrt(5));
		double y = 1 - (i + 0.5) / n * 2;
		double r = Math.sqrt(1 - y * y);
		double t = phi * i;
		return new Vec3d(Math.cos(t) * r, y, Math.sin(t) * r);
	}

	// ================================================================ attacks

	private void tickAttacks(ServerWorld world, List<ServerPlayerEntity> arena, int phase) {
		float speed = phase == 1 ? 1f : (phase == 2 ? 1.35f : 1.8f);
		int attack = getAttack();
		int t = getAttackTicks();

		if (attack == ATTACK_SLAM_WINDUP) {
			Vec3d target = getAttackTarget();
			if (t % 5 == 0) ring(world, target, 3.5);
			if (t <= 0) {
				setAttack(ATTACK_SLAM, 8, target);
				slam(world, target, phase);
			} else {
				this.dataTracker.set(ATTACK_TICKS, t - 1);
			}
			return;
		}
		if (attack == ATTACK_SLAM || attack == ATTACK_PULSE) {
			if (t <= 0) setAttack(ATTACK_NONE, 0, Vec3d.ZERO);
			else this.dataTracker.set(ATTACK_TICKS, t - 1);
			return;
		}
		if (attack == ATTACK_GRAB_REACH) {
			ServerPlayerEntity victim = grabbed == null ? null : (ServerPlayerEntity) world.getPlayerByUuid(grabbed);
			Vec3d target = victim != null ? victim.getPos().add(0, 1, 0) : getAttackTarget();
			this.dataTracker.set(ATTACK_TARGET, target.toVector3f());
			if (t <= 0) {
				if (victim != null && victim.isAlive() && victim.getPos().distanceTo(target) < 3) {
					setAttack(ATTACK_GRAB_HOLD, 120, target);
					grabSneaks = 0;
					world.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SpindleSounds.GRAB, SoundCategory.HOSTILE, 2f, 0.4f);
				} else {
					releaseGrab();
				}
			} else {
				this.dataTracker.set(ATTACK_TICKS, t - 1);
			}
			return;
		}
		if (attack == ATTACK_GRAB_HOLD) return; // tickGrab handles it

		// Choose the next attack.
		if (slamCooldown > 0) slamCooldown -= (int) Math.ceil(speed);
		if (grabCooldown > 0) grabCooldown -= (int) Math.ceil(speed);
		if (broodCooldown > 0) broodCooldown -= (int) Math.ceil(speed);
		if (pulseCooldown > 0) pulseCooldown -= (int) Math.ceil(speed);

		ServerPlayerEntity target = arena.get(getRandom().nextInt(arena.size()));
		if (phase >= 2 && pulseCooldown <= 0) {
			pulseCooldown = 360;
			pulse(world, arena);
		} else if (phase >= 2 && grabCooldown <= 0) {
			grabCooldown = phase == 3 ? 200 : 300;
			grabbed = target.getUuid();
			setAttack(ATTACK_GRAB_REACH, 18, target.getPos().add(0, 1, 0));
			statGrabs++;
			world.playSound(null, getX(), getY(), getZ(), SpindleSounds.REACH, SoundCategory.HOSTILE, 4f, 0.4f);
		} else if (broodCooldown <= 0) {
			broodCooldown = phase == 1 ? 420 : (phase == 2 ? 320 : 220);
			spawnBrood(world, phase == 3 ? 3 : 2);
		} else if (slamCooldown <= 0) {
			slamCooldown = phase == 1 ? 70 : (phase == 2 ? 50 : 34);
			Vec3d ground = groundBelow(world, target.getPos());
			setAttack(ATTACK_SLAM_WINDUP, phase == 3 ? 22 : 30, ground);
			world.playSound(null, ground.x, ground.y, ground.z, SpindleSounds.SHIFT, SoundCategory.HOSTILE, 3f, 0.3f);
		}
	}

	private Vec3d groundBelow(ServerWorld world, Vec3d p) {
		BlockHitResult hit = world.raycast(new RaycastContext(p.add(0, 1, 0), p.add(0, -12, 0), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, this));
		return hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : p;
	}

	private void ring(ServerWorld world, Vec3d c, double r) {
		for (int i = 0; i < 24; i++) {
			double a = i / 24.0 * Math.PI * 2;
			world.spawnParticles(ParticleTypes.WITCH, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
		}
	}

	private void slam(ServerWorld world, Vec3d at, int phase) {
		statSlams++;
		float dmg = phase == 1 ? 14f : (phase == 2 ? 18f : 22f);
		world.playSound(null, at.x, at.y, at.z, SpindleSounds.SLAM, SoundCategory.HOSTILE, 4f, 0.4f);
		world.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 1.5, 0.3, 1.5, 0);
		for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new net.minecraft.util.math.Box(at, at).expand(3.5, 3, 3.5),
				e -> !(e instanceof BroodmotherEntity) && !(e instanceof SpindleEntity) && !(e instanceof BroodNodeEntity))) {
			if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) continue;
			e.damage(getDamageSources().mobAttack(this), dmg);
			Vec3d away = e.getPos().subtract(at);
			away = new Vec3d(away.x, 0, away.z);
			if (away.lengthSquared() > 1e-4) away = away.normalize();
			e.addVelocity(away.x * 1.2, 0.9, away.z * 1.2);
			e.velocityModified = true;
		}
	}

	private void pulse(ServerWorld world, List<ServerPlayerEntity> arena) {
		statPulses++;
		setAttack(ATTACK_PULSE, 30, getMassCenter());
		world.playSound(null, getX(), getY(), getZ(), SpindleSounds.PROBE_MUMBLE, SoundCategory.HOSTILE, 8f, 0.4f);
		world.playSound(null, getX(), getY(), getZ(), SpindleSounds.PROBE_FOUND, SoundCategory.HOSTILE, 5f, 0.5f);
		for (ServerPlayerEntity p : arena) {
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 120, 0), this);
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0), this);
			p.damage(getDamageSources().indirectMagic(this, this), 4f);
		}
	}

	private void spawnBrood(ServerWorld world, int count) {
		brood.removeIf(id -> {
			Entity e = world.getEntity(id);
			return e == null || !e.isAlive();
		});
		Vec3d c = getMassCenter();
		for (int n = 0; n < count && brood.size() < 8; n++) {
			Vec3d dir = fibonacci(getRandom().nextInt(64), 64);
			dir = new Vec3d(dir.x, -Math.abs(dir.y) * 0.3 - 0.2, dir.z).normalize();
			BlockHitResult hit = world.raycast(new RaycastContext(c, c.add(dir.multiply(ARENA)), RaycastContext.ShapeType.COLLIDER,
					RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() != HitResult.Type.BLOCK) continue;
			BlockPos floor = net.spindle.world.SpaceFinder.floorBelow(world, BlockPos.ofFloored(hit.getPos().subtract(dir.multiply(2))), 20);
			if (floor == null) continue;
			SpindleEntity s = Spindle.SPINDLE.create(world);
			if (s == null) continue;
			s.refreshPositionAndAngles(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, getRandom().nextFloat() * 360, 0);
			world.spawnEntity(s);
			PlayerEntity p = world.getClosestPlayer(s, ARENA * 1.5);
			if (p != null && !p.isCreative() && !p.isSpectator()) {
				s.noticeTarget(p);
				s.startHunt();
			}
			world.spawnParticles(ParticleTypes.SQUID_INK, s.getX(), s.getY() + 1, s.getZ(), 30, 0.8, 1.2, 0.8, 0.05);
			brood.add(s.getUuid());
			statBrood++;
		}
	}

	private void tickGrab(ServerWorld world) {
		if (getAttack() != ATTACK_GRAB_HOLD) return;
		ServerPlayerEntity p = grabbed == null ? null : (ServerPlayerEntity) world.getPlayerByUuid(grabbed);
		if (p == null || !p.isAlive() || p.isSpectator() || p.isCreative()) {
			releaseGrab();
			return;
		}
		int t = getAttackTicks();
		this.dataTracker.set(ATTACK_TICKS, t - 1);
		boolean s = p.isSneaking();
		if (s && !grabLastSneak) grabSneaks++;
		grabLastSneak = s;
		if (grabSneaks >= 9 || t <= 0) {
			world.playSound(null, p.getX(), p.getY(), p.getZ(), SpindleSounds.SNAP, SoundCategory.HOSTILE, 2f, 0.5f);
			releaseGrab();
			return;
		}
		Vec3d mouth = getMassCenter().add(0, -getHeight() / 2 - 1, 0);
		Vec3d to = mouth.subtract(p.getPos());
		double d = to.length();
		if (d > 2.5) {
			p.setVelocity(to.multiply(Math.min(0.35, d) / d));
			p.velocityModified = true;
			p.fallDistance = 0;
		} else if (t % 10 == 0) {
			p.damage(getDamageSources().mobAttack(this), 5f);
		}
		this.dataTracker.set(ATTACK_TARGET, p.getPos().add(0, 1, 0).toVector3f());
	}

	private void releaseGrab() {
		if (getAttack() == ATTACK_GRAB_HOLD || getAttack() == ATTACK_GRAB_REACH) setAttack(ATTACK_NONE, 0, Vec3d.ZERO);
		grabbed = null;
	}

	/** Phase three: everything in the chamber slides toward it. */
	private void tickPull(List<ServerPlayerEntity> arena) {
		Vec3d c = getMassCenter();
		for (ServerPlayerEntity p : arena) {
			Vec3d to = c.subtract(p.getPos());
			double d = to.length();
			if (d < 1) continue;
			Vec3d pull = new Vec3d(to.x, 0, to.z).normalize().multiply(0.045);
			p.addVelocity(pull.x, 0, pull.z);
			p.velocityModified = true;
			if (d < 7 && this.age % 20 == 0) p.damage(getDamageSources().mobAttack(this), 6f);
		}
	}

	// ================================================================ death

	@Override
	public void onDeath(DamageSource source) {
		// Don't die yet: play the death first.
		if (getWorld().isClient || isDying()) return;
		setHealth(1f);
		this.dead = false;
		this.dataTracker.set(DYING, 1);
		dyingTicks = 1;
		releaseGrab();
		setAttack(ATTACK_NONE, 0, Vec3d.ZERO);
	}

	private void tickDying(ServerWorld world) {
		dyingTicks++;
		this.dataTracker.set(DYING, dyingTicks);
		setHealth(1f);
		bossBar.setPercent(0f);
		if (dyingTicks % 6 == 0) {
			Vec3d c = getMassCenter();
			world.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 40, 4, 4, 4, 0.1);
			world.spawnParticles(ParticleTypes.EXPLOSION, c.x + getRandom().nextGaussian() * 4, c.y + getRandom().nextGaussian() * 4,
					c.z + getRandom().nextGaussian() * 4, 1, 0, 0, 0, 0);
			world.playSound(null, c.x, c.y, c.z, SpindleSounds.HURT, SoundCategory.HOSTILE, 5f, 0.3f + getRandom().nextFloat() * 0.3f);
		}
		if (dyingTicks == 2) {
			world.playSound(null, getX(), getY(), getZ(), SpindleSounds.DEATH, SoundCategory.HOSTILE, 10f, 0.25f);
			for (UUID id : nodes) {
				Entity e = world.getEntity(id);
				if (e != null) e.discard();
			}
			nodes.clear();
		}
		if (dyingTicks >= 140) {
			for (ServerPlayerEntity p : world.getPlayers()) {
				if (p.squaredDistanceTo(this) < 128 * 128) HiveEvents.grant(p, HiveEvents.ADV_SIGNAL_LOST);
			}
			bossBar.clearPlayers();
			HiveEvents.endOfTheHive(world.getServer());
			world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + getHeight() / 2, getZ(), 6, 3, 3, 3, 0);
			dropExperience(world);
			SpindleChunkLoader.get(world).forget(getUuid());
			discard();
		}
	}

	private void dropExperience(ServerWorld world) {
		net.minecraft.entity.ExperienceOrbEntity.spawn(world, getPos().add(0, getHeight() / 2, 0), experiencePoints);
	}

	// ================================================================ save / load

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("Awake", isAwake());
		nbt.putInt("NodeWave", nodeWave);
		net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
		for (UUID id : nodes) list.add(net.minecraft.nbt.NbtHelper.fromUuid(id));
		nbt.put("Nodes", list);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.dataTracker.set(AWAKE, nbt.getBoolean("Awake"));
		nodeWave = nbt.getInt("NodeWave");
		nodes.clear();
		for (net.minecraft.nbt.NbtElement e : nbt.getList("Nodes", net.minecraft.nbt.NbtElement.INT_ARRAY_TYPE)) {
			nodes.add(net.minecraft.nbt.NbtHelper.toUuid(e));
		}
		if (hasCustomName()) bossBar.setName(getDisplayName());
	}

	/** Tests and commands. */
	public void debugExpose() {
		if (getWorld() instanceof ServerWorld world) {
			for (UUID id : nodes) {
				Entity e = world.getEntity(id);
				if (e != null) e.discard();
			}
			nodes.clear();
			regrowTicks = 0;
		}
	}
}
