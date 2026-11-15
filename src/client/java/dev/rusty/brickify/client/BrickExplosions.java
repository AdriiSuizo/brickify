package dev.rusty.brickify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In the brick world nothing gets blown to dust: an explosion knocks its blocks loose as bricks that fly off and
 * lie around, ready to be built back. TNT still goes off, and blocks with contents (chests ...) still drop them.
 * Runs on the integrated server's thread; the bricks are made on the client.
 */
public final class BrickExplosions {
	private BrickExplosions() {
	}

	/** Takes the blocks that become bricks out of the explosion; returns what the explosion should still handle. */
	public static List<BlockPos> capture(ServerLevel level, Vec3 centre, float radius, List<BlockPos> targets, net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity source) {
		Minecraft mc = Minecraft.getInstance();
		if (BrickWorld.mode() != BrickWorld.OFF && source instanceof net.minecraft.world.entity.monster.Creeper) {
			int id = source.getId();
			mc.execute(() -> {
				if (mc.level != null && mc.level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity living) {
					BrickEntities.shatter(mc, living);
				}
			});
		}
		if (dev.rusty.brickify.client.editor.Editor.playing() && !targets.isEmpty()) {
			// a level being played: only what its builder marked breaks (smashable, or TNT only)
			targets = new ArrayList<>(targets);
			targets.removeIf(dev.rusty.brickify.client.editor.Editor::blastProtects);
		}
		if (BrickWorld.mode() == BrickWorld.OFF || targets.isEmpty()) {
			return targets;
		}
		if (mc.level == null || mc.level.dimension() != level.dimension()) {
			return targets;
		}
		List<BlockPos> keep = new ArrayList<>();
		List<BlockPos> bricks = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (BlockPos pos : targets) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			if (state.getBlock() instanceof TntBlock || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) {
				keep.add(pos);
				continue;
			}
			bricks.add(pos.immutable());
			states.add(state);
		}
		List<BlockPos> order = new ArrayList<>(bricks);
		order.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
		for (BlockPos pos : order) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		}
		mc.execute(() -> BrickRuins.explode(mc, centre, radius, bricks, states));
		Statics.queue(level, targets, centre);
		return keep;
	}
}
