package dev.rusty.brickify.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+Z and Ctrl+Y in the editor. Every time the level is saved after a change, the level as it was and as it is
 * now become one step (as JSON text), together with the blocks the change took out of or put into the world (a
 * blueprint made, a blueprint put back). Undo puts the level and those blocks back. The tutorial's progress and
 * playing are not part of it.
 */
public final class EditorHistory {
	private record Step(String before, String after, List<BlockPos> pos, List<BlockState> was, List<BlockState> now) {
	}

	private static final int MAX = 80;
	private static final ArrayDeque<Step> UNDO = new ArrayDeque<>();
	private static final ArrayDeque<Step> REDO = new ArrayDeque<>();
	private static final List<BlockPos> POS = new ArrayList<>();
	private static final List<BlockState> WAS = new ArrayList<>();
	private static final List<BlockState> NOW = new ArrayList<>();
	private static String last;
	private static boolean restoring;

	private EditorHistory() {
	}

	private static String snapshot(LevelData d) {
		int step = d.tutorialStep;
		boolean playing = d.playing;
		d.tutorialStep = -1;
		d.playing = false;
		String json = d.toJson();
		d.tutorialStep = step;
		d.playing = playing;
		return json;
	}

	/** The level was saved: if it changed while building, that is one step. */
	public static void saved(LevelData d) {
		if (restoring || Editor.mode() != Editor.Mode.EDIT) {
			last = Editor.mode() == Editor.Mode.EDIT ? snapshot(d) : null;
			clearPending();
			return;
		}
		String now = snapshot(d);
		if (last == null) {
			last = now;
			clearPending();
			return;
		}
		if (now.equals(last) && POS.isEmpty()) {
			return;
		}
		UNDO.push(new Step(last, now, new ArrayList<>(POS), new ArrayList<>(WAS), new ArrayList<>(NOW)));
		while (UNDO.size() > MAX) {
			UNDO.removeLast();
		}
		REDO.clear();
		last = now;
		clearPending();
	}

	/** A block the current change takes out of or puts into the world. */
	public static void block(BlockPos pos, BlockState was, BlockState now) {
		POS.add(pos.immutable());
		WAS.add(was);
		NOW.add(now);
	}

	private static void clearPending() {
		POS.clear();
		WAS.clear();
		NOW.clear();
	}

	public static void clear() {
		UNDO.clear();
		REDO.clear();
		last = null;
		clearPending();
	}

	public static void tick() {
		if (Editor.mode() != Editor.Mode.EDIT) {
			last = null;
		}
	}

	public static void undo(Minecraft mc) {
		if (!Editor.editing()) {
			return;
		}
		Step s = UNDO.poll();
		if (s == null) {
			Editor.say(mc, "Nothing to undo");
			return;
		}
		restoring = true;
		try {
			Editor.restore(mc, s.before());
			Editor.setBlocks(mc, s.pos(), s.was());
		} finally {
			restoring = false;
		}
		REDO.push(s);
		Editor.say(mc, "Undone (" + UNDO.size() + " more) · Ctrl+Y: redo");
		Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 0.8F);
	}

	public static void redo(Minecraft mc) {
		if (!Editor.editing()) {
			return;
		}
		Step s = REDO.poll();
		if (s == null) {
			Editor.say(mc, "Nothing to redo");
			return;
		}
		restoring = true;
		try {
			Editor.restore(mc, s.after());
			Editor.setBlocks(mc, s.pos(), s.now());
		} finally {
			restoring = false;
		}
		UNDO.push(s);
		Editor.say(mc, "Redone · Ctrl+Z: undo");
		Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.2F);
	}
}
