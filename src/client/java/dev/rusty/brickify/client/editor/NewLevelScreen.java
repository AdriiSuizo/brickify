package dev.rusty.brickify.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The first time the editor key is pressed in a world: build on the tutorial plate (a little village, explained
 * step by step), or start with an empty plate right where the player stands.
 */
public final class NewLevelScreen extends Screen {
	private int ax;
	private int bx;
	private int cy;
	private int cw;
	private int ch;
	private float ui = 1.0F;

	public NewLevelScreen() {
		super(Component.literal("New level"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int realX, int realY, float a) {
		super.extractRenderState(g, realX, realY, a);
		this.ui = Math.max(0.5F, Math.min(1.0F, this.height / 330.0F));
		int vw = Math.round(this.width / this.ui);
		int vh = Math.round(this.height / this.ui);
		int mx = (int) (realX / this.ui);
		int my = (int) (realY / this.ui);
		g.pose().pushMatrix();
		g.pose().scale(this.ui, this.ui);
		g.fill(0, 0, vw, vh, 0x90000000);
		this.cw = 190;
		this.ch = 150;
		this.ax = vw / 2 - this.cw - 8;
		this.bx = vw / 2 + 8;
		this.cy = vh / 2 - this.ch / 2 + 10;
		g.centeredText(this.font, "A new level", vw / 2, this.cy - 34, 0xFFFFD84A);
		g.centeredText(this.font, "What do you want to build on?", vw / 2, this.cy - 20, 0xFFA9B4C2);
		this.card(g, this.ax, mx, my, 0xFF3C8DD8, new ItemStack(Items.BELL), "Tutorial plate",
			"A little village with a river, a wall and a gate. Build your first level on it step by step: every tool is explained. Recommended!",
			"Rebuilds the area around you");
		this.card(g, this.bx, mx, my, 0xFF6B7B8F, new ItemStack(Items.GRASS_BLOCK), "Empty plate",
			"A 48 x 48 plate right where you stand. Everything already here becomes part of your level.", "Nothing gets changed");
		g.centeredText(this.font, "Esc: not now", vw / 2, this.cy + this.ch + 14, 0xFF8A97A8);
		g.pose().popMatrix();
	}

	private void card(GuiGraphicsExtractor g, int x, int mx, int my, int colour, ItemStack icon, String title, String text, String note) {
		boolean hot = mx >= x && mx < x + this.cw && my >= this.cy && my < this.cy + this.ch;
		int y = this.cy - (hot ? 3 : 0);
		g.fill(x, y, x + this.cw, y + this.ch, hot ? 0xF0263446 : 0xE01A2230);
		g.fill(x, y + this.ch - 4, x + this.cw, y + this.ch, colour);
		g.outline(x, y, this.cw, this.ch, hot ? 0xFFFFD84A : 0x60FFFFFF);
		for (int sx = x + 14; sx < x + this.cw - 14; sx += 28) {
			g.fill(sx, y - 4, sx + 14, y, hot ? 0xFFFFD84A : colour);
		}
		g.pose().pushMatrix();
		g.pose().translate(x + 12.0F, y + 10.0F);
		g.pose().scale(2.0F, 2.0F);
		g.item(icon, 0, 0);
		g.pose().popMatrix();
		g.text(this.font, title, x + 50, y + 20, hot ? 0xFFFFD84A : 0xFFF2F2F2, false);
		int ty = y + 50;
		for (FormattedCharSequence line : this.font.split(Component.literal(text), this.cw - 24)) {
			g.text(this.font, line, x + 12, ty, 0xFFD8DEE6, false);
			ty += 10;
		}
		g.text(this.font, note, x + 12, y + this.ch - 18, 0xFF8A97A8, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		double mx = event.x() / this.ui;
		double my = event.y() / this.ui;
		Minecraft mc = Minecraft.getInstance();
		if (my >= this.cy - 3 && my < this.cy + this.ch) {
			if (mx >= this.ax && mx < this.ax + this.cw) {
				this.pick(mc, true);
			} else if (mx >= this.bx && mx < this.bx + this.cw) {
				this.pick(mc, false);
			}
		}
		return true;
	}

	private void pick(Minecraft mc, boolean tutorial) {
		Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
		mc.gui.setScreen(null);
		Editor.createLevel(mc, tutorial);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			Minecraft.getInstance().gui.setScreen(null);
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			this.pick(Minecraft.getInstance(), true);
			return true;
		}
		return true;
	}
}
