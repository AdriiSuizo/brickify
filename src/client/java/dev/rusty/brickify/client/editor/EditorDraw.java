package dev.rusty.brickify.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lines, boxes and labels of the editor in the world (both views): what is marked, the heaps and where their
 * blueprint goes, start and goal, and what the mouse is on. Collected every frame where the game gathers them.
 */
public final class EditorDraw {
	private static final int MARK = 0xFF4FC3FF;
	private static final int MARK_FILL = 0x334FC3FF;
	private static final int PILE_LINE = 0xD0FFC857;
	private static final int CHOSEN = 0xFFFFD84A;
	private static final int START = 0xFF5BD46A;
	private static final int GOAL = 0xFFFFC21A;
	private static final int LINK = 0xFF9D6BFF;
	private static final int CHECK = 0xFF7FD8F0;
	private static final int MOB = 0xFFE0523C;
	private static final int ZONE = 0xFF6FC3E0;
	private static final int PICK = 0xFFFFE14A;
	private static final int NPC = 0xFFD9A066;
	private static final int TRIGGER = 0xFF36D399;
	private static final int BLAST = 0xFFE0523C;

	/** Dashed lines with arrows from what sets things off to what it sets off. */
	private static void links(Minecraft mc, LevelData d, Vec3 from, java.util.List<Integer> targets, int col) {
		for (int id : targets) {
			Vec3 to = targetPos(mc, d, id);
			if (to != null) {
				dashed(from, to, col);
				Gizmos.arrow(to.add(from.subtract(to).normalize().scale(1.2)), to, col, 3.0F);
			}
		}
	}

	/** Where something that can be set off is (blueprint, gate, enemy, explosion). */
	static Vec3 targetPos(Minecraft mc, LevelData d, int id) {
		LevelData.Blueprint b = d.blueprint(id);
		if (b != null) {
			return b.box().getCenter();
		}
		LevelData.Gate g = d.gate(id);
		if (g != null) {
			return g.box().getCenter();
		}
		LevelData.Spawn sp = d.spawn(id);
		if (sp != null) {
			return Vec3.atBottomCenterOf(sp.pos).add(0.0, 1.0, 0.0);
		}
		LevelData.Blast bl = d.blast(id);
		return bl != null ? Vec3.atCenterOf(bl.pos) : null;
	}

	/** Where something that sets things off is (switch, villager, trigger area, thing to find). */
	static Vec3 sourcePos(Minecraft mc, LevelData d, int id) {
		for (LevelData.Switch sw : d.switches) {
			if (sw.id == id) {
				return Vec3.atCenterOf(sw.pos);
			}
		}
		LevelData.Npc n = d.npc(id);
		if (n != null) {
			return Vec3.atCenterOf(n.pos).add(0.0, 0.6, 0.0);
		}
		LevelData.Trigger t = d.trigger(id);
		if (t != null && mc.level != null) {
			return t.centre(Editor.triggerGround(mc.level, d, t) + 1.5);
		}
		LevelData.Pickup p = d.pickup(id);
		return p != null ? Vec3.atBottomCenterOf(p.pos).add(0.0, 0.5, 0.0) : null;
	}

	/** The white dots of a selected area: corners and sides resize it, the middle moves it. */
	private static void handles(LevelData d, LevelData.AreaBox a, double gy, Pick hover) {
		double[][] hs = Editor.zoneHandles(a);
		for (int i = 0; i < hs.length; i++) {
			boolean hot = hover != null && hover.kind() == Pick.Kind.HANDLE && hover.id() == i;
			double r = i == 8 ? 0.4 : i < 4 ? 0.32 : 0.25;
			Gizmos.cuboid(new AABB(hs[i][0] - r, gy, hs[i][1] - r, hs[i][0] + r, gy + 0.4, hs[i][1] + r),
				GizmoStyle.strokeAndFill(hot ? CHOSEN : 0xFFFFFFFF, 2.0F, hot ? 0xE0FFD84A : 0xD0FFFFFF));
		}
	}
	private static boolean warned;

	private EditorDraw() {
	}

	public static void draw(double camX, double camY, double camZ) {
		LevelData d = Editor.level();
		Minecraft mc = Minecraft.getInstance();
		if (d != null && mc.level != null && Editor.playing()) {
			try {
				drawPlay(mc, d, new Vec3(camX, camY, camZ));
			} catch (IllegalStateException e) {
				// nothing to draw into here
			}
			return;
		}
		if (d == null || mc.level == null || !Editor.editing()) {
			return;
		}
		try {
			Vec3 cam = new Vec3(camX, camY, camZ);
			if (!Editor.cutting()) {
				Gizmos.cuboid(new AABB(d.minX, d.floorY + 6, d.minZ, d.maxX + 1, d.floorY + 6.02, d.maxZ + 1), GizmoStyle.stroke(0x70FFFFFF, 1.5F));
			}
			// what is marked
			var sel = Editor.selection();
			if (sel.size() <= 2500) {
				for (long k : sel) {
					Gizmos.cuboid(BlockPos.of(k), 0.02F, GizmoStyle.strokeAndFill(MARK, 2.0F, MARK_FILL));
				}
			} else {
				Gizmos.cuboid(bounds(sel), GizmoStyle.strokeAndFill(MARK, 3.0F, MARK_FILL));
			}
			// the box being dragged out
			AABB drag = EditorScreen.dragBox();
			if (drag != null) {
				Gizmos.cuboid(drag, GizmoStyle.strokeAndFill(0xFFFFFFFF, 2.0F, 0x22FFFFFF));
			}
			// heaps: a little pile of their bricks, a dashed line to where they build
			LevelData.Blueprint chosen = Editor.selectedBlueprint();
			LevelData.Blueprint placing = Editor.placing();
			Pick hover = Editor.hover();
			for (LevelData.Blueprint b : d.blueprints) {
				AABB box = b.box();
				if (b == chosen) {
					Gizmos.cuboid(box.inflate(0.04), GizmoStyle.stroke(CHOSEN, 2.5F));
				}
				if (b == placing) {
					continue;
				}
				Vec3 pile = b.pileCentre();
				dashed(pile.add(0.0, 1.0, 0.0), box.getCenter(), PILE_LINE);
				label(cam, "Heap", pile.add(0.0, 2.1, 0.0), 0xFFFFE08A);
			}
			// the heap on the mouse
			if (placing != null && hover != null && hover.kind() == Pick.Kind.BLOCK) {
				BlockPos ground = hover.face() == net.minecraft.core.Direction.UP ? hover.pos() : hover.standing().below();
				Vec3 at = new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
				dashed(at.add(0.0, 0.9, 0.0), placing.box().getCenter(), CHOSEN);
				always(cam, "Put it here", at.add(0.0, 1.5, 0.0), 0xFFFFFFFF);
			}
			// start and goal
			if (d.start != null) {
				marker(d.start, START, 0);
				Vec3 s = Vec3.atBottomCenterOf(d.start);
				Vec3 dir = Vec3.directionFromRotation(0.0F, d.startYaw);
				Gizmos.arrow(s.add(0.0, 0.1, 0.0), s.add(dir.x * 1.4, 0.1, dir.z * 1.4), START, 3.0F);
				always(cam, "Start", s.add(0.0, 2.3, 0.0), START);
			}
			if (d.goal != null) {
				marker(d.goal, GOAL, 0);
				always(cam, "Goal", Vec3.atBottomCenterOf(d.goal).add(0.0, 2.3, 0.0), GOAL);
			}
			if ("start".equals(Editor.selectedMarker()) && d.start != null) {
				Gizmos.cuboid(new AABB(d.start).expandTowards(0.0, 1.0, 0.0).inflate(0.08), GizmoStyle.stroke(CHOSEN, 2.5F));
			}
			if ("goal".equals(Editor.selectedMarker()) && d.goal != null) {
				Gizmos.cuboid(new AABB(d.goal).expandTowards(0.0, 1.0, 0.0).inflate(0.08), GizmoStyle.stroke(CHOSEN, 2.5F));
			}
			// gates, switches and what they set off
			LevelData.Gate chosenGate = Editor.selectedGate();
			for (LevelData.Gate g : d.gates) {
				int stroke = g == chosenGate ? CHOSEN : 0xFFE0523C;
				if (g.blocks.size() <= 600) {
					for (BlockPos p : g.blocks) {
						Gizmos.cuboid(p, 0.03F, GizmoStyle.strokeAndFill(stroke, 1.5F, 0x38E0523C));
					}
				}
				AABB box = g.box();
				Gizmos.cuboid(box.inflate(0.05), GizmoStyle.stroke(stroke, 2.0F));
				label(cam, "Gate", new Vec3(box.getCenter().x, box.maxY + 0.6, box.getCenter().z), 0xFFFF8A78);
			}
			LevelData.Switch chosenSwitch = Editor.selectedSwitch();
			for (LevelData.Switch sw : d.switches) {
				boolean on = sw == chosenSwitch || sw.pos.equals(Editor.pendingSwitch());
				Gizmos.cuboid(sw.pos, 0.06F, GizmoStyle.strokeAndFill(on ? CHOSEN : LINK, 2.5F, 0x408E5CC7));
				Vec3 from = Vec3.atCenterOf(sw.pos);
				label(cam, "Switch", from.add(0.0, 1.0, 0.0), 0xFFC9A6FF);
				links(mc, d, from, sw.targets, on ? CHOSEN : LINK);
			}
			int pendingSrc = Editor.pendingSource();
			Vec3 pendingFrom = pendingSrc >= 0 ? sourcePos(mc, d, pendingSrc) : null;
			if (pendingFrom != null && hover != null) {
				dashed(pendingFrom, hover.at(), CHOSEN);
			}
			// trigger areas: a flag in the middle, links to what walking in sets off
			LevelData.Trigger chosenTrigger = Editor.selectedTrigger();
			for (LevelData.Trigger t : d.triggers) {
				boolean on = t == chosenTrigger || t.id == pendingSrc;
				int col = on ? CHOSEN : TRIGGER;
				double gy = Editor.triggerGround(mc.level, d, t);
				Gizmos.cuboid(new AABB(t.x0, gy, t.z0, t.x1 + 1, gy + 0.05, t.z1 + 1), GizmoStyle.strokeAndFill(col, on ? 3.0F : 2.0F, on ? 0x30FFD84A : 0x2236D399));
				Vec3 c = t.centre(gy);
				Gizmos.line(c, c.add(0.0, 1.8, 0.0), col, 3.0F);
				Gizmos.cuboid(new AABB(c.x, c.y + 1.3, c.z - 0.02, c.x + 0.6, c.y + 1.75, c.z + 0.02), GizmoStyle.strokeAndFill(col, 1.5F, (col & 0x00FFFFFF) | 0xA0000000));
				label(cam, "Trigger · " + (t.once ? "first time" : "every time") + (t.targets.isEmpty() ? " · not linked" : ""), c.add(0.0, 2.3, 0.0), col);
				links(mc, d, c.add(0.0, 1.5, 0.0), t.targets, on ? CHOSEN : TRIGGER);
				if (t == chosenTrigger) {
					handles(d, t, gy, hover);
				}
			}
			BlockPos tc = Editor.triggerCorner();
			if (tc != null && hover != null) {
				Gizmos.cuboid(new AABB(Math.min(tc.getX(), hover.pos().getX()), tc.getY() + 1, Math.min(tc.getZ(), hover.pos().getZ()),
					Math.max(tc.getX(), hover.pos().getX()) + 1, tc.getY() + 1.05, Math.max(tc.getZ(), hover.pos().getZ()) + 1), GizmoStyle.strokeAndFill(CHOSEN, 2.0F, 0x3036D399));
			}
			// explosion places
			LevelData.Blast chosenBlast = Editor.selectedBlast();
			for (LevelData.Blast b : d.blasts) {
				boolean on = b == chosenBlast;
				Gizmos.cuboid(new AABB(b.pos).deflate(0.15), GizmoStyle.strokeAndFill(on ? CHOSEN : BLAST, 2.5F, 0x80E0523C));
				Gizmos.cuboid(new AABB(b.pos).inflate(b.power * 0.5), GizmoStyle.stroke(on ? 0xA0FFD84A : 0x50E0523C, 1.0F));
				label(cam, "Explosion" + (d.triggered(b.id) ? "" : " · not linked"), Vec3.atBottomCenterOf(b.pos).add(0.0, 1.6, 0.0), 0xFFFF8A78);
			}
			// camera areas: the area on the ground, where the camera stands and where it looks
			LevelData.CamZone chosenZone = Editor.selectedZone();
			for (LevelData.CamZone z : d.zones) {
				boolean on = z == chosenZone;
				int col = on ? CHOSEN : z.after >= 0 ? LINK : ZONE;
				double gy = Editor.zoneGround(mc.level, d, z);
				AABB area = new AABB(z.x0, gy, z.z0, z.x1 + 1, gy + 0.05, z.z1 + 1);
				Gizmos.cuboid(area, GizmoStyle.strokeAndFill(col, on ? 3.0F : 2.0F, on ? 0x30FFD84A : 0x146FC3E0));
				Vec3 c = z.centre(gy + 1.0);
				Vec3 eye = Editor.zoneEye(mc.level, d, z);
				drawCamera(eye, c, col, on);
				if (on) {
					handles(d, z, gy, hover);
					Gizmos.line(eye, new Vec3(eye.x, gy, eye.z), 0x90FFFFFF, 1.0F);
				}
				label(cam, (on ? "Camera" : "Camera · follows " + Editor.followName(z.follow)) + (z.after >= 0 ? " · " + Editor.afterName(z) : ""), eye.add(0.0, 0.9, 0.0),
					on ? CHOSEN : 0xFF9FD8F0);
				if (z.after >= 0) {
					Vec3 from = sourcePos(mc, d, z.after);
					if (from != null) {
						dashed(from, eye, LINK);
					}
				}
			}
			BlockPos corner = Editor.zoneCorner();
			if (corner != null && hover != null) {
				Gizmos.cuboid(new AABB(Math.min(corner.getX(), hover.pos().getX()), corner.getY() + 1, Math.min(corner.getZ(), hover.pos().getZ()),
					Math.max(corner.getX(), hover.pos().getX()) + 1, corner.getY() + 1.05, Math.max(corner.getZ(), hover.pos().getZ()) + 1), GizmoStyle.strokeAndFill(CHOSEN, 2.0F, 0x30FFD84A));
			}
			// things to find, villagers and what their deals set off
			double tt = System.currentTimeMillis() / 1000.0;
			for (LevelData.Pickup pk : d.pickups) {
				Vec3 c = Vec3.atBottomCenterOf(pk.pos).add(0.0, 0.45 + 0.08 * Math.sin(tt * 3.0 + pk.id), 0.0);
				Gizmos.cuboid(new AABB(c.x - 0.22, c.y - 0.22, c.z - 0.22, c.x + 0.22, c.y + 0.22, c.z + 0.22), GizmoStyle.strokeAndFill(PICK, 2.0F, 0x90FFE14A));
				label(cam, LevelLife.itemName(pk.item), c.add(0.0, 0.8, 0.0), PICK);
				links(mc, d, c, pk.targets, pk.id == pendingSrc || pk.pos.equals(Editor.selectedPos()) ? CHOSEN : PICK);
				if (pk.pos.equals(Editor.selectedPos())) {
					Gizmos.cuboid(new AABB(pk.pos).inflate(0.08), GizmoStyle.stroke(CHOSEN, 2.5F));
				}
			}
			for (LevelData.Npc n : d.npcs) {
				boolean on = n.pos.equals(Editor.selectedPos()) || n.id == Editor.pendingNpc();
				marker(n.pos, on ? CHOSEN : NPC, 0);
				Vec3 top = Vec3.atBottomCenterOf(n.pos).add(0.0, 2.3, 0.0);
				always(cam, LevelLife.displayName(n), top.add(0.0, 0.35, 0.0), NPC);
				label(cam, (n.wants.isEmpty() ? "" : "wants " + LevelLife.itemName(n.wants) + " · ") + "gives " + LevelLife.itemName(n.gives), top, 0xFFE8D2B0);
				links(mc, d, Vec3.atCenterOf(n.pos).add(0.0, 0.6, 0.0), n.targets, on ? CHOSEN : NPC);
			}
			// checkpoints and mobs
			for (BlockPos c : d.checkpoints) {
				marker(c, CHECK, 0);
				label(cam, "Checkpoint", Vec3.atBottomCenterOf(c).add(0.0, 2.3, 0.0), CHECK);
				if (c.equals(Editor.selectedPos())) {
					Gizmos.cuboid(new AABB(c).expandTowards(0.0, 1.0, 0.0).inflate(0.08), GizmoStyle.stroke(CHOSEN, 2.5F));
				}
			}
			for (LevelData.Spawn sp : d.spawns) {
				marker(sp.pos, MOB, 0);
				label(cam, Editor.mobName(sp.type) + (sp.count > 1 ? " ×" + sp.count : "") + (d.triggered(sp.id) ? " · waits" : ""),
					Vec3.atBottomCenterOf(sp.pos).add(0.0, 2.3, 0.0), 0xFFFF8A78);
				if (sp.pos.equals(Editor.selectedPos())) {
					Gizmos.cuboid(new AABB(sp.pos).expandTowards(0.0, 1.0, 0.0).inflate(0.08), GizmoStyle.stroke(CHOSEN, 2.5F));
				}
			}
			// what the mouse (or the crosshair) is on
			if (hover != null && placing == null) {
				drawHover(mc, d, hover);
			}
			tutorialMarker(cam);
		} catch (IllegalStateException e) {
			if (!warned) {
				warned = true;
				dev.rusty.brickify.Brickify.LOGGER.warn("Editor cannot draw into the world here", e);
			}
		}
	}

	/** While playing: heaps that wait for a switch say so when the figure is near. */
	private static void drawPlay(Minecraft mc, LevelData d, Vec3 cam) {
		if (mc.player == null) {
			return;
		}
		tutorialMarker(cam);
		for (LevelData.Blueprint b : d.blueprints) {
			Vec3 pile = b.pileCentre();
			if (d.triggered(b.id) && mc.player.position().distanceTo(pile) < 8.0 && !mc.level.getBlockState(b.blocks.get(0)).equals(b.states.get(0))) {
				always(cam, "Needs a switch", pile.add(0.0, 1.6, 0.0), 0xFFC9A6FF);
			}
		}
	}

	/** A camera in the air: a box with a lens, and lines showing what it sees. */
	private static void drawCamera(Vec3 eye, Vec3 look, int col, boolean on) {
		Vec3 dir = look.subtract(eye).normalize();
		Gizmos.cuboid(new AABB(eye.x - 0.45, eye.y - 0.32, eye.z - 0.45, eye.x + 0.45, eye.y + 0.32, eye.z + 0.45), GizmoStyle.strokeAndFill(col, on ? 3.0F : 2.0F, 0xB0303A48));
		Vec3 lens = eye.add(dir.scale(0.6));
		Gizmos.cuboid(new AABB(lens.x - 0.2, lens.y - 0.2, lens.z - 0.2, lens.x + 0.2, lens.y + 0.2, lens.z + 0.2), GizmoStyle.strokeAndFill(col, 2.0F, 0xC0000000 | (col & 0xFFFFFF)));
		// a little view pyramid towards the area
		Vec3 side = new Vec3(-dir.z, 0.0, dir.x).normalize();
		Vec3 up = side.cross(dir).normalize();
		double reach = Math.min(5.0, look.distanceTo(eye) * 0.45);
		Vec3 mid = eye.add(dir.scale(reach));
		Vec3[] corners = {
			mid.add(side.scale(reach * 0.55)).add(up.scale(reach * 0.32)), mid.add(side.scale(-reach * 0.55)).add(up.scale(reach * 0.32)),
			mid.add(side.scale(-reach * 0.55)).add(up.scale(-reach * 0.32)), mid.add(side.scale(reach * 0.55)).add(up.scale(-reach * 0.32))
		};
		for (int i = 0; i < 4; i++) {
			Gizmos.line(lens, corners[i], col, on ? 2.5F : 1.5F);
			Gizmos.line(corners[i], corners[(i + 1) % 4], col, on ? 2.5F : 1.5F);
		}
		if (on) {
			dashed(eye, look, col);
		}
	}

	/** Where the tutorial wants the next click: a glowing column that breathes, and a label. */
	private static void tutorialMarker(Vec3 cam) {
		BlockPos spot = Tutorial.spot();
		if (spot == null || Tutorial.justDone()) {
			return;
		}
		double t = System.currentTimeMillis() / 1000.0;
		double pulse = 0.5 + 0.5 * Math.sin(t * 4.0);
		int a = (int) (0x50 + 0x50 * pulse);
		double grow = 0.05 + 0.12 * pulse;
		AABB box = new AABB(spot).inflate(grow);
		Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xFFFFE14A, 3.0F, a << 24 | 0xFFE14A));
		Vec3 c = Vec3.atCenterOf(spot);
		for (int i = 0; i < 6; i++) {
			double y0 = c.y + 0.8 + i * 0.7 + (t * 1.4 % 0.7);
			Gizmos.cuboid(new AABB(c.x - 0.08, y0, c.z - 0.08, c.x + 0.08, y0 + 0.35, c.z + 0.08),
				GizmoStyle.fill((int) (0xC0 * (1.0 - i / 6.0)) << 24 | 0xFFE14A));
		}
		always(cam, "\u25BC here", c.add(0.0, 1.4 + 0.25 * pulse, 0.0), 0xFFFFE14A);
	}

	private static void drawHover(Minecraft mc, LevelData d, Pick h) {
		Editor.Tool tool = Editor.tool();
		if (!(mc.gui.screen() instanceof EditorScreen) && mc.player != null) {
			Editor.Tool inHand = EditorTools.toolOf(mc.player.getMainHandItem());
			if (inHand != null) {
				tool = inHand;
			}
		}
		if (tool == Editor.Tool.BREAKABLE && (h.kind() == Pick.Kind.BLOCK || h.kind() == Pick.Kind.GATE || h.kind() == Pick.Kind.SWITCH) && mc.level != null) {
			// what a click takes, outlined orange
			boolean single = mc.player != null && mc.player.isShiftKeyDown() && !(mc.gui.screen() instanceof EditorScreen);
			var group = single ? null : Editor.breakPreview(mc.level, h.pos(), false);
			boolean on = Editor.breakableAt(h.pos());
			int col = on ? 0xFFFFFFFF : 0xFFFFA040;
			if (group == null || group.size() <= 1) {
				Gizmos.cuboid(h.pos(), 0.02F, GizmoStyle.strokeAndFill(col, 2.5F, 0x30FFA040));
			} else if (group.size() <= 400) {
				for (long k : group) {
					Gizmos.cuboid(BlockPos.of(k), 0.02F, GizmoStyle.strokeAndFill(col, 1.5F, 0x28FFA040));
				}
			} else {
				Gizmos.cuboid(bounds(group), GizmoStyle.strokeAndFill(col, 2.5F, 0x28FFA040));
			}
			return;
		}
		switch (h.kind()) {
			case BLOCK, GATE, SWITCH -> {
				if (tool == Editor.Tool.START || tool == Editor.Tool.GOAL || tool == Editor.Tool.CHECKPOINT || tool == Editor.Tool.ENEMY) {
					int col = switch (tool) {
						case START -> START;
						case GOAL -> GOAL;
						case CHECKPOINT -> CHECK;
						default -> MOB;
					};
					marker(h.standing(), col, 0x40);
				} else {
					Gizmos.cuboid(h.pos(), 0.015F, GizmoStyle.stroke(0xFFFFFFFF, 2.0F));
				}
			}
			case GHOST, PILE -> {
				LevelData.Blueprint b = d.blueprint(h.id());
				if (b != null) {
					Gizmos.cuboid(b.box().inflate(0.06), GizmoStyle.stroke(0xFFFFFFFF, 2.0F));
					if (h.kind() == Pick.Kind.PILE) {
						Vec3 c = b.pileCentre();
						Gizmos.cuboid(new AABB(c.x - 0.8, c.y, c.z - 0.8, c.x + 0.8, c.y + 1.0, c.z + 0.8), GizmoStyle.stroke(0xFFFFFFFF, 2.0F));
					}
				}
			}
			case HANDLE -> {
			}
			case CAMERA -> {
				LevelData.CamZone z = d.zone(h.id());
				Vec3 c = z != null && mc.level != null ? Editor.zoneEye(mc.level, d, z) : Vec3.atCenterOf(h.pos());
				Gizmos.cuboid(new AABB(c.x - 0.6, c.y - 0.45, c.z - 0.6, c.x + 0.6, c.y + 0.45, c.z + 0.6), GizmoStyle.stroke(0xFFFFFFFF, 2.5F));
			}
			case START, GOAL, CHECKPOINT, SPAWN, PICKUP, NPC -> Gizmos.cuboid(new AABB(h.pos()).expandTowards(0.0, 1.0, 0.0).inflate(0.1), GizmoStyle.stroke(0xFFFFFFFF, 2.0F));
			case BLAST -> Gizmos.cuboid(new AABB(h.pos()).inflate(0.08), GizmoStyle.stroke(0xFFFFFFFF, 2.0F));
			case TRIGGER -> {
				LevelData.Trigger t = d.trigger(h.id());
				if (t != null && mc.level != null) {
					double gy = Editor.triggerGround(mc.level, d, t);
					Gizmos.cuboid(new AABB(t.x0, gy, t.z0, t.x1 + 1, gy + 0.08, t.z1 + 1), GizmoStyle.stroke(0xFFFFFFFF, 2.5F));
				}
			}
		}
	}

	/** The height of the ground in the middle of a camera area (top of the highest block there). */
	private static double groundY(Minecraft mc, LevelData d, LevelData.CamZone z) {
		int x = (z.x0 + z.x1) / 2;
		int zz = (z.z0 + z.z1) / 2;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, d.topY, zz);
		while (p.getY() > d.floorY && mc.level.getBlockState(p).isAir()) {
			p.move(0, -1, 0);
		}
		return p.getY() + 1.02;
	}

	/** A small pile of bricks in the colours of the blueprint. */
	private static void heap(Minecraft mc, LevelData.Blueprint b, Vec3 at, boolean bright) {
		int n = b.states.size();
		float[][] spots = {
			{0.0F, 0.0F, 0.0F}, {-0.42F, 0.0F, 0.12F}, {0.38F, 0.0F, -0.2F}, {0.1F, 0.0F, 0.42F}, {-0.15F, 0.0F, -0.4F},
			{-0.1F, 0.28F, 0.05F}, {0.2F, 0.28F, 0.12F}, {0.02F, 0.52F, -0.02F}
		};
		for (int i = 0; i < spots.length; i++) {
			BlockState s = b.states.get((i * 7919) % Math.max(1, n));
			int col = s.getMapColor(mc.level, b.blocks.get((i * 7919) % Math.max(1, n))).col;
			if (col == 0) {
				col = 0xB0B0B0;
			}
			float w = i == 7 ? 0.22F : 0.26F;
			double x = at.x + spots[i][0];
			double y = at.y + spots[i][1];
			double z = at.z + spots[i][2];
			Gizmos.cuboid(new AABB(x - w, y, z - w * 0.7, x + w, y + 0.26, z + w * 0.7),
				GizmoStyle.strokeAndFill(0xFF000000 | darker(col), 1.0F, (bright ? 0xF0000000 : 0xD8000000) | col));
		}
	}

	private static int darker(int rgb) {
		int r = (rgb >> 16 & 255) * 3 / 5;
		int g = (rgb >> 8 & 255) * 3 / 5;
		int bl = (rgb & 255) * 3 / 5;
		return r << 16 | g << 8 | bl;
	}

	/** Start or goal: a post where the figure will stand. */
	private static void marker(BlockPos at, int col, int fade) {
		int fill = (fade > 0 ? fade : 0x60) << 24 | col & 0xFFFFFF;
		Gizmos.cuboid(new AABB(at.getX() + 0.3, at.getY(), at.getZ() + 0.3, at.getX() + 0.7, at.getY() + 1.8, at.getZ() + 0.7), GizmoStyle.strokeAndFill(col, 2.0F, fill));
		Gizmos.cuboid(new AABB(at.getX() + 0.05, at.getY(), at.getZ() + 0.05, at.getX() + 0.95, at.getY() + 0.06, at.getZ() + 0.95), GizmoStyle.strokeAndFill(col, 1.5F, fill));
	}

	private static void dashed(Vec3 a, Vec3 b, int col) {
		Vec3 d = b.subtract(a);
		double len = d.length();
		if (len < 0.1) {
			return;
		}
		Vec3 step = d.scale(1.0 / len);
		for (double t = 0.0; t < len; t += 0.8) {
			Gizmos.line(a.add(step.scale(t)), a.add(step.scale(Math.min(len, t + 0.45))), col, 2.5F);
		}
	}

	/** Names of things, only from close up (from far out they would cover the level); start and goal always. */
	private static void label(Vec3 cam, String text, Vec3 at, int col) {
		if (at.distanceTo(cam) > 38.0) {
			return;
		}
		always(cam, text, at, col);
	}

	private static void always(Vec3 cam, String text, Vec3 at, int col) {
		double distance = at.distanceTo(cam);
		float scale = Mth.clamp((float) distance * 0.042F, 0.4F, 4.0F);
		Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(col).withScale(scale)).setAlwaysOnTop();
	}

	private static AABB bounds(Iterable<Long> keys) {
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (long k : keys) {
			int x = BlockPos.getX(k);
			int y = BlockPos.getY(k);
			int z = BlockPos.getZ(k);
			x0 = Math.min(x0, x);
			y0 = Math.min(y0, y);
			z0 = Math.min(z0, z);
			x1 = Math.max(x1, x);
			y1 = Math.max(y1, y);
			z1 = Math.max(z1, z);
		}
		return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
	}
}
