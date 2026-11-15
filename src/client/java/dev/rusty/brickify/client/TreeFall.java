package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Chop the bottom of a tree in the brick world and the whole tree goes: it drops onto the stump, tips over away
 * from you, faster and faster, slams into the ground and bursts into bricks (which build back into the tree).
 */
public final class TreeFall {
	public static int maxBlocks = 700;

	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<Falling> FALLING = new ArrayList<>();
	private static int nextId = -9_000_000;

	private static final class Falling {
		final List<BlockPos> blocks = new ArrayList<>();
		final List<BlockState> states = new ArrayList<>();
		final List<Display.BlockDisplay> displays = new ArrayList<>();
		final LongOpenHashSet own = new LongOpenHashSet();
		BlockPos cut;
		BlockState cutState;
		Vec3 pivot;
		Vector3f axis;
		float angle;
		float speed = 0.012F;
		int age;
		float drop;
	}

	private TreeFall() {
	}

	/** A log was broken by the player: if a tree stands on it, it falls. Returns true if a tree fell. */
	public static boolean onLogBroken(Minecraft mc, BlockPos cut, BlockState cutState, Player player) {
		ClientLevel level = mc.level;
		MinecraftServer server = mc.getSingleplayerServer();
		if (level == null || server == null) {
			return false;
		}
		// logs above the cut, connected through any neighbour (branches go sideways and diagonally)
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		List<BlockPos> logs = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos start = cut.offset(dx, 1, dz);
				if (level.getBlockState(start).is(BlockTags.LOGS) && seen.add(start.asLong())) {
					queue.add(start);
				}
			}
		}
		while (!queue.isEmpty() && logs.size() < maxBlocks) {
			BlockPos p = queue.poll();
			logs.add(p);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.offset(dx, dy, dz);
						if (n.getY() <= cut.getY() || !seen.add(n.asLong())) {
							continue;
						}
						if (level.getBlockState(n).is(BlockTags.LOGS)) {
							queue.add(n);
						}
					}
				}
			}
		}
		if (logs.size() < 2) {
			return false;
		}
		// the leaves of this tree only: where crowns grow into each other, each leaf goes with its nearest trunk
		List<BlockPos> leaves = new ArrayList<>();
		for (BlockPos l : Trees.ownLeaves(level, logs)) {
			if (l.getY() > cut.getY()) {
				leaves.add(l);
			}
		}
		boolean natural = !leaves.isEmpty();
		if (!natural) {
			return false;
		}
		List<BlockPos> all = new ArrayList<>(logs);
		all.addAll(leaves);
		return begin(mc, level, server, cut, cutState, all, player, false);
	}

	/** A tree that was hit hard enough (brick world smashing): it comes down whole, stump included. */
	public static boolean fell(Minecraft mc, Trees.Tree tree, Player player) {
		ClientLevel level = mc.level;
		MinecraftServer server = mc.getSingleplayerServer();
		if (level == null || server == null) {
			return false;
		}
		List<BlockPos> all = new ArrayList<>();
		for (BlockPos p : tree.all()) {
			if (!p.equals(tree.base())) {
				all.add(p);
			}
		}
		if (all.isEmpty()) {
			return false;
		}
		return begin(mc, level, server, tree.base(), level.getBlockState(tree.base()), all, player, true);
	}

	private static boolean begin(Minecraft mc, ClientLevel level, MinecraftServer server, BlockPos cut, BlockState cutState, List<BlockPos> all,
		Player player, boolean removeCut) {
		Falling f = new Falling();
		// fall away from the player, unless something (a hill, a house) is in the way there: then the freest side
		Vec3 base = new Vec3(cut.getX() + 0.5, cut.getY(), cut.getZ() + 0.5);
		Vec3 away = new Vec3(base.x - player.getX(), 0.0, base.z - player.getZ());
		away = away.lengthSqr() < 1.0E-3 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
		f.pivot = new Vec3(base.x, cut.getY() + 1.0, base.z);
		f.cut = cut.immutable();
		f.cutState = cutState;
		LongOpenHashSet tree = new LongOpenHashSet();
		for (BlockPos p : all) {
			tree.add(p.asLong());
		}
		tree.add(cut.asLong());
		Vec3 bestDir = away;
		double bestScore = -1.0E9;
		for (int k = 0; k < 8; k++) {
			Vec3 dir = away.yRot(k * Mth.PI / 4.0F);
			float free = freeAngle(level, f.pivot, all, dir, tree);
			double turn = Math.min(k, 8 - k);
			double score = free - 0.12 * turn;
			if (score > bestScore) {
				bestScore = score;
				bestDir = dir;
			}
		}
		bestDir = bestDir.yRot((RANDOM.nextFloat() - 0.5F) * 0.25F);
		f.axis = new Vector3f(0.0F, 1.0F, 0.0F).cross(new Vector3f((float) bestDir.x, 0.0F, (float) bestDir.z)).normalize();
		for (BlockPos p : all) {
			BlockState s = level.getBlockState(p);
			f.blocks.add(p.immutable());
			f.states.add(s);
			f.own.add(p.asLong());
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setId(nextId--);
			d.setBlockState(s.getRenderShape() == RenderShape.MODEL ? s : Blocks.AIR.defaultBlockState());
			f.displays.add(d);
		}
		DisplayLook.group(f.blocks, f.states, f.displays);
		pose(f, true);
		for (Display.BlockDisplay d : f.displays) {
			level.addEntity(d);
		}
		for (BlockPos p : all) {
			BrickRuins.missing().add(p.asLong());
		}
		var dim = level.dimension();
		List<BlockPos> remove = new ArrayList<>(all);
		if (removeCut) {
			remove.add(cut);
			BrickRuins.missing().add(cut.asLong());
		}
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl != null) {
				for (BlockPos p : remove) {
					sl.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
				}
			}
		});
		FALLING.add(f);
		play(mc, SoundEvents.WOOD_BREAK, base.x, base.y + 1.0, base.z, 1.0F, 0.6F);
		return true;
	}

	/** How far (radians) the tree can tip over in this direction before any of its blocks hits something. */
	private static float freeAngle(ClientLevel level, Vec3 pivot, List<BlockPos> blocks, Vec3 dir, LongOpenHashSet ignore) {
		Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F).cross(new Vector3f((float) dir.x, 0.0F, (float) dir.z)).normalize();
		for (float a = 0.1F; a < 1.55F; a += 0.1F) {
			Quaternionf rot = new Quaternionf().rotationAxis(a, axis);
			for (int i = 0; i < blocks.size(); i++) {
				BlockPos p = blocks.get(i);
				if (!level.getBlockState(p).is(BlockTags.LOGS)) {
					continue;
				}
				Vector3f o = rot.transform(new Vector3f((float) (p.getX() + 0.5 - pivot.x), (float) (p.getY() + 0.5 - pivot.y), (float) (p.getZ() + 0.5 - pivot.z)));
				BlockPos q = BlockPos.containing(pivot.x + o.x, pivot.y - 1.0 + o.y - 0.45, pivot.z + o.z);
				if (!ignore.contains(q.asLong()) && !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
					return a;
				}
			}
		}
		return 1.6F;
	}

	/** Each block sits on the display at its old place, all turned together about the stump. */
	private static void pose(Falling f, boolean jump) {
		Quaternionf rot = new Quaternionf().rotationAxis(f.angle, f.axis);
		for (int i = 0; i < f.blocks.size(); i++) {
			BlockPos p = f.blocks.get(i);
			Display.BlockDisplay d = f.displays.get(i);
			Vector3f offset = new Vector3f((float) (p.getX() - f.pivot.x), (float) (p.getY() - f.pivot.y), (float) (p.getZ() - f.pivot.z));
			Vector3f translation = rot.transform(offset);
			DisplayPose.set(d, f.pivot.x, f.pivot.y - f.drop, f.pivot.z, new Transformation(translation, new Quaternionf(rot), new Vector3f(1.0F), null), jump);
		}
	}

	private static Vector3d centre(Falling f, int i, Quaternionf rot) {
		BlockPos p = f.blocks.get(i);
		Vector3f offset = new Vector3f((float) (p.getX() + 0.5 - f.pivot.x), (float) (p.getY() + 0.5 - f.pivot.y), (float) (p.getZ() + 0.5 - f.pivot.z));
		rot.transform(offset);
		return new Vector3d(f.pivot.x + offset.x, f.pivot.y - f.drop + offset.y, f.pivot.z + offset.z);
	}

	public static void tick(Minecraft mc, int ticks) {
		ClientLevel level = mc.level;
		if (level == null || FALLING.isEmpty()) {
			return;
		}
		for (int i = FALLING.size() - 1; i >= 0; i--) {
			Falling f = FALLING.get(i);
			f.age++;
			// sinks onto the stump first, then tips: the further it leans, the faster it goes
			f.drop = Math.min(1.0F, 0.06F * f.age * f.age);
			f.speed += 0.004F + 0.022F * Mth.sin(f.angle);
			f.angle += f.speed;
			Quaternionf rot = new Quaternionf().rotationAxis(f.angle, f.axis);
			boolean hit = f.angle > 1.5F;
			if (!hit && f.angle > 0.3F) {
				for (int k = 0; k < f.blocks.size(); k++) {
					if (!f.states.get(k).is(BlockTags.LOGS)) {
						continue;
					}
					Vector3d c = centre(f, k, rot);
					if (Body.solidAt(level, BrickRuins.missing(), c.x, c.y - 0.45, c.z)) {
						hit = true;
						break;
					}
				}
			}
			if (!hit) {
				pose(f, false);
				continue;
			}
			burst(mc, f, rot, ticks);
			FALLING.remove(i);
		}
	}

	/** The tree hits the ground: it bursts into clumps of bricks from where it is, with the tree's swing. */
	private static void burst(Minecraft mc, Falling f, Quaternionf rot, int ticks) {
		List<Vector3d> centres = new ArrayList<>();
		Vector3d top = null;
		for (int i = 0; i < f.blocks.size(); i++) {
			Vector3d c = centre(f, i, rot);
			Vector3d r = new Vector3d(c.x - f.pivot.x, c.y - f.pivot.y, c.z - f.pivot.z);
			centres.add(c);
			if (top == null || r.lengthSquared() > new Vector3d(top).sub(f.pivot.x, f.pivot.y, f.pivot.z).lengthSquared()) {
				top = c;
			}
		}
		Vector3d pivot = new Vector3d(f.pivot.x, f.pivot.y - f.drop, f.pivot.z);
		Vector3f angVel = new Vector3f(f.axis).mul(f.speed * 0.9F);
		BrickRuins.crash(mc, "tree", new ArrayList<>(f.blocks), new ArrayList<>(f.states), new ArrayList<>(f.displays), centres, rot,
			new Vector3d(0.0, 0.05, 0.0), angVel, pivot, ticks);
		if (f.cut != null && f.cutState != null && mc.level != null) {
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, mc.level);
			d.setId(nextId--);
			d.setBlockState(f.cutState);
			mc.level.addEntity(d);
			Vector3d at = new Vector3d(f.cut.getX() + 0.5, f.cut.getY() + 0.5, f.cut.getZ() + 0.5);
			BrickRuins.crash(mc, "tree", new ArrayList<>(List.of(f.cut)), new ArrayList<>(List.of(f.cutState)), new ArrayList<>(List.of(d)),
				new ArrayList<>(List.of(at)), new Quaternionf().rotationY(RANDOM.nextFloat() * 3.0F), new Vector3d(0.0, 0.15, 0.0),
				new Vector3f(0.2F, 0.0F, 0.1F), at, ticks);
		}
		Vector3d mid = centres.get(centres.size() / 2);
		play(mc, SoundEvents.WOOD_BREAK, mid.x, mid.y, mid.z, 1.0F, 0.5F);
		play(mc, SoundEvents.GRASS_BREAK, mid.x, mid.y, mid.z, 1.0F, 0.7F);
		if (top != null) {
			play(mc, SoundEvents.WOOD_BREAK, top.x, top.y, top.z, 1.0F, 0.8F);
			play(mc, SoundEvents.AZALEA_LEAVES_BREAK, top.x, top.y, top.z, 1.0F, 0.9F);
		}
		Coins.burst(mc, mid.x, mid.y + 0.5, mid.z, Math.min(14, 4 + f.blocks.size() / 10));
		BrickCam.shake(0.8F);
	}

	private static void play(Minecraft mc, SoundEvent event, double x, double y, double z, float volume, float pitch) {
		mc.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, RandomSource.create(), x, y, z));
		dev.rusty.brickify.client.dev.Harness.onSound(event.location().toString(), x, y, z, volume, pitch);
	}

	public static void forget() {
		FALLING.clear();
	}
}
