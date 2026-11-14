package net.spindle;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

public final class SpindleSounds {
	public static final SoundEvent PROBE_MUMBLE = register("probe_mumble");
	public static final SoundEvent PROBE_SERVO = register("probe_servo");
	public static final SoundEvent PROBE_FOUND = register("probe_found");

	private static SoundEvent register(String name) {
		return Registry.register(Registries.SOUND_EVENT, Spindle.id(name), SoundEvent.of(Spindle.id(name)));
	}

	public static void init() {
	}

	private SpindleSounds() {
	}
}
