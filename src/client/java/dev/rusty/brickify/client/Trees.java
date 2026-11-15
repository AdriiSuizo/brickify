package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Finding one tree among others: its logs, and the leaves that belong to it. Crowns of trees standing close together
 * grow into each other, so a leaf belongs to the trunk it is nearest to (counted through leaves, ties go to us).
 */
public final class Trees {
	public static int leafReach = 6;
	public static int maxLogs = 400;

	public record Tree(List<BlockPos> logs, List<BlockPos> leaves, BlockPos base, int height) {
		public int size() {
			return this.logs.size() + this.leaves.size();
		}

		/** A bush: hardly any trunk and low. */
		public boolean bush() {
			return this.logs.size() <= 2 && this.height <= 4;
		}

		public List<BlockPos> all() {
			List<BlockPos> out = new ArrayList<>(this.logs);
			out.addAll(this.leaves);
			return out;
		}
	}

	private Trees() {
	}

	public static boolean naturalLeaves(BlockState s) {
		return s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
	}

	public static boolean log(BlockState s) {
		return s.is(BlockTags.LOGS);
	}

	/** The tree (or bush) this block is part of, or null if it is not part of one (a log in a house wall). */
	public static @Nullable Tree find(Level level, BlockPos start) {
		BlockState s = level.getBlockState(start);
		BlockPos from = start;
		if (naturalLeaves(s)) {
			from = nearestLog(level, start, leafReach);
			if (from == null) {
				List<BlockPos> leaves = leafClump(level, start, 80);
				int minY = Integer.MAX_VALUE;
				int maxY = Integer.MIN_VALUE;
				for (BlockPos p : leaves) {
					minY = Math.min(minY, p.getY());
					maxY = Math.max(maxY, p.getY());
				}
				return new Tree(List.of(), leaves, start, maxY - minY + 1);
			}
		} else if (!log(s)) {
			return null;
		}
		List<BlockPos> logs = logs(level, from);
		List<BlockPos> leaves = ownLeaves(level, logs);
		if (leaves.isEmpty()) {
			return null;
		}
		BlockPos base = logs.get(0);
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (BlockPos p : logs) {
			if (p.getY() < base.getY()) {
				base = p;
			}
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
		}
		for (BlockPos p : leaves) {
			maxY = Math.max(maxY, p.getY());
		}
		return new Tree(logs, leaves, base, maxY - minY + 1);
	}

	/** Logs connected to this one (corners too: branches go diagonally). */
	public static List<BlockPos> logs(Level level, BlockPos start) {
		List<BlockPos> out = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		seen.add(start.asLong());
		while (!queue.isEmpty() && out.size() < maxLogs) {
			BlockPos p = queue.poll();
			out.add(p);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.offset(dx, dy, dz);
						if (seen.add(n.asLong()) && log(level.getBlockState(n))) {
							queue.add(n);
						}
					}
				}
			}
		}
		return out;
	}

	private static @Nullable BlockPos nearestLog(Level level, BlockPos start, int reach) {
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		ArrayDeque<Integer> depth = new ArrayDeque<>();
		queue.add(start);
		depth.add(0);
		seen.add(start.asLong());
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			int d = depth.poll();
			for (Direction dir : Direction.values()) {
				BlockPos n = p.relative(dir);
				if (!seen.add(n.asLong())) {
					continue;
				}
				BlockState ns = level.getBlockState(n);
				if (log(ns)) {
					return n.immutable();
				}
				if (d + 1 < reach && naturalLeaves(ns)) {
					queue.add(n);
					depth.add(d + 1);
				}
			}
		}
		return null;
	}

	private static List<BlockPos> leafClump(Level level, BlockPos start, int limit) {
		List<BlockPos> out = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start.immutable());
		seen.add(start.asLong());
		while (!queue.isEmpty() && out.size() < limit) {
			BlockPos p = queue.poll();
			out.add(p);
			for (Direction dir : Direction.values()) {
				BlockPos n = p.relative(dir);
				if (seen.add(n.asLong()) && naturalLeaves(level.getBlockState(n))) {
					queue.add(n);
				}
			}
		}
		return out;
	}

	/**
	 * The natural leaves that belong to these logs: spreading through leaves from these logs and from every other
	 * log around at the same time, a leaf goes to whoever reaches it first (ties to these logs).
	 */
	public static List<BlockPos> ownLeaves(Level level, Collection<BlockPos> ours) {
		List<BlockPos> out = new ArrayList<>();
		if (ours.isEmpty()) {
			return out;
		}
		LongOpenHashSet mine = new LongOpenHashSet();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos p : ours) {
			mine.add(p.asLong());
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		int r = leafReach * 2 + 1;
		List<BlockPos> foreign = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = minX - r; x <= maxX + r; x++) {
			for (int y = minY - 2; y <= maxY + r; y++) {
				for (int z = minZ - r; z <= maxZ + r; z++) {
					m.set(x, y, z);
					if (!mine.contains(m.asLong()) && log(level.getBlockState(m))) {
						foreign.add(m.immutable());
					}
				}
			}
		}
		// owner per leaf: 1 = ours, 2 = someone else's; breadth first in rounds, ours first in each round
		Long2IntOpenHashMap owner = new Long2IntOpenHashMap();
		List<BlockPos> frontier = new ArrayList<>();
		List<Integer> who = new ArrayList<>();
		for (BlockPos p : ours) {
			frontier.add(p);
			who.add(1);
		}
		for (BlockPos p : foreign) {
			frontier.add(p);
			who.add(2);
		}
		for (int d = 0; d < leafReach && !frontier.isEmpty(); d++) {
			List<BlockPos> next = new ArrayList<>();
			List<Integer> nextWho = new ArrayList<>();
			for (int i = 0; i < frontier.size(); i++) {
				BlockPos p = frontier.get(i);
				int w = who.get(i);
				for (Direction dir : Direction.values()) {
					BlockPos n = p.relative(dir);
					long k = n.asLong();
					if (owner.containsKey(k) || mine.contains(k)) {
						continue;
					}
					if (!naturalLeaves(level.getBlockState(n))) {
						continue;
					}
					owner.put(k, w);
					next.add(n);
					nextWho.add(w);
					if (w == 1) {
						out.add(n);
					}
				}
			}
			frontier = next;
			who = nextWho;
		}
		return out;
	}
}
