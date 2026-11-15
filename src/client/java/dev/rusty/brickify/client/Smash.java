package dev.rusty.brickify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Hitting things in the brick world smashes them like in the brick games: no slow mining, one hit knocks out the
 * block and a few around it (more with a weapon or tool), and they burst into bricks that build back with the build
 * key. Natural ground (dirt, stone, sand ...) is still dug the Minecraft way. Holding the button keeps smashing.
 */
public final class Smash {
	public static int cooldown = 5;
	public static boolean enabled = true;

	private static final RandomSource RANDOM = RandomSource.create();
	private static int last = -1000;

	private Smash() {
	}

	/** Fabric's attack-block event (client and server; only the client side does anything). */
	public static InteractionResult onAttack(Player player, Level level, InteractionHand hand, BlockPos pos, Direction face) {
		if (!enabled || !level.isClientSide() || BrickWorld.mode() == BrickWorld.OFF || !(player instanceof LocalPlayer) || player.isSpectator()
			|| dev.rusty.brickify.client.editor.Editor.editing()) {
			return InteractionResult.PASS;
		}
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.ADVENTURE) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(pos);
		if (dev.rusty.brickify.client.editor.Editor.playing() && dev.rusty.brickify.client.editor.Editor.level() != null
			&& !dev.rusty.brickify.client.editor.Editor.breakableAt(pos)) {
			// a level holds, except what its builder marked
			player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
			dev.rusty.brickify.client.editor.Editor.knock(mc, pos, state);
			return InteractionResult.FAIL;
		}
		if (!smashable(level, pos, state)) {
			return InteractionResult.PASS;
		}
		int now = BrickEntities.ticks();
		if (now - last < cooldown) {
			return InteractionResult.FAIL;
		}
		last = now;
		if (Trees.log(state) || Trees.naturalLeaves(state)) {
			Trees.Tree tree = Trees.find(level, pos);
			if (tree != null) {
				hitTree(mc, player, hand, pos, tree, now);
				return InteractionResult.FAIL;
			}
		}
		smash(mc, player, hand, pos);
		return InteractionResult.FAIL;
	}

	private static final java.util.Map<Long, int[]> HITS = new java.util.HashMap<>();

	/**
	 * Trees take two or three hits (big ones three), each one shakes the whole tree; then it falls. A small bush
	 * goes at once, a thick one takes two hits.
	 */
	private static void hitTree(Minecraft mc, Player player, InteractionHand hand, BlockPos hit, Trees.Tree tree, int now) {
		boolean bush = tree.bush() || tree.logs().isEmpty();
		int need = bush ? (tree.size() <= 10 ? 1 : 2) : (tree.logs().size() > 6 || tree.size() >= 90 ? 3 : 2);
		BlockPos keyPos = tree.base();
		if (tree.logs().isEmpty()) {
			for (BlockPos p : tree.leaves()) {
				if (p.getY() < keyPos.getY() || p.getY() == keyPos.getY() && p.asLong() < keyPos.asLong()) {
					keyPos = p;
				}
			}
		}
		long key = keyPos.asLong();
		HITS.values().removeIf(h -> now - h[1] > 100);
		int[] h = HITS.computeIfAbsent(key, k -> new int[] {0, now});
		h[0]++;
		h[1] = now;
		Level level = player.level();
		BlockState hitState = level.getBlockState(hit);
		Vec3 centre = Vec3.atCenterOf(hit);
		Vec3 pivot = new Vec3(keyPos.getX() + 0.5, keyPos.getY(), keyPos.getZ() + 0.5);
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		if (h[0] < need) {
			Wobble.start(mc, key, tree.all(), pivot, player.position(), bush ? 0.12F : 0.07F);
			sound(mc, hitState.getSoundType().getHitSound(), centre, 1.0F, 0.8F + RANDOM.nextFloat() * 0.2F);
			Hops.playClick(mc, centre.x, centre.y, centre.z, 0.45F, 0.8F + RANDOM.nextFloat() * 0.3F);
			if (!tree.leaves().isEmpty() && level instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
				BlockPos leaf = tree.leaves().get(RANDOM.nextInt(tree.leaves().size()));
				BlockFx.crumbs(cl, leaf, level.getBlockState(leaf), 2);
			}
			BrickCam.shake(0.1F);
			return;
		}
		HITS.remove(key);
		Wobble.stop(mc, key);
		if (bush) {
			burst(mc, player, tree.all(), centre, player.getViewVector(1.0F), 0.6F);
			return;
		}
		if (TreeFall.fell(mc, tree, player)) {
			sound(mc, hitState.getSoundType().getBreakSound(), centre, 1.0F, 0.7F);
			Coins.burst(mc, centre.x, centre.y, centre.z, 2);
			BrickCam.shake(0.3F);
		}
	}

	private static void sound(Minecraft mc, net.minecraft.sounds.SoundEvent event, Vec3 at, float volume, float pitch) {
		mc.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, RandomSource.create(), at.x, at.y, at.z));
		dev.rusty.brickify.client.dev.Harness.onSound(event.location().toString(), at.x, at.y, at.z, volume, pitch);
	}

	/** Can be smashed: built or grown things, not natural ground, nothing unbreakable, no full chests. */
	public static boolean smashable(Level level, BlockPos pos, BlockState state) {
		if (dev.rusty.brickify.client.editor.Editor.playing() && dev.rusty.brickify.client.editor.Editor.level() != null) {
			// in a level: exactly what its builder marked (even ground)
			return !state.isAir() && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
				&& dev.rusty.brickify.client.editor.Editor.breakableAt(pos);
		}
		return Statics.isStructure(level, pos, state);
	}

	/** How many blocks one hit knocks out. */
	private static float strength(Player player, ItemStack stack) {
		float s;
		if (stack.isEmpty()) {
			s = 2.0F;
		} else if (stack.is(Items.MACE)) {
			s = 7.0F;
		} else if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) {
			s = 4.5F;
		} else if (stack.has(DataComponents.TOOL)) {
			s = 4.0F;
		} else {
			s = 2.0F;
		}
		return player.getAbilities().instabuild ? s + 1.0F : s;
	}

	/** Knocks out the hit block and a few behind and around it; returns how many went. */
	public static int smash(Minecraft mc, Player player, InteractionHand hand, BlockPos hit) {
		Level level = player.level();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return 0;
		}
		Vec3 look = player.getViewVector(1.0F);
		List<BlockPos> picked = pick(level, hit, look, strength(player, player.getItemInHand(hand)));
		if (picked.isEmpty()) {
			return 0;
		}
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		return burst(mc, player, picked, Vec3.atCenterOf(hit), look, Math.min(1.15F, 0.75F + 0.05F * picked.size()));
	}

	/** These blocks go: taken out on the server, bursting into bricks away from the hit. */
	private static int burst(Minecraft mc, Player player, List<BlockPos> picked, Vec3 centre, Vec3 look, float force) {
		Level level = player.level();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || picked.isEmpty()) {
			return 0;
		}
		List<BlockState> states = new ArrayList<>();
		for (BlockPos p : picked) {
			states.add(level.getBlockState(p));
		}
		var dim = level.dimension();
		List<BlockPos> remove = new ArrayList<>(picked);
		remove.sort(Comparator.comparingInt((BlockPos q) -> q.getY()).reversed());
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl == null) {
				return;
			}
			for (BlockPos p : remove) {
				if (!sl.getBlockState(p).isAir()) {
					sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
				}
			}
			Statics.queue(sl, remove, centre);
		});
		// the bricks burst away from the hit side
		Vec3 from = centre.subtract(look.scale(1.3)).add(0.0, -0.4, 0.0);
		BrickRuins.smash(mc, picked, states, from, force);
		BlockState first = states.get(0);
		mc.getSoundManager().play(new SimpleSoundInstance(first.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.85F + RANDOM.nextFloat() * 0.2F,
			RandomSource.create(), centre.x, centre.y, centre.z));
		dev.rusty.brickify.client.dev.Harness.onSound(first.getSoundType().getBreakSound().location().toString(), centre.x, centre.y, centre.z, 1.0F, 0.9F);
		Hops.playClick(mc, centre.x, centre.y, centre.z, 0.55F, 0.9F + RANDOM.nextFloat() * 0.3F);
		Hops.playClick(mc, centre.x, centre.y, centre.z, 0.4F, 1.3F + RANDOM.nextFloat() * 0.3F);
		Coins.burst(mc, centre.x, centre.y, centre.z, picked.size() >= 4 ? 2 : 1);
		BrickCam.shake(0.12F + 0.03F * picked.size());
		return picked.size();
	}

	/**
	 * The hit block, then its neighbours nearest to a point a little behind it (the hit pushes through), within two
	 * steps of it. Small things (torches, flowers, fences) count half.
	 */
	private static List<BlockPos> pick(Level level, BlockPos hit, Vec3 look, float strength) {
		Vec3 focus = Vec3.atCenterOf(hit).add(look.scale(0.7));
		PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.distToCenterSqr(focus.x, focus.y, focus.z)));
		LongOpenHashSet seen = new LongOpenHashSet();
		List<BlockPos> out = new ArrayList<>();
		queue.add(hit);
		seen.add(hit.asLong());
		float weight = 0.0F;
		while (!queue.isEmpty() && weight < strength) {
			BlockPos p = queue.poll();
			BlockState s = level.getBlockState(p);
			if (!smashable(level, p, s)) {
				continue;
			}
			out.add(p);
			weight += s.isCollisionShapeFullBlock(level, p) ? 1.0F : 0.5F;
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (n.distManhattan(hit) <= 2 && seen.add(n.asLong())) {
					queue.add(n);
				}
			}
		}
		return out;
	}
}
