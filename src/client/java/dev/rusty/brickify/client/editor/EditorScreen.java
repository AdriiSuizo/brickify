package dev.rusty.brickify.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The diorama: the level on its plate, the mouse free. A tray of tools at the bottom (bricks in a sorting box), a
 * card for whatever is chosen, short help at the top. The world under the mouse is picked every frame.
 */
public final class EditorScreen extends Screen {
	private static final int PANEL = 0xC8161B22;
	private static final int EDGE = 0x40FFFFFF;
	private static final int TEXT = 0xFFF2F2F2;
	private static final int DIM = 0xFFA9B4C2;
	private static final int ACCENT = 0xFFFFD84A;

	private record Slot(Editor.Tool tool, String label, ItemStack icon, int colour) {
	}

	private static final Slot[] SLOTS = {
		new Slot(Editor.Tool.POINTER, "Pointer", new ItemStack(Items.ARROW), 0xFF8A97A8),
		new Slot(Editor.Tool.BLUEPRINT, "Blueprint", new ItemStack(Items.MAP), 0xFF3C8DD8),
		new Slot(Editor.Tool.LINK, "Link", new ItemStack(Items.LEAD), 0xFF8E5CC7),
		new Slot(Editor.Tool.START, "Start", new ItemStack(Items.BANNER.pick(net.minecraft.world.item.DyeColor.LIME)), 0xFF46A957),
		new Slot(Editor.Tool.CHECKPOINT, "Check", new ItemStack(Items.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE)), 0xFF5AA7B8),
		new Slot(Editor.Tool.GOAL, "Goal", new ItemStack(Items.NETHER_STAR), 0xFFE0A21C),
		new Slot(Editor.Tool.ENEMY, "Enemy", new ItemStack(Items.ZOMBIE_SPAWN_EGG), 0xFFC0453A),
		new Slot(Editor.Tool.CAMERA, "Camera", new ItemStack(Items.SPYGLASS), 0xFF4E6F8F),
		new Slot(Editor.Tool.BREAKABLE, "Smash", new ItemStack(Items.MACE), 0xFFD9822B),
		new Slot(Editor.Tool.ITEM, "Item", new ItemStack(Items.GOLDEN_CARROT), 0xFF3FA88F),
		new Slot(Editor.Tool.NPC, "Villager", new ItemStack(Items.VILLAGER_SPAWN_EGG), 0xFF8F6B45),
		new Slot(Editor.Tool.TRIGGER, "Trigger", new ItemStack(Items.TRIPWIRE_HOOK), 0xFF2E9E78),
		new Slot(Editor.Tool.BLAST, "Blast", new ItemStack(Items.TNT), 0xFFB8392B),
	};
	/** The key for each tray slot. */
	private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "N", "T", "X"};

	private record Button(int x, int y, int w, int h, String label, Runnable action, @Nullable ItemStack icon) {
		Button(int x, int y, int w, int h, String label, Runnable action) {
			this(x, y, w, h, label, action, null);
		}

		boolean inside(double mx, double my) {
			return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
		}
	}

	private final List<Button> buttons = new ArrayList<>();
	/** The screen draws at its own scale (about 330 units high), so the tray does not cover half the level. */
	private float ui = 1.0F;
	private int vw;
	private int vh;
	private int trayX;
	private int trayY;
	private int trayW;
	private int slotW = 40;
	private static final int SLOT_H = 44;
	/** Two clicks to rebuild the plate as the tutorial plate (the first one asks). */
	private boolean confirmRebuild;
	/** Where the card on the right ends (the help line at the bottom keeps out of its way). */
	private int cardBottom;
	private int cardTop = 999;
	private int cardX;

	/** Where a card this tall starts: under the plate box, higher up when it would reach the tray. */
	private int cardY(int h) {
		return Math.max(6, Math.min(106, this.trayY - 30 - h));
	}

	/** Notes where the card on the right is. */
	private void card(int x, int bottom) {
		this.cardX = x;
		this.cardBottom = bottom;
	}

	private void cardAt(int top) {
		this.cardTop = top;
	}

	/** A hint line, a bit smaller. */
	private void small(GuiGraphicsExtractor g, String text, int x, int y, int colour) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.8F, 0.8F);
		g.text(this.font, text, 0, 0, colour, false);
		g.pose().popMatrix();
	}

	private static String things(int n) {
		return n == 1 ? "1 thing" : n + " things";
	}

	/** The floor of an area: down, up, back to the ground below. */
	private void floorRow(GuiGraphicsExtractor g, Minecraft mc, LevelData.AreaBox a, int x, int y, int w) {
		LevelData d = Editor.level();
		String at = d != null && mc.level != null ? String.valueOf((int) Math.floor(Editor.areaGround(mc.level, d, a) - d.floorY - 6)) : "?";
		g.text(this.font, "Floor " + at + (Double.isNaN(a.ground) ? " (auto)" : ""), x + 8, y + 3, TEXT, false);
		this.buttons.add(new Button(x + w - 78, y, 34, 14, "Auto", () -> Editor.areaFloorAuto(a)));
		this.buttons.add(new Button(x + w - 42, y, 16, 14, "▼", () -> Editor.areaFloor(mc, a, -1.0)));
		this.buttons.add(new Button(x + w - 24, y, 16, 14, "▲", () -> Editor.areaFloor(mc, a, 1.0)));
	}

	/** One thing a villager says: what it is, the words (cut short), a click to change them. */
	private void sayRow(GuiGraphicsExtractor g, int x, int y, int w, String what, String words, boolean own, Runnable edit) {
		g.text(this.font, what + (own ? "" : " (usual)"), x + 8, y, DIM, false);
		String shown = "\u201C" + words + "\u201D";
		if (this.font.width(shown) > w - 34) {
			shown = this.font.plainSubstrByWidth(shown, w - 40) + "..";
		}
		this.buttons.add(new Button(x + 8, y + 9, w - 16, 13, shown + " \u270E", edit));
	}

	private double pressX;
	private double pressY;
	private boolean pressedInWorld;
	private boolean dragging;
	private @Nullable Pick pressPick;
	private double rightDrag;
	private static @Nullable AABB dragBox;
	/** Set by the test harness: where the mouse is, as parts of the screen (0..1). */
	public static double debugX = -1.0;
	public static double debugY = -1.0;

	public EditorScreen() {
		super(Component.literal("Level Editor"));
	}

	public static boolean down(Window w, int key) {
		return InputConstants.isKeyDown(key);
	}

	public static @Nullable AABB dragBox() {
		return dragBox;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// the level is the background
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int realX, int realY, float a) {
		super.extractRenderState(graphics, realX, realY, a);
		this.ui = Math.max(0.5F, Math.min(1.0F, this.height / 330.0F));
		this.vw = Math.round(this.width / this.ui);
		this.vh = Math.round(this.height / this.ui);
		int mouseX = (int) (realX / this.ui);
		int mouseY = (int) (realY / this.ui);
		this.updateHover(realX, realY);
		this.buttons.clear();
		graphics.pose().pushMatrix();
		graphics.pose().scale(this.ui, this.ui);
		this.drawHelp(graphics);
		this.drawTutorial(graphics);
		this.drawTray(graphics, mouseX, mouseY);
		this.cardBottom = 0;
		this.cardTop = 999;
		this.drawCard(graphics);
		this.drawInfo(graphics);
		this.drawStatus(graphics);
		this.drawButtons(graphics, mouseX, mouseY);
		if (this.prompt != null || this.picker != null) {
			// a pop-up: only its own buttons count
			graphics.fill(0, 0, this.vw, this.vh, 0x90000000);
			this.buttons.clear();
			if (this.prompt != null) {
				this.drawPrompt(graphics);
			} else {
				this.drawPicker(graphics, mouseX, mouseY);
			}
			this.drawButtons(graphics, mouseX, mouseY);
		}
		graphics.pose().popMatrix();
	}

	private void drawButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		for (Button b : this.buttons) {
			if (b.label.isEmpty() && b.icon == null) {
				continue;
			}
			boolean hot = b.inside(mouseX, mouseY);
			graphics.fill(b.x, b.y, b.x + b.w, b.y + b.h, hot ? 0xE0384658 : 0xD0242C38);
			graphics.outline(b.x, b.y, b.w, b.h, hot ? 0xFFFFFFFF : EDGE);
			if (b.icon != null) {
				// an icon at the left, the words after it
				graphics.pose().pushMatrix();
				graphics.pose().translate(b.x + 2.0F, b.y + (b.h - 12) / 2.0F);
				graphics.pose().scale(0.75F, 0.75F);
				graphics.item(b.icon, 0, 0);
				graphics.pose().popMatrix();
				String text = this.font.width(b.label) > b.w - 20 ? this.font.plainSubstrByWidth(b.label, b.w - 26) + ".." : b.label;
				graphics.text(this.font, text, b.x + 17, b.y + (b.h - 8) / 2, hot ? ACCENT : TEXT, false);
			} else {
				String text = this.font.width(b.label) > b.w - 4 ? this.font.plainSubstrByWidth(b.label, b.w - 10) + ".." : b.label;
				graphics.centeredText(this.font, text, b.x + b.w / 2, b.y + (b.h - 8) / 2, hot ? ACCENT : TEXT);
			}
		}
	}

	// ---------------------------------------------------------------- what the mouse is on

	private void updateHover(int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		LevelData d = Editor.level();
		if (d == null || mc.level == null || EditorCam.last() == null) {
			Editor.setHover(null);
			return;
		}
		double nx = debugX >= 0.0 ? debugX : (double) mouseX / Math.max(1, this.width);
		double ny = debugY >= 0.0 ? debugY : (double) mouseY / Math.max(1, this.height);
		if (this.prompt != null || this.picker != null || debugX < 0.0 && this.overUi(mouseX / this.ui, mouseY / this.ui)) {
			Editor.setHover(null);
			return;
		}
		Vec3[] ray = EditorCam.ray(nx, ny);
		if (ray == null) {
			Editor.setHover(null);
			return;
		}
		Editor.setHover(Pick.cast(mc.level, d, ray[0], ray[1], 400.0, true));
		if (this.dragging && this.pressPick != null) {
			Pick h = Editor.hover();
			if (h != null) {
				BlockPos a = this.pressPick.pos();
				BlockPos b = h.pos();
				dragBox = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
					Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
			}
		}
	}

	private boolean overUi(double mx, double my) {
		if (mx >= this.trayX && mx < this.trayX + this.trayW && my >= this.trayY - 4) {
			return true;
		}
		for (Button b : this.buttons) {
			if (b.inside(mx, my)) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- drawing the screen

	private void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.fill(x0, y0, x1, y1, PANEL);
		g.outline(x0, y0, x1 - x0, y1 - y0, EDGE);
	}

	private void drawHelp(GuiGraphicsExtractor g) {
		if (Tutorial.active()) {
			// the tutorial card explains it all, step by step
			this.panel(g, 6, 6, 18 + this.font.width("Level Editor"), 22);
			g.text(this.font, "Level Editor", 12, 11, ACCENT, false);
			return;
		}
		String[] lines = {
			"Left mouse: use the tool · Right-click: select",
			"Middle-drag: look around (Shift: slide) · Scroll: zoom",
			"WASD: fly · Space/C: up/down · Q/E: turn",
			"Ctrl+Z / Ctrl+Y: undo / redo · Tab: normal view · O: play"
		};
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, this.font.width(l));
		}
		this.panel(g, 6, 6, 18 + w, 22 + lines.length * 11);
		g.text(this.font, "Level Editor", 12, 11, ACCENT, false);
		for (int i = 0; i < lines.length; i++) {
			g.text(this.font, lines[i], 12, 23 + i * 11, DIM, false);
		}
	}

	private void drawInfo(GuiGraphicsExtractor g) {
		LevelData d = Editor.level();
		if (d == null || this.cardTop < 102) {
			// a tall card is up there now
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = 150;
		int x = this.vw - w - 6;
		this.panel(g, x, 6, x + w, 100);
		g.text(this.font, "Plate " + d.sizeX() + " x " + d.sizeZ(), x + 8, 12, TEXT, false);
		this.buttons.add(new Button(x + w - 44, 9, 16, 14, "-", () -> Editor.resize(mc, -1)));
		this.buttons.add(new Button(x + w - 24, 9, 16, 14, "+", () -> Editor.resize(mc, 1)));
		g.text(this.font, "Blueprints: " + d.blueprints.size() + " · Gates: " + d.gates.size(), x + 8, 30, DIM, false);
		g.text(this.font, "Smashable: " + d.breakable.size() + " blocks", x + 8, 42, d.breakable.isEmpty() ? DIM : 0xFFFFB066, false);
		g.text(this.font, "Start " + (d.start != null ? "\u2714" : "missing"), x + 8, 54, d.start != null ? 0xFF7FE08A : 0xFFFF9A7A, false);
		g.text(this.font, "Goal " + (d.goal != null ? "\u2714" : "missing"), x + 80, 54, d.goal != null ? 0xFF7FE08A : 0xFFFF9A7A, false);
		if (Tutorial.active()) {
			this.buttons.add(new Button(x + 8, 70, w - 16, 14, "End tutorial", Tutorial::end));
		} else if (!d.spots.isEmpty()) {
			this.buttons.add(new Button(x + 8, 70, w - 16, 14, "Tutorial again", Tutorial::restart));
		} else {
			this.buttons.add(new Button(x + 8, 70, w - 16, 14, this.confirmRebuild ? "Sure? Replaces this plate" : "Tutorial plate here", () -> {
				if (this.confirmRebuild) {
					this.confirmRebuild = false;
					Editor.rebuildAsTutorial(mc);
				} else {
					this.confirmRebuild = true;
				}
			}));
		}
	}

	private void drawTray(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int gap = 3;
		int extra = 2;
		// as wide as fits: more tools, narrower bricks
		this.slotW = Math.max(28, Math.min(40, (this.vw - 30 - (SLOTS.length + extra - 1) * gap - 6) / (SLOTS.length + extra)));
		this.trayW = (SLOTS.length + extra) * this.slotW + (SLOTS.length + extra - 1) * gap + 24;
		this.trayX = (this.vw - this.trayW) / 2;
		this.trayY = this.vh - SLOT_H - 14;
		// the sorting box
		g.fill(this.trayX, this.trayY - 6, this.trayX + this.trayW, this.vh - 4, 0xD03A2A1C);
		g.outline(this.trayX, this.trayY - 6, this.trayW, this.vh - 4 - (this.trayY - 6), 0x80000000);
		int x = this.trayX + 12;
		for (int i = 0; i < SLOTS.length; i++) {
			Slot s = SLOTS[i];
			boolean on = Editor.tool() == s.tool;
			boolean hot = mouseX >= x && mouseX < x + this.slotW && mouseY >= this.trayY && mouseY < this.trayY + SLOT_H;
			this.brick(g, x, on ? this.trayY - 4 : this.trayY, s.colour, on, hot);
			int y = on ? this.trayY - 4 : this.trayY;
			Tutorial.Step step = Tutorial.current();
			if (step != null && step.tool() == s.tool && !on && (System.currentTimeMillis() / 300L) % 2L == 0L) {
				// the tutorial wants this tool: it blinks
				g.outline(x - 2, y - 5, this.slotW + 4, SLOT_H + 7, 0xFFFFFFFF);
				g.outline(x - 1, y - 4, this.slotW + 2, SLOT_H + 5, ACCENT);
			}
			float isc = this.slotW >= 36 ? 1.5F : 1.25F;
			g.pose().pushMatrix();
			g.pose().translate(x + this.slotW / 2.0F - 8.0F * isc, y + 6.0F);
			g.pose().scale(isc, isc);
			g.item(s.icon, 0, 0);
			g.pose().popMatrix();
			this.label(g, s.label, x, y, on ? ACCENT : TEXT);
			g.text(this.font, i < KEYS.length ? KEYS[i] : "", x + 3, y + 3, 0xA0FFFFFF, false);
			int fx = x;
			Editor.Tool tool = s.tool;
			this.buttons.add(new Button(fx, this.trayY - 4, this.slotW, SLOT_H + 4, "", () -> this.pickTool(tool)));
			x += this.slotW + gap;
		}
		x += 6;
		Minecraft mc = Minecraft.getInstance();
		this.brick(g, x, this.trayY, 0xFF6B7B8F, false, mouseX >= x && mouseX < x + this.slotW && mouseY >= this.trayY);
		this.icon(g, new ItemStack(Items.ARMOR_STAND), x, this.trayY);
		this.label(g, "View", x, this.trayY, TEXT);
		this.buttons.add(new Button(x, this.trayY - 4, this.slotW, SLOT_H + 4, "", () -> Editor.closeDiorama(mc)));
		x += this.slotW + gap;
		this.brick(g, x, this.trayY, 0xFFD9452B, false, mouseX >= x && mouseX < x + this.slotW && mouseY >= this.trayY);
		this.icon(g, new ItemStack(Items.FIREWORK_ROCKET), x, this.trayY);
		this.label(g, "Play", x, this.trayY, TEXT);
		this.buttons.add(new Button(x, this.trayY - 4, this.slotW, SLOT_H + 4, "", () -> Editor.enterPlay(mc)));
	}

	/** The name under a tray slot, smaller if it would not fit. */
	private void label(GuiGraphicsExtractor g, String text, int x, int y, int colour) {
		int tw = this.font.width(text);
		if (tw <= this.slotW - 4) {
			g.centeredText(this.font, text, x + this.slotW / 2, y + SLOT_H - 12, colour);
			return;
		}
		float sc = (this.slotW - 4.0F) / tw;
		g.pose().pushMatrix();
		g.pose().translate(x + this.slotW / 2.0F, y + SLOT_H - 11.0F);
		g.pose().scale(sc, sc);
		g.centeredText(this.font, text, 0, 0, colour);
		g.pose().popMatrix();
	}

	/** The tutorial card at the left, with its buttons. */
	private void drawTutorial(GuiGraphicsExtractor g) {
		Tutorial.Step step = Tutorial.current();
		if (step == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int w = 210;
		int x = 6;
		int y = 34;
		int h = Tutorial.draw(g, this.font, x, y, w);
		int by = y + h + 4;
		int bw = (w - 8) / 3;
		if (step.done() == null) {
			boolean last = Tutorial.index() == Tutorial.STEPS.size() - 1;
			this.buttons.add(new Button(x, by, bw * 2 + 4, 14, last ? "Finish" : "Next", () -> Tutorial.next(mc)));
		} else {
			this.buttons.add(new Button(x, by, bw * 2 + 4, 14, "Skip this step", () -> Tutorial.next(mc)));
		}
		if (Tutorial.index() > 0) {
			this.buttons.add(new Button(x + bw * 2 + 8, by, bw, 14, "Back", Tutorial::back));
		}
	}

	private void icon(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
		float isc = this.slotW >= 36 ? 1.5F : 1.25F;
		g.pose().pushMatrix();
		g.pose().translate(x + this.slotW / 2.0F - 8.0F * isc, y + 6.0F);
		g.pose().scale(isc, isc);
		g.item(stack, 0, 0);
		g.pose().popMatrix();
	}

	/** A tool slot drawn as a brick: body, darker bottom edge, two studs on top. */
	private void brick(GuiGraphicsExtractor g, int x, int y, int colour, boolean on, boolean hot) {
		int body = hot ? lighter(colour) : colour;
		g.fill(x, y, x + this.slotW, y + SLOT_H, body);
		g.fill(x, y + SLOT_H - 3, x + this.slotW, y + SLOT_H, darker(colour));
		g.fill(x + 8, y - 3, x + 18, y, lighter(body));
		g.fill(x + this.slotW - 18, y - 3, x + this.slotW - 8, y, lighter(body));
		if (on) {
			g.outline(x - 1, y - 4, this.slotW + 2, SLOT_H + 5, ACCENT);
		}
	}

	private static int lighter(int c) {
		int r = Math.min(255, (c >> 16 & 255) + 40);
		int gr = Math.min(255, (c >> 8 & 255) + 40);
		int b = Math.min(255, (c & 255) + 40);
		return 0xFF000000 | r << 16 | gr << 8 | b;
	}

	private static int darker(int c) {
		return 0xFF000000 | (c >> 16 & 255) * 3 / 5 << 16 | (c >> 8 & 255) * 3 / 5 << 8 | (c & 255) * 3 / 5;
	}

	private void drawCard(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		int w = 170;
		int x = this.vw - w - 6;
		int y = 106;
		LevelData.Blueprint placing = Editor.placing();
		LevelData.Blueprint chosen = Editor.selectedBlueprint();
		String marker = Editor.selectedMarker();
		int n = Editor.selection().size();
		if (placing != null) {
			this.panel(g, x, y, x + w, y + 52);
			g.text(this.font, "Put down the heap", x + 8, y + 7, ACCENT, false);
			g.text(this.font, "Click the ground", x + 8, y + 19, DIM, false);
			this.buttons.add(new Button(x + 8, y + 32, w - 16, 14, "Cancel (Esc)", () -> Editor.cancelPlacing(mc)));
		} else if (n > 0 && Editor.tool() == Editor.Tool.BLUEPRINT) {
			this.panel(g, x, y, x + w, y + 88);
			g.text(this.font, n + (n == 1 ? " block" : " blocks") + " marked", x + 8, y + 7, ACCENT, false);
			g.text(this.font, "Shift: add · Alt: single block", x + 8, y + 19, DIM, false);
			this.buttons.add(new Button(x + 8, y + 32, w - 16, 14, "Make blueprint (Enter)", () -> Editor.makeBlueprint(mc)));
			this.buttons.add(new Button(x + 8, y + 50, w - 16, 14, "Make gate (G)", () -> Editor.makeGate(mc)));
			this.buttons.add(new Button(x + 8, y + 68, w - 16, 14, "Unmark (Esc)", Editor::clearSelection));
		} else if (Editor.selectedZone() != null) {
			LevelData.CamZone z = Editor.selectedZone();
			int h = z.after >= 0 ? 149 : 135;
			y = this.cardY(h);
			this.cardAt(y);
			this.panel(g, x, y, x + w, y + h);
			this.card(x, y + h);
			g.text(this.font, "Camera · follows " + Editor.followName(z.follow), x + 8, y + 6, ACCENT, false);
			this.buttons.add(new Button(x + 8, y + 18, w - 16, 14, "Look from here", () -> {
				var cam = EditorCam.last();
				if (cam != null) {
					Editor.zoneView(mc, z, cam.yaw(), cam.pitch(), (float) Math.max(3.0, EditorCam.distance() * 0.3));
				}
			}));
			this.buttons.add(new Button(x + 8, y + 35, w / 2 - 10, 14, "Follow", () -> Editor.cycleFollow(z)));
			g.text(this.font, String.valueOf(Math.round(z.dist)), x + w / 2 + 4, y + 38, DIM, false);
			this.buttons.add(new Button(x + w - 42, y + 35, 16, 14, "-", () -> Editor.zoneDistance(z, -1.0F)));
			this.buttons.add(new Button(x + w - 24, y + 35, 16, 14, "+", () -> Editor.zoneDistance(z, 1.0F)));
			this.buttons.add(new Button(x + 8, y + 52, w / 2 - 10, 14, "Preview", () -> EditorCam.preview(z)));
			this.buttons.add(new Button(x + w / 2 + 2, y + 52, w / 2 - 10, 14, "Delete", () -> Editor.deleteZone(z)));
			g.text(this.font, "Priority " + z.priority, x + 8, y + 72, DIM, false);
			this.buttons.add(new Button(x + w - 42, y + 69, 16, 14, "-", () -> Editor.zonePriority(z, -1)));
			this.buttons.add(new Button(x + w - 24, y + 69, 16, 14, "+", () -> Editor.zonePriority(z, 1)));
			this.floorRow(g, mc, z, x, y + 86, w);
			g.text(this.font, "Counts: " + Editor.afterName(z), x + 8, y + 105, z.after >= 0 ? 0xFFC9A6FF : DIM, false);
			int ty = y + 117;
			if (z.after >= 0) {
				this.buttons.add(new Button(x + 8, y + 116, w - 16, 14, "Always", () -> Editor.zoneAlways(z)));
				ty = y + 134;
			}
			g.pose().pushMatrix();
			g.pose().translate(x + 8.0F, ty + 2.0F);
			g.pose().scale(0.8F, 0.8F);
			g.text(this.font, "Drag camera: move · Shift: up/down", 0, 0, 0xFF9FD8F0, false);
			g.pose().popMatrix();
			g.pose().pushMatrix();
			g.pose().translate(x + 8.0F, ty + 10.0F);
			g.pose().scale(0.8F, 0.8F);
			g.text(this.font, "White dots: resize/move · Shift: floor", 0, 0, 0xFF9FD8F0, false);
			g.pose().popMatrix();
		} else if (Editor.selectedGate() != null) {
			LevelData.Gate gate = Editor.selectedGate();
			this.panel(g, x, y, x + w, y + 52);
			g.text(this.font, "Gate · " + gate.blocks.size() + " blocks", x + 8, y + 7, ACCENT, false);
			g.text(this.font, Editor.level() != null && Editor.level().triggered(gate.id) ? "Bursts when its switch is used" : "Not linked to a switch yet", x + 8, y + 19, DIM, false);
			this.buttons.add(new Button(x + 8, y + 32, w - 16, 14, "Delete (Del)", () -> Editor.deleteGate(gate)));
		} else if (Editor.selectedSwitch() != null) {
			LevelData.Switch sw = Editor.selectedSwitch();
			this.panel(g, x, y, x + w, y + 64);
			g.text(this.font, "Switch · " + sw.targets.size() + (sw.targets.size() == 1 ? " target" : " targets"), x + 8, y + 7, ACCENT, false);
			g.text(this.font, Editor.pendingSwitch() != null ? "Now click what it sets off" : "Link: the switch, then a target", x + 8, y + 19, DIM, false);
			g.text(this.font, "Lever, button or pressure plate", x + 8, y + 31, DIM, false);
			this.buttons.add(new Button(x + 8, y + 44, w - 16, 14, "Remove (Del)", () -> Editor.deleteSwitch(sw)));
		} else if (Editor.selectedPickup() != null) {
			LevelData.Pickup pk = Editor.selectedPickup();
			this.panel(g, x, y, x + w, y + 84);
			this.card(x, y + 84);
			g.text(this.font, "Something to find", x + 8, y + 6, ACCENT, false);
			this.buttons.add(new Button(x + 8, y + 18, w - 16, 16, LevelLife.itemName(pk.item) + "  ▼", () -> this.pickItem("What lies here?", pk.item, false, id -> {
				if (!id.isEmpty()) {
					pk.item = id;
					Editor.save();
				}
			}), new ItemStack(LevelLife.item(pk.item))));
			this.small(g, pk.targets.isEmpty() ? "Sets off: nothing yet (Link tool)" : "Finding it sets off " + things(pk.targets.size()), x + 8, y + 41,
				pk.targets.isEmpty() ? DIM : 0xFFC9A6FF);
			this.small(g, "The figure picks it up by walking into it", x + 8, y + 52, DIM);
			this.buttons.add(new Button(x + 8, y + 65, w - 16, 14, "Remove (Del)", () -> Editor.removePlace("pickup", pk.pos)));
		} else if (Editor.selectedNpc() != null) {
			LevelData.Npc npc = Editor.selectedNpc();
			int h = 182;
			y = this.cardY(h);
			this.cardAt(y);
			this.panel(g, x, y, x + w, y + h);
			this.card(x, y + h);
			g.text(this.font, LevelLife.displayName(npc), x + 8, y + 6, ACCENT, false);
			this.small(g, npc.targets.isEmpty() ? "Deal sets off: nothing yet (Link tool)" : "The deal sets off " + things(npc.targets.size()), x + 8, y + 18, npc.targets.isEmpty() ? DIM : 0xFFC9A6FF);
			int half = (w - 20) / 2;
			this.buttons.add(new Button(x + 8, y + 29, half, 15, LevelLife.kindName(npc.kind) + " ▼", () -> this.pickKind(npc.kind, k -> {
				npc.kind = k;
				Editor.save();
			}), new ItemStack(Items.VILLAGER_SPAWN_EGG)));
			this.buttons.add(new Button(x + 12 + half, y + 29, half, 15, "Name ✎", () -> this.typeText("Their name", "Empty: called by what they are (" + LevelLife.kindName(npc.kind) + ")",
				npc.name, 24, t -> {
					npc.name = t;
					Editor.save();
				})));
			g.text(this.font, "Wants", x + 8, y + 50, DIM, false);
			this.buttons.add(new Button(x + 44, y + 47, w - 52, 15, LevelLife.itemName(npc.wants) + " ▼", () -> this.pickItem("What do they want?", npc.wants, true, id -> {
				npc.wants = id;
				Editor.save();
			}), npc.wants.isEmpty() ? new ItemStack(Items.BARRIER) : new ItemStack(LevelLife.item(npc.wants))));
			g.text(this.font, "Gives", x + 8, y + 68, DIM, false);
			this.buttons.add(new Button(x + 44, y + 65, w - 88, 15, npc.givesCount + " " + LevelLife.itemName(npc.gives) + " ▼", () -> this.pickItem("What do they give?", npc.gives, true, id -> {
				npc.gives = id;
				Editor.save();
			}), npc.gives.isEmpty() ? new ItemStack(Items.BARRIER) : new ItemStack(LevelLife.item(npc.gives))));
			this.buttons.add(new Button(x + w - 42, y + 65, 16, 15, "-", () -> Editor.npcCount(npc, -1)));
			this.buttons.add(new Button(x + w - 24, y + 65, 16, 15, "+", () -> Editor.npcCount(npc, 1)));
			this.sayRow(g, x, y + 85, w, "First", LevelLife.askLine(npc), !npc.ask.isBlank(), () -> this.typeText("What they say first", "Empty: the usual words", npc.ask, 200, t -> {
				npc.ask = t;
				Editor.save();
			}));
			this.sayRow(g, x, y + 108, w, "Thanks", LevelLife.thanksLine(npc), !npc.thanks.isBlank(), () -> this.typeText("What they say at the deal",
				"Empty: the usual words", npc.thanks, 200, t -> {
					npc.thanks = t;
					Editor.save();
				}));
			this.sayRow(g, x, y + 131, w, "After", LevelLife.doneLine(npc), !npc.after.isBlank(), () -> this.typeText("What they say after the deal",
				"Empty: the usual words", npc.after, 200, t -> {
					npc.after = t;
					Editor.save();
				}));
			this.buttons.add(new Button(x + 8, y + 160, w - 16, 14, "Remove (Del)", () -> Editor.removePlace("npc", npc.pos)));
		} else if ("spawn".equals(marker) && Editor.selectedPos() != null && Editor.level() != null && Editor.level().spawnAt(Editor.selectedPos()) != null) {
			LevelData d = Editor.level();
			LevelData.Spawn sp = d.spawnAt(Editor.selectedPos());
			this.panel(g, x, y, x + w, y + 100);
			this.card(x, y + 100);
			g.text(this.font, "Enemy", x + 8, y + 6, ACCENT, false);
			var egg = LevelLife.item(sp.type + "_spawn_egg");
			this.buttons.add(new Button(x + 8, y + 18, w - 16, 16, Editor.mobName(sp.type) + "  ▼", () -> this.pickMob(sp.type, t -> {
				sp.type = t;
				Editor.save();
			}), new ItemStack(egg == Items.AIR ? Items.ZOMBIE_SPAWN_EGG : egg)));
			g.text(this.font, "How many: " + sp.count, x + 8, y + 42, TEXT, false);
			this.buttons.add(new Button(x + w - 42, y + 39, 16, 14, "-", () -> Editor.spawnCount(sp, -1)));
			this.buttons.add(new Button(x + w - 24, y + 39, 16, 14, "+", () -> Editor.spawnCount(sp, 1)));
			String when = "Appears: right at the start";
			for (LevelData.Trigger t : d.triggers) {
				if (t.targets.contains(sp.id)) {
					when = "Appears: when the trigger goes off";
				}
			}
			if (when.endsWith("start") && d.triggered(sp.id)) {
				when = "Appears: when what it's linked to goes off";
			}
			this.small(g, when, x + 8, y + 58, d.triggered(sp.id) ? 0xFFC9A6FF : DIM);
			this.small(g, "Link a trigger to it: it waits until then", x + 8, y + 69, DIM);
			this.buttons.add(new Button(x + 8, y + 81, w - 16, 14, "Remove (Del)", () -> Editor.removePlace("spawn", sp.pos)));
		} else if (Editor.selectedTrigger() != null && Editor.level() != null) {
			LevelData.Trigger t = Editor.selectedTrigger();
			int h = 118;
			this.panel(g, x, y, x + w, y + h);
			this.card(x, y + h);
			g.text(this.font, "Trigger area", x + 8, y + 6, ACCENT, false);
			this.small(g, t.targets.isEmpty() ? "Sets off: nothing yet (Link tool)" : "Walking in sets off " + things(t.targets.size()), x + 8, y + 19,
				t.targets.isEmpty() ? DIM : 0xFFC9A6FF);
			this.buttons.add(new Button(x + 8, y + 31, w - 16, 14, t.once ? "Goes off: the first time" : "Goes off: every time", () -> Editor.triggerOnce(t)));
			this.floorRow(g, mc, t, x, y + 50, w);
			this.buttons.add(new Button(x + 8, y + 69, w - 16, 14, "Delete (Del)", () -> Editor.deleteTrigger(t)));
			g.pose().pushMatrix();
			g.pose().translate(x + 8.0F, y + 89.0F);
			g.pose().scale(0.8F, 0.8F);
			g.text(this.font, "White dots: resize/move · Shift: floor", 0, 0, 0xFF9FD8F0, false);
			g.text(this.font, "Sets off: blueprints, gates, enemies,", 0, 11, 0xFF9FD8F0, false);
			g.text(this.font, "explosions and cameras", 0, 22, 0xFF9FD8F0, false);
			g.pose().popMatrix();
		} else if (Editor.selectedBlast() != null && Editor.level() != null) {
			LevelData.Blast b = Editor.selectedBlast();
			LevelData d = Editor.level();
			this.panel(g, x, y, x + w, y + 84);
			this.card(x, y + 84);
			g.text(this.font, "Explosion", x + 8, y + 6, ACCENT, false);
			String by = null;
			for (LevelData.Trigger t : d.triggers) {
				if (t.targets.contains(b.id)) {
					by = "a trigger";
				}
			}
			this.small(g, d.triggered(b.id) ? "Goes off with " + (by != null ? by : "what it's linked to") : "Not linked yet: Link a trigger or switch to it", x + 8, y + 19,
				d.triggered(b.id) ? 0xFFC9A6FF : 0xFFFF9A7A);
			g.text(this.font, "Power: " + Math.round(b.power), x + 8, y + 35, TEXT, false);
			this.buttons.add(new Button(x + w - 42, y + 32, 16, 14, "-", () -> Editor.blastPower(b, -1.0F)));
			this.buttons.add(new Button(x + w - 24, y + 32, 16, 14, "+", () -> Editor.blastPower(b, 1.0F)));
			this.small(g, "Breaks only blocks marked Smash or TNT only", x + 8, y + 51, DIM);
			this.buttons.add(new Button(x + 8, y + 64, w - 16, 14, "Remove (Del)", () -> Editor.deleteBlast(b)));
		} else if ("cp".equals(marker) && Editor.selectedPos() != null) {
			BlockPos cp = Editor.selectedPos();
			this.panel(g, x, y, x + w, y + 34);
			g.text(this.font, "Checkpoint", x + 8, y + 5, ACCENT, false);
			this.buttons.add(new Button(x + 8, y + 16, w - 16, 14, "Remove (Del)", () -> Editor.removePlace("cp", cp)));
		} else if (chosen != null) {
			this.panel(g, x, y, x + w, y + 88);
			g.text(this.font, "Blueprint · " + chosen.blocks.size() + " bricks", x + 8, y + 7, ACCENT, false);
			g.text(this.font, Editor.level() != null && Editor.level().triggered(chosen.id) ? "Builds itself by a switch" : "In play: hold R at the heap", x + 8, y + 19, DIM, false);
			this.buttons.add(new Button(x + 8, y + 32, w - 16, 14, "Move the heap", () -> Editor.movePile(chosen)));
			this.buttons.add(new Button(x + 8, y + 50, w - 16, 14, "Back into the world", () -> Editor.dissolve(mc, chosen)));
			this.buttons.add(new Button(x + 8, y + 68, w - 16, 14, "Delete (Del)", () -> Editor.delete(chosen)));
		} else if ("start".equals(marker) || "goal".equals(marker)) {
			this.panel(g, x, y, x + w, y + 34);
			g.text(this.font, marker.equals("start") ? "Start" : "Goal", x + 8, y + 5, ACCENT, false);
			this.buttons.add(new Button(x + 8, y + 16, w - 16, 14, "Remove (Del)", () -> Editor.removeMarker(marker)));
		} else if (Editor.tool() == Editor.Tool.BREAKABLE && Editor.level() != null) {
			LevelData d = Editor.level();
			net.minecraft.world.level.block.Block last = Editor.lastBreakBlock();
			int hgt = last != null ? 124 : 106;
			this.panel(g, x, y, x + w, y + hgt);
			g.text(this.font, "Smash " + d.breakable.size() + " · TNT " + d.blastable.size() + " · Lift " + d.liftable.size(), x + 8, y + 7, ACCENT, false);
			int mm = Editor.markMode();
			String how = mm == 2 ? (Carry.available() ? "Clicks mark: it can be carried" : "Lift needs the Carry On mod") : mm == 1 ? "Clicks mark: only TNT breaks it" : "Clicks mark: a hit smashes it";
			g.text(this.font, how, x + 8, y + 19, mm == 2 && !Carry.available() ? 0xFFE08A5A : DIM, false);
			int bw = (w - 16 - 8) / 3;
			this.buttons.add(new Button(x + 8, y + 32, bw, 14, (mm == 0 ? "> " : "") + "Smash", () -> Editor.markMode(0)));
			this.buttons.add(new Button(x + 12 + bw, y + 32, bw, 14, (mm == 1 ? "> " : "") + "TNT only", () -> Editor.markMode(1)));
			this.buttons.add(new Button(x + 16 + 2 * bw, y + 32, bw, 14, (mm == 2 ? "> " : "") + "Lift", () -> Editor.markMode(2)));
			y += 18;
			this.buttons.add(new Button(x + 8, y + 32, w - 16, 14, "All plants", () -> {
				if (mc.level != null) {
					Editor.markPlants(mc, mc.level);
				}
			}));
			int by = y + 50;
			if (last != null) {
				String name = last.getName().getString();
				if (this.font.width("All " + name) > w - 24) {
					name = this.font.plainSubstrByWidth(name, w - 40) + "..";
				}
				String label = "All " + name;
				this.buttons.add(new Button(x + 8, by, w - 16, 14, label, () -> {
					if (mc.level != null) {
						Editor.markAllOf(mc, mc.level, last);
					}
				}));
				by += 18;
			}
			this.buttons.add(new Button(x + 8, by, w - 16, 14, "Nothing smashable", () -> Editor.clearBreakable(mc)));
		}
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		String text = switch (Editor.tool()) {
			case POINTER -> "Click a blueprint, heap, start or goal: select it";
			case BLUEPRINT -> Editor.placing() != null ? "Click the ground: the heap lies there"
				: "Click: mark a thing · drag: mark an area · click a heap: move it";
			case LINK -> Editor.pendingSource() >= 0 ? "Now click what it sets off: blueprint, gate, enemy, explosion, camera (again: unlink)"
				: "Click what sets things off (lever, button, plate, villager, trigger, thing to find)";
			case START -> "Click: the figure starts here (looking the way the camera looks)";
			case CHECKPOINT -> "Click: checkpoint here (again: remove)";
			case GOAL -> "Click: the goal is here";
			case ENEMY -> "Click: enemy here · click an enemy: its card (kind, how many)";
			case CAMERA -> Editor.selectedZone() != null ? "Drag the camera box: move it (Shift: height) · drag the white dots: resize or move the area"
				: "Drag an area: new camera (looks the way you look now) · click a camera or its area: select it";
			case BREAKABLE -> (Editor.liftMode ? "Lift" : Editor.blastMode ? "TNT only" : "Smash") + " · click: mark (again: holds) · Shift: whole thing · Alt: one block · drag: area";
			case ITEM -> "Click: something to find lies here (you pick what) · click it: its card";
			case NPC -> "Click: a villager stands here · click a villager: name, words, deal";
			case TRIGGER -> Editor.selectedTrigger() != null ? "Drag the white dots: resize or move (Shift: floor) · Link tool: what it sets off"
				: "Drag across the ground: a trigger area · walking in sets things off";
			case BLAST -> "Click: an explosion goes off here when what it's linked to goes off";
		};
		int w = this.font.width(text) + 16;
		int x = (this.vw - w) / 2;
		int y = this.trayY - 26;
		if (this.cardBottom > y && x + w > this.cardX - 4) {
			// the card on the right reaches down here: the line moves left
			x = Math.max(6, this.cardX - w - 6);
		}
		this.panel(g, x, y, x + w, y + 16);
		g.centeredText(this.font, text, this.vw / 2, y + 4, TEXT);
	}

	private void pickTool(Editor.Tool t) {
		Editor.setTool(t);
		Editor.clearSelected();
		if (t != Editor.Tool.BLUEPRINT) {
			// blocks marked for a blueprint only matter to the blueprint tool; the next tool shows its own card
			Editor.clearSelection();
		}
		Editor.play(Minecraft.getInstance(), SoundEvents.UI_BUTTON_CLICK.value(), 1.3F);
	}

	// ---------------------------------------------------------------- pop-ups: typing words, picking a thing

	/** Typing a name or what someone says. */
	private static final class Prompt {
		final String title;
		final String hint;
		final StringBuilder text;
		final int max;
		final java.util.function.Consumer<String> done;
		int cursor;

		Prompt(String title, String hint, String start, int max, java.util.function.Consumer<String> done) {
			this.title = title;
			this.hint = hint;
			this.text = new StringBuilder(start);
			this.max = max;
			this.done = done;
			this.cursor = this.text.length();
		}
	}

	/** One thing to pick: what is stored, its picture, its name. */
	private record Choice(String id, ItemStack icon, String name) {
	}

	/** Picking one thing out of many (items, enemies, villagers): a grid of pictures with a search line. */
	private static final class Picker {
		final String title;
		final List<Choice> all;
		final @Nullable String none;
		final String current;
		final java.util.function.Consumer<String> done;
		final StringBuilder search = new StringBuilder();
		String searched = "\u0000";
		List<Choice> shown = List.of();
		int scroll;

		Picker(String title, List<Choice> all, @Nullable String none, String current, java.util.function.Consumer<String> done) {
			this.title = title;
			this.all = all;
			this.none = none;
			this.current = current;
			this.done = done;
		}

		List<Choice> shown() {
			String q = this.search.toString().strip().toLowerCase(java.util.Locale.ROOT);
			if (!q.equals(this.searched)) {
				this.searched = q;
				this.scroll = 0;
				if (q.isEmpty()) {
					this.shown = this.all;
				} else {
					List<Choice> hits = new ArrayList<>();
					for (Choice c : this.all) {
						if (c.name.toLowerCase(java.util.Locale.ROOT).contains(q) || c.id.contains(q)) {
							hits.add(c);
						}
					}
					this.shown = hits;
				}
			}
			return this.shown;
		}
	}

	private @Nullable Prompt prompt;
	private @Nullable Picker picker;
	private static final int CELL = 20;
	private static final int COLS = 12;
	private static final int ROWS = 7;
	private static @Nullable List<Choice> allItems;

	public void typeText(String title, String hint, String start, int max, java.util.function.Consumer<String> done) {
		this.picker = null;
		this.prompt = new Prompt(title, hint, start, max, done);
	}

	/** Every item of the game, the common level things first. */
	private static List<Choice> items() {
		if (allItems == null) {
			List<Choice> list = new ArrayList<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (String id : LevelLife.ITEMS) {
				var it = LevelLife.item(id);
				if (it != Items.AIR && seen.add(id)) {
					ItemStack st = new ItemStack(it);
					list.add(new Choice(id, st, st.getHoverName().getString()));
				}
			}
			for (var it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				if (it == Items.AIR) {
					continue;
				}
				String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString();
				if (seen.add(id)) {
					ItemStack st = new ItemStack(it);
					list.add(new Choice(id, st, st.getHoverName().getString()));
				}
			}
			allItems = list;
		}
		return allItems;
	}

	public void pickItem(String title, String current, boolean orNothing, java.util.function.Consumer<String> done) {
		this.prompt = null;
		this.picker = new Picker(title, items(), orNothing ? "Nothing" : null, current, done);
	}

	public void pickMob(String current, java.util.function.Consumer<String> done) {
		List<Choice> list = new ArrayList<>();
		for (int i = 0; i < Editor.MOBS.length; i++) {
			var egg = LevelLife.item(Editor.MOBS[i] + "_spawn_egg");
			list.add(new Choice(Editor.MOBS[i], new ItemStack(egg == Items.AIR ? Items.ZOMBIE_SPAWN_EGG : egg), Editor.MOB_NAMES[i]));
		}
		this.prompt = null;
		this.picker = new Picker("Which enemy?", list, null, current, done);
	}

	public void pickKind(String current, java.util.function.Consumer<String> done) {
		var icons = new net.minecraft.world.item.Item[] {Items.COMPOSTER, Items.LECTERN, Items.BARREL, Items.LOOM, Items.BLAST_FURNACE, Items.BREWING_STAND,
			Items.SMITHING_TABLE, Items.VILLAGER_SPAWN_EGG};
		List<Choice> list = new ArrayList<>();
		for (int i = 0; i < LevelLife.KINDS.length; i++) {
			list.add(new Choice(LevelLife.KINDS[i], new ItemStack(icons[Math.min(i, icons.length - 1)]), LevelLife.KIND_NAMES[i]));
		}
		this.prompt = null;
		this.picker = new Picker("Who is it?", list, null, current, done);
	}

	private void closePopup() {
		this.prompt = null;
		this.picker = null;
	}

	private void drawPrompt(GuiGraphicsExtractor g) {
		Prompt pr = this.prompt;
		if (pr == null) {
			return;
		}
		int w = Math.min(320, this.vw - 40);
		int x = (this.vw - w) / 2;
		int boxH = pr.max > 40 ? 64 : 16;
		int h = 52 + boxH + 20;
		int y = Math.max(10, (this.vh - h) / 2 - 20);
		this.panel(g, x, y, x + w, y + h);
		g.outline(x, y, w, h, ACCENT);
		g.text(this.font, pr.title, x + 10, y + 8, ACCENT, false);
		g.text(this.font, pr.hint, x + 10, y + 20, DIM, false);
		int bx = x + 10;
		int by = y + 34;
		g.fill(bx, by, x + w - 10, by + boxH, 0xF00E1218);
		g.outline(bx, by, w - 20, boxH, 0xFF5C6B80);
		// the words with a blinking cursor, wrapped
		boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
		String before = pr.text.substring(0, pr.cursor);
		String after = pr.text.substring(pr.cursor);
		var line = Component.literal(before).append(Component.literal(blink ? "|" : " ").withStyle(net.minecraft.ChatFormatting.YELLOW)).append(Component.literal(after));
		int ty = by + 4;
		for (var l : this.font.split(line, w - 30)) {
			if (ty > by + boxH - 10) {
				break;
			}
			g.text(this.font, l, bx + 5, ty, TEXT, false);
			ty += 10;
		}
		g.text(this.font, pr.text.length() + " / " + pr.max, x + w - 12 - this.font.width(pr.text.length() + " / " + pr.max), by + boxH + 4, DIM, false);
		int bw = 90;
		this.buttons.add(new Button(x + 10, y + h - 20, bw, 14, "OK (Enter)", this::confirmPrompt));
		this.buttons.add(new Button(x + 14 + bw, y + h - 20, bw, 14, "Cancel (Esc)", this::closePopup));
		this.buttons.add(new Button(x + 18 + 2 * bw, y + h - 20, w - 38 - 2 * bw, 14, "Clear", () -> {
			pr.text.setLength(0);
			pr.cursor = 0;
		}));
	}

	private void confirmPrompt() {
		Prompt pr = this.prompt;
		this.prompt = null;
		if (pr != null) {
			pr.done.accept(pr.text.toString().strip());
			Editor.play(Minecraft.getInstance(), SoundEvents.UI_BUTTON_CLICK.value(), 1.4F);
		}
	}

	private void drawPicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Picker pk = this.picker;
		if (pk == null) {
			return;
		}
		List<Choice> list = pk.shown();
		int w = COLS * CELL + 20;
		int h = 46 + ROWS * CELL + 42;
		int x = (this.vw - w) / 2;
		int y = Math.max(6, (this.vh - h) / 2 - 16);
		this.panel(g, x, y, x + w, y + h);
		g.outline(x, y, w, h, ACCENT);
		g.text(this.font, pk.title, x + 10, y + 8, ACCENT, false);
		// the search line: just type
		int sy = y + 22;
		g.fill(x + 10, sy, x + w - 10, sy + 16, 0xF00E1218);
		g.outline(x + 10, sy, w - 20, 16, 0xFF5C6B80);
		boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
		String q = pk.search.toString();
		g.text(this.font, q.isEmpty() ? "Type to search..." : q + (blink ? "|" : ""), x + 15, sy + 4, q.isEmpty() ? 0xFF6E7A8A : TEXT, false);
		g.text(this.font, list.size() + "", x + w - 14 - this.font.width(list.size() + ""), sy + 4, DIM, false);
		int rows = (list.size() + COLS - 1) / COLS;
		pk.scroll = Math.max(0, Math.min(pk.scroll, Math.max(0, rows - ROWS)));
		int gx = x + 10;
		int gy = y + 44;
		g.fill(gx - 1, gy - 1, gx + COLS * CELL + 1, gy + ROWS * CELL + 1, 0x60000000);
		Choice hot = null;
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = (pk.scroll + r) * COLS + c;
				if (i >= list.size()) {
					break;
				}
				Choice ch = list.get(i);
				int cx = gx + c * CELL;
				int cy = gy + r * CELL;
				boolean over = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
				if (ch.id.equals(pk.current)) {
					g.fill(cx, cy, cx + CELL, cy + CELL, 0x80FFD84A);
				}
				if (over) {
					g.fill(cx, cy, cx + CELL, cy + CELL, 0x60FFFFFF);
					hot = ch;
				}
				g.item(ch.icon, cx + 2, cy + 2);
				this.buttons.add(new Button(cx, cy, CELL, CELL, "", () -> this.choose(ch.id)));
			}
		}
		if (rows > ROWS) {
			// where in the list we are
			int barH = ROWS * CELL;
			int knob = Math.max(10, barH * ROWS / rows);
			int ky = gy + (barH - knob) * pk.scroll / Math.max(1, rows - ROWS);
			g.fill(gx + COLS * CELL + 3, gy, gx + COLS * CELL + 6, gy + barH, 0x40FFFFFF);
			g.fill(gx + COLS * CELL + 3, ky, gx + COLS * CELL + 6, ky + knob, 0xC0FFFFFF);
		}
		int fy = gy + ROWS * CELL + 6;
		String name = hot != null ? hot.name : list.isEmpty() ? "Nothing found" : "Scroll for more · click one";
		g.text(this.font, name, x + 10, fy, hot != null ? TEXT : DIM, false);
		int bw = 80;
		this.buttons.add(new Button(x + w - 10 - bw, fy + 12, bw, 14, "Cancel (Esc)", this::closePopup));
		if (pk.none != null) {
			this.buttons.add(new Button(x + w - 14 - 2 * bw, fy + 12, bw, 14, pk.none, () -> this.choose("")));
		}
	}

	private void choose(String id) {
		Picker pk = this.picker;
		this.picker = null;
		if (pk != null) {
			pk.done.accept(id);
			Editor.play(Minecraft.getInstance(), SoundEvents.ITEM_PICKUP, 1.4F);
		}
	}

	/** Keys while a pop-up is open (true: it took the key). */
	private boolean popupKey(KeyEvent event) {
		int key = event.key();
		if (this.prompt != null) {
			Prompt pr = this.prompt;
			if (event.isEscape()) {
				this.closePopup();
			} else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				this.confirmPrompt();
			} else if (key == InputConstants.KEY_BACKSPACE) {
				if (pr.cursor > 0) {
					int from = pr.cursor - 1;
					if (event.hasControlDownWithQuirk()) {
						// a whole word
						while (from > 0 && pr.text.charAt(from - 1) == ' ') {
							from--;
						}
						while (from > 0 && pr.text.charAt(from - 1) != ' ') {
							from--;
						}
					}
					pr.text.delete(from, pr.cursor);
					pr.cursor = from;
				}
			} else if (key == InputConstants.KEY_DELETE) {
				if (pr.cursor < pr.text.length()) {
					pr.text.deleteCharAt(pr.cursor);
				}
			} else if (key == InputConstants.KEY_LEFT) {
				pr.cursor = Math.max(0, pr.cursor - 1);
			} else if (key == InputConstants.KEY_RIGHT) {
				pr.cursor = Math.min(pr.text.length(), pr.cursor + 1);
			} else if (key == InputConstants.KEY_HOME) {
				pr.cursor = 0;
			} else if (key == InputConstants.KEY_END) {
				pr.cursor = pr.text.length();
			} else if (event.hasControlDownWithQuirk() && event.shortcutKey() == 'v') {
				this.insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace('\n', ' ').replace('\r', ' '));
			} else if (event.hasControlDownWithQuirk() && event.shortcutKey() == 'a') {
				pr.cursor = pr.text.length();
			}
			return true;
		}
		if (this.picker != null) {
			Picker pk = this.picker;
			if (event.isEscape()) {
				this.closePopup();
			} else if (key == InputConstants.KEY_BACKSPACE) {
				if (!pk.search.isEmpty()) {
					pk.search.setLength(pk.search.length() - 1);
				}
			} else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				List<Choice> list = pk.shown();
				if (!list.isEmpty()) {
					this.choose(list.get(0).id);
				}
			} else if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_PAGEDOWN) {
				pk.scroll += key == InputConstants.KEY_PAGEDOWN ? ROWS : 1;
			} else if (key == InputConstants.KEY_UP || key == InputConstants.KEY_PAGEUP) {
				pk.scroll = Math.max(0, pk.scroll - (key == InputConstants.KEY_PAGEUP ? ROWS : 1));
			}
			return true;
		}
		return false;
	}

	private void insert(String s) {
		Prompt pr = this.prompt;
		if (pr == null || s.isEmpty()) {
			return;
		}
		int room = pr.max - pr.text.length();
		if (room <= 0) {
			return;
		}
		String add = s.length() > room ? s.substring(0, room) : s;
		pr.text.insert(pr.cursor, add);
		pr.cursor += add.length();
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (this.prompt != null) {
			if (event.isAllowedChatCharacter()) {
				this.insert(event.codepointAsString());
			}
			return true;
		}
		if (this.picker != null) {
			if (event.isAllowedChatCharacter() && this.picker.search.length() < 30) {
				this.picker.search.append(event.codepointAsString());
			}
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (this.picker != null) {
			this.picker.scroll = Math.max(0, this.picker.scroll - (int) Math.signum(scrollY) * 2);
			return true;
		}
		if (this.prompt != null) {
			return true;
		}
		EditorCam.zoom(scrollY);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x() / this.ui;
		double my = event.y() / this.ui;
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Button b : new ArrayList<>(this.buttons)) {
				if (b.inside(mx, my)) {
					b.action.run();
					if (!b.label.isEmpty()) {
						Editor.play(Minecraft.getInstance(), SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
					}
					return true;
				}
			}
			if (this.prompt != null || this.picker != null) {
				return true;
			}
			this.pressX = mx;
			this.pressY = my;
			this.pressedInWorld = true;
			this.dragging = false;
			this.handling = false;
			this.pressPick = Editor.hover();
			Pick pp = this.pressPick;
			LevelData d = Editor.level();
			Minecraft mc = Minecraft.getInstance();
			if (pp != null && d != null && mc.level != null && (pp.kind() == Pick.Kind.HANDLE || pp.kind() == Pick.Kind.CAMERA)) {
				// remember the area as it was: dragging a handle or the camera box changes it from there
				this.handleZone = pp.kind() == Pick.Kind.HANDLE ? Editor.selectedArea() : d.zone(pp.id());
				if (this.handleZone != null) {
					LevelData.AreaBox z = this.handleZone;
					if (pp.kind() == Pick.Kind.HANDLE) {
						// its floor stays where it is while it moves (it does not climb onto roofs)
						Editor.holdFloor(mc.level, z);
					}
					this.handleFrom = new int[] {z.x0, z.z0, z.x1, z.z1};
					this.handleFloor = Editor.areaGround(mc.level, d, z);
					this.floorDrag = 0.0;
					this.handleEye = z instanceof LevelData.CamZone cz ? Editor.zoneEye(mc.level, d, cz) : null;
					Vec3 g = this.groundHit(event.x(), event.y(), this.handleFloor);
					this.handleStart = g != null ? new int[] {(int) Math.floor(g.x), (int) Math.floor(g.z)} : new int[] {z.x0, z.z0};
				}
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.rightDrag = 0.0;
		}
		return true;
	}

	/** Dragging a handle of a camera area, or the camera box itself. */
	private boolean handling;
	private LevelData.@Nullable AreaBox handleZone;
	private double handleFloor;
	private double floorDrag;
	private int[] handleFrom = new int[4];
	private int[] handleStart = new int[2];
	private @Nullable Vec3 handleEye;

	/** Where the mouse ray meets the level of height {@code y} (null when it looks away from it). */
	private @Nullable Vec3 groundHit(double guiX, double guiY, double y) {
		Vec3[] ray = EditorCam.ray(guiX / Math.max(1, this.width), guiY / Math.max(1, this.height));
		if (ray == null || Math.abs(ray[1].y) < 1.0E-4) {
			return null;
		}
		double t = (y - ray[0].y) / ray[1].y;
		return t < 0.0 ? null : ray[0].add(ray[1].scale(t));
	}

	private void dragHandle(Minecraft mc, MouseButtonEvent event, double dy) {
		LevelData d = Editor.level();
		LevelData.AreaBox area = this.handleZone;
		Pick pp = this.pressPick;
		if (d == null || area == null || pp == null || mc.level == null) {
			return;
		}
		if (pp.kind() == Pick.Kind.HANDLE) {
			if (event.hasShiftDown()) {
				// Shift: the floor of the area goes up and down with the mouse
				this.floorDrag -= dy * Math.max(0.04, EditorCam.distance() * 0.004);
				area.ground = Mth.clamp(Math.round(this.handleFloor + this.floorDrag - 1.02) + 1.02, d.floorY + 1.02, d.topY);
				return;
			}
			Vec3 g = this.groundHit(event.x(), event.y(), Editor.areaGround(mc.level, d, area));
			if (g != null) {
				Editor.dragZoneHandle(area, pp.id(), (int) Math.floor(g.x), (int) Math.floor(g.z), this.handleFrom, this.handleStart[0], this.handleStart[1]);
			}
			return;
		}
		Vec3 eye = this.handleEye;
		if (eye == null || !(area instanceof LevelData.CamZone z)) {
			return;
		}
		if (event.hasShiftDown()) {
			// Shift: the camera goes up and down
			eye = eye.add(0.0, -dy * Math.max(0.05, EditorCam.distance() * 0.004), 0.0);
		} else {
			Vec3 g = this.groundHit(event.x(), event.y(), eye.y);
			if (g == null) {
				return;
			}
			eye = new Vec3(g.x, eye.y, g.z);
		}
		this.handleEye = eye;
		Editor.selectZone(z.id);
		Editor.setZoneEye(mc.level, z, eye);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.prompt != null || this.picker != null) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.pressedInWorld && this.handleZone != null && this.pressPick != null
			&& (this.pressPick.kind() == Pick.Kind.HANDLE || this.pressPick.kind() == Pick.Kind.CAMERA)) {
			if (this.handling || Math.abs(event.x() / this.ui - this.pressX) + Math.abs(event.y() / this.ui - this.pressY) > 6.0) {
				this.handling = true;
				this.dragHandle(Minecraft.getInstance(), event, dy);
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			// the right button only selects; dragging it does nothing (so a shaky click still counts)
			this.rightDrag += Math.abs(dx) + Math.abs(dy);
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			if (event.hasShiftDown()) {
				EditorCam.pan(dx, dy);
			} else {
				EditorCam.orbit(dx, dy);
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.pressedInWorld
			&& (Editor.tool() == Editor.Tool.BLUEPRINT || Editor.tool() == Editor.Tool.CAMERA || Editor.tool() == Editor.Tool.BREAKABLE || Editor.tool() == Editor.Tool.TRIGGER)
			&& Editor.placing() == null && this.pressPick != null && (this.pressPick.kind() == Pick.Kind.BLOCK || this.pressPick.kind() == Pick.Kind.GATE)) {
			if (Math.abs(event.x() - this.pressX) + Math.abs(event.y() - this.pressY) > 8.0) {
				this.dragging = true;
			}
			return true;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		Minecraft mc = Minecraft.getInstance();
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (this.rightDrag < 12.0) {
				this.selectUnder(mc, Editor.hover());
			}
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || !this.pressedInWorld) {
			return true;
		}
		this.pressedInWorld = false;
		if (this.handling) {
			this.handling = false;
			this.handleZone = null;
			this.floorDrag = 0.0;
			Editor.save();
			Editor.play(mc, SoundEvents.SPYGLASS_USE, 1.2F);
			return true;
		}
		this.handleZone = null;
		if (this.pressPick != null && this.pressPick.kind() == Pick.Kind.HANDLE) {
			return true;
		}
		boolean shift = event.hasShiftDown();
		boolean alt = event.hasAltDown() || event.hasControlDown();
		if (this.dragging) {
			this.dragging = false;
			Pick end = Editor.hover();
			if (this.pressPick != null && end != null && mc.level != null) {
				if (Editor.tool() == Editor.Tool.BREAKABLE) {
					Editor.breakBox(mc, mc.level, this.pressPick.pos(), end.pos(), alt);
				} else if (Editor.tool() == Editor.Tool.TRIGGER) {
					Editor.makeTrigger(mc, this.pressPick.pos().getX(), this.pressPick.pos().getZ(), end.pos().getX(), end.pos().getZ(), this.pressPick.pos());
				} else if (Editor.tool() == Editor.Tool.CAMERA) {
					var cam = EditorCam.last();
					if (cam != null) {
						Editor.makeZone(mc, this.pressPick.pos().getX(), this.pressPick.pos().getZ(), end.pos().getX(), end.pos().getZ(), cam.yaw(), cam.pitch(),
							(float) Math.max(6.0, EditorCam.distance() * 0.3), this.pressPick.pos());
					}
				} else {
					Editor.selectBox(mc.level, this.pressPick.pos(), end.pos(), shift, alt);
					Editor.clearSelected();
					Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.6F);
				}
			}
			dragBox = null;
			return true;
		}
		this.clickWorld(mc, Editor.hover(), shift, alt);
		return true;
	}

	/**
	 * Right-click: whatever the mouse is on gets selected, whatever the tool (a blueprint, a heap, a gate, a switch,
	 * a villager, a camera zone ...); its card opens. On nothing, the selection goes. A heap waiting for its place is
	 * put back instead.
	 */
	public void selectUnder(Minecraft mc, @Nullable Pick h) {
		LevelData d = Editor.level();
		if (Editor.placing() != null) {
			Editor.cancelPlacing(mc);
			return;
		}
		if (h == null || d == null) {
			Editor.clearSelected();
			Editor.clearSelection();
			return;
		}
		switch (h.kind()) {
			case GHOST, PILE -> Editor.selectBlueprint(h.id());
			case START -> Editor.selectMarker("start");
			case GOAL -> Editor.selectMarker("goal");
			case GATE -> Editor.selectGate(h.id());
			case SWITCH -> Editor.selectSwitch(h.id());
			case CHECKPOINT -> Editor.selectPlace("cp", h.pos());
			case SPAWN -> Editor.selectPlace("spawn", h.pos());
			case PICKUP -> Editor.selectPlace("pickup", h.pos());
			case NPC -> Editor.selectPlace("npc", h.pos());
			case CAMERA -> Editor.selectZone(h.id());
			case TRIGGER -> Editor.selectTrigger(h.id());
			case BLAST -> Editor.selectBlast(h.id());
			case BLOCK -> {
				LevelData.CamZone z = Editor.tool() == Editor.Tool.CAMERA ? d.zoneAt(h.at().x, h.at().z) : null;
				LevelData.Trigger tr = Editor.tool() == Editor.Tool.TRIGGER ? d.triggerAt(h.at().x, h.at().z) : null;
				LevelData.Blueprint b = d.blueprintAt(h.pos());
				if (z != null) {
					Editor.selectZone(z.id);
				} else if (tr != null) {
					Editor.selectTrigger(tr.id);
				} else if (b != null) {
					Editor.selectBlueprint(b.id);
				} else {
					Editor.clearSelected();
					Editor.clearSelection();
				}
			}
		}
		Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.5F);
	}

	/** A click into the level with the chosen tool (also used by the test harness). */
	public void clickWorld(Minecraft mc, @Nullable Pick h, boolean shift, boolean alt) {
		LevelData d = Editor.level();
		if (d == null || mc.level == null) {
			return;
		}
		if (h == null) {
			Editor.clearSelected();
			return;
		}
		switch (Editor.tool()) {
			case POINTER -> {
				switch (h.kind()) {
					case GHOST, PILE -> Editor.selectBlueprint(h.id());
					case START -> Editor.selectMarker("start");
					case GOAL -> Editor.selectMarker("goal");
					case GATE -> Editor.selectGate(h.id());
					case SWITCH -> Editor.selectSwitch(h.id());
					case CHECKPOINT -> Editor.selectPlace("cp", h.pos());
					case SPAWN -> Editor.selectPlace("spawn", h.pos());
					case PICKUP -> Editor.selectPlace("pickup", h.pos());
					case NPC -> Editor.selectPlace("npc", h.pos());
					case CAMERA -> Editor.selectZone(h.id());
					case TRIGGER -> Editor.selectTrigger(h.id());
					case BLAST -> Editor.selectBlast(h.id());
					default -> Editor.clearSelected();
				}
			}
			case LINK -> Editor.linkClick(mc, h);
			case BREAKABLE -> {
				if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE || h.kind() == Pick.Kind.SWITCH) {
					Editor.breakClick(mc, mc.level, h.pos(), shift, alt);
				}
			}
			case ITEM -> {
				if (h.kind() == Pick.Kind.PICKUP) {
					Editor.selectPlace("pickup", h.pos());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE) {
					BlockPos at = h.standing();
					boolean had = d.pickupAt(at) != null;
					Editor.placePickup(mc, at);
					LevelData.Pickup pk = d.pickupAt(at);
					if (!had && pk != null) {
						// right away: what lies here?
						this.pickItem("What lies here?", pk.item, false, id -> {
							if (!id.isEmpty()) {
								pk.item = id;
								Editor.save();
							}
						});
					}
				}
			}
			case NPC -> {
				if (h.kind() == Pick.Kind.NPC) {
					Editor.placeNpc(mc, h.pos());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE) {
					Editor.placeNpc(mc, h.standing());
				}
			}
			case CAMERA -> {
				LevelData.CamZone z = h.kind() == Pick.Kind.CAMERA ? d.zone(h.id()) : d.zoneAt(h.at().x, h.at().z);
				if (z != null) {
					Editor.selectZone(z.id);
				} else {
					Editor.clearSelected();
				}
			}
			case CHECKPOINT -> {
				if (h.kind() == Pick.Kind.CHECKPOINT) {
					Editor.toggleCheckpoint(mc, h.pos());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE || h.kind() == Pick.Kind.SWITCH) {
					Editor.toggleCheckpoint(mc, h.standing());
				}
			}
			case ENEMY -> {
				if (h.kind() == Pick.Kind.SPAWN) {
					Editor.selectPlace("spawn", h.pos());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE) {
					Editor.placeMob(mc, h.standing());
				}
			}
			case TRIGGER -> {
				LevelData.Trigger t = h.kind() == Pick.Kind.TRIGGER ? d.trigger(h.id()) : d.triggerAt(h.at().x, h.at().z);
				if (t != null) {
					Editor.selectTrigger(t.id);
				} else {
					Editor.clearSelected();
					Editor.say(mc, "Drag across the ground: a trigger area");
				}
			}
			case BLAST -> {
				if (h.kind() == Pick.Kind.BLAST) {
					Editor.selectBlast(h.id());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE) {
					Editor.placeBlast(mc, h.standing());
				}
			}
			case BLUEPRINT -> {
				if (Editor.placing() != null) {
					if (h.kind() == Pick.Kind.BLOCK) {
						Editor.placePile(mc, h.face() == Direction.UP ? h.pos() : h.standing().below());
					}
				} else if (h.kind() == Pick.Kind.PILE) {
					LevelData.Blueprint b = d.blueprint(h.id());
					if (b != null) {
						Editor.selectBlueprint(b.id);
						Editor.movePile(b);
					}
				} else if (h.kind() == Pick.Kind.GHOST) {
					Editor.selectBlueprint(h.id());
				} else if (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE || h.kind() == Pick.Kind.SWITCH) {
					Editor.clearSelected();
					Editor.select(mc.level, h.pos(), shift, alt);
					Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.8F);
				}
			}
			case START -> {
				if (h.kind() == Pick.Kind.BLOCK) {
					var cam = EditorCam.last();
					Editor.setStart(mc, h.standing(), cam != null ? cam.yaw() : 0.0F);
				}
			}
			case GOAL -> {
				if (h.kind() == Pick.Kind.BLOCK) {
					Editor.setGoal(mc, h.standing());
				}
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		Minecraft mc = Minecraft.getInstance();
		int key = event.key();
		if (this.popupKey(event)) {
			return true;
		}
		// by the letter on the key (a German Z is where an English Y is)
		if (event.hasControlDownWithQuirk() && event.shortcutKey() == 'z') {
			if (event.hasShiftDown()) {
				EditorHistory.redo(mc);
			} else {
				EditorHistory.undo(mc);
			}
			return true;
		}
		if (event.hasControlDownWithQuirk() && event.shortcutKey() == 'y') {
			EditorHistory.redo(mc);
			return true;
		}
		if (event.isEscape()) {
			if (Editor.placing() != null) {
				Editor.cancelPlacing(mc);
			} else if (!Editor.selection().isEmpty()) {
				Editor.clearSelection();
			} else if (Editor.pendingSource() >= 0 || Editor.selectedBlueprint() != null || Editor.selectedMarker() != null
				|| Editor.selectedGate() != null || Editor.selectedSwitch() != null || Editor.selectedZone() != null || Editor.selectedTrigger() != null
				|| Editor.selectedBlast() != null) {
				Editor.linkClick(mc, null);
				Editor.clearSelected();
			} else {
				Editor.closeDiorama(mc);
			}
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			Editor.makeBlueprint(mc);
			return true;
		}
		if (key == InputConstants.KEY_G) {
			Editor.makeGate(mc);
			return true;
		}
		if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
			LevelData.Blueprint b = Editor.selectedBlueprint();
			String m = Editor.selectedMarker();
			if (b != null) {
				Editor.delete(b);
			} else if (Editor.selectedZone() != null) {
				Editor.deleteZone(Editor.selectedZone());
			} else if (Editor.selectedTrigger() != null) {
				Editor.deleteTrigger(Editor.selectedTrigger());
			} else if (Editor.selectedBlast() != null) {
				Editor.deleteBlast(Editor.selectedBlast());
			} else if (Editor.selectedGate() != null) {
				Editor.deleteGate(Editor.selectedGate());
			} else if (Editor.selectedSwitch() != null) {
				Editor.deleteSwitch(Editor.selectedSwitch());
			} else if (("cp".equals(m) || "spawn".equals(m) || "pickup".equals(m) || "npc".equals(m)) && Editor.selectedPos() != null) {
				Editor.removePlace(m, Editor.selectedPos());
			} else if (m != null) {
				Editor.removeMarker(m);
			}
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			Editor.closeDiorama(mc);
			return true;
		}
		if (key == InputConstants.KEY_O) {
			Editor.enterPlay(mc);
			return true;
		}
		if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
			this.pickTool(SLOTS[key - InputConstants.KEY_1].tool);
			return true;
		}
		if (key == InputConstants.KEY_0) {
			this.pickTool(Editor.Tool.ITEM);
			return true;
		}
		if (key == InputConstants.KEY_N) {
			this.pickTool(Editor.Tool.NPC);
			return true;
		}
		if (key == InputConstants.KEY_T) {
			this.pickTool(Editor.Tool.TRIGGER);
			return true;
		}
		if (key == InputConstants.KEY_X) {
			this.pickTool(Editor.Tool.BLAST);
			return true;
		}
		return true;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(null);
	}

	@Override
	public void removed() {
		dragBox = null;
		Editor.dioramaGone(Minecraft.getInstance());
	}
}
