package dev.rusty.brickify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The brick games' double jump: press jump again in the air and the figure jumps once more with a front flip.
 * Only in the brick world, and not for players who can fly (creative keeps its double-tap to fly).
 */
public final class DoubleJump {
	public static double power = 0.52;
	public static int flipTicks = 11;
	private static boolean used;
	private static int ticks;
	private static int flipStart = -100;

	private DoubleJump() {
	}

	/** Start of each client tick. */
	public static void tick(Minecraft mc) {
		ticks++;
		LocalPlayer p = mc.player;
		boolean pressed = false;
		while (mc.options.keyJump.consumeClick()) {
			pressed = true;
		}
		if (p == null) {
			return;
		}
		if (p.onGround() || p.isInWater() || p.onClimbable() || p.isPassenger()) {
			used = false;
			return;
		}
		if (pressed && !used && BrickWorld.mode() != BrickWorld.OFF && !p.getAbilities().mayfly) {
			trigger(mc);
		}
	}

	/** The second jump itself (also used by the test harness). */
	public static void trigger(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		Vec3 v = p.getDeltaMovement();
		p.setDeltaMovement(v.x * 1.15, power, v.z * 1.15);
		p.resetFallDistance();
		used = true;
		flipStart = ticks;
		Hops.playClick(mc, p.getX(), p.getY() + 0.2, p.getZ(), 0.5F, 1.4F);
	}

	/** Current flip angle of the local player (0 when not flipping). */
	public static float flipAngle(float partialTicks) {
		float t = ticks - flipStart + partialTicks;
		if (t <= 0.0F || t >= flipTicks) {
			return 0.0F;
		}
		float k = t / flipTicks;
		return -(k * k * (3.0F - 2.0F * k)) * Mth.TWO_PI;
	}
}
