package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.client.BrickEntities;
import dev.rusty.brickify.client.BrickRuins;
import dev.rusty.brickify.client.BrickifyClient;
import dev.rusty.brickify.client.Coins;
import dev.rusty.brickify.client.Statics;
import dev.rusty.brickify.client.Trees;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The level editor. A world gets a level the first time the editor key (O) is pressed: a base plate around the
 * player. From then on the key switches between building it and playing it. Building happens in two views that do
 * the same things: the diorama (the plate on a table, the mouse picks things) and the normal view (tool items in the
 * hotbar). Playing starts from a copy of the built level, and going back to building puts everything back.
 */
public final class Editor {
	public enum Mode {
		OFF, EDIT, PLAY
	}

	public enum Tool {
		POINTER("Pointer"), BLUEPRINT("Blueprint"), LINK("Link"), START("Start"), CHECKPOINT("Checkpoint"), GOAL("Goal"), ENEMY("Enemy"), CAMERA("Camera"),
		BREAKABLE("Smashable"), ITEM("Item"), NPC("Villager"), TRIGGER("Trigger"), BLAST("Blast");

		public final String label;

		Tool(String label) {
			this.label = label;
		}
	}

	public static int defaultSize = 48;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private static @Nullable LevelData level;
	private static @Nullable Path dir;
	private static boolean loaded;
	private static Mode mode = Mode.OFF;
	private static Tool tool = Tool.BLUEPRINT;
	private static final LongLinkedOpenHashSet SELECTION = new LongLinkedOpenHashSet();
	private static LevelData.@Nullable Blueprint placing;
	private static boolean placingNew;
	private static int selectedId = -1;
	private static @Nullable String selectedMarker;
	private static final Long2IntOpenHashMap GHOSTS = new Long2IntOpenHashMap();
	private static volatile int @Nullable [] cut;
	private static boolean won;
	private static Display.@Nullable ItemDisplay star;
	private static int nextId = -16_000_000;
	private static @Nullable Pick hover;
	private static int hintAt;
	private static boolean savedHideGui;
	/** Link tool: what the next click connects to (a switch, villager, trigger or found thing; -1: nothing). */
	private static int pendingSource = -1;
	private static int selectedGate = -1;
	private static int selectedSwitch = -1;
	private static @Nullable BlockPos selectedPos;
	private static int selectedZone = -1;
	/** Camera tool in the normal view: the first corner of a new area. */
	private static @Nullable BlockPos zoneCorner;
	/** While playing: switches already used, the checkpoint reached last. */
	private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet FIRED = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
	private static @Nullable BlockPos checkpoint;
	private static boolean wasDead;
	/** Switches that were already on (only switching on sets something off). */
	private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet WAS_ON = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
	private static boolean switchesRead;
	/** While playing: the trigger areas the figure stands in. */
	private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet INSIDE = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
	public static final String[] MOBS = {"minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper", "minecraft:slime", "minecraft:pillager",
		"minecraft:husk", "minecraft:stray", "minecraft:bogged", "minecraft:vindicator", "minecraft:witch", "minecraft:cave_spider", "minecraft:silverfish",
		"minecraft:breeze", "minecraft:drowned", "minecraft:piglin_brute"};
	public static final String[] MOB_NAMES = {"Zombie", "Skeleton", "Spider", "Creeper", "Slime", "Pillager", "Husk", "Stray", "Bogged", "Vindicator", "Witch",
		"Cave spider", "Silverfish", "Breeze", "Drowned", "Piglin brute"};

	private Editor() {
	}

	public static @Nullable LevelData level() {
		return level;
	}

	public static Mode mode() {
		return mode;
	}

	public static boolean editing() {
		return mode == Mode.EDIT;
	}

	public static boolean playing() {
		return mode == Mode.PLAY;
	}

	public static Tool tool() {
		return tool;
	}

	public static void setTool(Tool t) {
		if (placing != null && placingNew && t != Tool.BLUEPRINT) {
			return;
		}
		tool = t;
	}

	public static @Nullable Pick hover() {
		return hover;
	}

	public static void setHover(@Nullable Pick p) {
		hover = p;
	}

	public static LongLinkedOpenHashSet selection() {
		return SELECTION;
	}

	public static LevelData.@Nullable Blueprint placing() {
		return placing;
	}

	public static LevelData.@Nullable Blueprint selectedBlueprint() {
		return level == null || selectedId < 0 ? null : level.blueprint(selectedId);
	}

	public static @Nullable String selectedMarker() {
		return selectedMarker;
	}

	public static void selectBlueprint(int id) {
		clearSelected();
		selectedId = id;
	}

	public static void selectMarker(@Nullable String which) {
		clearSelected();
		selectedMarker = which;
	}

	public static void clearSelected() {
		selectedId = -1;
		selectedMarker = null;
		selectedGate = -1;
		selectedSwitch = -1;
		selectedPos = null;
		selectedZone = -1;
		selectedTrigger = -1;
		selectedBlast = -1;
	}

	private static int selectedTrigger = -1;
	private static int selectedBlast = -1;

	public static LevelData.@Nullable Trigger selectedTrigger() {
		return level == null || selectedTrigger < 0 ? null : level.trigger(selectedTrigger);
	}

	public static void selectTrigger(int id) {
		clearSelected();
		selectedTrigger = id;
	}

	public static LevelData.@Nullable Blast selectedBlast() {
		return level == null || selectedBlast < 0 ? null : level.blast(selectedBlast);
	}

	public static void selectBlast(int id) {
		clearSelected();
		selectedBlast = id;
	}

	/** The selected area with handles: a camera area or a trigger area. */
	public static LevelData.@Nullable AreaBox selectedArea() {
		LevelData.CamZone z = selectedZone();
		return z != null ? z : selectedTrigger();
	}

	public static LevelData.@Nullable CamZone selectedZone() {
		return level == null || selectedZone < 0 ? null : level.zone(selectedZone);
	}

	public static void selectZone(int id) {
		clearSelected();
		selectedZone = id;
	}

	public static @Nullable BlockPos zoneCorner() {
		return zoneCorner;
	}

	/** A camera area over this rectangle, looking the way the given view looks. */
	public static LevelData.@Nullable CamZone makeZone(Minecraft mc, int xa, int za, int xb, int zb, float yaw, float pitch, float dist) {
		return makeZone(mc, xa, za, xb, zb, yaw, pitch, dist, null);
	}

	/** {@code start}: the block the area was dragged from (its ground is under it). */
	public static LevelData.@Nullable CamZone makeZone(Minecraft mc, int xa, int za, int xb, int zb, float yaw, float pitch, float dist, @Nullable BlockPos start) {
		LevelData d = level;
		if (d == null) {
			return null;
		}
		LevelData.CamZone z = new LevelData.CamZone();
		z.id = d.nextId++;
		z.x0 = Math.max(d.minX, Math.min(xa, xb));
		z.z0 = Math.max(d.minZ, Math.min(za, zb));
		z.x1 = Math.min(d.maxX, Math.max(xa, xb));
		z.z1 = Math.min(d.maxZ, Math.max(za, zb));
		z.yaw = yaw;
		z.pitch = Mth.clamp(pitch, 15.0F, 80.0F);
		z.dist = Mth.clamp(dist, 3.0F, 40.0F);
		if (start != null && mc.level != null) {
			z.ground = groundBelow(mc.level, d, start);
		}
		d.zones.add(z);
		selectZone(z.id);
		save();
		play(mc, SoundEvents.SPYGLASS_USE, 1.0F);
		return z;
	}

	/** The camera of an area looks the way the given view looks now. */
	public static void zoneView(Minecraft mc, LevelData.CamZone z, float yaw, float pitch, float dist) {
		z.yaw = yaw;
		z.pitch = Mth.clamp(pitch, 15.0F, 80.0F);
		z.dist = Mth.clamp(dist, 3.0F, 40.0F);
		save();
		play(mc, SoundEvents.SPYGLASS_USE, 1.3F);
		Tutorial.note("camedit");
	}

	public static void cycleFollow(LevelData.CamZone z) {
		z.follow = z.follow >= 0.99F ? 0.0F : z.follow >= 0.49F ? 1.0F : 0.5F;
		save();
		Tutorial.note("camedit");
	}

	public static void zoneDistance(LevelData.CamZone z, float step) {
		z.dist = Mth.clamp(z.dist + step, 3.0F, 40.0F);
		save();
		Tutorial.note("camedit");
	}

	public static void deleteZone(LevelData.CamZone z) {
		if (level != null) {
			level.zones.remove(z);
			clearSelected();
			save();
		}
	}

	/** Camera tool in the normal view: first click a corner, then the other; the view is the player's view now. */
	public static void zoneClick(Minecraft mc, BlockPos ground) {
		LevelData d = level;
		if (d == null || mc.player == null) {
			return;
		}
		if (zoneCorner == null) {
			LevelData.CamZone in = d.zoneAt(ground.getX() + 0.5, ground.getZ() + 0.5);
			if (mc.player.isShiftKeyDown() && in != null) {
				zoneView(mc, in, mc.player.getYRot(), mc.player.getXRot() + 25.0F, in.dist);
				say(mc, "Camera zone now looks the way you look");
				return;
			}
			zoneCorner = ground.immutable();
			say(mc, "First corner set · now click the other corner");
			return;
		}
		makeZone(mc, zoneCorner.getX(), zoneCorner.getZ(), ground.getX(), ground.getZ(), mc.player.getYRot(), Math.max(30.0F, mc.player.getXRot() + 25.0F), 11.0F);
		zoneCorner = null;
		say(mc, "Camera zone made · the camera looks the way you look now");
	}

	/** Where a camera's area has its ground. */
	public static double zoneGround(Level world, LevelData d, LevelData.CamZone z) {
		return areaGround(world, d, z);
	}

	public static double triggerGround(Level world, LevelData d, LevelData.Trigger t) {
		return areaGround(world, d, t);
	}

	/** Worked out once per area shape (the ground under an area that was never set by hand). */
	private static final java.util.IdentityHashMap<LevelData.AreaBox, double[]> GROUND_CACHE = new java.util.IdentityHashMap<>();

	/**
	 * The height of an area's ground: set by hand, else the ground most of the area stands on (a house in the
	 * middle does not lift it onto its roof). A close camera (inside a house) takes the floor of the room.
	 */
	public static double areaGround(Level world, LevelData d, LevelData.AreaBox z) {
		if (!Double.isNaN(z.ground)) {
			return z.ground;
		}
		double key = z.x0 * 31.0 + z.z0 * 961.0 + z.x1 * 29791.0 + z.z1 * 923521.0;
		double[] c = GROUND_CACHE.get(z);
		if (c != null && c[0] == key) {
			return c[1];
		}
		double g = computeGround(world, d, z);
		GROUND_CACHE.put(z, new double[] {key, g});
		return g;
	}

	private static double computeGround(Level world, LevelData d, LevelData.AreaBox z) {
		int x = (z.x0 + z.x1) / 2;
		int zz = (z.z0 + z.z1) / 2;
		if (!(z instanceof LevelData.CamZone cz) || cz.dist >= 6.0F) {
			// the ground most of the area stands on
			double[] hs = new double[25];
			int n = 0;
			for (int i = 0; i < 5; i++) {
				for (int j = 0; j < 5; j++) {
					int sx = z.x0 + (z.x1 - z.x0) * i / 4;
					int sz = z.z0 + (z.z1 - z.z0) * j / 4;
					hs[n++] = groundBelow(world, d, new BlockPos(sx, d.topY, sz));
				}
			}
			java.util.Arrays.sort(hs, 0, n);
			return hs[n / 2];
		}
		{
			// a camera inside a house: the floor of the room, not its roof
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, d.floorY + 5, zz);
			while (p.getY() < d.topY && !(world.getBlockState(p.above()).isAir() && world.getBlockState(p.above(2)).isAir())) {
				p.move(0, 1, 0);
			}
			return p.getY() + 1.02;
		}
	}

	/** The floor of an area goes up or down by hand (it stays there when the area moves). */
	public static void areaFloor(Minecraft mc, LevelData.AreaBox a, double step) {
		LevelData d = level;
		if (d == null || mc.level == null) {
			return;
		}
		a.ground = Mth.clamp(areaGround(mc.level, d, a) + step, d.floorY + 1.02, d.topY);
		save();
		Tutorial.note("camedit");
	}

	/** The floor of an area is worked out from the blocks again. */
	public static void areaFloorAuto(LevelData.AreaBox a) {
		a.ground = Double.NaN;
		GROUND_CACHE.remove(a);
		save();
	}

	/** Before an area is dragged: its floor stays where it is (it does not jump onto roofs on the way). */
	public static void holdFloor(Level world, LevelData.AreaBox a) {
		LevelData d = level;
		if (d != null && Double.isNaN(a.ground)) {
			a.ground = areaGround(world, d, a);
		}
	}

	// ---------------------------------------------------------------- trigger areas

	/** A trigger area over this rectangle; {@code start}: where it was dragged from (its ground). */
	public static LevelData.@Nullable Trigger makeTrigger(Minecraft mc, int xa, int za, int xb, int zb, @Nullable BlockPos start) {
		LevelData d = level;
		if (d == null) {
			return null;
		}
		LevelData.Trigger t = new LevelData.Trigger();
		t.id = d.nextId++;
		t.x0 = Math.max(d.minX, Math.min(xa, xb));
		t.z0 = Math.max(d.minZ, Math.min(za, zb));
		t.x1 = Math.min(d.maxX, Math.max(xa, xb));
		t.z1 = Math.min(d.maxZ, Math.max(za, zb));
		if (start != null && mc.level != null) {
			t.ground = groundBelow(mc.level, d, start.above(3));
		}
		d.triggers.add(t);
		selectTrigger(t.id);
		save();
		play(mc, SoundEvents.TRIPWIRE_ATTACH, 1.0F);
		say(mc, "Trigger area made · Link tool: click it, then what walking in sets off");
		Tutorial.note("trigger");
		return t;
	}

	public static void deleteTrigger(LevelData.Trigger t) {
		if (level != null) {
			level.triggers.remove(t);
			level.unlink(t.id);
			if (pendingSource == t.id) {
				pendingSource = -1;
			}
			clearSelected();
			save();
		}
	}

	public static void triggerOnce(LevelData.Trigger t) {
		t.once = !t.once;
		save();
	}

	private static @Nullable BlockPos triggerCorner;

	public static @Nullable BlockPos triggerCorner() {
		return triggerCorner;
	}

	/** Trigger tool in the normal view: first click a corner, then the other. */
	public static void triggerClick(Minecraft mc, BlockPos ground) {
		if (level == null) {
			return;
		}
		if (triggerCorner == null) {
			triggerCorner = ground.immutable();
			say(mc, "First corner set · now click the other corner");
			return;
		}
		makeTrigger(mc, triggerCorner.getX(), triggerCorner.getZ(), ground.getX(), ground.getZ(), triggerCorner);
		triggerCorner = null;
	}

	// ---------------------------------------------------------------- explosions

	/** An explosion place here; clicking one again selects it. */
	public static void placeBlast(Minecraft mc, BlockPos at) {
		LevelData d = level;
		if (d == null || !d.inside(at)) {
			return;
		}
		LevelData.Blast b = d.blastAt(at);
		if (b == null) {
			b = new LevelData.Blast();
			b.id = d.nextId++;
			b.pos = at.immutable();
			d.blasts.add(b);
			play(mc, SoundEvents.TNT_PRIMED, 1.4F);
			say(mc, "Explosion here · link something to it (Link tool) to set it off");
		}
		selectBlast(b.id);
		save();
	}

	public static void blastPower(LevelData.Blast b, float step) {
		b.power = Mth.clamp(b.power + step, 1.0F, 8.0F);
		save();
	}

	public static void deleteBlast(LevelData.Blast b) {
		if (level != null) {
			level.blasts.remove(b);
			level.unlink(b.id);
			clearSelected();
			save();
		}
	}

	public static void spawnCount(LevelData.Spawn sp, int step) {
		sp.count = Mth.clamp(sp.count + step, 1, 12);
		save();
	}

	/** The ground under this place: down through air, leaves, trunks and plants (a tree is not the floor). */
	public static double groundBelow(Level world, LevelData d, BlockPos from) {
		BlockPos.MutableBlockPos p = from.mutable();
		while (p.getY() > d.floorY) {
			BlockState s = world.getBlockState(p);
			boolean skip = s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || Trees.log(s)
				|| s.getCollisionShape(world, p).isEmpty() && s.getFluidState().isEmpty();
			if (!skip) {
				break;
			}
			p.move(0, -1, 0);
		}
		return p.getY() + 1.02;
	}

	/** Where the camera of an area stands (shown as a box in the air in the editor). */
	public static Vec3 zoneEye(Level world, LevelData d, LevelData.CamZone z) {
		Vec3 c = z.centre(zoneGround(world, d, z) + 1.0);
		return c.subtract(Vec3.directionFromRotation(z.pitch, z.yaw).scale(z.dist));
	}

	/** While playing: a camera hung on a switch or villager only counts once that went off. */
	public static boolean zoneLive(LevelData.CamZone z) {
		return mode != Mode.PLAY || z.after < 0 || FIRED.contains(z.after) || LevelLife.isDone(z.after);
	}

	/** The camera area the figure is in: one that switched on later wins, then the smallest. */
	public static LevelData.@Nullable CamZone liveZoneAt(double x, double z) {
		LevelData d = level;
		if (d == null) {
			return null;
		}
		LevelData.CamZone best = null;
		for (LevelData.CamZone c : d.zones) {
			if (!c.contains(x, z) || !zoneLive(c)) {
				continue;
			}
			if (best == null) {
				best = c;
				continue;
			}
			if (c.priority != best.priority) {
				if (c.priority > best.priority) {
					best = c;
				}
				continue;
			}
			boolean later = c.after >= 0;
			boolean bestLater = best.after >= 0;
			if (later && !bestLater || later == bestLater && c.area() < best.area()) {
				best = c;
			}
		}
		return best;
	}

	/** What a camera waits for, in words. */
	public static String afterName(LevelData.CamZone z) {
		LevelData d = level;
		if (z.after < 0 || d == null) {
			return "always";
		}
		return "after " + d.sourceName(z.after);
	}

	public static void zonePriority(LevelData.CamZone z, int step) {
		z.priority = Math.max(-9, Math.min(9, z.priority + step));
		save();
	}

	/** The camera of an area stands here now: it keeps looking at the middle of its area. */
	public static void setZoneEye(Level world, LevelData.CamZone z, Vec3 eye) {
		LevelData d = level;
		if (d == null) {
			return;
		}
		Vec3 focus = z.centre(zoneGround(world, d, z) + 1.0);
		Vec3 v = focus.subtract(eye);
		double len = v.length();
		if (len < 1.0) {
			return;
		}
		Vec3 dir = v.scale(1.0 / len);
		z.pitch = Mth.clamp((float) Math.toDegrees(-Math.asin(dir.y)), -40.0F, 85.0F);
		z.yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
		z.dist = Mth.clamp((float) len, 3.0F, 40.0F);
		Tutorial.note("camedit");
	}

	/** The handles of a selected area: 4 corners, 4 sides, the middle (plate x, z at block centres). */
	public static double[][] zoneHandles(LevelData.AreaBox z) {
		double x0 = z.x0 + 0.5;
		double x1 = z.x1 + 0.5;
		double z0 = z.z0 + 0.5;
		double z1 = z.z1 + 0.5;
		double mx = (x0 + x1) / 2.0;
		double mz = (z0 + z1) / 2.0;
		return new double[][] {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}, {mx, z0}, {x1, mz}, {mx, z1}, {x0, mz}, {mx, mz}};
	}

	/** A handle was dragged to this block (x, z); {@code from}: the area as it was when the drag started. */
	public static void dragZoneHandle(LevelData.AreaBox z, int handle, int bx, int bz, int[] from, int startX, int startZ) {
		LevelData d = level;
		if (d == null) {
			return;
		}
		bx = Mth.clamp(bx, d.minX, d.maxX);
		bz = Mth.clamp(bz, d.minZ, d.maxZ);
		int x0 = from[0];
		int z0 = from[1];
		int x1 = from[2];
		int z1 = from[3];
		switch (handle) {
			case 0 -> {
				x0 = bx;
				z0 = bz;
			}
			case 1 -> {
				x1 = bx;
				z0 = bz;
			}
			case 2 -> {
				x1 = bx;
				z1 = bz;
			}
			case 3 -> {
				x0 = bx;
				z1 = bz;
			}
			case 4 -> z0 = bz;
			case 5 -> x1 = bx;
			case 6 -> z1 = bz;
			case 7 -> x0 = bx;
			default -> {
				// the middle: the whole area moves
				int dx = bx - startX;
				int dz = bz - startZ;
				dx = Mth.clamp(dx, d.minX - from[0], d.maxX - from[2]);
				dz = Mth.clamp(dz, d.minZ - from[1], d.maxZ - from[3]);
				x0 += dx;
				x1 += dx;
				z0 += dz;
				z1 += dz;
			}
		}
		z.x0 = Math.min(x0, x1);
		z.x1 = Math.max(x0, x1);
		z.z0 = Math.min(z0, z1);
		z.z1 = Math.max(z0, z1);
		Tutorial.note("camedit");
	}

	public static void zoneAlways(LevelData.CamZone z) {
		z.after = -1;
		save();
	}

	/** Link tool, second click on a camera: it waits for the switch or villager picked first (again: always). */
	private static void linkCamera(Minecraft mc, LevelData.CamZone z, int source) {
		if (z.after == source) {
			z.after = -1;
			say(mc, "This camera counts always again");
			play(mc, SoundEvents.LEAD_BREAK, 1.0F);
		} else {
			z.after = source;
			say(mc, "Linked! This camera takes over once that went off");
			play(mc, SoundEvents.LEAD_TIED, 1.0F);
		}
		selectZone(z.id);
		save();
	}

	public static String followName(float f) {
		return f >= 0.99F ? "fully" : f >= 0.49F ? "a little" : "fixed";
	}

	public static LevelData.@Nullable Gate selectedGate() {
		return level == null || selectedGate < 0 ? null : level.gate(selectedGate);
	}

	public static LevelData.@Nullable Switch selectedSwitch() {
		if (level == null || selectedSwitch < 0) {
			return null;
		}
		for (LevelData.Switch sw : level.switches) {
			if (sw.id == selectedSwitch) {
				return sw;
			}
		}
		return null;
	}

	public static @Nullable BlockPos selectedPos() {
		return selectedPos;
	}

	public static void selectGate(int id) {
		clearSelected();
		selectedGate = id;
	}

	public static void selectSwitch(int id) {
		clearSelected();
		selectedSwitch = id;
	}

	/** A checkpoint ("cp") or mob place ("spawn") is chosen. */
	public static void selectPlace(String kind, BlockPos pos) {
		clearSelected();
		selectedMarker = kind;
		selectedPos = pos;
	}

	public static int pendingSource() {
		return pendingSource;
	}

	/** Link tool: the switch the next click connects to (null: none, or something else is picked). */
	public static @Nullable BlockPos pendingSwitch() {
		if (level == null || pendingSource < 0) {
			return null;
		}
		for (LevelData.Switch sw : level.switches) {
			if (sw.id == pendingSource) {
				return sw.pos;
			}
		}
		return null;
	}

	/** Link tool: the villager the next click connects to (-1: none). */
	public static int pendingNpc() {
		return level != null && pendingSource >= 0 && level.npc(pendingSource) != null ? pendingSource : -1;
	}

	public static String mobName(String type) {
		for (int i = 0; i < MOBS.length; i++) {
			if (MOBS[i].equals(type)) {
				return MOB_NAMES[i];
			}
		}
		return type;
	}

	// ---------------------------------------------------------------- the diorama cut

	/** Read by the chunk meshing threads: blocks outside the plate are not drawn while the diorama is up. */
	public static boolean cuts(int x, int y, int z) {
		int[] c = cut;
		return c != null && (x < c[0] || z < c[1] || x > c[2] || z > c[3] || y < c[4]);
	}

	/** Drawn things that are not blocks (displays, mobs): outside the plate they are not shown either. */
	public static boolean cuts(double x, double y, double z) {
		return cuts((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
	}

	public static boolean cutting() {
		return cut != null;
	}

	private static void setCut(Minecraft mc, boolean on) {
		LevelData d = level;
		int[] next = on && d != null ? new int[] {d.minX, d.minZ, d.maxX, d.maxZ, d.floorY} : null;
		int[] before = cut;
		cut = next;
		if (mc.level == null || mc.player == null) {
			return;
		}
		int[] any = next != null ? next : before;
		if (any == null) {
			return;
		}
		int rd = mc.options.getEffectiveRenderDistance() + 1;
		int pcx = mc.player.getBlockX() >> 4;
		int pcz = mc.player.getBlockZ() >> 4;
		mc.levelExtractor.setSectionRangeDirty(Math.min(pcx - rd, (any[0] >> 4) - 1), mc.level.getMinSectionY(), Math.min(pcz - rd, (any[1] >> 4) - 1),
			Math.max(pcx + rd, (any[2] >> 4) + 1), mc.level.getMaxSectionY(), Math.max(pcz + rd, (any[3] >> 4) + 1));
	}

	// ---------------------------------------------------------------- ghosts

	public static LevelData.@Nullable Blueprint ghostAt(BlockPos pos) {
		if (mode != Mode.EDIT || level == null) {
			return null;
		}
		int id = GHOSTS.getOrDefault(pos.asLong(), -1);
		return id < 0 ? null : level.blueprint(id);
	}

	private static void rebuildGhosts() {
		GHOSTS.clear();
		if (level != null) {
			for (LevelData.Blueprint b : level.blueprints) {
				for (BlockPos p : b.blocks) {
					GHOSTS.put(p.asLong(), b.id);
				}
			}
		}
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		EditorHistory.tick();
		if (!loaded) {
			load(mc);
		}
		while (BrickifyClient.EDITOR.consumeClick()) {
			toggle(mc);
		}
		while (BrickifyClient.EDITOR_VIEW.consumeClick()) {
			if (mode == Mode.EDIT && mc.gui.screen() == null) {
				openDiorama(mc, false);
			}
		}
		if (mode == Mode.EDIT) {
			if (!(mc.gui.screen() instanceof EditorScreen)) {
				EditorTools.tickEgo(mc);
			}
		} else if (mode == Mode.PLAY && level != null) {
			tickPlay(mc, level);
		}
		Tutorial.tick(mc);
	}

	/** O: build the level (making one if there is none yet), or play it. */
	public static void toggle(Minecraft mc) {
		if (mode == Mode.EDIT) {
			enterPlay(mc);
		} else {
			enterEdit(mc);
		}
	}

	private static void load(Minecraft mc) {
		loaded = true;
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null) {
			return;
		}
		dir = server.getWorldPath(LevelResource.ROOT);
		level = LevelData.load(dir.resolve("brickify_level.json"), mc.level.registryAccess().lookupOrThrow(Registries.BLOCK));
		EditorHistory.clear();
		if (level != null) {
			mode = level.playing ? Mode.PLAY : Mode.EDIT;
			rebuildGhosts();
			// where undo can go back to
			EditorHistory.saved(level);
		}
	}

	public static void save() {
		GROUND_CACHE.clear();
		if (level != null && dir != null) {
			level.save(dir.resolve("brickify_level.json"));
		}
		if (level != null) {
			EditorHistory.saved(level);
		}
	}

	public static void forget() {
		save();
		level = null;
		dir = null;
		loaded = false;
		mode = Mode.OFF;
		SELECTION.clear();
		placing = null;
		GHOSTS.clear();
		cut = null;
		star = null;
		hover = null;
		won = false;
		clearSelected();
		pendingSource = -1;
		FIRED.clear();
		checkpoint = null;
		BrickRuins.locked = n -> false;
		EditorCam.reset();
		Tutorial.forget();
		LevelLife.forget();
		EditorHistory.clear();
	}

	// ---------------------------------------------------------------- building / playing

	public static void enterEdit(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null) {
			say(mc, "The level editor only works in single player for now.");
			return;
		}
		if (!loaded) {
			load(mc);
		}
		if (level == null) {
			// a new level: the tutorial plate or an empty one, the player picks
			if (!(mc.gui.screen() instanceof NewLevelScreen)) {
				mc.gui.setScreen(new NewLevelScreen());
			}
			return;
		}
		LevelData d = level;
		if (d.playing || mode == Mode.PLAY) {
			// back from playing: the level as it was built
			BrickRuins.clearLevel(n -> n.startsWith("level:"), d.minX - 4, d.minZ - 4, d.maxX + 4, d.maxZ + 4);
			Path file = snapshotFile();
			var dim = mc.level.dimension();
			UUID me = mc.player.getUUID();
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null && file != null) {
					Snapshot.restore(sl, file);
				}
				if (sl != null) {
					for (Entity e : sl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(d.minX - 16, d.floorY - 32, d.minZ - 16, d.maxX + 17, d.topY + 32, d.maxZ + 17),
						e -> e.entityTags().contains("brickify_level") || e instanceof net.minecraft.world.entity.item.PrimedTnt)) {
						e.discard();
					}
				}
				restoreInventory(server, me);
			});
			removeStar(mc);
			LevelLife.stop(mc);
		}
		d.playing = false;
		mode = Mode.EDIT;
		won = false;
		FIRED.clear();
		checkpoint = null;
		BrickRuins.locked = n -> false;
		rebuildGhosts();
		save();
		gameMode(mc, net.minecraft.world.level.GameType.CREATIVE);
		EditorTools.give(mc);
		play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 1.0F);
		openDiorama(mc, false);
	}

	/** A new level here: an empty plate around the player, or the tutorial plate (built first, then explained). */
	public static void createLevel(Minecraft mc, boolean tutorial) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null) {
			return;
		}
		EditorHistory.clear();
		if (!loaded) {
			load(mc);
		}
		BlockPos feet = mc.player.blockPosition();
		if (tutorial) {
			LevelData d = TutorialPlate.plan(feet);
			level = d;
			mode = Mode.EDIT;
			save();
			var dim = mc.level.dimension();
			UUID id = mc.player.getUUID();
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null) {
					TutorialPlate.build(sl, d);
					ServerPlayer sp = server.getPlayerList().getPlayer(id);
					BlockPos at = d.spots.getOrDefault("start", feet);
					if (sp != null) {
						sp.teleportTo(sl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 180.0F, 20.0F, true);
					}
				}
			});
			Tutorial.begin(d);
			// the diorama looks over the plate from the meadow, towards the village and the wall
			mc.player.setYRot(180.0F);
			mc.player.yRotO = 180.0F;
		} else {
			level = LevelData.around(feet, defaultSize, feet.getY() - 1);
			level.start = feet;
			level.startYaw = mc.player.getYRot();
		}
		enterEdit(mc);
	}

	/** Builds the tutorial plate again where this level's plate is (everything on it is replaced). */
	public static void rebuildAsTutorial(Minecraft mc) {
		LevelData old = level;
		MinecraftServer server = mc.getSingleplayerServer();
		if (old == null || server == null || mc.level == null) {
			return;
		}
		if (mode == Mode.PLAY) {
			enterEdit(mc);
		}
		BlockPos centre = new BlockPos((old.minX + old.maxX + 1) / 2, old.floorY + 7, (old.minZ + old.maxZ + 1) / 2);
		cancelPlacing(mc);
		SELECTION.clear();
		clearSelected();
		LevelData d = TutorialPlate.plan(centre);
		EditorHistory.clear();
		var dim = mc.level.dimension();
		int x0 = Math.min(old.minX, d.minX);
		int z0 = Math.min(old.minZ, d.minZ);
		int x1 = Math.max(old.maxX, d.maxX);
		int z1 = Math.max(old.maxZ, d.maxZ);
		int y0 = Math.min(old.floorY, d.floorY);
		int y1 = Math.max(old.topY, d.topY);
		level = d;
		rebuildGhosts();
		save();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				for (Entity e : sl.getEntities((Entity) null, new net.minecraft.world.phys.AABB(x0 - 8, y0 - 8, z0 - 8, x1 + 9, y1 + 8, z1 + 9),
					e -> e.entityTags().contains("brickify_level") || e instanceof net.minecraft.world.entity.item.ItemEntity)) {
					e.discard();
				}
				TutorialPlate.clear(sl, x0, y0, z0, x1, y1, z1);
				TutorialPlate.build(sl, d);
			}
		});
		Tutorial.begin(d);
		if (cut != null) {
			setCut(mc, true);
		}
		EditorCam.enter(d, 180.0F, false);
	}

	public static void enterPlay(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		LevelData d = level;
		if (server == null || mc.player == null || mc.level == null || d == null) {
			return;
		}
		cancelPlacing(mc);
		SELECTION.clear();
		clearSelected();
		closeDiorama(mc);
		mode = Mode.PLAY;
		d.playing = true;
		won = false;
		save();
		Path file = snapshotFile();
		var dim = mc.level.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null && file != null) {
				Snapshot.capture(sl, d, file);
			}
		});
		FIRED.clear();
		WAS_ON.clear();
		INSIDE.clear();
		switchesRead = false;
		checkpoint = null;
		pendingSource = -1;
		stashInventory(mc);
		LIFT_NOW.clear();
		LIFT_NOW.addAll(d.liftable);
		BrickRuins.locked = Editor::lockedHeap;
		for (LevelData.Blueprint b : d.blueprints) {
			BrickRuins.pile(mc, "level:" + b.id, b.blocks, b.states, b.pileCentre());
		}
		spawnMobs(mc, d);
		LevelLife.start(mc, d);
		gameMode(mc, net.minecraft.world.level.GameType.SURVIVAL);
		// a figure that falls apart keeps its things (the tools, too)
		command(mc, "gamerule keep_inventory true");
		// beaten: straight back to the checkpoint, no death screen
		command(mc, "gamerule immediate_respawn true");
		if (d.start != null) {
			toStart(mc, d);
		}
		dev.rusty.brickify.client.BrickCam.startView(d.startYaw);
		mc.gui.hud.setTimes(5, 40, 15);
		Tutorial.note("play");
		breakHintShown = false;
		mc.gui.hud.setTitle(Component.literal("Let's go!"));
		mc.gui.hud.setSubtitle(Component.literal("O: back to building"));
		play(mc, SoundEvents.NOTE_BLOCK_BELL.value(), 1.2F);
	}

	/** Heaps that R does not build: those a switch builds, and burst gates. */
	public static boolean lockedHeap(String name) {
		LevelData d = level;
		if (!name.startsWith("level:") || d == null || mode != Mode.PLAY) {
			return false;
		}
		if (name.startsWith("level:gate:")) {
			return true;
		}
		try {
			int id = Integer.parseInt(name.substring(6));
			return d.triggered(id);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** The enemies that are there from the start (the others wait for what they are linked to). */
	private static void spawnMobs(Minecraft mc, LevelData d) {
		List<LevelData.Spawn> now = new ArrayList<>();
		for (LevelData.Spawn sp : d.spawns) {
			if (!d.triggered(sp.id)) {
				now.add(sp);
			}
		}
		spawnMobs(mc, now, false);
	}

	private static void spawnMobs(Minecraft mc, List<LevelData.Spawn> which, boolean poof) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null || which.isEmpty()) {
			return;
		}
		var dim = mc.level.dimension();
		List<LevelData.Spawn> spawns = new ArrayList<>(which);
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl == null) {
				return;
			}
			for (LevelData.Spawn sp : spawns) {
				var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.parse(sp.type));
				if (type.isEmpty()) {
					continue;
				}
				for (int k = 0; k < Math.max(1, sp.count); k++) {
					Entity e = type.get().create(sl, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
					if (e == null) {
						continue;
					}
					// more than one: in a little ring around the place
					double ox = sp.count > 1 ? Math.cos(k * 2.4) * 0.9 : 0.0;
					double oz = sp.count > 1 ? Math.sin(k * 2.4) * 0.9 : 0.0;
					e.snapTo(sp.pos.getX() + 0.5 + ox, sp.pos.getY(), sp.pos.getZ() + 0.5 + oz, (sp.pos.getX() * 37 + sp.pos.getZ() * 11 + k * 60) % 360, 0.0F);
					if (poof) {
						sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, e.getX(), e.getY() + 0.8, e.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
					}
					e.addTag("brickify_level");
					if (e instanceof net.minecraft.world.entity.Mob mob) {
						mob.setPersistenceRequired();
						if (sp.type.contains("zombie") || sp.type.contains("skeleton")) {
							mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
							mob.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0.0F);
						}
					}
					sl.addFreshEntity(e);
				}
			}
		});
	}

	/** Something that sets things off went off while playing (a trigger area, a thing found ...). */
	public static void sourceFired(Minecraft mc, LevelData d, int id, Vec3 at) {
		if (mode != Mode.PLAY || FIRED.contains(id) && !(d.trigger(id) != null && !d.trigger(id).once)) {
			return;
		}
		FIRED.add(id);
		List<Integer> targets = d.targetsOf(id);
		if (targets != null && !targets.isEmpty()) {
			fireTargets(mc, d, targets, at);
		}
	}

	/** A switch was used: its blueprints build themselves, its gates burst. */
	private static void fire(Minecraft mc, LevelData d, LevelData.Switch sw) {
		FIRED.add(sw.id);
		Tutorial.note("switch");
		play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.4F);
		dev.rusty.brickify.client.BrickCam.shake(0.15F);
		fireTargets(mc, d, sw.targets, Vec3.atCenterOf(sw.pos));
	}

	/**
	 * Something set these off (a switch, a villager's deal, a trigger area ...): blueprints build, gates burst, enemies
	 * appear, explosions go off.
	 */
	public static void fireTargets(Minecraft mc, LevelData d, List<Integer> targets, Vec3 at) {
		MinecraftServer server = mc.getSingleplayerServer();
		for (int id : new ArrayList<>(targets)) {
			LevelData.Spawn sp = d.spawn(id);
			if (sp != null) {
				spawnMobs(mc, List.of(sp), true);
				play(mc, SoundEvents.EVOKER_PREPARE_SUMMON, 1.2F);
				continue;
			}
			LevelData.Blast bl = d.blast(id);
			if (bl != null && server != null && mc.level != null) {
				var dim = mc.level.dimension();
				Vec3 c = Vec3.atCenterOf(bl.pos);
				float power = bl.power;
				server.execute(() -> {
					ServerLevel sl = server.getLevel(dim);
					if (sl != null) {
						sl.explode(null, c.x, c.y, c.z, power, Level.ExplosionInteraction.TNT);
					}
				});
				continue;
			}
			LevelData.Blueprint b = d.blueprint(id);
			if (b != null) {
				BrickRuins.buildNamed("level:" + id, BrickEntities.ticks());
				continue;
			}
			LevelData.Gate g = d.gate(id);
			if (g != null && server != null && mc.level != null) {
				List<BlockPos> blocks = new ArrayList<>();
				List<BlockState> states = new ArrayList<>();
				for (int i = 0; i < g.blocks.size(); i++) {
					if (!mc.level.getBlockState(g.blocks.get(i)).isAir()) {
						blocks.add(g.blocks.get(i));
						states.add(g.states.get(i));
					}
				}
				var dim = mc.level.dimension();
				List<BlockPos> remove = new ArrayList<>(blocks);
				remove.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
				server.execute(() -> {
					ServerLevel sl = server.getLevel(dim);
					if (sl != null) {
						for (BlockPos p : remove) {
							sl.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
						}
					}
				});
				Vec3 c = g.box().getCenter();
				Vec3 from = c.add(c.subtract(at).normalize().scale(-1.5)).add(0.0, -0.5, 0.0);
				BrickRuins.burst(mc, "level:gate:" + g.id, blocks, states, from, 1.0F);
				play(mc, SoundEvents.GENERIC_EXPLODE.value(), 1.3F);
			}
		}
	}

	/** Where the figure goes after a fall or after dying: the last checkpoint, else the start. */
	private static void respawn(Minecraft mc, LevelData d) {
		if (checkpoint != null) {
			BlockPos s = d.start;
			float yaw = d.startYaw;
			d.start = checkpoint;
			toStart(mc, d);
			d.start = s;
			d.startYaw = yaw;
		} else if (d.start != null) {
			toStart(mc, d);
		}
	}

	private static boolean powered(BlockState s) {
		if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
			return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
		}
		if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER)) {
			return s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) > 0;
		}
		return false;
	}

	/** Levers, buttons and pressure plates can set things off. */
	public static boolean isSwitch(BlockState s) {
		var b = s.getBlock();
		return b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.ButtonBlock
			|| b instanceof net.minecraft.world.level.block.BasePressurePlateBlock;
	}

	private static @Nullable Path snapshotFile() {
		return dir == null ? null : dir.resolve("brickify_level_play.dat");
	}

	private static void tickPlay(Minecraft mc, LevelData d) {
		LevelLife.tick(mc, d);
		for (LevelData.Switch sw : d.switches) {
			boolean on = powered(mc.level.getBlockState(sw.pos));
			if (!switchesRead) {
				if (on) {
					WAS_ON.add(sw.id);
				}
				continue;
			}
			if (on && !WAS_ON.contains(sw.id) && !FIRED.contains(sw.id)) {
				fire(mc, d, sw);
			}
			if (on) {
				WAS_ON.add(sw.id);
			} else {
				WAS_ON.remove(sw.id);
			}
		}
		switchesRead = true;
		Vec3 me = mc.player.position();
		// trigger areas: walking in sets off what they are linked to
		for (LevelData.Trigger t : d.triggers) {
			boolean in = t.contains(me.x, me.z) && Math.abs(me.y - triggerGround(mc.level, d, t)) < 4.0;
			if (in && !INSIDE.contains(t.id)) {
				INSIDE.add(t.id);
				sourceFired(mc, d, t.id, me);
			} else if (!in) {
				INSIDE.remove(t.id);
			}
		}
		for (BlockPos c : d.checkpoints) {
			if (!c.equals(checkpoint) && Math.abs(me.x - (c.getX() + 0.5)) < 1.2 && Math.abs(me.z - (c.getZ() + 0.5)) < 1.2 && Math.abs(me.y - c.getY()) < 2.0) {
				checkpoint = c;
				play(mc, SoundEvents.NOTE_BLOCK_PLING.value(), 1.6F);
				Coins.burst(mc, c.getX() + 0.5, c.getY() + 1.5, c.getZ() + 0.5, 4);
				say(mc, "Checkpoint!");
			}
		}
		if (mc.player.isDeadOrDying()) {
			wasDead = true;
		} else if (wasDead) {
			wasDead = false;
			respawn(mc, d);
		}
		if (d.goal != null && !won) {
			ensureStar(mc, d.goal);
			Vec3 g = new Vec3(d.goal.getX() + 0.5, d.goal.getY() + 0.5, d.goal.getZ() + 0.5);
			Vec3 p = mc.player.position();
			if (Math.abs(p.x - g.x) < 1.0 && Math.abs(p.z - g.z) < 1.0 && p.y > g.y - 1.6 && p.y < g.y + 1.5) {
				won = true;
				removeStar(mc);
				Coins.burst(mc, g.x, g.y + 0.5, g.z, 24);
				mc.gui.hud.setTimes(8, 70, 20);
				mc.gui.hud.setTitle(Component.literal("Level complete!"));
				mc.gui.hud.setSubtitle(Component.literal("O: back to building"));
				Tutorial.note("won");
				play(mc, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
			}
		}
		if (star != null) {
			int t = BrickEntities.ticks();
			Quaternionf rot = new Quaternionf().rotationY(t * 0.08F);
			float bob = (float) Math.sin(t * 0.1) * 0.12F;
			star.setTransformation(new Transformation(new Vector3f(0.0F, 0.9F + bob, 0.0F), rot, new Vector3f(1.3F), null));
			star.setTransformationInterpolationDuration(1);
		}
		// fell off the plate: back to the start
		if ((d.start != null || checkpoint != null) && mc.player.getY() < d.floorY - 10) {
			respawn(mc, d);
			play(mc, SoundEvents.CHICKEN_EGG, 1.0F);
		}
	}

	private static void ensureStar(Minecraft mc, BlockPos at) {
		ClientLevel cl = mc.level;
		if (cl == null || star != null && !star.isRemoved()) {
			return;
		}
		Display.ItemDisplay d = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, cl);
		d.setId(nextId--);
		d.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		d.setItemStack(new ItemStack(Items.NETHER_STAR));
		d.setItemTransform(ItemDisplayContext.FIXED);
		d.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
		cl.addEntity(d);
		star = d;
	}

	private static void removeStar(Minecraft mc) {
		if (star != null && mc.level != null) {
			mc.level.removeEntity(star.getId(), Entity.RemovalReason.DISCARDED);
		}
		star = null;
	}

	// ---------------------------------------------------------------- views

	public static void openDiorama(Minecraft mc, boolean instant) {
		if (level == null || mc.player == null) {
			return;
		}
		EditorCam.enter(level, mc.player.getYRot(), instant);
		setCut(mc, true);
		Tutorial.note("diorama");
		if (!(mc.gui.screen() instanceof EditorScreen)) {
			savedHideGui = mc.gui.hud.isHidden();
			mc.gui.setScreen(new EditorScreen());
		}
		if (!mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}
	}

	/** Back to the normal view (the diorama screen closes). */
	public static void closeDiorama(Minecraft mc) {
		if (mc.gui.screen() instanceof EditorScreen) {
			mc.gui.setScreen(null);
		} else {
			dioramaGone(mc);
		}
	}

	/** The diorama screen went away (closed, or replaced by another screen). */
	public static void dioramaGone(Minecraft mc) {
		EditorCam.leave();
		if (mode == Mode.EDIT) {
			Tutorial.note("ego");
		}
		setCut(mc, false);
		if (mc.gui.hud.isHidden() != savedHideGui) {
			mc.gui.hud.toggle();
		}
		hover = null;
	}

	// ---------------------------------------------------------------- marking

	/**
	 * A click on a block: the whole thing it belongs to (connected, not ground) is marked. With {@code add} it is
	 * added to what is marked; {@code single} marks (or unmarks) just this one block, also ground.
	 */
	public static void select(Level world, BlockPos pos, boolean add, boolean single) {
		LevelData d = level;
		if (d == null || mode != Mode.EDIT || !d.inside(pos)) {
			return;
		}
		if (single) {
			long k = pos.asLong();
			if (!SELECTION.remove(k)) {
				SELECTION.add(k);
			}
			return;
		}
		if (!add) {
			SELECTION.clear();
		}
		BlockState s = world.getBlockState(pos);
		if (s.isAir()) {
			return;
		}
		if (!Statics.isStructure(world, pos, s)) {
			SELECTION.add(pos.asLong());
			return;
		}
		LongArrayList queue = new LongArrayList();
		LongOpenHashSet seen = new LongOpenHashSet();
		queue.add(pos.asLong());
		seen.add(pos.asLong());
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		int head = 0;
		while (head < queue.size() && SELECTION.size() < 6000) {
			long at = queue.getLong(head++);
			SELECTION.add(at);
			BlockPos p = BlockPos.of(at);
			for (Direction dir : Direction.values()) {
				q.setWithOffset(p, dir);
				long nk = q.asLong();
				if (seen.contains(nk) || !d.inside(q)) {
					continue;
				}
				seen.add(nk);
				BlockState ns = world.getBlockState(q);
				// from something thin (bars, a fence, a torch) the marking does not spread into solid blocks:
				// clicking the bars in a wall marks the bars, clicking the wall marks the whole wall
				if (!ns.isAir() && Statics.isStructure(world, q, ns)
					&& (world.getBlockState(p).isCollisionShapeFullBlock(world, p) || !ns.isCollisionShapeFullBlock(world, q))) {
					queue.add(nk);
				}
			}
		}
	}

	/** A box dragged out: what stands in it (and with {@code ground} also the ground in it). */
	public static void selectBox(Level world, BlockPos a, BlockPos b, boolean add, boolean ground) {
		LevelData d = level;
		if (d == null || mode != Mode.EDIT) {
			return;
		}
		if (!add) {
			SELECTION.clear();
		}
		int x0 = Math.max(d.minX, Math.min(a.getX(), b.getX()));
		int x1 = Math.min(d.maxX, Math.max(a.getX(), b.getX()));
		int z0 = Math.max(d.minZ, Math.min(a.getZ(), b.getZ()));
		int z1 = Math.min(d.maxZ, Math.max(a.getZ(), b.getZ()));
		int y0 = Math.max(d.floorY, Math.min(a.getY(), b.getY()));
		int y1 = Math.min(d.topY, Math.max(a.getY(), b.getY()) + (ground ? 0 : 12));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1 && SELECTION.size() < 12000; x++) {
					BlockState s = world.getBlockState(p.set(x, y, z));
					if (!s.isAir() && (ground || Statics.isStructure(world, p, s))) {
						SELECTION.add(p.asLong());
					}
				}
			}
		}
	}

	public static void clearSelection() {
		SELECTION.clear();
	}

	/** What is marked becomes a blueprint: the blocks leave the world and wait in a heap. Its heap is placed next. */
	public static LevelData.@Nullable Blueprint makeBlueprint(Minecraft mc) {
		LevelData d = level;
		MinecraftServer server = mc.getSingleplayerServer();
		ClientLevel world = mc.level;
		if (d == null || server == null || world == null || SELECTION.isEmpty()) {
			return null;
		}
		List<BlockPos> blocks = new ArrayList<>();
		for (long k : SELECTION) {
			BlockPos p = BlockPos.of(k);
			if (!world.getBlockState(p).isAir() && ghostAt(p) == null) {
				blocks.add(p);
			}
		}
		SELECTION.clear();
		if (blocks.isEmpty()) {
			return null;
		}
		blocks.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()));
		LevelData.Blueprint b = new LevelData.Blueprint();
		b.id = d.nextId++;
		for (BlockPos p : blocks) {
			b.blocks.add(p);
			b.states.add(world.getBlockState(p));
		}
		var box = b.box();
		b.pile = BlockPos.containing(box.getCenter().x, box.minY - 1, box.getCenter().z);
		d.blueprints.add(b);
		rebuildGhosts();
		List<BlockPos> remove = new ArrayList<>(blocks);
		remove.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
		for (int i = 0; i < b.blocks.size(); i++) {
			EditorHistory.block(b.blocks.get(i), b.states.get(i), Blocks.AIR.defaultBlockState());
		}
		var dim = world.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				for (BlockPos p : remove) {
					sl.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
				}
			}
		});
		placing = b;
		placingNew = true;
		selectedId = b.id;
		selectedMarker = null;
		save();
		play(mc, SoundEvents.BOOK_PAGE_TURN, 1.0F);
		return b;
	}

	/** Pick up the heap of a blueprint to put it somewhere else. */
	public static void movePile(LevelData.Blueprint b) {
		placing = b;
		placingNew = false;
	}

	/** The heap goes on top of this block. */
	public static void placePile(Minecraft mc, BlockPos ground) {
		LevelData.Blueprint b = placing;
		if (b == null) {
			return;
		}
		b.pile = ground.immutable();
		placing = null;
		placingNew = false;
		save();
		play(mc, SoundEvents.STONE_PLACE, 1.0F);
	}

	/** Esc while a heap waits for its place: a new blueprint is undone, a moved heap stays where it was. */
	public static void cancelPlacing(Minecraft mc) {
		LevelData.Blueprint b = placing;
		placing = null;
		if (b != null && placingNew) {
			dissolve(mc, b);
		}
		placingNew = false;
	}

	/** The blueprint goes back into the world as normal blocks. */
	public static void dissolve(Minecraft mc, LevelData.Blueprint b) {
		LevelData d = level;
		MinecraftServer server = mc.getSingleplayerServer();
		if (d == null || server == null || mc.level == null) {
			return;
		}
		d.blueprints.remove(b);
		if (placing == b) {
			placing = null;
		}
		if (selectedId == b.id) {
			selectedId = -1;
		}
		rebuildGhosts();
		List<BlockPos> blocks = new ArrayList<>(b.blocks);
		List<BlockState> states = new ArrayList<>(b.states);
		for (int i = 0; i < blocks.size(); i++) {
			EditorHistory.block(blocks.get(i), mc.level.getBlockState(blocks.get(i)), states.get(i));
		}
		var dim = mc.level.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				for (int i = 0; i < blocks.size(); i++) {
					sl.setBlock(blocks.get(i), states.get(i), FLAGS);
				}
			}
		});
		save();
	}

	/** The blueprint and its blocks are gone. */
	public static void delete(LevelData.Blueprint b) {
		if (level == null) {
			return;
		}
		level.blueprints.remove(b);
		for (LevelData.Switch sw : level.switches) {
			sw.targets.remove((Integer) b.id);
		}
		for (LevelData.Npc n : level.npcs) {
			n.targets.remove((Integer) b.id);
		}
		if (placing == b) {
			placing = null;
		}
		if (selectedId == b.id) {
			selectedId = -1;
		}
		rebuildGhosts();
		save();
	}

	public static void setStart(Minecraft mc, BlockPos stand, float yaw) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		level.start = stand.immutable();
		level.startYaw = Math.round(yaw / 45.0F) * 45.0F;
		save();
		play(mc, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1.0F);
	}

	public static void setGoal(Minecraft mc, BlockPos stand) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		level.goal = stand.immutable();
		save();
		play(mc, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F);
	}

	public static void removeMarker(String which) {
		if (level == null) {
			return;
		}
		if (which.equals("start")) {
			level.start = null;
		} else {
			level.goal = null;
		}
		selectedMarker = null;
		save();
	}

	/** The plate grows or shrinks around its middle (16 blocks a step). */
	public static void resize(Minecraft mc, int step) {
		LevelData d = level;
		if (d == null) {
			return;
		}
		int size = Math.max(16, Math.min(128, Math.max(d.sizeX(), d.sizeZ()) + step * 16));
		int cx = (d.minX + d.maxX + 1) / 2;
		int cz = (d.minZ + d.maxZ + 1) / 2;
		d.minX = cx - size / 2;
		d.minZ = cz - size / 2;
		d.maxX = d.minX + size - 1;
		d.maxZ = d.minZ + size - 1;
		save();
		if (cut != null) {
			setCut(mc, true);
		}
	}

	// ---------------------------------------------------------------- gates, switches, checkpoints, mobs

	/** What is marked becomes a gate: it stays where it is and bursts when a switch connected to it is used. */
	public static LevelData.@Nullable Gate makeGate(Minecraft mc) {
		LevelData d = level;
		ClientLevel world = mc.level;
		if (d == null || world == null || SELECTION.isEmpty()) {
			return null;
		}
		LevelData.Gate g = new LevelData.Gate();
		for (long k : SELECTION) {
			BlockPos p = BlockPos.of(k);
			BlockState st = world.getBlockState(p);
			if (!st.isAir() && ghostAt(p) == null && d.gateAt(p) == null) {
				g.blocks.add(p);
				g.states.add(st);
			}
		}
		SELECTION.clear();
		if (g.blocks.isEmpty()) {
			return null;
		}
		g.id = d.nextId++;
		d.gates.add(g);
		selectGate(g.id);
		save();
		play(mc, SoundEvents.CHAIN_PLACE, 1.0F);
		return g;
	}

	public static void deleteGate(LevelData.Gate g) {
		if (level == null) {
			return;
		}
		level.gates.remove(g);
		for (LevelData.Switch sw : level.switches) {
			sw.targets.remove((Integer) g.id);
		}
		for (LevelData.Npc n : level.npcs) {
			n.targets.remove((Integer) g.id);
		}
		clearSelected();
		save();
	}

	public static void deleteSwitch(LevelData.Switch sw) {
		if (level == null) {
			return;
		}
		level.switches.remove(sw);
		for (LevelData.CamZone z : level.zones) {
			if (z.after == sw.id) {
				z.after = -1;
			}
		}
		if (pendingSource == sw.id) {
			pendingSource = -1;
		}
		clearSelected();
		save();
	}

	/**
	 * The link tool: first a lever, button or pressure plate, then what it sets off (a blueprint or a gate).
	 * Connecting the same two again takes the link away.
	 */
	public static void linkClick(Minecraft mc, @Nullable Pick p) {
		LevelData d = level;
		if (d == null || mc.level == null) {
			return;
		}
		if (p == null) {
			pendingSource = -1;
			return;
		}
		// what sets things off: a switch block, a villager, a trigger area, a thing to find
		int source = linkSource(mc, d, p);
		if (source >= 0 && (pendingSource < 0 || source != pendingSource && !isTarget(d, p))) {
			pendingSource = source;
			selectSource(d, source);
			play(mc, SoundEvents.LEAD_TIED, 1.4F);
			say(mc, "Picked " + d.sourceName(source) + " · now click what it sets off: a blueprint, gate, enemy, explosion or camera");
			return;
		}
		if (pendingSource < 0) {
			say(mc, "First click what sets things off: a lever/button/plate, a villager, a trigger area or a thing to find");
			return;
		}
		int src = pendingSource;
		List<Integer> targets = d.targetsOf(src);
		if (targets == null) {
			pendingSource = -1;
			return;
		}
		if (p.kind() == Pick.Kind.CAMERA && d.zone(p.id()) != null) {
			pendingSource = -1;
			linkCamera(mc, d.zone(p.id()), src);
			return;
		}
		int target = targetId(d, p);
		pendingSource = -1;
		if (target < 0) {
			say(mc, "Linking cancelled");
			return;
		}
		if (targets.contains(target)) {
			targets.remove((Integer) target);
			say(mc, "Link removed");
			play(mc, SoundEvents.LEAD_BREAK, 1.0F);
		} else {
			targets.add(target);
			say(mc, "Linked! " + capital(d.sourceName(src)) + " sets off the " + d.targetName(target));
			play(mc, SoundEvents.LEAD_TIED, 1.0F);
			Tutorial.note("link");
		}
		selectSource(d, src);
		save();
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** The id of what this click picks as something that sets things off (-1: nothing like that). */
	private static int linkSource(Minecraft mc, LevelData d, Pick p) {
		switch (p.kind()) {
			case NPC -> {
				return p.id();
			}
			case PICKUP -> {
				return p.id();
			}
			case TRIGGER -> {
				return p.id();
			}
			default -> {
			}
		}
		boolean isSwitch = p.kind() == Pick.Kind.SWITCH || (p.kind() == Pick.Kind.BLOCK && isSwitch(mc.level.getBlockState(p.pos())));
		if (isSwitch) {
			LevelData.Switch sw = d.switchAt(p.pos());
			if (sw == null) {
				sw = new LevelData.Switch();
				sw.id = d.nextId++;
				sw.pos = p.pos().immutable();
				d.switches.add(sw);
				save();
			}
			return sw.id;
		}
		if (p.kind() == Pick.Kind.BLOCK) {
			LevelData.Trigger t = d.triggerAt(p.at().x, p.at().z);
			if (t != null && Math.abs(p.at().y - triggerGround(mc.level, d, t)) < 1.5) {
				return t.id;
			}
		}
		return -1;
	}

	/** The id of what this click picks as something to set off (-1: nothing like that). */
	private static int targetId(LevelData d, Pick p) {
		return switch (p.kind()) {
			case GHOST, PILE, GATE, BLAST -> p.id();
			case SPAWN -> {
				LevelData.Spawn sp = d.spawnAt(p.pos());
				yield sp != null ? sp.id : -1;
			}
			default -> -1;
		};
	}

	private static boolean isTarget(LevelData d, Pick p) {
		return targetId(d, p) >= 0 || p.kind() == Pick.Kind.CAMERA;
	}

	/** Shows the card of what sets things off. */
	private static void selectSource(LevelData d, int id) {
		for (LevelData.Switch sw : d.switches) {
			if (sw.id == id) {
				int keep = pendingSource;
				selectSwitch(id);
				pendingSource = keep;
				return;
			}
		}
		int keep = pendingSource;
		LevelData.Npc n = d.npc(id);
		if (n != null) {
			selectPlace("npc", n.pos);
		} else if (d.trigger(id) != null) {
			selectTrigger(id);
		} else if (d.pickup(id) != null) {
			selectPlace("pickup", d.pickup(id).pos);
		}
		pendingSource = keep;
	}

	public static void toggleCheckpoint(Minecraft mc, BlockPos stand) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		if (!level.checkpoints.remove(stand)) {
			level.checkpoints.add(stand.immutable());
			play(mc, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1.3F);
		}
		save();
	}

	/** A mob place; clicking a place that has one again cycles its mob. */
	public static void placeMob(Minecraft mc, BlockPos stand) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		LevelData.Spawn sp = level.spawnAt(stand);
		if (sp == null) {
			sp = new LevelData.Spawn();
			sp.id = level.nextId++;
			sp.pos = stand.immutable();
			level.spawns.add(sp);
		} else {
			cycleMob(sp);
		}
		selectPlace("spawn", sp.pos);
		save();
		play(mc, SoundEvents.ZOMBIE_AMBIENT, 1.2F);
	}

	public static void cycleMob(LevelData.Spawn sp) {
		int i = 0;
		for (; i < MOBS.length; i++) {
			if (MOBS[i].equals(sp.type)) {
				break;
			}
		}
		sp.type = MOBS[(i + 1) % MOBS.length];
		save();
	}

	public static void removePlace(String kind, BlockPos pos) {
		if (level == null) {
			return;
		}
		if (kind.equals("cp")) {
			level.checkpoints.remove(pos);
		} else if (kind.equals("pickup")) {
			LevelData.Pickup gone = level.pickupAt(pos);
			if (gone != null) {
				level.unlink(gone.id);
			}
			level.pickups.removeIf(p -> p.pos.equals(pos));
		} else if (kind.equals("npc")) {
			LevelData.Npc gone = level.npcAt(pos);
			if (gone != null) {
				level.unlink(gone.id);
			}
			level.npcs.removeIf(n -> n.pos.equals(pos));
		} else {
			LevelData.Spawn gone = level.spawnAt(pos);
			if (gone != null) {
				level.unlink(gone.id);
			}
			level.spawns.removeIf(s -> s.pos.equals(pos));
		}
		clearSelected();
		save();
	}

	// ---------------------------------------------------------------- things to find, villagers

	/** Something to pick up here; clicking a place that has one again picks the next kind of thing. */
	public static void placePickup(Minecraft mc, BlockPos stand) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		LevelData.Pickup p = level.pickupAt(stand);
		if (p == null) {
			p = new LevelData.Pickup();
			p.id = level.nextId++;
			p.pos = stand.immutable();
			level.pickups.add(p);
		} else {
			p.item = LevelLife.nextItem(p.item, false);
		}
		selectPlace("pickup", p.pos);
		save();
		play(mc, SoundEvents.ITEM_PICKUP, 1.3F);
		say(mc, "Hidden here: " + LevelLife.itemName(p.item) + " · click again for another thing");
	}

	public static void cyclePickup(LevelData.Pickup p) {
		p.item = LevelLife.nextItem(p.item, false);
		save();
	}

	/** A villager here; clicking one again selects it. */
	public static void placeNpc(Minecraft mc, BlockPos stand) {
		if (level == null || !level.inside(stand)) {
			return;
		}
		LevelData.Npc n = level.npcAt(stand);
		if (n == null) {
			n = new LevelData.Npc();
			n.id = level.nextId++;
			n.pos = stand.immutable();
			level.npcs.add(n);
			play(mc, SoundEvents.VILLAGER_YES, 1.0F);
			say(mc, LevelLife.kindName(n.kind) + " · wants " + LevelLife.itemName(n.wants) + ", gives " + LevelLife.itemName(n.gives) + " · change it on the card");
		}
		selectPlace("npc", n.pos);
		save();
	}

	public static void cycleKind(LevelData.Npc n) {
		n.kind = LevelLife.nextKind(n.kind);
		save();
	}

	public static void cycleWants(LevelData.Npc n) {
		n.wants = LevelLife.nextItem(n.wants, true);
		save();
	}

	public static void cycleGives(LevelData.Npc n) {
		n.gives = LevelLife.nextItem(n.gives, false);
		save();
	}

	public static void npcCount(LevelData.Npc n, int step) {
		n.givesCount = Math.max(1, Math.min(16, n.givesCount + step));
		save();
	}

	public static LevelData.@Nullable Pickup selectedPickup() {
		return level == null || !"pickup".equals(selectedMarker) || selectedPos == null ? null : level.pickupAt(selectedPos);
	}

	public static LevelData.@Nullable Npc selectedNpc() {
		return level == null || !"npc".equals(selectedMarker) || selectedPos == null ? null : level.npcAt(selectedPos);
	}

	// ---------------------------------------------------------------- what may be smashed

	private static @Nullable Block lastBreakBlock;
	private static long previewKey = Long.MIN_VALUE;
	private static final LongArrayList PREVIEW = new LongArrayList();
	private static boolean breakHintShown;
	private static int lastTink = -100;

	/** The smashable tool marks blocks only TNT breaks (instead of blocks a hit breaks). */
	public static boolean blastMode;

	/** The smashable tool marks blocks the figure can lift and carry (wins over {@link #blastMode}). */
	public static boolean liftMode;

	/** Where liftable blocks stand while playing (they move when carried away). Server thread and client read it. */
	private static final it.unimi.dsi.fastutil.longs.LongSet LIFT_NOW = it.unimi.dsi.fastutil.longs.LongSets.synchronize(new it.unimi.dsi.fastutil.longs.LongOpenHashSet());

	/** What a click with the smashable tool marks right now. */
	private static it.unimi.dsi.fastutil.longs.LongOpenHashSet markSet(LevelData d) {
		return liftMode ? d.liftable : blastMode ? d.blastable : d.breakable;
	}

	/** A block is one kind of mark only. */
	private static void unmarkOthers(LevelData d, long k) {
		var keep = markSet(d);
		if (keep != d.breakable) {
			d.breakable.remove(k);
		}
		if (keep != d.blastable) {
			d.blastable.remove(k);
		}
		if (keep != d.liftable) {
			d.liftable.remove(k);
		}
	}

	private static String markWord() {
		return liftMode ? "Liftable: " : blastMode ? "Only TNT breaks: " : "Smashable: ";
	}

	/** The mark mode: 0 smash, 1 TNT only, 2 liftable. */
	public static void markMode(int m) {
		blastMode = m == 1;
		liftMode = m == 2;
	}

	public static int markMode() {
		return liftMode ? 2 : blastMode ? 1 : 0;
	}

	/** While playing: the figure may lift this block (it was marked liftable, or was carried here). */
	public static boolean liftableAt(BlockPos pos) {
		return mode == Mode.PLAY && LIFT_NOW.contains(pos.asLong());
	}

	/** While playing: a liftable block was carried away from here / set down there. */
	public static void lifted(BlockPos from) {
		LIFT_NOW.remove(from.asLong());
	}

	public static void setDown(BlockPos at) {
		LIFT_NOW.add(at.asLong());
	}

	/** While playing, an explosion does not break this block (it is neither smashable nor TNT-only). Any thread. */
	public static boolean blastProtects(BlockPos pos) {
		LevelData d = level;
		long k = pos.asLong();
		return mode == Mode.PLAY && d != null && !d.breakable.contains(k) && !d.blastable.contains(k);
	}

	public static boolean blastableAt(BlockPos pos) {
		LevelData d = level;
		return d != null && d.blastable.contains(pos.asLong());
	}

	/** While playing, this block holds: nothing breaks that the builder did not allow. Read on any thread. */
	public static boolean protects(BlockPos pos) {
		LevelData d = level;
		return mode == Mode.PLAY && d != null && !d.breakable.contains(pos.asLong());
	}

	/** Inside the plate of a level (any thread): decoration bushes there are walked through. */
	public static boolean walkThrough(BlockPos pos) {
		LevelData d = level;
		return d != null && d.inside(pos);
	}

	public static boolean breakableAt(BlockPos pos) {
		LevelData d = level;
		return d != null && d.breakable.contains(pos.asLong());
	}

	/** The kind of block clicked last with the smashable tool (for "all of these"). */
	public static @Nullable Block lastBreakBlock() {
		return lastBreakBlock;
	}

	/** The figure hit something that holds: a dull knock, nothing more. */
	public static void knock(Minecraft mc, BlockPos pos, BlockState state) {
		int now = BrickEntities.ticks();
		if (now - lastTink < 6) {
			return;
		}
		lastTink = now;
		mc.getSoundManager().play(new SimpleSoundInstance(state.getSoundType().getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 0.6F,
			net.minecraft.util.RandomSource.create(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
		if (!breakHintShown) {
			breakHintShown = true;
			say(mc, "That holds · only what the builder marked as smashable breaks");
		}
	}

	/**
	 * What one click with the smashable tool takes: a whole tree or bush, else the connected blocks of the same
	 * kind (a stack of barrels, a fence); {@code whole}: everything connected; {@code single}: just this block.
	 */
	public static LongArrayList breakGroup(Level world, BlockPos pos, boolean whole, boolean single) {
		LongArrayList out = new LongArrayList();
		LevelData d = level;
		BlockState s = world.getBlockState(pos);
		if (d == null || s.isAir() || !d.inside(pos)) {
			return out;
		}
		if (single) {
			out.add(pos.asLong());
			return out;
		}
		if (!whole && (Trees.log(s) || Trees.naturalLeaves(s))) {
			Trees.Tree t = Trees.find(world, pos);
			if (t != null) {
				for (BlockPos p : t.all()) {
					if (d.inside(p)) {
						out.add(p.asLong());
					}
				}
				return out;
			}
		}
		Block block = s.getBlock();
		flood(world, d, pos, out, q -> whole ? Statics.isStructure(world, q, world.getBlockState(q)) : world.getBlockState(q).getBlock() == block, 3000);
		return out;
	}

	private static void flood(Level world, LevelData d, BlockPos start, LongArrayList out, java.util.function.Predicate<BlockPos> take, int max) {
		LongOpenHashSet seen = new LongOpenHashSet();
		out.add(start.asLong());
		seen.add(start.asLong());
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int head = 0; head < out.size() && out.size() < max; head++) {
			BlockPos p = BlockPos.of(out.getLong(head));
			for (Direction dir : Direction.values()) {
				q.setWithOffset(p, dir);
				long k = q.asLong();
				if (seen.add(k) && d.inside(q) && !world.getBlockState(q).isAir() && take.test(q)) {
					out.add(k);
				}
			}
		}
	}

	/** What a click would take, for the outline under the mouse (worked out again only when the mouse moves on). */
	public static LongArrayList breakPreview(Level world, BlockPos pos, boolean whole) {
		long k = pos.asLong() * 31 + (whole ? 1 : 0) + (liftMode ? 7 : 0);
		if (k != previewKey) {
			previewKey = k;
			PREVIEW.clear();
			PREVIEW.addAll(breakGroup(world, pos, whole, liftMode && !whole));
		}
		return PREVIEW;
	}

	/** A click with the smashable tool: what it takes becomes smashable, or holds again if it already was. */
	public static int breakClick(Minecraft mc, Level world, BlockPos pos, boolean whole, boolean single) {
		LevelData d = level;
		if (d == null || mode != Mode.EDIT) {
			return 0;
		}
		// a liftable thing is one block (a crate, a barrel), unless the whole thing is asked for
		LongArrayList group = breakGroup(world, pos, whole, single || liftMode && !whole);
		if (group.isEmpty()) {
			return 0;
		}
		var set = markSet(d);
		boolean remove = set.contains(pos.asLong());
		for (long k : group) {
			if (remove) {
				set.remove(k);
			} else {
				set.add(k);
				unmarkOthers(d, k);
			}
		}
		lastBreakBlock = world.getBlockState(pos).getBlock();
		previewKey = Long.MIN_VALUE;
		save();
		play(mc, remove ? SoundEvents.STONE_PLACE : SoundEvents.AMETHYST_BLOCK_CHIME, remove ? 0.8F : 1.5F);
		say(mc, (remove ? "Holds again: " : markWord()) + group.size() + (group.size() == 1 ? " block" : " blocks"));
		Tutorial.note(liftMode ? "lift" : blastMode ? "blast" : "smash");
		return remove ? -group.size() : group.size();
	}

	/** A box dragged out with the smashable tool: what stands in it becomes smashable ({@code remove}: holds again). */
	public static int breakBox(Minecraft mc, Level world, BlockPos a, BlockPos b, boolean remove) {
		LevelData d = level;
		if (d == null || mode != Mode.EDIT) {
			return 0;
		}
		int x0 = Math.max(d.minX, Math.min(a.getX(), b.getX()));
		int x1 = Math.min(d.maxX, Math.max(a.getX(), b.getX()));
		int z0 = Math.max(d.minZ, Math.min(a.getZ(), b.getZ()));
		int z1 = Math.min(d.maxZ, Math.max(a.getZ(), b.getZ()));
		int y0 = Math.max(d.floorY, Math.min(a.getY(), b.getY()));
		int y1 = Math.min(d.topY, Math.max(a.getY(), b.getY()) + 12);
		int n = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					BlockState s = world.getBlockState(p.set(x, y, z));
					if (s.isAir()) {
						continue;
					}
					if (remove) {
						if (d.breakable.remove(p.asLong()) | d.blastable.remove(p.asLong()) | d.liftable.remove(p.asLong())) {
							n++;
						}
					} else if (Statics.isStructure(world, p, s) && markSet(d).add(p.asLong())) {
						unmarkOthers(d, p.asLong());
						n++;
					}
				}
			}
		}
		previewKey = Long.MIN_VALUE;
		save();
		play(mc, remove ? SoundEvents.STONE_PLACE : SoundEvents.AMETHYST_BLOCK_CHIME, remove ? 0.8F : 1.3F);
		say(mc, (remove ? "Holds again: " : markWord()) + n + (n == 1 ? " block" : " blocks"));
		return n;
	}

	/** Plants: flowers, grass, bushes, crops, and whole trees. */
	public static boolean isPlant(BlockState s) {
		Block b = s.getBlock();
		return b instanceof net.minecraft.world.level.block.VegetationBlock || s.is(net.minecraft.tags.BlockTags.FLOWERS)
			|| s.is(net.minecraft.tags.BlockTags.SAPLINGS) || Trees.naturalLeaves(s) || b instanceof net.minecraft.world.level.block.SugarCaneBlock
			|| b instanceof net.minecraft.world.level.block.CactusBlock || b instanceof net.minecraft.world.level.block.BambooStalkBlock;
	}

	/** Every plant on the plate (trees with their trunks) becomes smashable. */
	public static int markPlants(Minecraft mc, Level world) {
		LevelData d = level;
		if (d == null) {
			return 0;
		}
		int n = 0;
		LongOpenHashSet done = new LongOpenHashSet();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = d.floorY; y <= d.topY; y++) {
			for (int z = d.minZ; z <= d.maxZ; z++) {
				for (int x = d.minX; x <= d.maxX; x++) {
					BlockState s = world.getBlockState(p.set(x, y, z));
					if (s.isAir() || !isPlant(s) || done.contains(p.asLong())) {
						continue;
					}
					if (Trees.naturalLeaves(s)) {
						Trees.Tree t = Trees.find(world, p.immutable());
						if (t != null) {
							for (BlockPos q : t.all()) {
								done.add(q.asLong());
								if (d.inside(q) && d.breakable.add(q.asLong())) {
									n++;
								}
							}
							continue;
						}
					}
					done.add(p.asLong());
					if (d.breakable.add(p.asLong())) {
						n++;
					}
				}
			}
		}
		previewKey = Long.MIN_VALUE;
		save();
		play(mc, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F);
		say(mc, "Smashable: " + n + " plant blocks");
		Tutorial.note("plants");
		return n;
	}

	/** Every block of this kind on the plate becomes smashable. */
	public static int markAllOf(Minecraft mc, Level world, Block block) {
		LevelData d = level;
		if (d == null) {
			return 0;
		}
		int n = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = d.floorY; y <= d.topY; y++) {
			for (int z = d.minZ; z <= d.maxZ; z++) {
				for (int x = d.minX; x <= d.maxX; x++) {
					if (world.getBlockState(p.set(x, y, z)).getBlock() == block && markSet(d).add(p.asLong())) {
						unmarkOthers(d, p.asLong());
						n++;
					}
				}
			}
		}
		previewKey = Long.MIN_VALUE;
		save();
		play(mc, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F);
		say(mc, markWord() + n + " more blocks of " + block.getName().getString());
		return n;
	}

	/** Nothing on the plate may be smashed any more. */
	public static void clearBreakable(Minecraft mc) {
		LevelData d = level;
		if (d != null) {
			d.breakable.clear();
			d.blastable.clear();
			d.liftable.clear();
			previewKey = Long.MIN_VALUE;
			save();
			play(mc, SoundEvents.STONE_PLACE, 0.7F);
			say(mc, "Everything holds again");
		}
	}

	// ---------------------------------------------------------------- undo

	/** The level as it was at a step of the history (the tutorial's progress stays where it is now). */
	static void restore(Minecraft mc, String json) {
		LevelData now = level;
		if (now == null || mc.level == null || mode != Mode.EDIT) {
			return;
		}
		LevelData d = LevelData.parse(json, mc.level.registryAccess().lookupOrThrow(Registries.BLOCK));
		if (d == null) {
			return;
		}
		d.tutorialStep = now.tutorialStep;
		d.playing = false;
		level = d;
		placing = null;
		placingNew = false;
		SELECTION.clear();
		clearSelected();
		pendingSource = -1;
		previewKey = Long.MIN_VALUE;
		rebuildGhosts();
		save();
	}

	/** Blocks put back the way a history step had them (server). */
	static void setBlocks(Minecraft mc, List<BlockPos> where, List<BlockState> states) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null || where.isEmpty()) {
			return;
		}
		var dim = mc.level.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				for (int i = 0; i < where.size(); i++) {
					sl.setBlock(where.get(i), states.get(i), FLAGS);
				}
			}
		});
	}

	// ---------------------------------------------------------------- the figure's pockets

	/** What the builder carried (the tools ...), put away while the level is played. Server thread only. */
	private static java.util.@Nullable List<ItemStack> stash;

	/** Playing starts with empty pockets: what the level gives fits the hotbar. */
	private static void stashInventory(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp == null) {
				return;
			}
			var inv = sp.getInventory();
			List<ItemStack> kept = new ArrayList<>();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				kept.add(inv.getItem(i).copy());
				inv.setItem(i, ItemStack.EMPTY);
			}
			inv.setSelectedSlot(0);
			stash = kept;
		});
	}

	/** Back to building: the level's things go, the builder's own things come back. Server thread. */
	private static void restoreInventory(MinecraftServer server, UUID id) {
		ServerPlayer sp = server.getPlayerList().getPlayer(id);
		List<ItemStack> kept = stash;
		if (sp == null || kept == null) {
			return;
		}
		var inv = sp.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			inv.setItem(i, i < kept.size() ? kept.get(i) : ItemStack.EMPTY);
		}
		stash = null;
	}

	// ---------------------------------------------------------------- little helpers

	private static void gameMode(Minecraft mc, net.minecraft.world.level.GameType type) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp != null) {
				sp.setGameMode(type);
			}
		});
	}

	/** The figure stands on the start, looking the way the start looks, healthy again. */
	public static void toStart(Minecraft mc, LevelData d) {
		MinecraftServer server = mc.getSingleplayerServer();
		BlockPos s = d.start;
		if (server == null || mc.player == null || mc.level == null || s == null) {
			return;
		}
		UUID id = mc.player.getUUID();
		var dim = mc.level.dimension();
		float yaw = d.startYaw;
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			ServerLevel sl = server.getLevel(dim);
			if (sp != null && sl != null) {
				sp.removeAllEffects();
				sp.setHealth(sp.getMaxHealth());
				sp.fallDistance = 0.0;
				sp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
				sp.teleportTo(sl, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, java.util.Set.of(), yaw, 10.0F, true);
			}
		});
	}

	public static void command(Minecraft mc, String cmd) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	public static void say(Minecraft mc, String text) {
		mc.gui.hud.setOverlayMessage(Component.literal(text), false);
	}

	public static void play(Minecraft mc, SoundEvent sound, float pitch) {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
	}

	public static @Nullable ServerPlayer serverPlayer(MinecraftServer server, UUID id) {
		return server.getPlayerList().getPlayer(id);
	}
}
