package net.spindle;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

/**
 * Every sound it makes is its own event with its own subtitle, so subtitles never give away what
 * kind of thing is out there ("Spider hisses").
 */
public final class SpindleSounds {
	public static final SoundEvent PROBE_MUMBLE = register("probe_mumble");
	public static final SoundEvent PROBE_SERVO = register("probe_servo");
	public static final SoundEvent PROBE_FOUND = register("probe_found");
	public static final SoundEvent HISS = register("hiss");
	public static final SoundEvent HURT = register("hurt");
	public static final SoundEvent DEATH = register("death");
	public static final SoundEvent STEP = register("step");
	public static final SoundEvent SHIFT = register("shift");
	public static final SoundEvent REACH = register("reach");
	public static final SoundEvent GRAB = register("grab");
	public static final SoundEvent SNAP = register("snap");
	public static final SoundEvent SLAM = register("slam");
	public static final SoundEvent STEAL = register("steal");

	private static SoundEvent register(String name) {
		return Registry.register(Registries.SOUND_EVENT, Spindle.id(name), SoundEvent.of(Spindle.id(name)));
	}

	public static void init() {
	}

	private SpindleSounds() {
	}
}
