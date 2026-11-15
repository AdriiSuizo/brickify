package dev.rusty.brickify.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rusty.brickify.Brickify;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One level: the base plate it is built on and the things placed on it. Kept next to the world as
 * {@code brickify_level.json}; the blocks of the level are the world itself.
 */
public final class LevelData {
	/** Something that is put together from a heap of bricks when the player builds it (a bridge, a ladder ...). */
	public static final class Blueprint {
		public int id;
		public final List<BlockPos> blocks = new ArrayList<>();
		public final List<BlockState> states = new ArrayList<>();
		/** The block the heap lies on (the heap sits on top of it). */
		public BlockPos pile = BlockPos.ZERO;

		public AABB box() {
			int x0 = Integer.MAX_VALUE;
			int y0 = Integer.MAX_VALUE;
			int z0 = Integer.MAX_VALUE;
			int x1 = Integer.MIN_VALUE;
			int y1 = Integer.MIN_VALUE;
			int z1 = Integer.MIN_VALUE;
			for (BlockPos p : this.blocks) {
				x0 = Math.min(x0, p.getX());
				y0 = Math.min(y0, p.getY());
				z0 = Math.min(z0, p.getZ());
				x1 = Math.max(x1, p.getX());
				y1 = Math.max(y1, p.getY());
				z1 = Math.max(z1, p.getZ());
			}
			return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		}

		public Vec3 centre() {
			return this.box().getCenter();
		}

		/** Where the heap lies: on top of {@link #pile}. */
		public Vec3 pileCentre() {
			return new Vec3(this.pile.getX() + 0.5, this.pile.getY() + 1.0, this.pile.getZ() + 0.5);
		}
	}

	/** Blocks that stand in the way until a switch is used: then they burst into bricks (a gate, a wall, a cage). */
	public static final class Gate {
		public int id;
		public final List<BlockPos> blocks = new ArrayList<>();
		public final List<BlockState> states = new ArrayList<>();

		public AABB box() {
			AABB b = null;
			for (BlockPos p : this.blocks) {
				AABB c = new AABB(p);
				b = b == null ? c : b.minmax(c);
			}
			return b == null ? new AABB(0, 0, 0, 1, 1, 1) : b;
		}
	}

	/** A lever, button or pressure plate of the level and what it sets off: blueprints build, gates burst. */
	public static final class Switch {
		public int id;
		public BlockPos pos = BlockPos.ZERO;
		public final List<Integer> targets = new ArrayList<>();
	}

	/**
	 * Where a mob waits when the level is played ({@link #count} of them). Linked to a trigger, switch or villager,
	 * they only appear once that went off.
	 */
	public static final class Spawn {
		public int id;
		public BlockPos pos = BlockPos.ZERO;
		public String type = "minecraft:zombie";
		public int count = 1;
	}

	/**
	 * An area on the ground (x and z, inclusive) that sets things off when the figure walks in: enemies appear, a gate
	 * bursts, an explosion goes off, a camera takes over ... {@link #once}: only the first time.
	 */
	public static final class Trigger extends AreaBox {
		public boolean once = true;
		public final List<Integer> targets = new ArrayList<>();
	}

	/** A place that explodes when what it is linked to goes off (only blocks marked "TNT only" or smashable break). */
	public static final class Blast {
		public int id;
		public BlockPos pos = BlockPos.ZERO;
		public float power = 3.0F;
	}

	/** Something lying in the level that the figure picks up by walking into it (a carrot, a key, TNT ...). */
	public static final class Pickup {
		public int id;
		public BlockPos pos = BlockPos.ZERO;
		public String item = "minecraft:golden_carrot";
		/** What finding it sets off. */
		public final List<Integer> targets = new ArrayList<>();
	}

	/**
	 * Someone who lives in the level: talks when the figure uses them, wants something ({@link #wants}, may be
	 * empty) and gives something for it ({@link #gives}). When the deal is done, what they are linked to goes off,
	 * like a switch (a blueprint builds, a gate bursts).
	 */
	public static final class Npc {
		public int id;
		public BlockPos pos = BlockPos.ZERO;
		public String kind = "farmer";
		public String wants = "minecraft:golden_carrot";
		public String gives = "minecraft:tnt";
		public int givesCount = 1;
		public final List<Integer> targets = new ArrayList<>();
		/** Their own name and words (empty: the kind's name, the usual words). */
		public String name = "";
		public String ask = "";
		public String thanks = "";
		public String after = "";
	}

	/** An area on the plate (x and z, inclusive) with the height of its ground: camera areas, trigger areas. */
	public abstract static class AreaBox {
		public int id;
		public int x0;
		public int z0;
		public int x1;
		public int z1;
		/** The height of the area's ground (NaN: worked out from the blocks). */
		public double ground = Double.NaN;

		public boolean contains(double x, double z) {
			return x >= this.x0 && x < this.x1 + 1 && z >= this.z0 && z < this.z1 + 1;
		}

		public int area() {
			return (this.x1 - this.x0 + 1) * (this.z1 - this.z0 + 1);
		}

		public Vec3 centre(double y) {
			return new Vec3((this.x0 + this.x1 + 1) / 2.0, y, (this.z0 + this.z1 + 1) / 2.0);
		}
	}

	/**
	 * While the figure is inside this area (x and z, inclusive), the camera looks from this direction. It follows
	 * the figure fully ({@link #follow} 1), a little (0.5), or stays over the middle of the area (0).
	 */
	public static final class CamZone extends AreaBox {
		public float yaw;
		public float pitch = 40.0F;
		public float dist = 11.0F;
		public float follow = 0.5F;
		/** A switch or villager: this camera only counts once that went off (-1: always). */
		public int after = -1;
		/** Where areas overlap, the higher priority wins (then one waiting for a switch, then the smaller one). */
		public int priority;
	}

	/** The base plate: x and z inclusive; nothing below {@link #floorY} belongs to the level. */
	public int minX;
	public int minZ;
	public int maxX;
	public int maxZ;
	public int floorY;
	public int topY;
	public @Nullable BlockPos start;
	public float startYaw;
	public @Nullable BlockPos goal;
	public final List<Blueprint> blueprints = new ArrayList<>();
	public final List<Gate> gates = new ArrayList<>();
	public final List<Switch> switches = new ArrayList<>();
	public final List<BlockPos> checkpoints = new ArrayList<>();
	public final List<Spawn> spawns = new ArrayList<>();
	public final List<CamZone> zones = new ArrayList<>();
	public final List<Trigger> triggers = new ArrayList<>();
	public final List<Blast> blasts = new ArrayList<>();
	public int nextId = 1;
	/** The level is being played (not edited) right now. */
	public boolean playing;
	/**
	 * Blocks the figure may smash while playing. Everything else holds: a level is unbreakable unless its builder
	 * says otherwise (barrels, a wooden fence, the flowers ...).
	 */
	public final LongOpenHashSet breakable = new LongOpenHashSet();
	/** Blocks only an explosion breaks (cracked walls): hits do nothing, TNT does. */
	public final LongOpenHashSet blastable = new LongOpenHashSet();

	/** Blocks the figure may lift, carry and set down somewhere else while playing (with the Carry On mod). */
	public final LongOpenHashSet liftable = new LongOpenHashSet();
	public final List<Pickup> pickups = new ArrayList<>();
	public final List<Npc> npcs = new ArrayList<>();
	/** The step of the tutorial this level is at, -1 when it is not (or no longer) one. */
	public int tutorialStep = -1;
	/** Named places of a generated plate the tutorial points at (the bridge, the lever ...). */
	public final Map<String, BlockPos> spots = new LinkedHashMap<>();

	public static LevelData around(BlockPos centre, int size, int ground) {
		LevelData d = new LevelData();
		int half = size / 2;
		d.minX = centre.getX() - half;
		d.minZ = centre.getZ() - half;
		d.maxX = d.minX + size - 1;
		d.maxZ = d.minZ + size - 1;
		d.floorY = ground - 6;
		d.topY = ground + 40;
		return d;
	}

	public int sizeX() {
		return this.maxX - this.minX + 1;
	}

	public int sizeZ() {
		return this.maxZ - this.minZ + 1;
	}

	public boolean inside(int x, int y, int z) {
		return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ && y >= this.floorY && y <= this.topY;
	}

	public boolean inside(BlockPos p) {
		return this.inside(p.getX(), p.getY(), p.getZ());
	}

	public Vec3 centre() {
		return new Vec3((this.minX + this.maxX + 1) / 2.0, this.floorY + 6.0, (this.minZ + this.maxZ + 1) / 2.0);
	}

	public @Nullable Blueprint blueprint(int id) {
		for (Blueprint b : this.blueprints) {
			if (b.id == id) {
				return b;
			}
		}
		return null;
	}

	public @Nullable Gate gate(int id) {
		for (Gate g : this.gates) {
			if (g.id == id) {
				return g;
			}
		}
		return null;
	}

	public @Nullable Gate gateAt(BlockPos pos) {
		for (Gate g : this.gates) {
			if (g.blocks.contains(pos)) {
				return g;
			}
		}
		return null;
	}

	public @Nullable Switch switchAt(BlockPos pos) {
		for (Switch s : this.switches) {
			if (s.pos.equals(pos)) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Something sets this off (a switch, a villager, a trigger area, a found thing): a blueprint does not build by
	 * itself, a gate does not open, enemies wait.
	 */
	public boolean triggered(int id) {
		for (Switch s : this.switches) {
			if (s.targets.contains(id)) {
				return true;
			}
		}
		for (Npc n : this.npcs) {
			if (n.targets.contains(id)) {
				return true;
			}
		}
		for (Trigger t : this.triggers) {
			if (t.targets.contains(id)) {
				return true;
			}
		}
		for (Pickup p : this.pickups) {
			if (p.targets.contains(id)) {
				return true;
			}
		}
		return false;
	}

	/** What the thing with this id sets off, if it is something that can (switch, villager, trigger, found thing). */
	public @Nullable List<Integer> targetsOf(int source) {
		for (Switch s : this.switches) {
			if (s.id == source) {
				return s.targets;
			}
		}
		Npc n = this.npc(source);
		if (n != null) {
			return n.targets;
		}
		Trigger t = this.trigger(source);
		if (t != null) {
			return t.targets;
		}
		Pickup p = this.pickup(source);
		return p != null ? p.targets : null;
	}

	/** In words, what sets things off. */
	public String sourceName(int source) {
		for (Switch s : this.switches) {
			if (s.id == source) {
				return "the switch";
			}
		}
		Npc n = this.npc(source);
		if (n != null) {
			return (n.name.isEmpty() ? "the " + LevelLife.kindName(n.kind).toLowerCase(java.util.Locale.ROOT) : n.name) + "'s deal";
		}
		if (this.trigger(source) != null) {
			return "the trigger";
		}
		Pickup p = this.pickup(source);
		return p != null ? "finding the " + LevelLife.itemName(p.item).toLowerCase(java.util.Locale.ROOT) : "something";
	}

	/** In words, what can be set off. */
	public String targetName(int id) {
		if (this.blueprint(id) != null) {
			return "blueprint";
		}
		if (this.gate(id) != null) {
			return "gate";
		}
		Spawn sp = this.spawn(id);
		if (sp != null) {
			return (sp.count > 1 ? sp.count + " " : "") + Editor.mobName(sp.type).toLowerCase(java.util.Locale.ROOT) + (sp.count > 1 ? "s" : "");
		}
		if (this.blast(id) != null) {
			return "explosion";
		}
		return "thing";
	}

	public @Nullable Trigger trigger(int id) {
		for (Trigger t : this.triggers) {
			if (t.id == id) {
				return t;
			}
		}
		return null;
	}

	public @Nullable Trigger triggerAt(double x, double z) {
		Trigger best = null;
		for (Trigger t : this.triggers) {
			if (t.contains(x, z) && (best == null || t.area() < best.area())) {
				best = t;
			}
		}
		return best;
	}

	public @Nullable Blast blast(int id) {
		for (Blast b : this.blasts) {
			if (b.id == id) {
				return b;
			}
		}
		return null;
	}

	public @Nullable Blast blastAt(BlockPos pos) {
		for (Blast b : this.blasts) {
			if (b.pos.equals(pos)) {
				return b;
			}
		}
		return null;
	}

	public @Nullable Spawn spawn(int id) {
		for (Spawn s : this.spawns) {
			if (s.id == id) {
				return s;
			}
		}
		return null;
	}

	/** Takes this id out of everything that sets things off (the thing is gone). */
	public void unlink(int id) {
		for (Switch s : this.switches) {
			s.targets.remove((Integer) id);
		}
		for (Npc n : this.npcs) {
			n.targets.remove((Integer) id);
		}
		for (Trigger t : this.triggers) {
			t.targets.remove((Integer) id);
		}
		for (Pickup p : this.pickups) {
			p.targets.remove((Integer) id);
		}
		for (CamZone z : this.zones) {
			if (z.after == id) {
				z.after = -1;
			}
		}
	}

	public @Nullable Pickup pickupAt(BlockPos pos) {
		for (Pickup p : this.pickups) {
			if (p.pos.equals(pos)) {
				return p;
			}
		}
		return null;
	}

	public @Nullable Pickup pickup(int id) {
		for (Pickup p : this.pickups) {
			if (p.id == id) {
				return p;
			}
		}
		return null;
	}

	public @Nullable Npc npc(int id) {
		for (Npc n : this.npcs) {
			if (n.id == id) {
				return n;
			}
		}
		return null;
	}

	public @Nullable Npc npcAt(BlockPos pos) {
		for (Npc n : this.npcs) {
			if (n.pos.equals(pos)) {
				return n;
			}
		}
		return null;
	}

	public @Nullable Spawn spawnAt(BlockPos pos) {
		for (Spawn s : this.spawns) {
			if (s.pos.equals(pos)) {
				return s;
			}
		}
		return null;
	}

	public @Nullable CamZone zone(int id) {
		for (CamZone z : this.zones) {
			if (z.id == id) {
				return z;
			}
		}
		return null;
	}

	/** The camera area the figure is in (the smallest, where they overlap). */
	public @Nullable CamZone zoneAt(double x, double z) {
		CamZone best = null;
		for (CamZone c : this.zones) {
			if (c.contains(x, z) && (best == null || c.area() < best.area())) {
				best = c;
			}
		}
		return best;
	}

	/** The blueprint with a block at this place, if any. */
	public @Nullable Blueprint blueprintAt(BlockPos pos) {
		for (Blueprint b : this.blueprints) {
			if (b.blocks.contains(pos)) {
				return b;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- file

	public static @Nullable LevelData load(Path path, HolderLookup<Block> blocks) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			return parse(Files.readString(path, StandardCharsets.UTF_8), blocks);
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not load the level: {}", e.toString());
			return null;
		}
	}

	/** A level from its JSON text (the file, or a step of the undo history). */
	public static @Nullable LevelData parse(String text, HolderLookup<Block> blocks) {
		try {
			JsonObject o = JsonParser.parseString(text).getAsJsonObject();
			LevelData d = new LevelData();
			JsonArray area = o.getAsJsonArray("area");
			d.minX = area.get(0).getAsInt();
			d.floorY = area.get(1).getAsInt();
			d.minZ = area.get(2).getAsInt();
			d.maxX = area.get(3).getAsInt();
			d.topY = area.get(4).getAsInt();
			d.maxZ = area.get(5).getAsInt();
			d.start = o.has("start") ? pos(o.getAsJsonArray("start")) : null;
			d.startYaw = o.has("startYaw") ? o.get("startYaw").getAsFloat() : 0.0F;
			d.goal = o.has("goal") ? pos(o.getAsJsonArray("goal")) : null;
			d.nextId = o.has("nextId") ? o.get("nextId").getAsInt() : 1;
			d.playing = o.has("playing") && o.get("playing").getAsBoolean();
			d.tutorialStep = o.has("tutorial") ? o.get("tutorial").getAsInt() : -1;
			if (o.has("breakable")) {
				for (var el : o.getAsJsonArray("breakable")) {
					d.breakable.add(el.getAsLong());
				}
			}
			if (o.has("blastable")) {
				for (var el : o.getAsJsonArray("blastable")) {
					d.blastable.add(el.getAsLong());
				}
			}
			if (o.has("liftable")) {
				for (var el : o.getAsJsonArray("liftable")) {
					d.liftable.add(el.getAsLong());
				}
			}
			if (o.has("pickups")) {
				for (var el : o.getAsJsonArray("pickups")) {
					JsonObject po = el.getAsJsonObject();
					Pickup pk = new Pickup();
					pk.id = po.get("id").getAsInt();
					pk.pos = pos(po.getAsJsonArray("pos"));
					pk.item = po.get("item").getAsString();
					ints(po, "targets", pk.targets);
					d.pickups.add(pk);
					d.nextId = Math.max(d.nextId, pk.id + 1);
				}
			}
			if (o.has("npcs")) {
				for (var el : o.getAsJsonArray("npcs")) {
					JsonObject no = el.getAsJsonObject();
					Npc n = new Npc();
					n.id = no.get("id").getAsInt();
					n.pos = pos(no.getAsJsonArray("pos"));
					n.kind = no.get("kind").getAsString();
					n.wants = no.get("wants").getAsString();
					n.gives = no.get("gives").getAsString();
					n.givesCount = no.has("count") ? no.get("count").getAsInt() : 1;
					n.name = str(no, "name");
					n.ask = str(no, "ask");
					n.thanks = str(no, "thanks");
					n.after = str(no, "after");
					for (var t : no.getAsJsonArray("targets")) {
						n.targets.add(t.getAsInt());
					}
					d.npcs.add(n);
					d.nextId = Math.max(d.nextId, n.id + 1);
				}
			}
			if (o.has("spots")) {
				for (var e : o.getAsJsonObject("spots").entrySet()) {
					d.spots.put(e.getKey(), pos(e.getValue().getAsJsonArray()));
				}
			}
			if (o.has("blueprints")) {
				for (var el : o.getAsJsonArray("blueprints")) {
					JsonObject bo = el.getAsJsonObject();
					Blueprint b = new Blueprint();
					b.id = bo.get("id").getAsInt();
					b.pile = pos(bo.getAsJsonArray("pile"));
					for (var be : bo.getAsJsonArray("blocks")) {
						JsonArray e = be.getAsJsonArray();
						b.blocks.add(new BlockPos(e.get(0).getAsInt(), e.get(1).getAsInt(), e.get(2).getAsInt()));
						b.states.add(BlockStateParser.parseForBlock(blocks, e.get(3).getAsString(), false).blockState());
					}
					d.blueprints.add(b);
					d.nextId = Math.max(d.nextId, b.id + 1);
				}
			}
			if (o.has("gates")) {
				for (var el : o.getAsJsonArray("gates")) {
					JsonObject go = el.getAsJsonObject();
					Gate g = new Gate();
					g.id = go.get("id").getAsInt();
					for (var be : go.getAsJsonArray("blocks")) {
						JsonArray e = be.getAsJsonArray();
						g.blocks.add(new BlockPos(e.get(0).getAsInt(), e.get(1).getAsInt(), e.get(2).getAsInt()));
						g.states.add(BlockStateParser.parseForBlock(blocks, e.get(3).getAsString(), false).blockState());
					}
					d.gates.add(g);
					d.nextId = Math.max(d.nextId, g.id + 1);
				}
			}
			if (o.has("switches")) {
				for (var el : o.getAsJsonArray("switches")) {
					JsonObject so = el.getAsJsonObject();
					Switch sw = new Switch();
					sw.id = so.get("id").getAsInt();
					sw.pos = pos(so.getAsJsonArray("pos"));
					for (var t : so.getAsJsonArray("targets")) {
						sw.targets.add(t.getAsInt());
					}
					d.switches.add(sw);
					d.nextId = Math.max(d.nextId, sw.id + 1);
				}
			}
			if (o.has("checkpoints")) {
				for (var el : o.getAsJsonArray("checkpoints")) {
					d.checkpoints.add(pos(el.getAsJsonArray()));
				}
			}
			if (o.has("spawns")) {
				for (var el : o.getAsJsonArray("spawns")) {
					JsonObject so = el.getAsJsonObject();
					Spawn sp = new Spawn();
					sp.pos = pos(so.getAsJsonArray("pos"));
					sp.type = so.get("type").getAsString();
					sp.id = so.has("id") ? so.get("id").getAsInt() : 0;
					sp.count = so.has("count") ? so.get("count").getAsInt() : 1;
					d.spawns.add(sp);
				}
			}
			if (o.has("zones")) {
				for (var el : o.getAsJsonArray("zones")) {
					JsonObject zo = el.getAsJsonObject();
					CamZone z = new CamZone();
					z.id = zo.get("id").getAsInt();
					JsonArray r = zo.getAsJsonArray("area");
					z.x0 = r.get(0).getAsInt();
					z.z0 = r.get(1).getAsInt();
					z.x1 = r.get(2).getAsInt();
					z.z1 = r.get(3).getAsInt();
					z.yaw = zo.get("yaw").getAsFloat();
					z.pitch = zo.get("pitch").getAsFloat();
					z.dist = zo.get("dist").getAsFloat();
					z.follow = zo.get("follow").getAsFloat();
					z.after = zo.has("after") ? zo.get("after").getAsInt() : -1;
					z.priority = zo.has("priority") ? zo.get("priority").getAsInt() : 0;
					z.ground = zo.has("ground") ? zo.get("ground").getAsDouble() : Double.NaN;
					d.zones.add(z);
					d.nextId = Math.max(d.nextId, z.id + 1);
				}
			}
			if (o.has("triggers")) {
				for (var el : o.getAsJsonArray("triggers")) {
					JsonObject to = el.getAsJsonObject();
					Trigger t = new Trigger();
					t.id = to.get("id").getAsInt();
					JsonArray r = to.getAsJsonArray("area");
					t.x0 = r.get(0).getAsInt();
					t.z0 = r.get(1).getAsInt();
					t.x1 = r.get(2).getAsInt();
					t.z1 = r.get(3).getAsInt();
					t.ground = to.has("ground") ? to.get("ground").getAsDouble() : Double.NaN;
					t.once = !to.has("once") || to.get("once").getAsBoolean();
					ints(to, "targets", t.targets);
					d.triggers.add(t);
					d.nextId = Math.max(d.nextId, t.id + 1);
				}
			}
			if (o.has("blasts")) {
				for (var el : o.getAsJsonArray("blasts")) {
					JsonObject bo = el.getAsJsonObject();
					Blast b = new Blast();
					b.id = bo.get("id").getAsInt();
					b.pos = pos(bo.getAsJsonArray("pos"));
					b.power = bo.has("power") ? bo.get("power").getAsFloat() : 3.0F;
					d.blasts.add(b);
					d.nextId = Math.max(d.nextId, b.id + 1);
				}
			}
			// older levels: enemies had no id yet
			for (Spawn sp : d.spawns) {
				d.nextId = Math.max(d.nextId, sp.id + 1);
			}
			for (Spawn sp : d.spawns) {
				if (sp.id <= 0) {
					sp.id = d.nextId++;
				}
			}
			return d;
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not load the level: {}", e.toString());
			return null;
		}
	}

	private static void ints(JsonObject o, String key, List<Integer> into) {
		if (o.has(key)) {
			for (var t : o.getAsJsonArray(key)) {
				into.add(t.getAsInt());
			}
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsString() : "";
	}

	private static JsonArray ints(List<Integer> list) {
		JsonArray a = new JsonArray();
		for (int i : list) {
			a.add(i);
		}
		return a;
	}

	public void save(Path path) {
		try {
			Files.writeString(path, this.toJson(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not save the level: {}", e.toString());
		}
	}

	/** The whole level as JSON text. */
	public String toJson() {
		JsonObject o = new JsonObject();
		JsonArray area = new JsonArray();
		area.add(this.minX);
		area.add(this.floorY);
		area.add(this.minZ);
		area.add(this.maxX);
		area.add(this.topY);
		area.add(this.maxZ);
		o.add("area", area);
		if (this.start != null) {
			o.add("start", json(this.start));
			o.addProperty("startYaw", this.startYaw);
		}
		if (this.goal != null) {
			o.add("goal", json(this.goal));
		}
		o.addProperty("nextId", this.nextId);
		o.addProperty("playing", this.playing);
		o.addProperty("tutorial", this.tutorialStep);
		JsonArray brk = new JsonArray();
		for (long k : this.breakable) {
			brk.add(k);
		}
		o.add("breakable", brk);
		JsonArray blast = new JsonArray();
		for (long k : this.blastable) {
			blast.add(k);
		}
		o.add("blastable", blast);
		JsonArray lift = new JsonArray();
		for (long k : this.liftable) {
			lift.add(k);
		}
		o.add("liftable", lift);
		JsonArray pks = new JsonArray();
		for (Pickup pk : this.pickups) {
			JsonObject po = new JsonObject();
			po.addProperty("id", pk.id);
			po.add("pos", json(pk.pos));
			po.addProperty("item", pk.item);
			po.add("targets", ints(pk.targets));
			pks.add(po);
		}
		o.add("pickups", pks);
		JsonArray ns = new JsonArray();
		for (Npc n : this.npcs) {
			JsonObject no = new JsonObject();
			no.addProperty("id", n.id);
			no.add("pos", json(n.pos));
			no.addProperty("kind", n.kind);
			no.addProperty("wants", n.wants);
			no.addProperty("gives", n.gives);
			no.addProperty("count", n.givesCount);
			no.addProperty("name", n.name);
			no.addProperty("ask", n.ask);
			no.addProperty("thanks", n.thanks);
			no.addProperty("after", n.after);
			JsonArray t = new JsonArray();
			for (int id : n.targets) {
				t.add(id);
			}
			no.add("targets", t);
			ns.add(no);
		}
		o.add("npcs", ns);
		JsonObject spotsJson = new JsonObject();
		for (var e : this.spots.entrySet()) {
			spotsJson.add(e.getKey(), json(e.getValue()));
		}
		o.add("spots", spotsJson);
		JsonArray bps = new JsonArray();
		for (Blueprint b : this.blueprints) {
			JsonObject bo = new JsonObject();
			bo.addProperty("id", b.id);
			bo.add("pile", json(b.pile));
			JsonArray blocks = new JsonArray();
			for (int i = 0; i < b.blocks.size(); i++) {
				BlockPos p = b.blocks.get(i);
				JsonArray e = new JsonArray();
				e.add(p.getX());
				e.add(p.getY());
				e.add(p.getZ());
				e.add(BlockStateParser.serialize(b.states.get(i)));
				blocks.add(e);
			}
			bo.add("blocks", blocks);
			bps.add(bo);
		}
		o.add("blueprints", bps);
		JsonArray gates = new JsonArray();
		for (Gate g : this.gates) {
			JsonObject go = new JsonObject();
			go.addProperty("id", g.id);
			JsonArray blocks = new JsonArray();
			for (int i = 0; i < g.blocks.size(); i++) {
				BlockPos p = g.blocks.get(i);
				JsonArray e = new JsonArray();
				e.add(p.getX());
				e.add(p.getY());
				e.add(p.getZ());
				e.add(BlockStateParser.serialize(g.states.get(i)));
				blocks.add(e);
			}
			go.add("blocks", blocks);
			gates.add(go);
		}
		o.add("gates", gates);
		JsonArray sws = new JsonArray();
		for (Switch sw : this.switches) {
			JsonObject so = new JsonObject();
			so.addProperty("id", sw.id);
			so.add("pos", json(sw.pos));
			JsonArray t = new JsonArray();
			for (int id : sw.targets) {
				t.add(id);
			}
			so.add("targets", t);
			sws.add(so);
		}
		o.add("switches", sws);
		JsonArray cps = new JsonArray();
		for (BlockPos p : this.checkpoints) {
			cps.add(json(p));
		}
		o.add("checkpoints", cps);
		JsonArray sps = new JsonArray();
		for (Spawn sp : this.spawns) {
			JsonObject so = new JsonObject();
			so.add("pos", json(sp.pos));
			so.addProperty("type", sp.type);
			so.addProperty("id", sp.id);
			so.addProperty("count", sp.count);
			sps.add(so);
		}
		o.add("spawns", sps);
		JsonArray zs = new JsonArray();
		for (CamZone z : this.zones) {
			JsonObject zo = new JsonObject();
			zo.addProperty("id", z.id);
			JsonArray r = new JsonArray();
			r.add(z.x0);
			r.add(z.z0);
			r.add(z.x1);
			r.add(z.z1);
			zo.add("area", r);
			zo.addProperty("yaw", z.yaw);
			zo.addProperty("pitch", z.pitch);
			zo.addProperty("dist", z.dist);
			zo.addProperty("follow", z.follow);
			zo.addProperty("after", z.after);
			zo.addProperty("priority", z.priority);
			if (!Double.isNaN(z.ground)) {
				zo.addProperty("ground", z.ground);
			}
			zs.add(zo);
		}
		o.add("zones", zs);
		JsonArray ts = new JsonArray();
		for (Trigger t : this.triggers) {
			JsonObject to = new JsonObject();
			to.addProperty("id", t.id);
			JsonArray r = new JsonArray();
			r.add(t.x0);
			r.add(t.z0);
			r.add(t.x1);
			r.add(t.z1);
			to.add("area", r);
			if (!Double.isNaN(t.ground)) {
				to.addProperty("ground", t.ground);
			}
			to.addProperty("once", t.once);
			to.add("targets", ints(t.targets));
			ts.add(to);
		}
		o.add("triggers", ts);
		JsonArray bs = new JsonArray();
		for (Blast b : this.blasts) {
			JsonObject bo = new JsonObject();
			bo.addProperty("id", b.id);
			bo.add("pos", json(b.pos));
			bo.addProperty("power", b.power);
			bs.add(bo);
		}
		o.add("blasts", bs);
		return o.toString();
	}

	private static BlockPos pos(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	private static JsonArray json(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}
}
