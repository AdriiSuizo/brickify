package dev.rusty.brickify.client.editor;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What a villager says: a speech box above the hotbar with their name, the thing they talk about, and the words
 * appearing one after another. It goes away by itself after a while.
 */
public final class Dialog {
	private static String name = "";
	private static String text = "";
	private static ItemStack icon = ItemStack.EMPTY;
	private static long shownAt;
	private static long until;
	/** Who speaks (entity id), -1: nobody in particular. Walk away from them and the box goes. */
	private static int speaker = -1;
	private static final double AWAY = 6.5;
	private static final long FADE = 300L;

	private Dialog() {
	}

	public static void show(String who, String words, ItemStack thing) {
		show(who, words, thing, null);
	}

	public static void show(String who, String words, ItemStack thing, net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity by) {
		speaker = by != null ? by.getId() : -1;
		name = who;
		text = words;
		icon = thing;
		shownAt = System.currentTimeMillis();
		until = shownAt + 2600L + words.length() * 45L;
	}

	public static void hide() {
		until = 0L;
	}

	/** Lets the box fade out quickly. */
	public static void close() {
		until = Math.min(until, System.currentTimeMillis() + FADE);
	}

	/** This one's words are on screen right now (not already fading out). */
	public static boolean showingFor(net.minecraft.world.entity.Entity e) {
		return e.getId() == speaker && System.currentTimeMillis() < until - FADE;
	}

	public static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (now > until || mc.gui.hud.isHidden() || text.isEmpty()) {
			return;
		}
		if (speaker >= 0 && mc.level != null && mc.player != null && now < until - FADE) {
			// walked off: the conversation is over
			net.minecraft.world.entity.Entity who = mc.level.getEntity(speaker);
			if (who == null || who.distanceTo(mc.player) > AWAY) {
				close();
			}
		}
		int shown = (int) Math.min(text.length(), (now - shownAt) / 22L);
		int w = Math.min(300, g.guiWidth() - 40);
		List<FormattedCharSequence> lines = mc.font.split(Component.literal(text), w - 46);
		int h = 22 + lines.size() * 10;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 62 - h;
		float fade = Math.min(1.0F, (until - now) / 400.0F);
		int a = (int) (0xE0 * fade) << 24;
		g.fill(x, y, x + w, y + h, a | 0x1A2230);
		g.outline(x, y, w, h, (int) (0xFF * fade) << 24 | 0xFFD84A);
		for (int sx = x + 10; sx < x + w - 12; sx += 20) {
			g.fill(sx, y - 3, sx + 10, y, (int) (0xFF * fade) << 24 | 0xFFD84A);
		}
		g.pose().pushMatrix();
		g.pose().translate(x + 8.0F, y + 6.0F);
		g.pose().scale(1.5F, 1.5F);
		g.item(icon, 0, 0);
		g.pose().popMatrix();
		g.text(mc.font, name, x + 40, y + 6, (int) (0xFF * fade) << 24 | 0xFFD84A, false);
		// the words come one after another
		String part = text.substring(0, shown);
		int ty = y + 18;
		for (FormattedCharSequence line : mc.font.split(Component.literal(part), w - 46)) {
			g.text(mc.font, line, x + 40, ty, (int) (0xFF * fade) << 24 | 0xF2F2F2, false);
			ty += 10;
		}
	}
}
