package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * A part that lost its hold ({@link Statics}) comes down in one piece: it drops, tips a little (a tree tips right
 * over), and when it hits the ground it bursts into clumps of bricks that join the heap next to it.
 */
public final class Collapse {
	public static float gravity = 0.04F;
	public static int maxProbes = 500;

	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<Falling> FALLING = new ArrayList<>();
	private static int nextId = -12_000_000;

	private static final class Falling {
		final List<BlockPos> blocks;
		final List<BlockState> states;
		final List<Display.BlockDisplay> displays = new ArrayList<>();
		final int[] probes;
		final Vector3d home;
		final Vector3d com;
		final Vector3d vel = new Vector3d();
		final Vector3f axis;
		final boolean tree;
		float angle;
		float angSpeed;
		int age;

		Falling(List<BlockPos> blocks, List<BlockState> states, int[] probes, Vector3d home, Vector3f axis, boolean tree) {
			this.blocks = blocks;
			this.states = states;
			this.probes = probes;
			this.home = home;
			this.com = new Vector3d(home);
			this.axis = axis;
			this.tree = tree;
		}

		Quaternionf rot() {
			return new Quaternionf().rotationAxis(this.angle, this.axis);
		}

		Vector3d centre(int i, Quaternionf rot) {
			BlockPos p = this.blocks.get(i);
			Vector3f o = rot.transform(new Vector3f((float) (p.getX() + 0.5 - this.home.x), (float) (p.getY() + 0.5 - this.home.y), (float) (p.getZ() + 0.5 - this.home.z)));
			return new Vector3d(this.com.x + o.x, this.com.y + o.y, this.com.z + o.z);
		}
	}

	private Collapse() {
	}

	public static int live() {
		return FALLING.size();
	}

	/** The server took these blocks out because nothing holds them any more: they come down together. */
	public static void start(Minecraft mc, List<BlockPos> blocks, List<BlockState> states, Vec3 cause) {
		ClientLevel level = mc.level;
		if (level == null || blocks.isEmpty()) {
			return;
		}
		LongOpenHashSet own = new LongOpenHashSet();
		double cx = 0.0;
		double cy = 0.0;
		double cz = 0.0;
		int logs = 0;
		boolean natural = false;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < blocks.size(); i++) {
			BlockPos p = blocks.get(i);
			BlockState s = states.get(i);
			own.add(p.asLong());
			BrickRuins.missing().add(p.asLong());
			cx += p.getX() + 0.5;
			cy += p.getY() + 0.5;
			cz += p.getZ() + 0.5;
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
			if (s.is(BlockTags.LOGS)) {
				logs++;
			}
			if (s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
				natural = true;
			}
		}
		int n = blocks.size();
		Vector3d home = new Vector3d(cx / n, cy / n, cz / n);
		int height = maxY - minY + 1;
		int width = Math.max(maxX - minX, maxZ - minZ) + 1;
		boolean tree = logs > 0 && natural || height >= 4 && height > width * 1.6;
		// tips away from what knocked it loose
		Vec3 away = new Vec3(home.x - cause.x, 0.0, home.z - cause.z);
		if (away.lengthSqr() < 0.04) {
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			away = new Vec3(Mth.cos(a), 0.0, Mth.sin(a));
		}
		away = away.normalize().yRot((RANDOM.nextFloat() - 0.5F) * 0.5F);
		Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F).cross(new Vector3f((float) away.x, 0.0F, (float) away.z)).normalize();
		// probes: blocks with an open side below or around, so we notice what hits first
		List<Integer> hull = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			BlockPos p = blocks.get(i);
			if (!own.contains(p.below().asLong())) {
				hull.add(i);
				continue;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (!own.contains(p.relative(d).asLong())) {
					hull.add(i);
					break;
				}
			}
		}
		if (hull.size() > maxProbes) {
			java.util.Collections.shuffle(hull, new java.util.Random(RANDOM.nextLong()));
			hull = hull.subList(0, maxProbes);
		}
		int[] probes = hull.stream().mapToInt(Integer::intValue).toArray();
		Falling f = new Falling(new ArrayList<>(blocks), new ArrayList<>(states), probes, home, axis, tree);
		f.angSpeed = tree ? 0.01F : 0.003F + RANDOM.nextFloat() * 0.01F;
		f.vel.set(away.x * 0.015, 0.0, away.z * 0.015);
		for (int i = 0; i < n; i++) {
			BlockState s = states.get(i);
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setId(nextId--);
			d.setBlockState(s.getRenderShape() == RenderShape.MODEL ? s : Blocks.AIR.defaultBlockState());
			d.setBrightnessOverride(BrickRuins.lightAround(level, blocks.get(i)));
			f.displays.add(d);
		}
		DisplayLook.group(blocks, states, f.displays);
		pose(f, true);
		for (Display.BlockDisplay d : f.displays) {
			level.addEntity(d);
		}
		FALLING.add(f);
		BlockState sample = states.get(RANDOM.nextInt(n));
		play(mc, sample.getSoundType().getBreakSound(), home.x, home.y, home.z, 1.0F, 0.55F);
		Hops.playClick(mc, home.x, home.y, home.z, 0.6F, 0.7F);
	}

	private static void pose(Falling f, boolean jump) {
		Quaternionf rot = f.rot();
		for (int i = 0; i < f.blocks.size(); i++) {
			BlockPos p = f.blocks.get(i);
			Display.BlockDisplay d = f.displays.get(i);
			Vector3f corner = new Vector3f((float) (p.getX() - f.home.x), (float) (p.getY() - f.home.y), (float) (p.getZ() - f.home.z));
			DisplayPose.set(d, f.com.x, f.com.y, f.com.z, new Transformation(rot.transform(corner), new Quaternionf(rot), new Vector3f(1.0F), null), jump);
		}
	}

	public static void tick(Minecraft mc, int ticks) {
		ClientLevel level = mc.level;
		if (level == null || FALLING.isEmpty()) {
			return;
		}
		for (int i = FALLING.size() - 1; i >= 0; i--) {
			Falling f = FALLING.get(i);
			f.age++;
			f.vel.y = Math.max(f.vel.y - gravity, -1.2);
			f.com.add(f.vel);
			// tipping speeds up the further it leans (a tree goes right over), a block of house only a little
			f.angSpeed = Math.min(0.28F, f.angSpeed + (f.tree ? 0.003F + 0.02F * Mth.sin(Math.min(f.angle, Mth.HALF_PI)) : 0.0008F));
			f.angle += f.angSpeed;
			Quaternionf rot = f.rot();
			boolean hit = f.age > 160 || f.com.y < level.getMinY() - 16;
			if (!hit && f.age >= 2) {
				for (int k : f.probes) {
					Vector3d c = f.centre(k, rot);
					if (Body.solidAt(level, BrickRuins.missing(), c.x, c.y - 0.45, c.z) || Body.solidAt(level, BrickRuins.missing(), c.x, c.y, c.z)) {
						hit = true;
						break;
					}
				}
			}
			if (!hit) {
				pose(f, false);
				continue;
			}
			land(mc, f, rot, ticks);
			FALLING.remove(i);
		}
	}

	/** It hits the ground: bursts into clumps of bricks with the swing it had. */
	private static void land(Minecraft mc, Falling f, Quaternionf rot, int ticks) {
		List<Vector3d> centres = new ArrayList<>();
		for (int i = 0; i < f.blocks.size(); i++) {
			centres.add(f.centre(i, rot));
		}
		Vector3f angVel = new Vector3f(f.axis).mul(f.angSpeed);
		BrickRuins.crash(mc, f.tree ? "tree" : "collapse", f.blocks, f.states, f.displays, centres, rot, new Vector3d(f.vel), angVel, new Vector3d(f.com), ticks);
		int n = f.blocks.size();
		BlockState sample = f.states.get(RANDOM.nextInt(n));
		play(mc, sample.getSoundType().getBreakSound(), f.com.x, f.com.y, f.com.z, 1.2F, 0.5F + RANDOM.nextFloat() * 0.15F);
		BlockState other = f.states.get(RANDOM.nextInt(n));
		play(mc, other.getSoundType().getBreakSound(), f.com.x, f.com.y, f.com.z, 1.0F, 0.75F);
		for (int k = 0; k < Math.min(5, 1 + n / 20); k++) {
			Vector3d c = centres.get(RANDOM.nextInt(n));
			Hops.playClick(mc, c.x, c.y, c.z, 0.6F, 0.7F + RANDOM.nextFloat() * 0.6F);
		}
		Coins.burst(mc, f.com.x, f.com.y + 0.5, f.com.z, Math.min(14, 3 + n / 12));
		BrickCam.shake(Math.min(1.0F, 0.25F + n / 250.0F));
	}

	private static void play(Minecraft mc, SoundEvent event, double x, double y, double z, float volume, float pitch) {
		mc.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, RandomSource.create(), x, y, z));
		dev.rusty.brickify.client.dev.Harness.onSound(event.location().toString(), x, y, z, volume, pitch);
	}

	/** Leaving the world. */
	public static void forget() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			for (Falling f : FALLING) {
				for (Display.BlockDisplay d : f.displays) {
					level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
				}
			}
		}
		FALLING.clear();
	}
}
