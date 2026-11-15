package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.client.Coins;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The editor tutorial: the first level is built step by step on a prepared plate ({@link TutorialPlate}), one
 * thing at a time, each step done for real. A card says what to do, the tool it needs glows in the tray, a marker
 * in the level shows where. A step is over when the level shows it is done (a start is set, a gate exists ...), or,
 * for things that leave no trace in the level (turning the view, pressing Tab), when the editor reports it.
 * <p>
 * New editor features get a step by adding one entry to {@link #STEPS}: a title, a text, the tool, the spot of the
 * plate to point at (see {@link TutorialPlate}), and when it is done.
 */
public final class Tutorial {
	/**
	 * One step. {@code done} null: the card has a button to go on. {@code progress}: an optional line of what is
	 * done of it so far.
	 */
	public record Step(String title, String text, Editor.@Nullable Tool tool, @Nullable String spot, @Nullable Predicate<Ctx> done,
		@Nullable Function<Ctx, String> progress) {
		static Step of(String title, String text, Editor.@Nullable Tool tool, @Nullable String spot, @Nullable Predicate<Ctx> done) {
			return new Step(title, text, tool, spot, done, null);
		}
	}

	/** What a step can look at: the level, the editor, and what happened since the step began. */
	public record Ctx(LevelData level, Minecraft mc) {
		public boolean seen(String event) {
			return EVENTS.contains(event);
		}

		public @Nullable BlockPos spot(String name) {
			return this.level.spots.get(name);
		}
	}

	public static final List<Step> STEPS = List.of(
		Step.of("Welcome to the level editor!",
			"This plate is going to be your first level. Each step tells you what to do, and a glowing marker shows where. Nothing can go wrong here: just try things.",
			null, null, null),
		new Step("Look around",
			"Hold the middle mouse button and drag to look around. Scroll to zoom. WASD flies over the plate (Space and C: up and down). Try all three.",
			null, null, c -> c.seen("orbit") && c.seen("zoom") && c.seen("pan"),
			c -> check(c.seen("orbit"), "look around") + "   " + check(c.seen("zoom"), "zoom") + "   " + check(c.seen("pan"), "fly")),
		Step.of("Where it starts",
			"Pick the Start tool (key 4) and click the path in the meadow. The figure will look the way the camera looks when you click.",
			Editor.Tool.START, "start", c -> c.level().start != null),
		Step.of("Where it ends",
			"Pick the Goal tool (key 6) and click the golden pedestal behind the wall. Reaching it wins the level.",
			Editor.Tool.GOAL, "goal", c -> c.level().goal != null),
		Step.of("Mark the bridge",
			"Pick the Blueprint tool (key 2) and click the bridge. Everything connected to it gets marked blue.",
			Editor.Tool.BLUEPRINT, "bridge", c -> !c.level().blueprints.isEmpty() || markedNear(c.spot("bridge"))),
		Step.of("Turn it into a blueprint",
			"Press Enter (or the button on the card). The bridge becomes a see-through ghost: in the game it has to be built from bricks first.",
			Editor.Tool.BLUEPRINT, "bridge", c -> !c.level().blueprints.isEmpty()),
		Step.of("Drop the heap",
			"Click the ground on this side of the river. The bricks of the bridge wait there in a heap until the player builds them.",
			Editor.Tool.BLUEPRINT, "heap", c -> !c.level().blueprints.isEmpty() && Editor.placing() == null),
		Step.of("A gate",
			"Click the iron bars in the wall to mark them, then press G. A gate stays shut until its switch is used, then it bursts into bricks.",
			Editor.Tool.BLUEPRINT, "bars", c -> !c.level().gates.isEmpty()),
		Step.of("Wire up the lever",
			"Pick the Link tool (key 3). Click the lever in the little shed by the gate (look in from above), then click the gate. In the game, pulling the lever opens it.",
			Editor.Tool.LINK, "lever", c -> c.level().switches.stream().anyMatch(sw -> sw.targets.stream().anyMatch(t -> c.level().gate(t) != null))),
		Step.of("Smashable things",
			"When you play, nothing breaks unless you allow it. Pick the Smashable tool (key 9) and click the barrels at the market stall.",
			Editor.Tool.BREAKABLE, "barrels", c -> c.spot("barrels") != null ? c.level().breakable.contains(c.spot("barrels").asLong()) : !c.level().breakable.isEmpty()),
		Step.of("All the plants at once",
			"On the Smashable card at the right, click \"All plants\": every flower, bush and tree becomes smashable. Orange shows what can break.",
			Editor.Tool.BREAKABLE, null, c -> c.seen("plants")),
		Step.of("Walls only TNT breaks",
			"Some walls are too strong to smash. Click \"TNT only\" on the card, then click the cracked wall of the little shed by the gate. It glows red: only an explosion breaks it.",
			Editor.Tool.BREAKABLE, "cracked", c -> c.spot("cracked") != null ? c.level().blastable.contains(c.spot("cracked").asLong()) : !c.level().blastable.isEmpty()),
		Step.of("Things to carry",
			"Click \"Lift\" on the card, then click a hay bale at the market stall. It glows blue: when you play, sneak + right-click lifts it and a right-click sets it down somewhere else. (This needs the Carry On mod. Without it, this step skips itself.)",
			Editor.Tool.BREAKABLE, "hay", c -> !Carry.available() || (c.spot("hay") != null ? c.level().liftable.contains(c.spot("hay").asLong()) : !c.level().liftable.isEmpty())),
		Step.of("Hide something",
			"Pick the Item tool (key 0) and click the grass between the flowers in the meadow, then pick what lies there (a golden carrot). Type to search, any item works.",
			Editor.Tool.ITEM, "pickup", c -> !c.level().pickups.isEmpty()),
		Step.of("Someone who wants it",
			"Pick the Villager tool (key N) and click the grass at the end of the path by the farm. This farmer lost a golden carrot and gives TNT for it. On the card you give them a name, their own words, and what they want and give.",
			Editor.Tool.NPC, "npc", c -> !c.level().npcs.isEmpty()),
		Step.of("A checkpoint",
			"Pick the Checkpoint tool (key 5) and click the path behind the bridge. Fall down or get beaten, and you come back there.",
			Editor.Tool.CHECKPOINT, "checkpoint", c -> !c.level().checkpoints.isEmpty()),
		Step.of("An enemy",
			"Pick the Enemy tool (key 7) and click the grass west of the village, by the river. Its card sets the kind and how many.",
			Editor.Tool.ENEMY, "enemy", c -> !c.level().spawns.isEmpty()),
		Step.of("A trigger area",
			"Things can wait until the figure gets somewhere. Pick the Trigger tool (key T) and drag an area across the path right before the enemy.",
			Editor.Tool.TRIGGER, "enemy", c -> !c.level().triggers.isEmpty()),
		Step.of("Make it do something",
			"Pick the Link tool (key 3), click the trigger's green flag, then the enemy. Now the enemy only appears when you walk in. A trigger can also open gates, build blueprints, set off explosions (key X) and switch cameras.",
			Editor.Tool.LINK, "enemy", c -> c.level().triggers.stream().anyMatch(t -> t.targets.stream().anyMatch(id -> c.level().spawn(id) != null))),
		Step.of("A camera",
			"The boxes in the air are cameras: while the figure is in a camera's area, the game looks from there. One is missing on the village square. Pick the Camera tool (key 8), turn the view the way it should look, then drag an area over the square.",
			Editor.Tool.CAMERA, "zone", c -> c.spot("zone") != null && c.level().zoneAt(c.spot("zone").getX() + 0.5, c.spot("zone").getZ() + 0.5) != null),
		Step.of("Change a camera",
			"Click a camera box (or right-click its area) to open its card. \"Look from here\" takes your view now, \"Follow\" sets how much it moves with the figure, and \"Preview\" shows what the player will see.",
			Editor.Tool.CAMERA, null, c -> c.seen("camedit")),
		new Step("Up close",
			"Press Tab: now you stand in the level yourself, and the same tools are in your hotbar (right-click to use them). Press Tab again to come back.",
			null, null, c -> c.seen("back"), c -> check(c.seen("ego"), "Tab: in the level") + "   " + check(c.seen("back"), "Tab: back")),
		Step.of("Play it!",
			"Press O (or the Play brick). Your level starts at the start, with everything you set up.",
			null, null, c -> c.seen("play")),
		Step.of("Reach the goal",
			"Find the golden carrot in the meadow. Hold R at the heap to build the bridge. Bring the carrot to the farmer, carry his TNT to the cracked wall and set it down (right-click), pull the lever inside, then walk to the star. (O: back to building)",
			null, null, c -> c.seen("won")),
		Step.of("You built a level!",
			"That's everything for now. Press O to build again: the level is yours. Change it, add more, make it hard.",
			null, null, null));

	private static final Set<String> EVENTS = new HashSet<>();
	private static int ticks;
	private static int doneAt = -1;

	private Tutorial() {
	}

	private static String check(boolean ok, String what) {
		return (ok ? "✔ " : "○ ") + what;
	}

	private static boolean markedNear(@Nullable BlockPos p) {
		return p != null && Editor.selection().contains(p.asLong());
	}

	// ---------------------------------------------------------------- progress

	public static void begin(LevelData d) {
		d.tutorialStep = 0;
		EVENTS.clear();
		doneAt = -1;
	}

	public static boolean active() {
		LevelData d = Editor.level();
		return d != null && d.tutorialStep >= 0 && d.tutorialStep < STEPS.size();
	}

	public static int index() {
		LevelData d = Editor.level();
		return d == null ? -1 : d.tutorialStep;
	}

	public static @Nullable Step current() {
		return active() ? STEPS.get(Editor.level().tutorialStep) : null;
	}

	/** The step is done, the card shows it for a moment before the next one. */
	public static boolean justDone() {
		return doneAt >= 0;
	}

	/** Something happened in the editor that a step may wait for (the view turned, Tab was pressed ...). */
	public static void note(String event) {
		EVENTS.add(event);
		if (event.equals("diorama") && EVENTS.contains("ego")) {
			EVENTS.add("back");
		}
	}

	public static void forget() {
		EVENTS.clear();
		doneAt = -1;
	}

	public static void tick(Minecraft mc) {
		ticks++;
		LevelData d = Editor.level();
		Step s = current();
		if (d == null || s == null) {
			return;
		}
		if (doneAt < 0 && s.done() != null && s.done().test(new Ctx(d, mc))) {
			doneAt = ticks;
			Editor.play(mc, SoundEvents.NOTE_BLOCK_PLING.value(), 1.5F);
			Editor.play(mc, SoundEvents.NOTE_BLOCK_BELL.value(), 2.0F);
		}
		if (doneAt >= 0 && ticks - doneAt > 28) {
			next(mc);
		}
	}

	/** On to the next step (also the card's button). */
	public static void next(Minecraft mc) {
		LevelData d = Editor.level();
		if (d == null || d.tutorialStep < 0) {
			return;
		}
		d.tutorialStep++;
		EVENTS.clear();
		doneAt = -1;
		if (d.tutorialStep >= STEPS.size()) {
			d.tutorialStep = -1;
			mc.gui.hud.setTimes(8, 60, 20);
			mc.gui.hud.setTitle(Component.literal("Tutorial complete!"));
			mc.gui.hud.setSubtitle(Component.literal("The level is yours now"));
			Editor.play(mc, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
			if (mc.player != null) {
				Coins.burst(mc, mc.player.getX(), mc.player.getY() + 1.5, mc.player.getZ(), 10);
			}
		}
		Editor.save();
	}

	public static void back() {
		LevelData d = Editor.level();
		if (d != null && d.tutorialStep > 0) {
			d.tutorialStep--;
			EVENTS.clear();
			doneAt = -1;
			Editor.save();
		}
	}

	public static void end() {
		LevelData d = Editor.level();
		if (d != null) {
			d.tutorialStep = -1;
			doneAt = -1;
			Editor.save();
		}
	}

	/** Start over (only on a plate that has the places the steps point at). */
	public static void restart() {
		LevelData d = Editor.level();
		if (d != null) {
			begin(d);
			Editor.save();
		}
	}

	public static @Nullable String progressLine(Minecraft mc) {
		LevelData d = Editor.level();
		Step s = current();
		if (d == null || s == null || s.progress() == null) {
			return null;
		}
		return s.progress().apply(new Ctx(d, mc));
	}

	/** Where the marker of the current step stands, if it has one. */
	public static @Nullable BlockPos spot() {
		Step s = current();
		LevelData d = Editor.level();
		return s == null || s.spot() == null || d == null ? null : d.spots.get(s.spot());
	}

	// ---------------------------------------------------------------- the card

	/** The card's height for this width (title, text, progress), so the caller can lay out around it. */
	public static int height(Font font, int w) {
		Step s = current();
		if (s == null) {
			return 0;
		}
		int lines = font.split(Component.literal(s.text()), w - 16).size();
		return 40 + lines * 10 + (progressLine(Minecraft.getInstance()) != null ? 12 : 0);
	}

	/** Draws the card (without buttons); returns its height. */
	public static int draw(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
		Step s = current();
		if (s == null) {
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		int h = height(font, w);
		boolean done = justDone();
		g.fill(x, y, x + w, y + h, done ? 0xE0183A24 : 0xE01A2230);
		g.outline(x, y, w, h, done ? 0xFF7FE08A : 0xFFFFD84A);
		// a row of studs on top: the card is a brick, too
		for (int sx = x + 8; sx < x + w - 10; sx += 18) {
			g.fill(sx, y - 3, sx + 10, y, done ? 0xFF7FE08A : 0xFFFFD84A);
		}
		int step = index() + 1;
		g.text(font, "TUTORIAL  " + step + "/" + STEPS.size(), x + 8, y + 6, 0xFFA9B4C2, false);
		// progress bar
		int bx = x + w - 68;
		g.fill(bx, y + 8, bx + 60, y + 12, 0xFF2E3846);
		g.fill(bx, y + 8, bx + Math.round(60.0F * step / STEPS.size()), y + 12, done ? 0xFF7FE08A : 0xFFFFD84A);
		g.text(font, done ? "✔ " + s.title() : s.title(), x + 8, y + 19, done ? 0xFF7FE08A : 0xFFFFD84A, false);
		int ty = y + 32;
		for (FormattedCharSequence line : font.split(Component.literal(s.text()), w - 16)) {
			g.text(font, line, x + 8, ty, 0xFFF2F2F2, false);
			ty += 10;
		}
		String p = progressLine(mc);
		if (p != null) {
			g.text(font, p, x + 8, ty + 2, 0xFF9FD8F0, false);
		}
		return h;
	}

	/** In the normal view and while playing, the card sits in the top left corner of the screen. */
	public static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !active() || mc.gui.screen() instanceof EditorScreen || mc.gui.hud.isHidden() || Editor.mode() == Editor.Mode.OFF) {
			return;
		}
		int w = 230;
		int y = 12;
		g.pose().pushMatrix();
		g.pose().scale(0.78F, 0.78F);
		int h = draw(g, mc.font, 10, y, w);
		Step s = current();
		if (s != null && s.done() == null) {
			g.text(mc.font, Editor.playing() ? "O: back to building to go on" : "Tab: open the diorama to go on", 18, y + h + 4, 0xFFA9B4C2, true);
		}
		g.pose().popMatrix();
	}
}
