package net.spindle.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.spindle.SpindleSounds;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A glowing growth on the heart chamber's walls. While any are alive, the Broodmother is shielded. */
public class BroodNodeEntity extends HostileEntity {
	@Nullable private UUID mother;

	public BroodNodeEntity(EntityType<? extends BroodNodeEntity> type, World world) {
		super(type, world);
		this.setNoGravity(true);
		this.experiencePoints = 10;
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 50.0)
				.add(EntityAttributes.GENERIC_ARMOR, 4.0)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0);
	}

	public void setMother(UUID id) {
		mother = id;
	}

	@Override
	protected void initGoals() {
	}

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
	public boolean cannotDespawn() {
		return true;
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.isInvulnerableTo(source);
		return !(source.getAttacker() instanceof PlayerEntity || source.isIn(DamageTypeTags.IS_EXPLOSION)) || super.isInvulnerableTo(source);
	}

	@Override
	public void tick() {
		super.tick();
		setVelocity(Vec3d.ZERO);
		if (getWorld() instanceof ServerWorld world && this.age % 40 == 0) {
			// A node without its mother withers away.
			Entity m = mother == null ? null : world.getEntity(mother);
			if (m == null || !m.isAlive()) {
				if (this.age > 200) discard();
			}
			world.spawnParticles(ParticleTypes.WITCH, getX(), getY() + 0.6, getZ(), 2, 0.4, 0.4, 0.4, 0);
		}
	}

	@Override
	public SoundEvent getHurtSound(DamageSource source) {
		return SpindleSounds.HURT;
	}

	@Override
	public SoundEvent getDeathSound() {
		return SpindleSounds.SNAP;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		if (mother != null) nbt.putUuid("Mother", mother);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.containsUuid("Mother")) mother = nbt.getUuid("Mother");
	}
}
