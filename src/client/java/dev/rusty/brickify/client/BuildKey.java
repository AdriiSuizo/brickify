package dev.rusty.brickify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Standing next to a heap of bricks (a figure that came apart, or a broken building), the build key (R) puts it
 * back together; holding it builds everything around at once. A hint shows above the hotbar while something is in reach.
 */
public final class BuildKey {
	public static double mobReach = 3.5;
	public static double ruinReach = 4.5;
	public static int holdTicks = 12;
	private static int hint;
	private static boolean holding;
	private static int held;
	private static boolean mega;
	private static int megaStart;

	private BuildKey() {
	}

	/** Start of each client tick, before the game reads its keys. */
	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
			return;
		}
		if (holding) {
			if (BrickifyClient.BUILD.isDown()) {
				if (++held == holdTicks) {
					startMega(mc);
				}
			} else {
				holding = false;
			}
		}
		boolean pressed = false;
		while (BrickifyClient.BUILD.consumeClick()) {
			pressed = true;
		}
		if (pressed) {
			buildNearest(mc);
			holding = true;
			held = 0;
		}
		Vec3 at = mc.player.position();
		int mob = Breakup.nearestBuildable(at, mobReach);
		int ruin = mob == 0 ? BrickRuins.nearestBuildable(at, ruinReach) : -1;
		if (mob == 0 && ruin < 0) {
			hint = 0;
			return;
		}
		if (--hint <= 0) {
			mc.gui.hud.setOverlayMessage(Component.translatable("hint.brickify.build", BrickifyClient.BUILD.getTranslatedKeyMessage()), false);
			hint = 30;
		}
	}

	/** Holding the key: everything around builds at once, in a wave from here. */
	public static int startMega(Minecraft mc) {
		if (mc.player == null) {
			return 0;
		}
		lastPress = BrickEntities.ticks();
		int now = BrickEntities.ticks();
		int n = BrickRuins.buildAll(mc.player.position(), 96.0, now) + Breakup.buildAll(mc.player.position(), 40.0, now);
		if (n > 0 || BrickRuins.anyBuilding()) {
			mega = true;
			megaStart = now;
		}
		return n;
	}

	/** Something near is being built right now (the figure waves its hands). */
	public static boolean building() {
		return BrickRuins.anyBuilding() || Breakup.anyBuilding();
	}

	private static int lastPress = -1000;
	private static int swing;

	/**
	 * While the figure builds (it pressed the build key, and something is coming together), it works with both hands:
	 * a swing now with the one, now with the other. These are the game's own arm swings, so every animation mod shows
	 * them its own way.
	 */
	private static void work(Minecraft mc) {
		int now = BrickEntities.ticks();
		if (mc.player == null || now - lastPress > 400 || !building()) {
			return;
		}
		if (++swing % 6 == 0) {
			var hand = (swing / 6) % 2 == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
			mc.player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		}
	}

	/** End of each tick: notes when a big build is over. */
	public static void after(Minecraft mc) {
		work(mc);
		if (mega && BrickEntities.ticks() - megaStart > 30 && !building()) {
			mega = false;
		}
	}

	/** Builds whatever is nearest (also used by the test harness). Returns what was started. */
	public static String buildNearest(Minecraft mc) {
		if (mc.player == null) {
			return "nothing";
		}
		lastPress = BrickEntities.ticks();
		Vec3 at = mc.player.position();
		int mob = Breakup.nearestBuildable(at, mobReach + 2.0);
		if (mob != 0) {
			Breakup.build(mob, BrickEntities.ticks());
			return "mob";
		}
		int ruin = BrickRuins.nearestBuildable(at, ruinReach + 4.0);
		if (ruin >= 0) {
			BrickRuins.build(ruin, BrickEntities.ticks());
			hint = 0;
			return "ruin";
		}
		return "nothing";
	}
}
