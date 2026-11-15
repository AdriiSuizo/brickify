package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Entities in the brick world: each one turns when the wave reaches it (with a bounce and a click), loses a few
 * bricks when it is hit, and falls apart into bricks when it dies.
 */
public final class BrickEntities {
	private static final Int2IntOpenHashMap TURNED = new Int2IntOpenHashMap();
	private static final Int2IntOpenHashMap LAST_HURT = new Int2IntOpenHashMap();
	private static final IntOpenHashSet SHATTERED = new IntOpenHashSet();
	private static final Int2IntOpenHashMap HIT = new Int2IntOpenHashMap();
	private static int ticks;
	private static int joined;
	/** New mobs near the player click themselves together from parts. */
	public static boolean assembleSpawns = true;

	private BrickEntities() {
	}

	public static boolean isBrick(Entity entity) {
		return BrickWorld.isBrickAt(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
	}

	public static int ticks() {
		return ticks;
	}

	private static boolean playerBroken;
	private static boolean wasOnGround = true;
	private static double fallSpeed;
	private static int landed = -100;

	/** A copy that is already brick (no bounce when it appears). */
	public static void markTurned(int id) {
		TURNED.put(id, ticks - 100);
	}

	/** A quick bounce right after turning (up, squash, settle), and a squash when it is hit. */
	public static float popScale(Entity entity, float partialTicks) {
		float scale = 1.0F;
		int t0 = TURNED.getOrDefault(entity.getId(), Integer.MIN_VALUE);
		if (t0 != Integer.MIN_VALUE) {
			float age = ticks - t0 + partialTicks;
			if (age >= 0.0F && age <= 12.0F) {
				scale += 0.28F * (float) Math.exp(-age / 3.2) * Mth.sin(age * 1.15F);
			}
		}
		if (entity == Minecraft.getInstance().player) {
			float age = ticks - landed + partialTicks;
			if (age >= 0.0F && age <= 8.0F) {
				scale -= 0.1F * (float) Math.exp(-age / 2.2) * Mth.sin(age * 1.4F);
			}
		}
		int h0 = HIT.getOrDefault(entity.getId(), Integer.MIN_VALUE);
		if (h0 != Integer.MIN_VALUE) {
			float age = ticks - h0 + partialTicks;
			if (age >= 0.0F && age <= 10.0F) {
				scale -= 0.14F * (float) Math.exp(-age / 2.6) * Mth.sin(age * 1.3F);
			}
		}
		return scale;
	}

	public static boolean hidden(Entity entity) {
		return BrickWorld.mode() != BrickWorld.OFF && SHATTERED.contains(entity.getId());
	}

	public static void tick(Minecraft mc) {
		ticks++;
		if (BrickWorld.mode() == BrickWorld.OFF || mc.level == null || mc.player == null) {
			if (!TURNED.isEmpty() || !SHATTERED.isEmpty()) {
				forget();
			}
			return;
		}
		Breakup.tick(mc, ticks);
		if (mc.player.isDeadOrDying()) {
			if (!playerBroken) {
				playerBroken = true;
				SHATTERED.add(mc.player.getId());
				Breakup.startPlayer(mc, ticks);
				Coins.lose(mc, mc.player.getX(), mc.player.getY() + 1.0, mc.player.getZ());
			}
		} else if (playerBroken) {
			playerBroken = false;
			SHATTERED.remove(mc.player.getId());
		}
		boolean ground = mc.player.onGround();
		if (ground && !wasOnGround && fallSpeed < -0.3) {
			landed = ticks;
		}
		wasOnGround = ground;
		fallSpeed = mc.player.getDeltaMovement().y;
		boolean wave = BrickWorld.mode() == BrickWorld.WAVE;
		// a copy: bursts and break-ups add entities to the level while we go through them
		java.util.List<Entity> entities = new java.util.ArrayList<>();
		mc.level.entitiesForRendering().forEach(entities::add);
		for (Entity entity : entities) {
			if (!(entity instanceof LivingEntity living) || entity.distanceToSqr(mc.player) > 64.0 * 64.0) {
				continue;
			}
			int id = entity.getId();
			if (!isBrick(entity)) {
				continue;
			}
			if (!TURNED.containsKey(id)) {
				TURNED.put(id, ticks);
				if (wave) {
					Hops.playClick(mc, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(), 0.8F, 1.1F);
				} else if (assembleSpawns && entity != mc.player && !Breakup.isCopy(entity) && entity.tickCount < 5 && ticks - joined > 80
					&& entity.distanceToSqr(mc.player) < 24.0 * 24.0 && !living.isDeadOrDying() && !Breakup.isRespawn(entity)) {
					TURNED.put(id, ticks - 100);
					Breakup.assemble(mc, living, ticks);
				}
			}
			if (entity == mc.player || Breakup.isCopy(entity)) {
				continue;
			}
			int hurt = living.hurtTime;
			int last = LAST_HURT.getOrDefault(id, 0);
			if (hurt > last && !living.isDeadOrDying()) {
				HIT.put(id, ticks);
				Coins.burst(mc, entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ(), 1);
				Hops.playClick(mc, entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ(), 0.75F, 1.25F);
			}
			LAST_HURT.put(id, hurt);
			if (living.isDeadOrDying() && SHATTERED.add(id)) {
				Breakup.start(mc, living, ticks);
			}
		}
	}

	/** Something that does not die the normal way (a creeper blowing itself up) falls apart all the same. */
	public static void shatter(Minecraft mc, LivingEntity living) {
		if (BrickWorld.mode() != BrickWorld.OFF && SHATTERED.add(living.getId())) {
			Breakup.start(mc, living, ticks);
		}
	}

	public static void forget() {
		joined = ticks;
		Breakup.clear(Minecraft.getInstance());
		TURNED.clear();
		LAST_HURT.clear();
		SHATTERED.clear();
		HIT.clear();
	}
}
