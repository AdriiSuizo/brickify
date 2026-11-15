package dev.rusty.brickify.client;

import dev.rusty.brickify.Brickify;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Trying out player animation mods: with Smooth Player Animations and Mo' Bends both installed, a key switches live
 * between them (and none). Talks to them only by reflection, so Brickify needs neither. Does nothing if they are missing.
 */
public final class AnimSwitch {
	private static final String[] NAMES = {"Smooth Player Animations", "Mo' Bends", "ohne Animations-Mod"};
	private static int mode;
	private static boolean applied;

	private AnimSwitch() {
	}

	public static boolean available() {
		FabricLoader l = FabricLoader.getInstance();
		return l.isModLoaded("spa_packs") || l.isModLoaded("mobends");
	}

	/** First tick in a world: make sure only the chosen one runs. */
	public static void tick(Minecraft mc) {
		if (!applied && mc.player != null && available()) {
			applied = true;
			apply();
		}
	}

	public static void cycle(Minecraft mc) {
		if (!available()) {
			return;
		}
		mode = (mode + 1) % 3;
		apply();
		mc.gui.hud.setOverlayMessage(Component.literal("Animationen: " + NAMES[mode]), false);
		if (mc.player != null) {
			Hops.playClick(mc, mc.player.getX(), mc.player.getY() + 1.0, mc.player.getZ(), 0.5F, 1.0F + mode * 0.2F);
		}
	}

	public static String name() {
		return NAMES[mode];
	}

	private static void apply() {
		setSmooth(mode == 0);
		setMoBends(mode == 1);
		mobsVanilla();
	}

	/**
	 * Mobs keep plain Minecraft animations (Mo' Bends would animate them, and they would no longer fall apart into
	 * their body parts). The animation mods are for the player only.
	 */
	private static void mobsVanilla() {
		try {
			Class<?> c = Class.forName("goblinbob.mobends.core.configuration.ClientConfig");
			Object cfg = c.getField("INSTANCE").get(null);
			// straight into the map and saved once (setAnimated writes the config file on every call)
			var valuesField = c.getDeclaredField("values");
			valuesField.setAccessible(true);
			Object values = valuesField.get(cfg);
			var animatedField = values.getClass().getDeclaredField("animated");
			animatedField.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Boolean> animated = (java.util.Map<String, Boolean>) animatedField.get(values);
			boolean changed = false;
			for (var key : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet()) {
				if (key.getPath().equals("player")) {
					continue;
				}
				changed |= animated.put(key.toString(), false) == null;
				changed |= animated.put("mobends:" + key.getPath(), false) == null;
			}
			// Mo' Bends' own model names (pig_zombie and the like, also from resource packs)
			for (var id : Minecraft.getInstance().getResourceManager().listResources("bends/models", i -> i.getPath().endsWith(".json")).keySet()) {
				String name = id.getPath().substring(id.getPath().lastIndexOf('/') + 1).replace(".json", "");
				if (!name.equals("player")) {
					changed |= animated.put(id.getNamespace() + ":" + name, false) == null;
				}
			}
			if (changed) {
				c.getMethod("save").invoke(cfg);
			}
		} catch (ClassNotFoundException ignored) {
		} catch (Throwable t) {
			Brickify.LOGGER.warn("Could not keep mobs vanilla in Mo' Bends: {}", t.toString());
		}
	}

	private static void setSmooth(boolean on) {
		try {
			Class<?> c = Class.forName("com.huaxiaosheng.spapacks.client.ModConfig");
			Object cfg = c.getMethod("get").invoke(null);
			c.getField("enabled").setBoolean(cfg, on);
		} catch (ClassNotFoundException ignored) {
		} catch (Throwable t) {
			Brickify.LOGGER.warn("Could not switch Smooth Player Animations: {}", t.toString());
		}
	}

	private static void setMoBends(boolean on) {
		try {
			Class<?> c = Class.forName("goblinbob.mobends.core.configuration.ClientConfig");
			Object cfg = c.getField("INSTANCE").get(null);
			var set = c.getMethod("setAnimated", String.class, boolean.class);
			set.invoke(cfg, "mobends:player", on);
			set.invoke(cfg, "minecraft:player", on);
		} catch (ClassNotFoundException ignored) {
		} catch (Throwable t) {
			Brickify.LOGGER.warn("Could not switch Mo' Bends: {}", t.toString());
		}
	}

	public static void forget() {
		applied = false;
	}
}
