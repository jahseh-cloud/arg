package net.spindle.selftest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * A simulated Survival player for the automated tests: it exists in the world like a real player
 * (mobs see it, it has physics and takes damage) and walks where the test tells it to.
 * Only ever created by the self-test and screenshot harnesses in the development environment.
 */
public class TestPlayer extends FakePlayer {
	@Nullable public Vec3d walkTo;
	@Nullable private Vec3d[] patrol;
	private int patrolIndex;
	public boolean sneak;
	public boolean sprint;
	public float faceYaw = Float.NaN;

	protected TestPlayer(ServerWorld world, String name) {
		super(world, new GameProfile(UUID.nameUUIDFromBytes(("spindle-test:" + name).getBytes(StandardCharsets.UTF_8)), name));
	}

	public static TestPlayer spawn(ServerWorld world, String name, Vec3d pos, float yaw) {
		TestPlayer p = new TestPlayer(world, name);
		p.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f);
		p.faceYaw = yaw;
		try {
			Field f = ServerPlayerEntity.class.getDeclaredField("joinInvulnerabilityTicks");
			f.setAccessible(true);
			f.setInt(p, 0);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Only matters for damage checks in tests.
		}
		world.getServer().getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(p)));
		world.onPlayerConnected(p);
		return p;
	}

	/** Walk back and forth between these points. */
	public void patrol(Vec3d... points) {
		patrol = points;
		patrolIndex = 0;
		walkTo = points.length > 0 ? points[0] : null;
	}

	public void stopWalking() {
		patrol = null;
		walkTo = null;
	}

	@Override
	public boolean isLogicalSideForUpdatingMovement() {
		return true; // its movement is simulated here on the server
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		return false;
	}

	/** Runs one tick of player physics and movement. Call once per server tick. */
	public void drive() {
		if (isRemoved() || isDead()) return;
		setSneaking(sneak);
		float forward = 0f;
		if (walkTo != null) {
			double dx = walkTo.x - getX(), dz = walkTo.z - getZ();
			if (dx * dx + dz * dz > 0.36) {
				float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
				setYaw(yaw);
				setHeadYaw(yaw);
				setBodyYaw(yaw);
				forward = sneak ? 0.3f : 1f;
			} else if (patrol != null && patrol.length > 1) {
				patrolIndex = (patrolIndex + 1) % patrol.length;
				walkTo = patrol[patrolIndex];
			}
		} else if (!Float.isNaN(faceYaw)) {
			setYaw(faceYaw);
			setHeadYaw(faceYaw);
			setBodyYaw(faceYaw);
		}
		setSprinting(sprint && forward > 0f && !sneak);
		this.forwardSpeed = forward;
		this.sidewaysSpeed = 0f;
		playerTick();
		getServerWorld().getChunkManager().updatePosition(this);
	}

	public void despawn() {
		if (!isRemoved()) getServerWorld().removePlayer(this, Entity.RemovalReason.DISCARDED);
	}
}
