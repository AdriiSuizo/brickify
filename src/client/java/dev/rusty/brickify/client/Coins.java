package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import dev.rusty.brickify.Brickify;
import dev.rusty.brickify.client.dev.Harness;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Smashing things in the brick world spills studs: silver, gold and blue coins that hop out, spin and then get
 * pulled to the player, each pickup a little higher in pitch than the last. A counter in the corner adds them up.
 */
public final class Coins {
	public static final int SILVER = 10;
	public static final int GOLD = 100;
	public static final int BLUE = 1000;

	public static float magnetRange = 8.0F;
	public static int life = 400;
	/** Coins in the air at once (each is two displays); a big blast spills no more than this. */
	public static int maxLive = 60;

	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<Coin> COINS = new ArrayList<>();
	private static int nextId = -8_000_000;
	private static long total;
	private static double shown;
	private static int combo;
	private static int lastPickup = -100;
	private static int ticks;
	private static float pop;
	private static boolean loaded;

	private static final class Coin {
		final int value;
		final Display.BlockDisplay a;
		final Display.BlockDisplay b;
		final Vector3d pos = new Vector3d();
		final Vector3d vel = new Vector3d();
		float spin;
		final float spinSpeed;
		int age;
		boolean pulled;

		Coin(int value, Display.BlockDisplay a, Display.BlockDisplay b) {
			this.value = value;
			this.a = a;
			this.b = b;
			this.spinSpeed = 0.28F + RANDOM.nextFloat() * 0.12F;
			this.spin = RANDOM.nextFloat() * Mth.TWO_PI;
		}
	}

	private Coins() {
	}

	public static long total() {
		return total;
	}

	private static BlockState look(int value) {
		return value >= BLUE ? Blocks.LAPIS_BLOCK.defaultBlockState() : value >= GOLD ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
	}

	/** A burst of coins worth about {@code worth}: mostly silver, some gold, now and then a blue one. */
	public static void burst(Minecraft mc, double x, double y, double z, int count) {
		if (mc.level == null || BrickWorld.mode() == BrickWorld.OFF) {
			return;
		}
		for (int i = 0; i < count; i++) {
			if (COINS.size() >= maxLive) {
				return;
			}
			float r = RANDOM.nextFloat();
			int value = r < 0.03F ? BLUE : r < 0.2F ? GOLD : SILVER;
			spawn(mc.level, value, x, y, z);
		}
	}

	/** The player died: a tenth of the studs spill out where they fell (they can be picked up again). */
	public static void lose(Minecraft mc, double x, double y, double z) {
		if (mc.level == null || total <= 0) {
			return;
		}
		long lost = Math.max(Math.min(total, 50L), total / 10);
		total -= lost;
		shown = Math.min(shown, total);
		int n = 0;
		while (lost > 0 && n < 40) {
			int value = lost >= BLUE * 3 ? BLUE : lost >= GOLD * 3 ? GOLD : SILVER;
			spawn(mc.level, value, x, y, z);
			lost -= value;
			n++;
		}
		save(mc);
	}

	private static void spawn(ClientLevel level, int value, double x, double y, double z) {
		Display.BlockDisplay a = display(level, value);
		Display.BlockDisplay b = display(level, value);
		Coin c = new Coin(value, a, b);
		c.pos.set(x, y, z);
		float ang = RANDOM.nextFloat() * Mth.TWO_PI;
		double out = 0.06 + RANDOM.nextFloat() * 0.12;
		c.vel.set(Mth.cos(ang) * out, 0.32 + RANDOM.nextFloat() * 0.22, Mth.sin(ang) * out);
		pose(c, true);
		level.addEntity(a);
		level.addEntity(b);
		COINS.add(c);
	}

	private static Display.BlockDisplay display(ClientLevel level, int value) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setId(nextId--);
		d.setBlockState(look(value));
		d.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
		return d;
	}

	/** An upright coin from two thin boxes (straight and turned by 45 degrees), spinning about the vertical. */
	private static void pose(Coin c, boolean jump) {
		float size = c.value >= BLUE ? 0.34F : c.value >= GOLD ? 0.3F : 0.26F;
		float shrink = c.age > life - 10 ? Math.max(0.0F, (life - c.age) / 10.0F) : 1.0F;
		for (int i = 0; i < 2; i++) {
			Display.BlockDisplay d = i == 0 ? c.a : c.b;
			d.setPos(c.pos.x, c.pos.y, c.pos.z);
			Quaternionf rot = new Quaternionf().rotationY(c.spin).rotateZ(i == 0 ? 0.0F : Mth.PI / 4.0F);
			Vector3f scale = new Vector3f(size * 0.92F, size * 0.92F, 0.07F).mul(shrink);
			Vector3f translation = rot.transform(new Vector3f(scale).mul(-0.5F));
			d.setTransformation(new Transformation(translation, rot, scale, null));
			d.setTransformationInterpolationDuration(jump ? 0 : 1);
			d.setTransformationInterpolationDelay(0);
		}
	}

	public static void tick(Minecraft mc) {
		ticks++;
		pop = Math.max(0.0F, pop - 0.12F);
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (!loaded) {
			loaded = true;
			load(mc);
		}
		if (shown < total) {
			shown = Math.min(total, shown + Math.max(1.0, (total - shown) * 0.18));
		}
		if (COINS.isEmpty()) {
			return;
		}
		var player = mc.player;
		for (int i = COINS.size() - 1; i >= 0; i--) {
			Coin c = COINS.get(i);
			c.age++;
			c.spin += c.spinSpeed;
			boolean collect = false;
			if (player != null && c.age > 18 && !player.isDeadOrDying()) {
				Vec3 target = player.position().add(0.0, 0.9, 0.0);
				double dx = target.x - c.pos.x;
				double dy = target.y - c.pos.y;
				double dz = target.z - c.pos.z;
				double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d < magnetRange || c.pulled) {
					c.pulled = true;
					double pull = 0.06 + 0.25 / Math.max(d, 0.5);
					c.vel.add(dx / d * pull, dy / d * pull, dz / d * pull).mul(0.86);
					c.pos.add(c.vel);
					collect = d < 0.75;
				}
			}
			if (!c.pulled) {
				c.vel.y -= 0.05;
				c.pos.add(c.vel);
				double ground = Body.groundBelow(level, null, c.pos.x, c.pos.y + 0.3, c.pos.z) + 0.16;
				if (c.pos.y < ground) {
					c.pos.y = ground;
					c.vel.y = c.vel.y < -0.1 ? -c.vel.y * 0.45 : 0.0;
					c.vel.x *= 0.7;
					c.vel.z *= 0.7;
				}
			}
			if (collect) {
				pickup(mc, c);
			}
			if (collect || c.age > life) {
				level.removeEntity(c.a.getId(), Entity.RemovalReason.DISCARDED);
				level.removeEntity(c.b.getId(), Entity.RemovalReason.DISCARDED);
				COINS.remove(i);
				continue;
			}
			pose(c, false);
		}
	}

	private static void pickup(Minecraft mc, Coin c) {
		if (ticks - lastPickup > 16) {
			combo = 0;
		}
		lastPickup = ticks;
		combo++;
		total += c.value;
		pop = 1.0F;
		float pitch = Math.min(2.0F, 0.75F + combo * 0.045F);
		float volume = c.value >= GOLD ? 0.45F : 0.3F;
		mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, volume, pitch,
			RandomSource.create(), c.pos.x, c.pos.y, c.pos.z));
		Harness.onSound("minecraft:entity.experience_orb.pickup", c.pos.x, c.pos.y, c.pos.z, volume, pitch);
		if (combo % 10 == 0) {
			save(mc);
		}
	}

	// ---------------------------------------------------------------- counter

	public static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || BrickWorld.mode() == BrickWorld.OFF || mc.gui.hud.isHidden()) {
			return;
		}
		String text = String.format(Locale.GERMANY, "%,d", (long) shown);
		int w = g.guiWidth();
		int x = w - 12 - mc.font.width(text);
		int y = 10;
		float s = 1.0F + 0.25F * pop * pop;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x - 14, y + 4);
		pose.scale(s, s);
		// a little coin: a yellow octagon-ish disc with a light rim
		g.fill(-5, -3, 5, 3, 0xFFE8B321);
		g.fill(-3, -5, 3, 5, 0xFFE8B321);
		g.fill(-2, -3, 2, 3, 0xFFFFD75A);
		pose.popMatrix();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(s, s);
		g.text(mc.font, text, 0, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
	}

	// ---------------------------------------------------------------- saving

	private static Path file(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		return server == null ? null : server.getWorldPath(LevelResource.ROOT).resolve("brickify_studs.txt");
	}

	private static void save(Minecraft mc) {
		Path p = file(mc);
		if (p == null) {
			return;
		}
		try {
			Files.writeString(p, Long.toString(total));
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not save studs: {}", e.toString());
		}
	}

	private static void load(Minecraft mc) {
		Path p = file(mc);
		total = 0;
		try {
			if (p != null && Files.isRegularFile(p)) {
				total = Long.parseLong(Files.readString(p).trim());
			}
		} catch (Exception e) {
			total = 0;
		}
		shown = total;
	}

	public static void forget(Minecraft mc) {
		if (loaded) {
			save(mc);
		}
		COINS.clear();
		loaded = false;
	}
}
