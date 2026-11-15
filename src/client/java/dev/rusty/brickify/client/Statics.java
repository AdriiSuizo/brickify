package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What still holds up after something was knocked out (integrated server, end of each server tick). Every part next
 * to the gap is followed through its connected blocks: if nothing of it touches the ground any more, the whole part
 * breaks off and comes down in one piece ({@link Collapse}). Strict mode also breaks off long overhangs.
 * Natural ground (dirt, stone, sand ...) always holds; so does anything too big to follow (a castle stays a castle).
 */
public final class Statics {
	/** Parts bigger than this count as held (and are not followed further). */
	public static int limit = 3000;
	/** Strict mode: how far a block may reach out sideways (or hang down) from something that stands on the ground. */
	public static int strictSpan = 5;
	/** Blocks that may come down per server tick, all parts together. */
	public static int maxFallPerTick = 5000;
	public static boolean debug = false;

	private record Pending(ResourceKey<Level> dim, long pos, Vec3 cause) {
	}

	private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
	/** Ground blocks named one by one (tags move between versions: grass is not in the dirt tag any more). */
	private static final java.util.Set<Block> NATURAL = java.util.Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
		Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.PALE_MOSS_BLOCK, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.STONE, Blocks.GRANITE,
		Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.SAND, Blocks.RED_SAND,
		Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, Blocks.BLACKSTONE,
		Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.MAGMA_BLOCK);

	private Statics() {
	}

	/** Blocks at these places just went (call on the server thread). */
	public static void queue(ServerLevel level, Collection<BlockPos> removed, Vec3 cause) {
		if (Physics.mode() == Physics.OFF || BrickWorld.mode() == BrickWorld.OFF || dev.rusty.brickify.client.editor.Editor.editing()) {
			return;
		}
		for (BlockPos p : removed) {
			QUEUE.add(new Pending(level.dimension(), p.asLong(), cause));
		}
	}

	/** Natural ground: holds everything that stands on it. */
	public static boolean isTerrain(BlockState s) {
		if (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.SAND)
			|| s.is(BlockTags.TERRACOTTA)) {
			return true;
		}
		Block b = s.getBlock();
		if (NATURAL.contains(b)) {
			return true;
		}
		if (b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.BEDROCK || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.SNOW_BLOCK
			|| b == Blocks.POWDER_SNOW || b == Blocks.END_STONE || b == Blocks.OBSIDIAN) {
			return true;
		}
		return b.getDescriptionId().endsWith("_ore");
	}

	/** Holds like ground although it is not: unbreakable blocks, and chests (and such) with things in them. */
	public static boolean isAnchor(Level level, BlockPos pos, BlockState s) {
		if (dev.rusty.brickify.client.editor.Editor.protects(pos)) {
			// a level being played: what may not be smashed holds like ground
			return true;
		}
		if (s.getDestroySpeed(level, pos) < 0.0F) {
			return true;
		}
		if (s.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container c && !c.isEmpty()) {
			return true;
		}
		return false;
	}

	/** A block that is part of something built (or grown): can break off and fall. */
	public static boolean isStructure(Level level, BlockPos pos, BlockState s) {
		return !s.isAir() && !(s.getBlock() instanceof LiquidBlock) && !isTerrain(s) && !isAnchor(level, pos, s);
	}

	public static void serverTick(MinecraftServer server) {
		if (QUEUE.isEmpty()) {
			return;
		}
		Map<ResourceKey<Level>, List<Pending>> byDim = new HashMap<>();
		Pending p;
		while ((p = QUEUE.poll()) != null) {
			byDim.computeIfAbsent(p.dim(), k -> new ArrayList<>()).add(p);
		}
		if (Physics.mode() == Physics.OFF) {
			return;
		}
		for (var e : byDim.entrySet()) {
			ServerLevel level = server.getLevel(e.getKey());
			if (level != null) {
				check(level, e.getValue());
			}
		}
	}

	private static final class Part {
		final List<BlockPos> blocks = new ArrayList<>();
		final LongOpenHashSet set = new LongOpenHashSet();
		boolean held;
		boolean capped;
	}

	private static void check(ServerLevel level, List<Pending> pending) {
		Minecraft mc = Minecraft.getInstance();
		var client = mc.level;
		if (client == null || client.dimension() != level.dimension()) {
			return;
		}
		boolean strict = Physics.mode() == Physics.STRICT;
		if (debug) {
			dev.rusty.brickify.Brickify.LOGGER.info("[statics] check {} removed positions", pending.size());
		}
		LongOpenHashSet visited = new LongOpenHashSet();
		int fell = 0;
		for (Pending p : pending) {
			BlockPos at = BlockPos.of(p.pos());
			for (Direction d : Direction.values()) {
				if (fell > maxFallPerTick) {
					return;
				}
				BlockPos n = at.relative(d);
				if (visited.contains(n.asLong()) || !level.isLoaded(n)) {
					continue;
				}
				BlockState seed = level.getBlockState(n);
				if (Trees.naturalLeaves(seed)) {
					// a crown whose trunk is gone (nothing but leaves, touching nothing solid) comes down
					Part crown = crown(level, n, visited);
					if (!crown.held) {
						fell += drop(level, crown.blocks, p.cause());
					}
					continue;
				}
				if (!isStructure(level, n, seed)) {
					continue;
				}
				Part part = follow(level, n, visited);
				if (debug) {
					dev.rusty.brickify.Brickify.LOGGER.info("[statics] part at {} size {} held {} capped {} mode {}", n.toShortString(), part.blocks.size(), part.held, part.capped, Physics.name());
					if (part.blocks.size() > 300) {
						java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
						int minY = Integer.MAX_VALUE;
						int maxY = Integer.MIN_VALUE;
						for (BlockPos q : part.blocks) {
							hist.merge(level.getBlockState(q).getBlock().getDescriptionId(), 1, Integer::sum);
							minY = Math.min(minY, q.getY());
							maxY = Math.max(maxY, q.getY());
						}
						dev.rusty.brickify.Brickify.LOGGER.info("[statics]   y {}..{} blocks {}", minY, maxY, hist);
					}
				}
				if (!part.held) {
					List<BlockPos> logs = new ArrayList<>();
					for (BlockPos b : part.blocks) {
						if (Trees.log(level.getBlockState(b))) {
							logs.add(b);
						}
					}
					if (!logs.isEmpty()) {
						part.blocks.addAll(Trees.ownLeaves(level, logs));
					}
					fell += drop(level, part.blocks, p.cause());
				} else if (strict && !part.capped) {
					for (List<BlockPos> loose : overhangs(level, part)) {
						fell += drop(level, loose, p.cause());
					}
				}
			}
		}
	}

	/** Leaves connected to {@code start}; held as soon as they touch anything that is not air, water or leaves. */
	private static Part crown(ServerLevel level, BlockPos start, LongOpenHashSet visited) {
		Part part = new Part();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		visited.add(start.asLong());
		while (!queue.isEmpty()) {
			BlockPos b = queue.poll();
			part.blocks.add(b);
			if (part.blocks.size() > 800) {
				part.held = true;
				return part;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = b.relative(d);
				if (!level.isLoaded(n)) {
					part.held = true;
					continue;
				}
				BlockState ns = level.getBlockState(n);
				if (!Trees.naturalLeaves(ns)) {
					// grass, flowers, vines and water do not hold a crown up
					if (!ns.getCollisionShape(level, n).isEmpty()) {
						part.held = true;
					}
					continue;
				}
				if (visited.add(n.asLong())) {
					queue.add(n);
				}
			}
		}
		return part;
	}

	/** Everything connected to {@code start} (face to face; logs also corner to corner), and whether it is held. */
	private static Part follow(ServerLevel level, BlockPos start, LongOpenHashSet visited) {
		Part part = new Part();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		visited.add(start.asLong());
		part.set.add(start.asLong());
		int minY = level.getMinY();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty()) {
			BlockPos b = queue.poll();
			part.blocks.add(b);
			if (part.blocks.size() > limit) {
				part.held = true;
				part.capped = true;
				return part;
			}
			if (b.getY() <= minY + 1) {
				part.held = true;
			}
			boolean log = level.getBlockState(b).is(BlockTags.LOGS);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
						if (manhattan == 0 || manhattan > 1 && !log) {
							continue;
						}
						n.set(b.getX() + dx, b.getY() + dy, b.getZ() + dz);
						if (!level.isLoaded(n)) {
							part.held = true;
							continue;
						}
						BlockState ns = level.getBlockState(n);
						if (manhattan > 1 && !ns.is(BlockTags.LOGS)) {
							continue;
						}
						if (ns.isAir() || Trees.naturalLeaves(ns)) {
							continue;
						}
						if (manhattan == 1 && (isTerrain(ns) || isAnchor(level, n, ns))) {
							part.held = true;
							continue;
						}
						if (!isStructure(level, n, ns)) {
							continue;
						}
						long key = n.asLong();
						if (visited.add(key)) {
							part.set.add(key);
							queue.add(n.immutable());
						} else if (!part.set.contains(key)) {
							// touches a part followed before that was too big to follow: that holds it
							part.held = true;
						}
					}
				}
			}
		}
		return part;
	}

	/**
	 * Strict mode: from every block that stands on the ground (or leans on it), going up costs nothing, going
	 * sideways or hanging down costs one. What ends up further out than {@link #strictSpan} breaks off, in pieces.
	 */
	private static List<List<BlockPos>> overhangs(ServerLevel level, Part part) {
		Long2IntOpenHashMap cost = new Long2IntOpenHashMap();
		cost.defaultReturnValue(Integer.MAX_VALUE);
		LongArrayFIFOQueue zero = new LongArrayFIFOQueue();
		LongArrayFIFOQueue one = new LongArrayFIFOQueue();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (BlockPos b : part.blocks) {
			for (Direction d : Direction.values()) {
				n.setWithOffset(b, d);
				BlockState ns = level.getBlockState(n);
				if (!ns.isAir() && (isTerrain(ns) || isAnchor(level, n, ns))) {
					cost.put(b.asLong(), 0);
					zero.enqueue(b.asLong());
					break;
				}
			}
		}
		// 0-1 breadth first search, in rounds of equal cost
		int round = 0;
		while (!zero.isEmpty() || !one.isEmpty()) {
			if (zero.isEmpty()) {
				round++;
				while (!one.isEmpty()) {
					zero.enqueue(one.dequeueLong());
				}
			}
			long key = zero.dequeueLong();
			int c = cost.get(key);
			if (c != round) {
				continue;
			}
			BlockPos b = BlockPos.of(key);
			for (Direction d : Direction.values()) {
				n.setWithOffset(b, d);
				long nk = n.asLong();
				if (!part.set.contains(nk)) {
					continue;
				}
				int step = d == Direction.UP ? 0 : 1;
				int nc = c + step;
				if (nc < cost.get(nk) && nc <= strictSpan + 1) {
					cost.put(nk, nc);
					(step == 0 ? zero : one).enqueue(nk);
				}
			}
		}
		LongOpenHashSet out = new LongOpenHashSet();
		for (BlockPos b : part.blocks) {
			if (cost.get(b.asLong()) > strictSpan) {
				out.add(b.asLong());
			}
		}
		List<List<BlockPos>> groups = new ArrayList<>();
		LongOpenHashSet done = new LongOpenHashSet();
		for (long start : out) {
			if (!done.add(start)) {
				continue;
			}
			List<BlockPos> g = new ArrayList<>();
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			q.add(BlockPos.of(start));
			while (!q.isEmpty()) {
				BlockPos b = q.poll();
				g.add(b);
				for (Direction d : Direction.values()) {
					long nk = b.relative(d).asLong();
					if (out.contains(nk) && done.add(nk)) {
						q.add(BlockPos.of(nk));
					}
				}
			}
			groups.add(g);
		}
		return groups;
	}

	/** Takes the part out of the world (no drops, no updates around it) and lets it fall on the client. */
	private static int drop(ServerLevel level, List<BlockPos> blocks, Vec3 cause) {
		List<BlockPos> positions = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (BlockPos b : blocks) {
			BlockState s = level.getBlockState(b);
			if (s.isAir()) {
				continue;
			}
			positions.add(b.immutable());
			states.add(s);
		}
		if (positions.isEmpty()) {
			return 0;
		}
		List<BlockPos> order = new ArrayList<>(positions);
		order.sort(Comparator.comparingInt((BlockPos q) -> q.getY()).reversed());
		for (BlockPos b : order) {
			level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> Collapse.start(mc, positions, states, cause));
		return positions.size();
	}
}
