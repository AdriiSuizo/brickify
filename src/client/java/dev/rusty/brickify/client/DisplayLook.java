package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Makes blocks drawn as displays look like the same blocks in the world.
 *
 * <p>A display draws its block alone: every face, even the ones that touch the next block of the same falling tree,
 * and the brick logs have a coloured band at each end that the world hides between two logs (a striped trunk). Each
 * display that belongs to a group gets the sides that touch the group culled, just like in the world. Displays also
 * take the leaf and grass colour of the place their block came from instead of the default colour.
 */
public final class DisplayLook {
	private static final Int2IntOpenHashMap CULL = new Int2IntOpenHashMap();
	private static final Int2LongOpenHashMap ORIGIN = new Int2LongOpenHashMap();
	private static final Int2ObjectOpenHashMap<int[]> TINTS = new Int2ObjectOpenHashMap<>();
	private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[1]);
	private static int pruneAt;

	private DisplayLook() {
	}

	/** Blocks that move together: sides touching each other are hidden. */
	public static void group(List<BlockPos> blocks, List<BlockState> states, List<? extends Display> displays) {
		group(blocks, states, displays, null);
	}

	/** As above; with a level, also the sides resting against blocks around it (for something that stays in place). */
	public static void group(List<BlockPos> blocks, List<BlockState> states, List<? extends Display> displays, net.minecraft.world.level.@org.jspecify.annotations.Nullable Level world) {
		int n = Math.min(blocks.size(), Math.min(states.size(), displays.size()));
		Long2ObjectOpenHashMap<BlockState> at = new Long2ObjectOpenHashMap<>(n);
		for (int i = 0; i < n; i++) {
			if (displays.get(i) != null) {
				at.put(blocks.get(i).asLong(), states.get(i));
			}
		}
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int i = 0; i < n; i++) {
			Display d = displays.get(i);
			if (d == null) {
				continue;
			}
			BlockPos p = blocks.get(i);
			BlockState s = states.get(i);
			int mask = 0;
			for (Direction dir : Direction.values()) {
				BlockState ns = at.get(q.setWithOffset(p, dir).asLong());
				if (ns == null && world != null) {
					ns = world.getBlockState(q);
				}
				if (ns != null && !Block.shouldRenderFace(s, ns, dir)) {
					mask |= 1 << dir.ordinal();
				}
			}
			set(d, p, mask);
		}
	}

	/** A block on its own. */
	public static void single(Display d, BlockPos origin) {
		set(d, origin, 0);
	}

	private static void set(Display d, BlockPos origin, int mask) {
		int id = d.getId();
		if (mask == 0) {
			CULL.remove(id);
		} else {
			CULL.put(id, mask);
		}
		if (ORIGIN.get(id) != origin.asLong() || !ORIGIN.containsKey(id)) {
			ORIGIN.put(id, origin.asLong());
			TINTS.remove(id);
		}
	}

	// ---------------------------------------------------------------- render side

	/** Around the display's model being picked up for drawing. */
	public static void begin(int id) {
		CURRENT.get()[0] = CULL.get(id);
	}

	public static void end(Display.BlockDisplay entity, BlockDisplayEntityRenderState state) {
		CURRENT.get()[0] = 0;
		if (!ORIGIN.containsKey(entity.getId())) {
			return;
		}
		Display.BlockDisplay.BlockRenderState block = entity.blockRenderState();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (block == null || level == null) {
			return;
		}
		BlockState bs = block.blockState();
		List<BlockTintSource> sources = mc.getBlockColors().getTintSources(bs);
		if (sources.isEmpty()) {
			return;
		}
		IntList layers = state.blockModel.tintLayers();
		if (layers.size() != sources.size()) {
			return;
		}
		int[] tints = TINTS.get(entity.getId());
		if (tints == null || tints.length != sources.size()) {
			BlockPos origin = BlockPos.of(ORIGIN.get(entity.getId()));
			tints = new int[sources.size()];
			for (int i = 0; i < tints.length; i++) {
				tints[i] = sources.get(i).colorInWorld(bs, level, origin);
			}
			TINTS.put(entity.getId(), tints);
		}
		for (int i = 0; i < tints.length; i++) {
			layers.set(i, tints[i]);
		}
	}

	/** Sides to leave out of the block being drawn right now (bit per direction), 0 outside a display. */
	public static int cull() {
		return CURRENT.get()[0];
	}

	// ---------------------------------------------------------------- bookkeeping

	public static void tick(Minecraft mc, int ticks) {
		if (ticks < pruneAt || mc.level == null) {
			return;
		}
		pruneAt = ticks + 200;
		ClientLevel level = mc.level;
		ORIGIN.keySet().removeIf(id -> {
			if (level.getEntity(id) != null) {
				return false;
			}
			CULL.remove(id);
			TINTS.remove(id);
			return true;
		});
	}

	public static void forget() {
		CULL.clear();
		ORIGIN.clear();
		TINTS.clear();
	}
}
