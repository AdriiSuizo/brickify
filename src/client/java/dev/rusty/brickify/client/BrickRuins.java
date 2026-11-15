package dev.rusty.brickify.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import dev.rusty.brickify.Brickify;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Broken buildings: every block of a building becomes a loose brick that flies off and lies around in a heap.
 * Stand next to the heap and press the build key: the bricks jump back into place one after the other, from the
 * ground up, each with a click, until the building stands again. Single player for now (the blocks are taken
 * out and put back on the integrated server); the broken state is saved next to the world.
 *
 * <p>Loose bricks fly in {@link Clump}s: a single brick, or a few bricks that stayed stuck together. Only a limited
 * number of bricks is shown at once ({@link #maxShown}); the oldest resting ones quietly shrink away when there
 * are too many, but they stay part of their heap and still fly back when it is built.
 */
public final class BrickRuins {
	public static float looseScale = 0.55F;
	public static int buildFlight = 10;
	/** The building itself goes up in a beat: every brick flies to the side of its spot, hovers, then snaps in. */
	public static int beat = 2;
	public static int snapFly = 6;
	public static int snapHover = 4;
	public static int snapIn = 2;
	/** Loose bricks drawn at once, over all heaps. */
	public static int maxShown = 550;
	/** One explosion, smash or collapse shows at most this many bricks; the rest lie "inside" the heap. */
	public static int maxShownPerBurst = 220;
	/** A new burst close to a heap that just came down joins it (so a house and its blown-out wall build as one). */
	public static int joinTicks = 120;
	public static double joinDist = 14.0;
	/** Heaps that R must not build (a level's heaps that wait for a switch, a burst gate). */
	public static java.util.function.Predicate<String> locked = n -> false;

	private static final RandomSource RANDOM = RandomSource.create();
	private static final java.util.Random KICK = new java.util.Random();
	private static final List<Ruin> RUINS = new ArrayList<>();
	private static final LongOpenHashSet MISSING = new LongOpenHashSet();
	private static int nextId = -6_000_000;
	private static int clicksThisTick;
	private static boolean loaded;
	private static boolean dirty;
	private static int lastSave;
	private static @Nullable Path savePath;
	private static int shownNow;
	private static int fadingNow;

	private static final Set<Block> GROUND = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT,
		Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE,
		Blocks.TUFF, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.CLAY, Blocks.WATER, Blocks.LAVA, Blocks.DIRT_PATH, Blocks.SHORT_GRASS,
		Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.SNOW, Blocks.BEDROCK);

	private static final class Brick {
		final BlockPos pos;
		final BlockState state;
		/** Where the brick is during the build flight (and, while loose, where it is in the world). */
		final Body body;
		Display.@Nullable BlockDisplay display;
		Clump clump;
		/** Centre of the brick relative to the centre of its clump, in block units. */
		final Vector3f local = new Vector3f();
		int order;
		int placedAt = -1;
		boolean lit;
		boolean snap;
		final Vector3d side = new Vector3d();
		float startScale = looseScale;
		/** Not drawn while it waits for its turn: its display is made when its flight starts. */
		boolean lazy;
		final Vector3d fromPos = new Vector3d();
		final Quaternionf fromRot = new Quaternionf();

		Brick(BlockPos pos, BlockState state) {
			this.pos = pos;
			this.state = state;
			float h = looseScale * 0.5F;
			this.body = new Body(new Vector3f(h, h, h));
		}
	}

	/** Bricks that move as one piece (often just one brick). */
	private static final class Clump {
		final List<Brick> members = new ArrayList<>();
		final Body body;
		/** Half size at full scale. */
		final Vector3f half1;
		float startScale = looseScale;
		int bornAt = -1000;
		boolean hidden;
		int fadeAt = -1;

		Clump(Vector3f half1) {
			this.half1 = half1;
			this.body = new Body(new Vector3f(half1).mul(looseScale));
		}

		float scaleAt(int ticks) {
			float k = Math.min(1.0F, Math.max(0.0F, (ticks - this.bornAt) / 8.0F));
			float s = Mth.lerp(k * k * (3.0F - 2.0F * k), this.startScale, looseScale);
			if (this.fadeAt >= 0) {
				s *= Math.max(0.0F, 1.0F - (ticks - this.fadeAt) / 6.0F);
			}
			return s;
		}
	}

	private static final class Ruin {
		final String name;
		final List<Brick> bricks = new ArrayList<>();
		final List<Clump> clumps = new ArrayList<>();
		Vec3 centre = Vec3.ZERO;
		int buildStart = -1;
		int placed;
		int madeAt;
		/** A build heap of a level: it lies here, away from what it builds. */
		@Nullable Vec3 pileAt;

		Ruin(String name, int madeAt) {
			this.name = name;
			this.madeAt = madeAt;
		}

		void recentre() {
			double cx = 0.0;
			double cy = 0.0;
			double cz = 0.0;
			for (Brick b : this.bricks) {
				cx += b.pos.getX() + 0.5;
				cy += b.pos.getY();
				cz += b.pos.getZ() + 0.5;
			}
			int n = Math.max(1, this.bricks.size());
			this.centre = new Vec3(cx / n, cy / n, cz / n);
		}
	}

	/** One piece of a structure the server generated (a house, the bell, a lamp ...). */
	public record Piece(String name, BoundingBox box) {
	}

	private BrickRuins() {
	}

	public static int count() {
		return RUINS.size();
	}

	/** Loose bricks over all heaps, bricks drawn right now, clumps. */
	public static int[] stats() {
		int bricks = 0;
		int clumps = 0;
		for (Ruin r : RUINS) {
			bricks += r.bricks.size();
			clumps += r.clumps.size();
		}
		return new int[] {bricks, shownNow, clumps};
	}

	// ---------------------------------------------------------------- finding buildings

	/** The village pieces around the player, nearest first (single player only). */
	public static List<Piece> pieces(Minecraft mc, int radiusChunks) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null || mc.player == null) {
			return List.of();
		}
		var dim = mc.level.dimension();
		ChunkPos centre = mc.player.chunkPosition();
		Vec3 at = mc.player.position();
		try {
			return server.submit(() -> {
				ServerLevel level = server.getLevel(dim);
				List<Piece> out = new ArrayList<>();
				if (level == null) {
					return out;
				}
				Set<StructureStart> seen = new java.util.HashSet<>();
				for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
					for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
						ChunkAccess chunk = level.getChunk(centre.x() + dx, centre.z() + dz);
						for (StructureStart start : chunk.getAllStarts().values()) {
							if (!start.isValid() || !seen.add(start)) {
								continue;
							}
							for (StructurePiece piece : start.getPieces()) {
								String name = piece instanceof PoolElementStructurePiece pool ? pool.getElement().toString() : piece.getClass().getSimpleName();
								out.add(new Piece(clean(name), piece.getBoundingBox()));
							}
						}
					}
				}
				out.sort(Comparator.comparingDouble(p -> p.box().getCenter().distToCenterSqr(at.x, at.y, at.z)));
				return out;
			}).get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not list structures: {}", e.toString());
			return List.of();
		}
	}

	private static String clean(String name) {
		int i = name.lastIndexOf(':');
		String s = i >= 0 ? name.substring(i + 1) : name;
		return s.replace("]", "");
	}

	// ---------------------------------------------------------------- clumps

	/**
	 * Splits bricks into clumps: neighbours (by their places in the building) stay stuck together, with a size the
	 * sizer picks for each clump. A clump never gets wider than three blocks.
	 */
	private static List<Clump> makeClumps(List<Brick> bricks, IntSupplier sizer) {
		Long2ObjectOpenHashMap<Brick> at = new Long2ObjectOpenHashMap<>();
		for (Brick b : bricks) {
			at.put(b.pos.asLong(), b);
		}
		List<Brick> order = new ArrayList<>(bricks);
		Collections.shuffle(order, new java.util.Random(RANDOM.nextLong()));
		LongOpenHashSet taken = new LongOpenHashSet();
		List<Clump> out = new ArrayList<>();
		ArrayDeque<Brick> queue = new ArrayDeque<>();
		for (Brick seed : order) {
			if (taken.contains(seed.pos.asLong())) {
				continue;
			}
			int want = Math.max(1, sizer.getAsInt());
			List<Brick> group = new ArrayList<>();
			taken.add(seed.pos.asLong());
			queue.clear();
			queue.add(seed);
			int minX = seed.pos.getX();
			int minY = seed.pos.getY();
			int minZ = seed.pos.getZ();
			int maxX = minX;
			int maxY = minY;
			int maxZ = minZ;
			while (!queue.isEmpty() && group.size() < want) {
				Brick b = queue.poll();
				if (Math.max(maxX, b.pos.getX()) - Math.min(minX, b.pos.getX()) > 2 || Math.max(maxY, b.pos.getY()) - Math.min(minY, b.pos.getY()) > 2
					|| Math.max(maxZ, b.pos.getZ()) - Math.min(minZ, b.pos.getZ()) > 2) {
					taken.remove(b.pos.asLong());
					continue;
				}
				group.add(b);
				minX = Math.min(minX, b.pos.getX());
				minY = Math.min(minY, b.pos.getY());
				minZ = Math.min(minZ, b.pos.getZ());
				maxX = Math.max(maxX, b.pos.getX());
				maxY = Math.max(maxY, b.pos.getY());
				maxZ = Math.max(maxZ, b.pos.getZ());
				for (Direction d : Direction.values()) {
					BlockPos n = b.pos.relative(d);
					Brick nb = at.get(n.asLong());
					if (nb == null || taken.contains(n.asLong())) {
						continue;
					}
					if (Math.max(maxX, n.getX()) - Math.min(minX, n.getX()) > 2 || Math.max(maxY, n.getY()) - Math.min(minY, n.getY()) > 2
						|| Math.max(maxZ, n.getZ()) - Math.min(minZ, n.getZ()) > 2) {
						continue;
					}
					taken.add(n.asLong());
					queue.add(nb);
				}
			}
			// whatever was queued but not used goes back
			for (Brick left : queue) {
				taken.remove(left.pos.asLong());
			}
			out.add(clumpOf(group));
		}
		return out;
	}

	private static Clump clumpOf(List<Brick> group) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Brick b : group) {
			minX = Math.min(minX, b.pos.getX());
			minY = Math.min(minY, b.pos.getY());
			minZ = Math.min(minZ, b.pos.getZ());
			maxX = Math.max(maxX, b.pos.getX());
			maxY = Math.max(maxY, b.pos.getY());
			maxZ = Math.max(maxZ, b.pos.getZ());
		}
		Clump c = new Clump(new Vector3f((maxX - minX + 1) * 0.5F, (maxY - minY + 1) * 0.5F, (maxZ - minZ + 1) * 0.5F));
		float cx = (minX + maxX + 1) * 0.5F;
		float cy = (minY + maxY + 1) * 0.5F;
		float cz = (minZ + maxZ + 1) * 0.5F;
		for (Brick b : group) {
			b.clump = c;
			b.local.set(b.pos.getX() + 0.5F - cx, b.pos.getY() + 0.5F - cy, b.pos.getZ() + 0.5F - cz);
			c.members.add(b);
		}
		return c;
	}

	/** Centre of a clump's box in the building, in world coordinates. */
	private static Vector3d homeCentre(Clump c) {
		Brick b = c.members.get(0);
		return new Vector3d(b.pos.getX() + 0.5 - b.local.x, b.pos.getY() + 0.5 - b.local.y, b.pos.getZ() + 0.5 - b.local.z);
	}

	/** Explosions: most bricks fly alone, some stay stuck in twos and threes, now and then a bigger chunk. */
	private static int blastSize() {
		float r = RANDOM.nextFloat();
		return r < 0.62F ? 1 : r < 0.84F ? 2 : r < 0.94F ? 3 + RANDOM.nextInt(2) : 5 + RANDOM.nextInt(4);
	}

	/** Something that came down whole and hit the ground: bigger pieces stay together. */
	private static int crashSize() {
		float r = RANDOM.nextFloat();
		return r < 0.35F ? 1 : r < 0.62F ? 2 + RANDOM.nextInt(2) : r < 0.86F ? 4 + RANDOM.nextInt(4) : 8 + RANDOM.nextInt(10);
	}

	// ---------------------------------------------------------------- breaking

	/** A heap that came down a moment ago near this spot, to join, or a new one. */
	private static Ruin heapAt(String name, Vec3 at, int ticks) {
		Ruin best = null;
		for (Ruin r : RUINS) {
			if (r.buildStart >= 0 || r.pileAt != null || ticks - r.madeAt > joinTicks || r.centre.distanceTo(at) > joinDist) {
				continue;
			}
			if (best == null || r.madeAt > best.madeAt) {
				best = r;
			}
		}
		if (best != null) {
			best.madeAt = ticks;
			return best;
		}
		Ruin r = new Ruin(name, ticks);
		RUINS.add(r);
		return r;
	}

	/**
	 * A build heap of a level: the bricks of {@code states} lie in a heap at {@code at}; built (R) they fly to
	 * {@code homes}. A heap of the same name that is already there is replaced.
	 */
	public static void pile(Minecraft mc, String name, List<BlockPos> homes, List<BlockState> states, Vec3 at) {
		ClientLevel level = mc.level;
		if (level == null || homes.isEmpty()) {
			return;
		}
		if (!loaded) {
			loaded = true;
			load(mc);
		}
		clearLevel(n -> n.equals(name), Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
		List<Brick> bricks = new ArrayList<>();
		for (int i = 0; i < homes.size(); i++) {
			Brick b = new Brick(homes.get(i).immutable(), states.get(i));
			MISSING.add(b.pos.asLong());
			bricks.add(b);
		}
		Ruin r = new Ruin(name, BrickEntities.ticks());
		r.pileAt = at;
		RUINS.add(r);
		layPile(level, r, bricks, BrickEntities.ticks());
		markDirty();
	}

	private static void layPile(ClientLevel level, Ruin r, List<Brick> bricks, int ticks) {
		Vec3 at = r.pileAt != null ? r.pileAt : Vec3.ZERO;
		r.bricks.addAll(bricks);
		r.centre = at;
		List<Clump> clumps = makeClumps(bricks, BrickRuins::blastSize);
		Collections.shuffle(clumps, new java.util.Random(RANDOM.nextLong()));
		double radius = 0.45 + Math.sqrt(clumps.size()) * 0.2;
		int budget = Math.max(60, Math.min(maxShownPerBurst, maxShown - shownNow));
		int shown = 0;
		int i = 0;
		for (Clump c : clumps) {
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			double rr = radius * Math.sqrt(RANDOM.nextFloat());
			double h = 0.3 + (i++ % 7) * 0.3 + RANDOM.nextFloat() * 0.25;
			Quaternionf rot = new Quaternionf().rotationY(RANDOM.nextFloat() * Mth.TWO_PI).rotateX((RANDOM.nextFloat() - 0.5F) * 0.8F);
			c.body.place(at.x + Mth.cos(a) * rr, at.y + h, at.z + Mth.sin(a) * rr, rot);
			c.startScale = looseScale;
			c.bornAt = ticks - 20;
			c.body.vel.set(0.0, -0.05, 0.0);
			c.body.spin.set((RANDOM.nextFloat() - 0.5F) * 0.05F, 0.0F, (RANDOM.nextFloat() - 0.5F) * 0.05F);
			c.body.half.set(c.half1).mul(looseScale);
			if (shown + c.members.size() <= budget) {
				shown += c.members.size();
				shownNow += c.members.size();
				for (Brick b : c.members) {
					if (b.state.getRenderShape() == RenderShape.MODEL) {
						Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
						d.setId(nextId--);
						d.setBlockState(b.state);
						b.display = d;
						level.addEntity(d);
					}
				}
				pose(c, looseScale, true);
			} else {
				c.hidden = true;
			}
			r.clumps.add(c);
		}
	}

	/**
	 * Heaps go away without building anything (the level is put back another way): those whose name matches, and those
	 * lying in the box (x and z).
	 */
	public static int clearLevel(java.util.function.Predicate<String> name, int minX, int minZ, int maxX, int maxZ) {
		ClientLevel level = Minecraft.getInstance().level;
		int n = 0;
		for (int i = RUINS.size() - 1; i >= 0; i--) {
			Ruin r = RUINS.get(i);
			boolean inBox = r.centre.x >= minX && r.centre.x <= maxX + 1 && r.centre.z >= minZ && r.centre.z <= maxZ + 1;
			if (!name.test(r.name) && !inBox) {
				continue;
			}
			for (Clump c : r.clumps) {
				if (level != null) {
					dropDisplays(level, c);
				}
			}
			for (Brick b : r.bricks) {
				if (b.display != null && level != null) {
					level.removeEntity(b.display.getId(), Entity.RemovalReason.DISCARDED);
					b.display = null;
				}
				MISSING.remove(b.pos.asLong());
			}
			RUINS.remove(i);
			n++;
		}
		if (n > 0) {
			markDirty();
		}
		return n;
	}

	/** Takes the building in this box apart: its blocks fly off as loose bricks. Returns the number of bricks. */
	public static int ruin(Minecraft mc, String name, BoundingBox box) {
		ClientLevel level = mc.level;
		MinecraftServer server = mc.getSingleplayerServer();
		if (level == null || server == null) {
			return 0;
		}
		List<Brick> bricks = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int x = box.minX(); x <= box.maxX(); x++) {
				for (int z = box.minZ(); z <= box.maxZ(); z++) {
					p.set(x, y, z);
					BlockState state = level.getBlockState(p);
					if (GROUND.contains(state.getBlock()) || MISSING.contains(p.asLong())) {
						continue;
					}
					bricks.add(new Brick(p.immutable(), state));
				}
			}
		}
		if (bricks.isEmpty()) {
			return 0;
		}
		List<BlockPos> remove = new ArrayList<>();
		for (Brick b : bricks) {
			MISSING.add(b.pos.asLong());
			remove.add(b.pos);
		}
		var dim = level.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				// top down, so nothing that hangs on something else is left floating for a moment
				remove.sort(Comparator.comparingInt((BlockPos q) -> q.getY()).reversed());
				for (BlockPos pos : remove) {
					sl.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
				}
			}
		});
		Ruin ruin = new Ruin(name, BrickEntities.ticks());
		RUINS.add(ruin);
		addLoose(level, ruin, bricks, BrickRuins::blastSize, 1.0F, null, 0.0F, BrickEntities.ticks());
		markDirty();
		return bricks.size();
	}

	/** An explosion in the brick world: the blocks it took (already gone on the server) fly off as bricks. */
	public static void explode(Minecraft mc, Vec3 centre, float radius, List<BlockPos> positions, List<BlockState> states) {
		ClientLevel level = mc.level;
		if (level == null || positions.isEmpty()) {
			return;
		}
		List<Brick> bricks = new ArrayList<>();
		for (int i = 0; i < positions.size(); i++) {
			BlockPos pos = positions.get(i);
			if (MISSING.add(pos.asLong())) {
				bricks.add(new Brick(pos, states.get(i)));
			}
		}
		if (bricks.isEmpty()) {
			return;
		}
		int ticks = BrickEntities.ticks();
		Ruin ruin = heapAt("explosion", centre, ticks);
		addLoose(level, ruin, bricks, BrickRuins::blastSize, 1.0F, centre, radius, ticks);
		Coins.burst(mc, centre.x, centre.y + 0.5, centre.z, Math.min(18, 2 + bricks.size() / 6));
		BrickCam.shake(Math.min(1.0F, radius / 4.0F));
		markDirty();
	}

	/** A smash: the hit blocks (already gone on the server) burst away from the one who hit them. */
	/** Like {@link #smash}, but the bricks make a heap of their own with this name (never joined with others). */
	public static void burst(Minecraft mc, String name, List<BlockPos> positions, List<BlockState> states, Vec3 from, float force) {
		ClientLevel level = mc.level;
		if (level == null || positions.isEmpty()) {
			return;
		}
		List<Brick> bricks = new ArrayList<>();
		for (int i = 0; i < positions.size(); i++) {
			BlockPos pos = positions.get(i);
			if (MISSING.add(pos.asLong())) {
				bricks.add(new Brick(pos, states.get(i)));
			}
		}
		if (bricks.isEmpty()) {
			return;
		}
		int ticks = BrickEntities.ticks();
		Ruin ruin = new Ruin(name, ticks);
		RUINS.add(ruin);
		addLoose(level, ruin, bricks, () -> RANDOM.nextFloat() < 0.7F ? 1 : 2, force, from, 2.6F, ticks);
		markDirty();
	}

	/** Starts building the heap with this name (whatever R would say). */
	public static boolean buildNamed(String name, int ticks) {
		for (int i = 0; i < RUINS.size(); i++) {
			if (RUINS.get(i).name.equals(name)) {
				build(i, ticks);
				return true;
			}
		}
		return false;
	}

	public static void smash(Minecraft mc, List<BlockPos> positions, List<BlockState> states, Vec3 from, float force) {
		ClientLevel level = mc.level;
		if (level == null || positions.isEmpty()) {
			return;
		}
		List<Brick> bricks = new ArrayList<>();
		for (int i = 0; i < positions.size(); i++) {
			BlockPos pos = positions.get(i);
			if (MISSING.add(pos.asLong())) {
				bricks.add(new Brick(pos, states.get(i)));
			}
		}
		if (bricks.isEmpty()) {
			return;
		}
		int ticks = BrickEntities.ticks();
		Vec3 mid = Vec3.atCenterOf(bricks.get(0).pos);
		Ruin ruin = heapAt("smash", mid, ticks);
		addLoose(level, ruin, bricks, () -> RANDOM.nextFloat() < 0.7F ? 1 : 2, force, from, 2.6F, ticks);
		markDirty();
	}

	/** Bricks become clumps that are thrown off their places (from a blast centre, or just outwards). */
	private static void addLoose(ClientLevel level, Ruin ruin, List<Brick> bricks, IntSupplier sizer, float force, @Nullable Vec3 blast, float radius, int ticks) {
		ruin.bricks.addAll(bricks);
		ruin.recentre();
		List<Clump> clumps = makeClumps(bricks, sizer);
		int shown = 0;
		// many blasts at once: each still shows a little, but together they stay near the limit (the oldest fade later)
		int floor = shownNow < maxShown * 1.5F ? Math.min(30, maxShownPerBurst) : 6;
		int budget = Math.max(floor, Math.min(maxShownPerBurst, (int) (maxShown * 1.3F) - shownNow));
		Collections.shuffle(clumps, new java.util.Random(RANDOM.nextLong()));
		for (Clump c : clumps) {
			Vector3d home = homeCentre(c);
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			Quaternionf start = new Quaternionf().rotationAxis(RANDOM.nextFloat() * 0.6F, Mth.cos(a), 0.0F, Mth.sin(a));
			c.body.place(home.x, home.y, home.z, start);
			c.startScale = looseScale;
			c.bornAt = ticks;
			Body body = c.body;
			float heavy = 1.0F / (float) Math.sqrt(c.members.size());
			if (blast != null) {
				Vec3 d = new Vec3(home.x - blast.x, home.y - blast.y, home.z - blast.z);
				double dist = d.length();
				Vec3 dir = dist < 0.01 ? new Vec3(Mth.cos(a), 1.0, Mth.sin(a)).normalize() : d.scale(1.0 / dist);
				double speed = force * (0.16 + 0.34 * Math.max(0.0, 1.0 - dist / (Math.max(radius, 0.5F) * 1.4))) * (0.8 + RANDOM.nextFloat() * 0.4) * (0.6 + 0.4 * heavy);
				body.vel.set(dir.x * speed, Math.max(dir.y, 0.0) * speed + 0.16 + RANDOM.nextFloat() * 0.18, dir.z * speed);
			} else {
				Vec3 out = new Vec3(home.x - ruin.centre.x, 0.0, home.z - ruin.centre.z);
				out = out.lengthSqr() < 0.01 ? new Vec3(Mth.cos(a), 0.0, Mth.sin(a)) : out.normalize();
				double speed = (0.04 + RANDOM.nextFloat() * 0.14) * force;
				body.vel.set(out.x * speed + (RANDOM.nextFloat() - 0.5) * 0.06, (0.12 + RANDOM.nextFloat() * 0.3) * force, out.z * speed + (RANDOM.nextFloat() - 0.5) * 0.06);
			}
			float sa = RANDOM.nextFloat() * Mth.TWO_PI;
			float spin = (0.1F + RANDOM.nextFloat() * 0.35F) * heavy;
			body.spin.set(Mth.cos(sa) * spin, (RANDOM.nextFloat() - 0.5F) * 0.3F * heavy, Mth.sin(sa) * spin);
			body.half.set(c.half1).mul(looseScale);
			if (shown + c.members.size() <= budget) {
				shown += c.members.size();
				shownNow += c.members.size();
				for (Brick b : c.members) {
					if (b.state.getRenderShape() == RenderShape.MODEL) {
						Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
						d.setId(nextId--);
						d.setBlockState(b.state);
						b.display = d;
						level.addEntity(d);
					}
				}
				pose(c, looseScale, true);
			} else {
				c.hidden = true;
			}
			ruin.clumps.add(c);
		}
	}

	/**
	 * Something that came down whole hit the ground (a broken-off part of a house, a falling tree): its blocks, already
	 * flying as displays, burst into clumps that keep the swing they had. {@code centres} are the blocks' centres now.
	 */
	public static void crash(Minecraft mc, String name, List<BlockPos> positions, List<BlockState> states, List<Display.BlockDisplay> displays,
		List<Vector3d> centres, Quaternionf rot, Vector3d vel, Vector3f angVel, Vector3d pivot, int ticks) {
		ClientLevel level = mc.level;
		if (level == null || positions.isEmpty()) {
			return;
		}
		List<Brick> bricks = new ArrayList<>();
		java.util.IdentityHashMap<Brick, Integer> index = new java.util.IdentityHashMap<>();
		for (int i = 0; i < positions.size(); i++) {
			Brick b = new Brick(positions.get(i), states.get(i));
			b.display = displays.get(i);
			MISSING.add(b.pos.asLong());
			bricks.add(b);
			index.put(b, i);
		}
		Vector3d mid = new Vector3d();
		for (Vector3d c : centres) {
			mid.add(c);
		}
		mid.div(centres.size());
		Ruin ruin = heapAt(name, new Vec3(mid.x, mid.y, mid.z), ticks);
		ruin.bricks.addAll(bricks);
		ruin.recentre();
		List<Clump> clumps = makeClumps(bricks, BrickRuins::crashSize);
		int shown = 0;
		int budget = Math.max(60, Math.min(maxShownPerBurst * 2, (int) (maxShown * 1.5F) - shownNow));
		for (Clump c : clumps) {
			// where the clump's box centre is now: the members' centres minus their offsets, turned like the whole
			Vector3d now = new Vector3d();
			for (Brick b : c.members) {
				Vector3d bc = centres.get(index.get(b));
				Vector3f off = rot.transform(new Vector3f(b.local));
				now.add(bc.x - off.x, bc.y - off.y, bc.z - off.z);
			}
			now.div(c.members.size());
			c.body.place(now.x, now.y, now.z, new Quaternionf(rot));
			Vector3d r = new Vector3d(now).sub(pivot);
			Vector3d swing = new Vector3d(angVel.x, angVel.y, angVel.z).cross(r);
			float heavy = 1.0F / (float) Math.sqrt(c.members.size());
			c.body.vel.set(vel).add(swing.mul(0.6)).add((RANDOM.nextFloat() - 0.5) * 0.16 * heavy, 0.08 + RANDOM.nextFloat() * 0.22 * heavy, (RANDOM.nextFloat() - 0.5) * 0.16 * heavy);
			if (c.body.vel.y < 0.0) {
				c.body.vel.y = -c.body.vel.y * 0.35 + 0.06;
			}
			float sa = RANDOM.nextFloat() * Mth.TWO_PI;
			float spin = (0.08F + RANDOM.nextFloat() * 0.3F) * heavy;
			c.body.spin.set(Mth.cos(sa) * spin + angVel.x * 0.5F, (RANDOM.nextFloat() - 0.5F) * 0.25F * heavy, Mth.sin(sa) * spin + angVel.z * 0.5F);
			c.startScale = 1.0F;
			c.bornAt = ticks;
			c.body.half.set(c.half1);
			if (shown + c.members.size() <= budget) {
				shown += c.members.size();
				shownNow += c.members.size();
				for (Brick b : c.members) {
					if (b.display != null) {
						b.display.setBrightnessOverride(null);
					}
				}
				pose(c, 1.0F, true);
			} else {
				c.hidden = true;
				for (Brick b : c.members) {
					if (b.display != null) {
						level.removeEntity(b.display.getId(), Entity.RemovalReason.DISCARDED);
						b.display = null;
					}
				}
			}
			ruin.clumps.add(c);
		}
		markDirty();
	}

	/** Builds every heap within the radius, starting near the origin and running outwards like a wave. */
	public static int buildAll(Vec3 origin, double radius, int ticks) {
		int n = 0;
		for (int i = 0; i < RUINS.size(); i++) {
			Ruin r = RUINS.get(i);
			if (r.buildStart >= 0 || locked.test(r.name)) {
				continue;
			}
			double d = r.centre.distanceTo(origin);
			if (d > radius) {
				continue;
			}
			build(i, ticks + (int) (d / 3.0));
			n++;
		}
		return n;
	}

	public static boolean anyBuilding() {
		for (Ruin r : RUINS) {
			if (r.buildStart >= 0) {
				return true;
			}
		}
		return false;
	}

	/** Positions of blocks currently missing (taken apart), for things that must not collide with them. */
	public static it.unimi.dsi.fastutil.longs.LongSet missing() {
		return MISSING;
	}

	/** Light around a block position (taken before the block is back), so a landing brick isn't drawn dark. */
	public static Brightness lightAround(ClientLevel level, BlockPos pos) {
		int sky = level.getBrightness(LightLayer.SKY, pos);
		int block = level.getBrightness(LightLayer.BLOCK, pos);
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			n.setWithOffset(pos, d);
			sky = Math.max(sky, level.getBrightness(LightLayer.SKY, n));
			block = Math.max(block, level.getBrightness(LightLayer.BLOCK, n));
		}
		return new Brightness(block, sky);
	}

	/** Every brick of the clump sits on its display: centred on the clump, turned like it, at the given size. */
	private static void pose(Clump c, float scale, boolean jump) {
		Body body = c.body;
		if (jump) {
			List<BlockPos> ps = new ArrayList<>(c.members.size());
			List<BlockState> ss = new ArrayList<>(c.members.size());
			List<Display.BlockDisplay> ds = new ArrayList<>(c.members.size());
			for (Brick b : c.members) {
				ps.add(b.pos);
				ss.add(b.state);
				ds.add(b.display);
			}
			DisplayLook.group(ps, ss, ds);
		}
		for (Brick b : c.members) {
			Display.BlockDisplay d = b.display;
			if (d == null) {
				continue;
			}
			Quaternionf rot = new Quaternionf(body.rot);
			Vector3f translation = rot.transform(new Vector3f(b.local).mul(scale).sub(0.5F * scale, 0.5F * scale, 0.5F * scale));
			DisplayPose.set(d, body.pos.x, body.pos.y, body.pos.z, new Transformation(translation, rot, new Vector3f(scale), null), jump);
		}
	}

	/** One brick on its own (during the build flight). */
	private static void pose(Brick b, float scale, boolean jump) {
		Display.BlockDisplay d = b.display;
		if (d == null) {
			return;
		}
		Body body = b.body;
		if (jump) {
			DisplayLook.single(d, b.pos);
		}
		Quaternionf rot = new Quaternionf(body.rot);
		Vector3f translation = rot.transform(new Vector3f(-0.5F * scale));
		DisplayPose.set(d, body.pos.x, body.pos.y, body.pos.z, new Transformation(translation, rot, new Vector3f(scale), null), jump);
	}

	private static void dropDisplays(ClientLevel level, Clump c) {
		for (Brick b : c.members) {
			if (b.display != null) {
				level.removeEntity(b.display.getId(), Entity.RemovalReason.DISCARDED);
				b.display = null;
			}
		}
	}

	// ---------------------------------------------------------------- building

	/** Index of the nearest ruin with bricks within reach of the player, or -1. */
	public static int nearestBuildable(Vec3 at, double reach) {
		double reachSq = reach * reach;
		int best = -1;
		double bestD = reachSq;
		for (int i = 0; i < RUINS.size(); i++) {
			Ruin r = RUINS.get(i);
			if (r.buildStart >= 0 || locked.test(r.name)) {
				continue;
			}
			for (Clump c : r.clumps) {
				double d = c.hidden ? at.distanceToSqr(r.centre) : at.distanceToSqr(c.body.pos.x, c.body.pos.y, c.body.pos.z);
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
		}
		return best;
	}

	private static boolean isGround(BlockState state) {
		return GROUND.contains(state.getBlock()) || Statics.isTerrain(state) || state.is(net.minecraft.tags.BlockTags.DIRT)
			|| state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.SAND);
	}

	/** Where a brick that is not drawn (it lay "inside" the heap) comes out from when its heap is built. */
	private static Vector3d outOfHeap(ClientLevel level, Ruin r) {
		List<Clump> seen = new ArrayList<>();
		for (Clump c : r.clumps) {
			if (!c.hidden) {
				seen.add(c);
			}
		}
		Vector3d at;
		if (!seen.isEmpty()) {
			Body b = seen.get(RANDOM.nextInt(seen.size())).body;
			at = new Vector3d(b.pos.x + (RANDOM.nextFloat() - 0.5) * 1.6, b.pos.y, b.pos.z + (RANDOM.nextFloat() - 0.5) * 1.6);
		} else {
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			float d = RANDOM.nextFloat() * 3.0F;
			at = new Vector3d(r.centre.x + Mth.cos(a) * d, r.centre.y + 2.0, r.centre.z + Mth.sin(a) * d);
		}
		double ground = Body.groundBelow(level, MISSING, at.x, at.y + 1.0, at.z);
		if (ground > -1.0E8) {
			at.y = ground + 0.25;
		}
		return at;
	}

	public static void build(int index, int ticks) {
		if (index < 0 || index >= RUINS.size()) {
			return;
		}
		Ruin r = RUINS.get(index);
		if (r.buildStart >= 0) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		r.buildStart = ticks;
		Vec3 c = r.centre;
		// every brick leaves its clump: it starts its flight from where it is now
		for (Clump cl : r.clumps) {
			if (cl.hidden || cl.fadeAt >= 0 && ticks - cl.fadeAt >= 6) {
				if (level != null) {
					dropDisplays(level, cl);
				}
				for (Brick b : cl.members) {
					Vector3d at = level != null ? outOfHeap(level, r) : new Vector3d(r.centre.x, r.centre.y, r.centre.z);
					b.body.place(at.x, at.y, at.z, new Quaternionf().rotationY(RANDOM.nextFloat() * Mth.TWO_PI));
					b.startScale = 0.15F;
					b.lazy = b.state.getRenderShape() == RenderShape.MODEL;
				}
				continue;
			}
			float s = cl.scaleAt(ticks);
			for (Brick b : cl.members) {
				Vector3f off = cl.body.rot.transform(new Vector3f(b.local).mul(s));
				b.body.place(cl.body.pos.x + off.x, cl.body.pos.y + off.y, cl.body.pos.z + off.z, new Quaternionf(cl.body.rot));
				b.startScale = s;
			}
		}
		r.clumps.clear();
		Comparator<Brick> upwards = Comparator.comparingInt((Brick b) -> b.pos.getY()).thenComparingDouble(b -> b.pos.distToCenterSqr(c.x, b.pos.getY(), c.z));
		List<Brick> ground = new ArrayList<>();
		List<Brick> building = new ArrayList<>();
		for (Brick b : r.bricks) {
			(isGround(b.state) ? ground : building).add(b);
			b.fromPos.set(b.body.pos);
			b.fromRot.set(b.body.rot);
		}
		ground.sort(upwards);
		building.sort(upwards);
		// the ground: quick, many at once (about a second and a half)
		float perTick = Math.max(2.0F, ground.size() / 30.0F);
		int groundEnd = 0;
		for (int i = 0; i < ground.size(); i++) {
			Brick b = ground.get(i);
			b.snap = false;
			b.order = (int) (i / perTick);
			groundEnd = Math.max(groundEnd, b.order + buildFlight);
		}
		// the building: one beat after the other, a small group per beat for big houses (about five seconds)
		int perBeat = Math.max(1, (int) Math.ceil(building.size() / 60.0));
		int first = ground.isEmpty() ? 0 : groundEnd + 2;
		int lead = snapFly + snapHover + snapIn;
		for (int i = 0; i < building.size(); i++) {
			Brick b = building.get(i);
			b.snap = true;
			int snapAt = first + lead + (i / perBeat) * beat;
			b.order = snapAt - lead;
			double dx = b.pos.getX() + 0.5 - c.x;
			double dz = b.pos.getZ() + 0.5 - c.z;
			if (Math.abs(dx) < 0.3 && Math.abs(dz) < 0.3) {
				dx = RANDOM.nextBoolean() ? 1.0 : -1.0;
			}
			if (Math.abs(dx) >= Math.abs(dz)) {
				b.side.set(Math.signum(dx), 0.0, 0.0);
			} else {
				b.side.set(0.0, 0.0, Math.signum(dz));
			}
		}
	}

	public static void tick(Minecraft mc, int ticks) {
		clicksThisTick = 0;
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (!loaded) {
			loaded = true;
			load(mc);
		}
		if (dirty && ticks - lastSave >= 40) {
			save();
		}
		if (RUINS.isEmpty()) {
			shownNow = 0;
			return;
		}
		cull(level, ticks);
		MinecraftServer server = mc.getSingleplayerServer();
		List<Brick> placeNow = new ArrayList<>();
		int shown = 0;
		for (int i = RUINS.size() - 1; i >= 0; i--) {
			Ruin r = RUINS.get(i);
			if (r.buildStart < 0) {
				shown += tickLoose(mc, level, r, ticks);
				continue;
			}
			int t = ticks - r.buildStart;
			int total = r.bricks.size();
			for (Brick b : r.bricks) {
				Body body = b.body;
				body.prevPos.set(body.pos);
				body.prevRot.set(body.rot);
				if (b.placedAt >= 0) {
					if (b.display != null && ticks - b.placedAt > 3) {
						level.removeEntity(b.display.getId(), Entity.RemovalReason.DISCARDED);
						b.display = null;
					}
					continue;
				}
				if (b.display != null) {
					shown++;
				}
				int u = t - b.order;
				if (u <= 0) {
					continue;
				}
				if (b.lazy) {
					b.lazy = false;
					Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
					d.setId(nextId--);
					d.setBlockState(b.state);
					b.display = d;
					pose(b, b.startScale, true);
					level.addEntity(d);
				}
				float f = u / (float) buildFlight;
				Vector3d target = new Vector3d(b.pos.getX() + 0.5, b.pos.getY() + 0.5, b.pos.getZ() + 0.5);
				if (!b.lit && b.display != null) {
					b.lit = true;
					b.display.setBrightnessOverride(lightAround(level, b.pos));
				}
				boolean landed = b.snap ? u >= snapFly + snapHover + snapIn : f >= 1.0F;
				if (landed) {
					body.pos.set(target);
					body.rot.identity();
					pose(b, 1.0F, false);
					b.placedAt = ticks;
					r.placed++;
					placeNow.add(b);
					float progress = r.placed / (float) total;
					if (b.snap) {
						Hops.playPlace(mc, b.state, target.x, target.y, target.z);
						Hops.playClick(mc, target.x, target.y, target.z, 0.45F, 0.9F + progress * 0.7F);
					} else {
						click(mc, target, 0.5F, 0.75F + progress * 0.6F);
					}
					continue;
				}
				if (b.snap) {
					Vector3d hover = new Vector3d(target).add(b.side.x * 1.1, 0.25, b.side.z * 1.1);
					if (u <= snapFly) {
						float k = u / (float) snapFly;
						float e = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
						b.fromPos.lerp(hover, e, body.pos);
						body.pos.y += Mth.sin(k * Mth.PI) * 0.7;
						b.fromRot.slerp(new Quaternionf(), e, body.rot);
						pose(b, Mth.lerp(e, b.startScale, 1.0F), false);
					} else if (u <= snapFly + snapHover) {
						float k = (u - snapFly) / (float) snapHover;
						body.pos.set(hover);
						body.pos.y += Mth.sin(k * Mth.PI) * 0.06;
						body.rot.identity();
						pose(b, 1.0F, false);
					} else {
						float k = (u - snapFly - snapHover) / (float) snapIn;
						hover.lerp(target, k * k, body.pos);
						body.rot.identity();
						pose(b, 1.0F, false);
					}
					continue;
				}
				float k = f * f * (3.0F - 2.0F * f);
				b.fromPos.lerp(target, k, body.pos);
				body.pos.y += Mth.sin(f * Mth.PI) * (1.2 + (b.order % 5) * 0.12);
				b.fromRot.slerp(new Quaternionf(), k, body.rot);
				pose(b, Mth.lerp(k, b.startScale, 1.0F), false);
			}
			if (r.placed >= total) {
				boolean gone = true;
				for (Brick b : r.bricks) {
					gone &= b.display == null;
				}
				if (gone) {
					Coins.burst(mc, r.centre.x, r.centre.y + 2.0, r.centre.z, Math.min(14, 3 + total / 15));
					RUINS.remove(i);
					markDirty();
				}
			}
		}
		shownNow = shown;
		if (!placeNow.isEmpty() && server != null) {
			var dim = level.dimension();
			List<Brick> batch = placeNow;
			for (Brick b : batch) {
				MISSING.remove(b.pos.asLong());
			}
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null) {
					for (Brick b : batch) {
						sl.setBlock(b.pos, b.state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
			});
		}
	}

	/** Loose clumps of a heap: physics, kicks, the odd hop. Returns how many bricks of it are drawn. */
	private static int tickLoose(Minecraft mc, ClientLevel level, Ruin r, int ticks) {
		var player = mc.player;
		int shown = 0;
		boolean near = player != null && player.position().distanceToSqr(r.centre) < 12.0 * 12.0;
		if (near && ticks % 12 == 0 && !r.clumps.isEmpty()) {
			int hops = Math.max(1, r.clumps.size() / 40);
			for (int k = 0; k < hops; k++) {
				Clump c = r.clumps.get(RANDOM.nextInt(r.clumps.size()));
				if (c.body.resting && !c.hidden && c.fadeAt < 0 && c.members.size() <= 2) {
					c.body.wake();
					c.body.vel.set((RANDOM.nextFloat() - 0.5) * 0.03, 0.14 + RANDOM.nextFloat() * 0.06, (RANDOM.nextFloat() - 0.5) * 0.03);
					c.body.spin.set((RANDOM.nextFloat() - 0.5F) * 0.2F, 0.0F, (RANDOM.nextFloat() - 0.5F) * 0.2F);
				}
			}
		}
		for (Clump c : r.clumps) {
			if (c.hidden) {
				continue;
			}
			if (c.fadeAt >= 0) {
				if (ticks - c.fadeAt >= 6) {
					dropDisplays(level, c);
					c.hidden = true;
					continue;
				}
				pose(c, c.scaleAt(ticks), false);
				shown += c.members.size();
				continue;
			}
			shown += c.members.size();
			if (player != null && c.body.kick(player.getX(), player.getY(), player.getZ(), player.getDeltaMovement().x, player.getDeltaMovement().z, KICK)) {
				click(mc, c.body.pos, 0.3F, 1.2F + KICK.nextFloat() * 0.5F);
			}
			float scale = c.scaleAt(ticks);
			if (c.body.resting) {
				c.body.step(level, MISSING);
				if (ticks - c.bornAt <= 9) {
					c.body.half.set(c.half1).mul(scale);
					pose(c, scale, false);
				}
				continue;
			}
			c.body.half.set(c.half1).mul(scale);
			float impact = c.body.step(level, MISSING);
			pose(c, scale, false);
			if (impact > 0.16F) {
				click(mc, c.body.pos, Math.min(0.7F, 0.25F + impact), (0.9F + RANDOM.nextFloat() * 0.5F) / (float) Math.cbrt(c.members.size()));
			}
		}
		return shown;
	}

	/** Too many bricks drawn: the oldest resting ones shrink away (they stay in their heap and still build back). */
	private static void cull(ClientLevel level, int ticks) {
		int over = shownNow - fadingNow - maxShown;
		fadingNow = 0;
		for (Ruin r : RUINS) {
			for (Clump c : r.clumps) {
				if (c.fadeAt >= 0 && !c.hidden) {
					fadingNow += c.members.size();
				}
			}
		}
		if (over <= 0) {
			return;
		}
		// the ones furthest from the player go first
		var player = Minecraft.getInstance().player;
		double px = player == null ? 0.0 : player.getX();
		double py = player == null ? 0.0 : player.getY();
		double pz = player == null ? 0.0 : player.getZ();
		List<Clump> resting = new ArrayList<>();
		for (Ruin r : RUINS) {
			if (r.buildStart >= 0) {
				continue;
			}
			for (Clump c : r.clumps) {
				if (!c.hidden && c.fadeAt < 0 && c.body.resting) {
					resting.add(c);
				}
			}
		}
		resting.sort(Comparator.comparingDouble((Clump c) -> c.body.pos.distanceSquared(px, py, pz)).reversed());
		int started = 0;
		for (Clump c : resting) {
			if (over <= 0 || started > 240) {
				return;
			}
			c.fadeAt = ticks;
			over -= c.members.size();
			fadingNow += c.members.size();
			started++;
		}
	}

	private static void click(Minecraft mc, Vector3d at, float volume, float pitch) {
		if (clicksThisTick >= 7) {
			return;
		}
		clicksThisTick++;
		Hops.playClick(mc, at.x, at.y, at.z, volume, pitch);
	}

	/** Puts every broken building back at once (no animation). */
	public static int restoreAll(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		List<Brick> all = new ArrayList<>();
		for (Ruin r : RUINS) {
			for (Brick b : r.bricks) {
				if (b.placedAt < 0) {
					all.add(b);
				}
				if (b.display != null && mc.level != null) {
					mc.level.removeEntity(b.display.getId(), Entity.RemovalReason.DISCARDED);
				}
			}
		}
		int n = all.size();
		RUINS.clear();
		MISSING.clear();
		if (server != null && mc.level != null) {
			var dim = mc.level.dimension();
			all.sort(Comparator.comparingInt(b -> b.pos.getY()));
			server.execute(() -> {
				ServerLevel sl = server.getLevel(dim);
				if (sl != null) {
					for (Brick b : all) {
						sl.setBlock(b.pos, b.state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
					}
				}
			});
		}
		markDirty();
		save();
		return n;
	}

	// ---------------------------------------------------------------- saving

	private static void markDirty() {
		dirty = true;
	}

	private static @Nullable Path file(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		return server == null ? null : server.getWorldPath(LevelResource.ROOT).resolve("brickify_ruins.json");
	}

	private static void save() {
		dirty = false;
		lastSave = BrickEntities.ticks();
		Path path = savePath;
		if (path == null) {
			return;
		}
		JsonArray ruins = new JsonArray();
		for (Ruin r : RUINS) {
			JsonObject o = new JsonObject();
			o.addProperty("name", r.name);
			if (r.pileAt != null) {
				JsonArray at = new JsonArray();
				at.add(r.pileAt.x);
				at.add(r.pileAt.y);
				at.add(r.pileAt.z);
				o.add("pile", at);
			}
			JsonArray blocks = new JsonArray();
			for (Brick b : r.bricks) {
				if (b.placedAt >= 0) {
					continue;
				}
				JsonArray e = new JsonArray();
				e.add(b.pos.getX());
				e.add(b.pos.getY());
				e.add(b.pos.getZ());
				e.add(BlockStateParser.serialize(b.state));
				blocks.add(e);
			}
			o.add("blocks", blocks);
			ruins.add(o);
		}
		try {
			if (ruins.isEmpty()) {
				Files.deleteIfExists(path);
			} else {
				Files.writeString(path, ruins.toString(), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not save ruins: {}", e.toString());
		}
	}

	private static void load(Minecraft mc) {
		savePath = file(mc);
		Path path = savePath;
		if (path == null || mc.level == null || !Files.isRegularFile(path)) {
			return;
		}
		try {
			var blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
			JsonArray ruins = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonArray();
			int ticks = BrickEntities.ticks();
			for (var el : ruins) {
				JsonObject o = el.getAsJsonObject();
				Ruin r = new Ruin(o.get("name").getAsString(), ticks - 10_000);
				List<Brick> bricks = new ArrayList<>();
				for (var be : o.getAsJsonArray("blocks")) {
					JsonArray e = be.getAsJsonArray();
					BlockPos pos = new BlockPos(e.get(0).getAsInt(), e.get(1).getAsInt(), e.get(2).getAsInt());
					BlockState state = BlockStateParser.parseForBlock(blocks, e.get(3).getAsString(), false).blockState();
					bricks.add(new Brick(pos, state));
					MISSING.add(pos.asLong());
				}
				if (!bricks.isEmpty()) {
					RUINS.add(r);
					if (o.has("pile")) {
						JsonArray at = o.getAsJsonArray("pile");
						r.pileAt = new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble());
						layPile(mc.level, r, bricks, ticks);
					} else {
						addLoose(mc.level, r, bricks, BrickRuins::blastSize, 0.6F, null, 0.0F, ticks);
					}
				}
			}
			Brickify.LOGGER.info("Loaded {} broken buildings", RUINS.size());
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not load ruins: {}", e.toString());
		}
	}

	/** Leaving the world: forget the displays (the save file stays, written now if anything changed). */
	public static void forget() {
		if (dirty) {
			save();
		}
		RUINS.clear();
		MISSING.clear();
		loaded = false;
		savePath = null;
		shownNow = 0;
		fadingNow = 0;
	}
}
