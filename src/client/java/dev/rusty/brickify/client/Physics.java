package dev.rusty.brickify.client;

import dev.rusty.brickify.Brickify;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * How strict the brick world is about things holding up (cycled with a key, remembered in the config folder):
 * off = nothing ever comes down by itself; normal = a part only falls once nothing connects it to the ground any
 * more; strict = long overhangs break off too.
 */
public final class Physics {
	public static final int OFF = 0;
	public static final int NORMAL = 1;
	public static final int STRICT = 2;
	private static final String[] NAMES = {"off", "normal", "strict"};

	private static volatile int mode = NORMAL;
	private static boolean loaded;

	private Physics() {
	}

	public static int mode() {
		return mode;
	}

	public static String name() {
		return NAMES[mode];
	}

	public static void set(int m) {
		mode = Math.floorMod(m, 3);
		save();
	}

	public static void cycle(Minecraft mc) {
		// normal -> strict -> off -> normal
		set(mode == NORMAL ? STRICT : mode == STRICT ? OFF : NORMAL);
		mc.gui.hud.setOverlayMessage(Component.translatable("physics.brickify.mode", Component.translatable("physics.brickify." + name())), false);
		if (mc.player != null) {
			Hops.playClick(mc, mc.player.getX(), mc.player.getY() + 1.0, mc.player.getZ(), 0.5F, mode == OFF ? 0.8F : mode == NORMAL ? 1.1F : 1.4F);
		}
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("brickify.properties");
	}

	public static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file();
			if (Files.isRegularFile(f)) {
				Properties p = new Properties();
				try (var in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					p.load(in);
				}
				String v = p.getProperty("physics", "normal");
				for (int i = 0; i < NAMES.length; i++) {
					if (NAMES[i].equals(v)) {
						mode = i;
					}
				}
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not read brickify.properties: {}", e.toString());
		}
	}

	private static void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Properties p = new Properties();
			p.setProperty("physics", name());
			try (var out = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
				p.store(out, "Brickify");
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not write brickify.properties: {}", e.toString());
		}
	}
}
