package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everyday building in the brick world: a placed block drops in and snaps down with a squash and a click, a broken
 * block pops into a few small bricks that tumble and fade (plus a stud or two), and chopping a tree fells it.
 */
public final class BlockFx {
	private static final RandomSource RANDOM = RandomSource.create();
	private static final Set<Long> HIDDEN = ConcurrentHashMap.newKeySet();
	private static final List<Crumb> CRUMBS = new ArrayList<>();
	private static final List<Snap> SNAPS = new ArrayList<>();
	private static int nextId = -10_000_000;
	public static int crumbLife = 70;

	private static final class Crumb {
		final Body body;
		final Display.BlockDisplay display;
		final float size;
		int age;

		Crumb(Body body, Display.BlockDisplay display, float size) {
			this.body = body;
			this.display = display;
			this.size = size;
		}
	}

	private static final class Snap {
		final BlockPos pos;
		final Display.BlockDisplay display;
		final Vector3f axis;
		final float tilt;
		int age = -1;

		Snap(BlockPos pos, Display.BlockDisplay display, Vector3f axis, float tilt) {
			this.pos = pos;
			this.display = display;
			this.axis = axis;
			this.tilt = tilt;
		}
	}

	private BlockFx() {
	}

	/** Leaves a block out of the world mesh (something else draws it for a moment). */
	public static void hide(Minecraft mc, BlockPos pos) {
		if (HIDDEN.add(pos.asLong())) {
			remesh(mc, pos);
		}
	}

	public static void unhide(Minecraft mc, BlockPos pos) {
		if (HIDDEN.remove(pos.asLong())) {
			remesh(mc, pos);
		}
	}

	/** A few small bricks pop off this block (a hit that did not break it). */
	public static void crumbs(ClientLevel level, BlockPos pos, BlockState state, int n) {
		if (state.getRenderShape() != RenderShape.MODEL) {
			return;
		}
		for (int i = 0; i < n; i++) {
			crumb(level, pos, state);
		}
	}

	public static boolean hidden(BlockPos pos) {
		return !HIDDEN.isEmpty() && HIDDEN.contains(pos.asLong());
	}

	/** The local player broke a block. */
	public static void broken(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state) {
		if (BrickWorld.mode() == BrickWorld.OFF) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (state.is(BlockTags.LOGS) && TreeFall.onLogBroken(mc, pos, state, player)) {
			return;
		}
		var server = mc.getSingleplayerServer();
		if (server != null) {
			var dim = level.dimension();
			BlockPos at = pos.immutable();
			server.execute(() -> {
				var sl = server.getLevel(dim);
				if (sl != null) {
					Statics.queue(sl, java.util.List.of(at), net.minecraft.world.phys.Vec3.atCenterOf(at));
				}
			});
		}
		if (state.getRenderShape() == RenderShape.MODEL) {
			int n = 4 + RANDOM.nextInt(3);
			for (int i = 0; i < n; i++) {
				crumb(level, pos, state);
			}
		}
		boolean decor = !state.isCollisionShapeFullBlock(level, pos);
		Coins.burst(mc, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, decor ? 2 : (RANDOM.nextFloat() < 0.35F ? 1 : 0));
	}

	private static void crumb(ClientLevel level, BlockPos pos, BlockState state) {
		float size = 0.26F + RANDOM.nextFloat() * 0.14F;
		Body body = new Body(new Vector3f(size * 0.5F));
		float a = RANDOM.nextFloat() * Mth.TWO_PI;
		body.place(pos.getX() + 0.5 + Mth.cos(a) * 0.2, pos.getY() + 0.5, pos.getZ() + 0.5 + Mth.sin(a) * 0.2,
			new Quaternionf().rotationAxis(RANDOM.nextFloat() * 3.0F, Mth.cos(a), 0.5F, Mth.sin(a)).normalize());
		double out = 0.05 + RANDOM.nextFloat() * 0.1;
		body.vel.set(Mth.cos(a) * out, 0.18 + RANDOM.nextFloat() * 0.2, Mth.sin(a) * out);
		float sa = RANDOM.nextFloat() * Mth.TWO_PI;
		body.spin.set(Mth.cos(sa) * 0.3F, (RANDOM.nextFloat() - 0.5F) * 0.3F, Mth.sin(sa) * 0.3F);
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setId(nextId--);
		d.setBlockState(state);
		Crumb c = new Crumb(body, d, size);
		poseCrumb(c, size, true);
		level.addEntity(d);
		CRUMBS.add(c);
	}

	private static void poseCrumb(Crumb c, float scale, boolean jump) {
		Display.BlockDisplay d = c.display;
		d.setPos(c.body.pos.x, c.body.pos.y, c.body.pos.z);
		Quaternionf rot = new Quaternionf(c.body.rot);
		Vector3f s = new Vector3f(scale);
		d.setTransformation(new Transformation(rot.transform(new Vector3f(-0.5F * scale)), rot, s, null));
		d.setTransformationInterpolationDuration(jump ? 0 : 1);
		d.setTransformationInterpolationDelay(0);
	}

	/** The local player placed a block: hide the real one for a moment and let a copy drop in and snap. */
	public static void placed(BlockPos pos, BlockState state) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || BrickWorld.mode() == BrickWorld.OFF || state.getRenderShape() != RenderShape.MODEL) {
			return;
		}
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setId(nextId--);
		d.setBlockState(state);
		d.setPos(pos.getX(), pos.getY(), pos.getZ());
		d.setBrightnessOverride(lightAround(level, pos));
		float a = RANDOM.nextFloat() * Mth.TWO_PI;
		Snap s = new Snap(pos.immutable(), d, new Vector3f(Mth.cos(a), 0.0F, Mth.sin(a)), 0.25F + RANDOM.nextFloat() * 0.2F);
		poseSnap(s, 0);
		level.addEntity(d);
		SNAPS.add(s);
		HIDDEN.add(pos.asLong());
		remesh(mc, pos);
	}

	private static void poseSnap(Snap s, int age) {
		float y;
		float sx;
		float sy;
		float tilt;
		switch (age) {
			case 0 -> { y = 0.55F; sx = 0.8F; sy = 0.8F; tilt = 1.0F; }
			case 1 -> { y = 0.22F; sx = 0.95F; sy = 0.98F; tilt = 0.4F; }
			case 2 -> { y = 0.0F; sx = 1.1F; sy = 0.82F; tilt = 0.0F; }
			case 3 -> { y = 0.0F; sx = 0.96F; sy = 1.07F; tilt = 0.0F; }
			case 4 -> { y = 0.0F; sx = 1.02F; sy = 0.98F; tilt = 0.0F; }
			default -> { y = 0.0F; sx = 1.0F; sy = 1.0F; tilt = 0.0F; }
		}
		Quaternionf rot = new Quaternionf().rotateAxis(s.tilt * tilt, s.axis);
		Vector3f scale = new Vector3f(sx, sy, sx);
		Vector3f pivot = new Vector3f(0.5F, 0.0F, 0.5F);
		Vector3f rs = new Vector3f(pivot).mul(scale).rotate(rot);
		Vector3f translation = new Vector3f(pivot).add(0.0F, y, 0.0F).sub(rs);
		s.display.setTransformation(new Transformation(translation, rot, scale, null));
		s.display.setTransformationInterpolationDuration(age == 0 ? 0 : 1);
		s.display.setTransformationInterpolationDelay(0);
	}

	private static Brightness lightAround(ClientLevel level, BlockPos pos) {
		int sky = 0;
		int block = 0;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			n.setWithOffset(pos, d);
			sky = Math.max(sky, level.getBrightness(LightLayer.SKY, n));
			block = Math.max(block, level.getBrightness(LightLayer.BLOCK, n));
		}
		return new Brightness(block, sky);
	}

	private static void remesh(Minecraft mc, BlockPos pos) {
		mc.levelExtractor.setSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		for (int i = SNAPS.size() - 1; i >= 0; i--) {
			Snap s = SNAPS.get(i);
			s.age++;
			if (s.age == 2) {
				Hops.playClick(mc, s.pos.getX() + 0.5, s.pos.getY() + 0.5, s.pos.getZ() + 0.5, 0.6F, 1.0F + RANDOM.nextFloat() * 0.3F);
			}
			if (s.age == 5) {
				HIDDEN.remove(s.pos.asLong());
				remesh(mc, s.pos);
			}
			if (s.age > 7) {
				level.removeEntity(s.display.getId(), Entity.RemovalReason.DISCARDED);
				SNAPS.remove(i);
				continue;
			}
			poseSnap(s, s.age);
		}
		for (int i = CRUMBS.size() - 1; i >= 0; i--) {
			Crumb c = CRUMBS.get(i);
			c.age++;
			c.body.step(level, null);
			float scale = c.size;
			if (c.age > crumbLife) {
				scale *= Math.max(0.0F, 1.0F - (c.age - crumbLife) / 8.0F);
			}
			if (c.age > crumbLife + 8) {
				level.removeEntity(c.display.getId(), Entity.RemovalReason.DISCARDED);
				CRUMBS.remove(i);
				continue;
			}
			poseCrumb(c, scale, false);
		}
	}

	public static void forget() {
		CRUMBS.clear();
		SNAPS.clear();
		HIDDEN.clear();
	}
}
