package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import dev.rusty.brickify.Brickify;
import dev.rusty.brickify.client.dev.Harness;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The jump each visible block makes when the wave reaches it: a brick copy pops out of the block, flies up,
 * slams back into place with a click and squashes a little. Drawn with client-only block displays, so the
 * normal block renderer (and with it the brick model) draws them.
 */
public final class Hops {
	public static final SoundEvent CLICK = SoundEvent.createVariableRangeEvent(Brickify.id("click"));
	/** The tick on which a hop lands (the chunk mesh turns to bricks then). */
	public static final int LAND_TICK = 3;
	/** Ticks a landed hop stays on top of its block, covering the re-mesh. */
	public static int hold = 4;
	public static int maxPerTick = 160;
	public static int maxClicksPerTick = 9;
	public static float lift = 0.62F;

	private static final List<Hop> HOPS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static int nextId = -2_000_000;

	private static final class Hop {
		final Display.BlockDisplay entity;
		final BlockPos pos;
		final Vector3f axis;
		final float tilt;
		int age;

		Hop(Display.BlockDisplay entity, BlockPos pos, Vector3f axis, float tilt) {
			this.entity = entity;
			this.pos = pos;
			this.axis = axis;
			this.tilt = tilt;
		}
	}

	private Hops() {
	}

	public static int live() {
		return HOPS.size();
	}

	/** Finds the visible blocks between distance r0 and r1 from the origin and starts their jump. */
	public static void spawnShell(Minecraft mc, double ox, double oy, double oz, double r0, double r1) {
		ClientLevel level = mc.level;
		if (level == null || r1 <= r0) {
			return;
		}
		int bx = Mth.floor(ox);
		int by = Mth.floor(oy);
		int bz = Mth.floor(oz);
		int r = Mth.ceil(r1) + 1;
		double r0s = r0 * r0;
		double r1s = r1 * r1;
		int minY = Math.max(level.getMinY(), by - r);
		int maxY = Math.min(level.getMaxY(), by + r);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		List<BlockPos> found = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			double fx = bx + dx + 0.5 - ox;
			for (int dz = -r; dz <= r; dz++) {
				double fz = bz + dz + 0.5 - oz;
				double h = fx * fx + fz * fz;
				if (h >= r1s) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					double fy = y + 0.5 - oy;
					double d = h + fy * fy;
					if (d < r0s || d >= r1s) {
						continue;
					}
					p.set(bx + dx, y, bz + dz);
					BlockState state = level.getBlockState(p);
					if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || !exposed(level, p)) {
						continue;
					}
					found.add(p.immutable());
				}
			}
		}
		found.sort(Comparator.comparingDouble(q -> q.distToCenterSqr(ox, oy, oz)));
		int n = Math.min(found.size(), maxPerTick);
		for (int i = 0; i < n; i++) {
			spawn(level, found.get(i));
		}
	}

	private static boolean exposed(ClientLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			n.setWithOffset(pos, d);
			if (!level.getBlockState(n).isSolidRender()) {
				return true;
			}
		}
		return false;
	}

	private static void spawn(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (BrickModels.brickOf(state) == null) {
			return;
		}
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setId(nextId--);
		d.setPos(pos.getX(), pos.getY(), pos.getZ());
		d.setBlockState(state);
		int sky = 0;
		int block = 0;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction dir : Direction.values()) {
			n.setWithOffset(pos, dir);
			sky = Math.max(sky, level.getBrightness(LightLayer.SKY, n));
			block = Math.max(block, level.getBrightness(LightLayer.BLOCK, n));
		}
		d.setBrightnessOverride(new Brightness(block, sky));
		float a = RANDOM.nextFloat() * Mth.TWO_PI;
		Hop hop = new Hop(d, pos, new Vector3f(Mth.cos(a), 0.0F, Mth.sin(a)), (RANDOM.nextFloat() * 2.0F - 1.0F) * 0.2F);
		pose(hop, 0);
		level.addEntity(d);
		HOPS.add(hop);
	}

	public static void tick(Minecraft mc) {
		if (HOPS.isEmpty()) {
			return;
		}
		ClientLevel level = mc.level;
		List<Hop> landed = new ArrayList<>();
		for (int i = HOPS.size() - 1; i >= 0; i--) {
			Hop hop = HOPS.get(i);
			hop.age++;
			if (hop.age > LAND_TICK + 3 + hold || hop.entity.isRemoved()) {
				if (level != null) {
					level.removeEntity(hop.entity.getId(), Entity.RemovalReason.DISCARDED);
				}
				HOPS.remove(i);
				continue;
			}
			pose(hop, hop.age);
			if (hop.age == LAND_TICK) {
				landed.add(hop);
			}
		}
		if (!landed.isEmpty() && mc.gameRenderer != null) {
			Vec3 ear = mc.gameRenderer.mainCamera().position();
			landed.sort(Comparator.comparingDouble(h -> h.pos.distToCenterSqr(ear.x, ear.y, ear.z)));
			for (int i = 0; i < Math.min(landed.size(), maxClicksPerTick); i++) {
				BlockPos p = landed.get(i).pos;
				playClick(mc, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 0.55F + RANDOM.nextFloat() * 0.25F, 0.85F + RANDOM.nextFloat() * 0.4F);
			}
		}
	}

	/** Beyond the jumping blocks the world still clicks: a few quieter clicks around the listener. */
	public static void distantClicks(Minecraft mc, double ox, double oy, double oz, double front) {
		if (mc.gameRenderer == null || front > 160.0) {
			return;
		}
		Vec3 ear = mc.gameRenderer.mainCamera().position();
		int n = front < 60.0 ? 3 : 2;
		for (int i = 0; i < n; i++) {
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			double r = 6.0 + RANDOM.nextFloat() * 8.0;
			playClick(mc, ear.x + Mth.cos(a) * r, ear.y - 2.0, ear.z + Mth.sin(a) * r, 0.22F + RANDOM.nextFloat() * 0.18F, 0.8F + RANDOM.nextFloat() * 0.5F);
		}
	}

	public static void playClick(Minecraft mc, double x, double y, double z, float volume, float pitch) {
		mc.getSoundManager().play(new SimpleSoundInstance(CLICK, SoundSource.BLOCKS, volume, pitch, RandomSource.create(), x, y, z));
		Harness.onClick(x, y, z, volume, pitch);
	}

	/** The game's own sound for placing this block (wood, stone, gravel ...). */
	public static void playPlace(Minecraft mc, BlockState state, double x, double y, double z) {
		net.minecraft.world.level.block.SoundType type = state.getSoundType();
		float volume = (type.getVolume() + 1.0F) / 2.0F;
		float pitch = type.getPitch() * 0.8F;
		SoundEvent event = type.getPlaceSound();
		mc.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS, volume, pitch, RandomSource.create(), x, y, z));
		Harness.onSound(event.location().toString(), x, y, z, volume, pitch);
	}

	/** The jump, keyed on ticks; the display smooths each step over one tick. */
	private static void pose(Hop hop, int age) {
		float y;
		float sx;
		float sy;
		float tilt;
		switch (age) {
			case 0 -> { y = 0.0F; sx = 1.03F; sy = 1.03F; tilt = 0.0F; }
			case 1 -> { y = lift * 0.8F; sx = 0.99F; sy = 1.02F; tilt = 0.6F; }
			case 2 -> { y = lift; sx = 1.0F; sy = 1.0F; tilt = 1.0F; }
			case LAND_TICK -> { y = 0.0F; sx = 1.08F; sy = 0.86F; tilt = 0.0F; }
			case LAND_TICK + 1 -> { y = 0.0F; sx = 0.98F; sy = 1.05F; tilt = 0.0F; }
			default -> { y = 0.0F; sx = 1.015F; sy = 1.015F; tilt = 0.0F; }
		}
		Quaternionf rot = new Quaternionf().rotateAxis(hop.tilt * tilt, hop.axis);
		Vector3f scale = new Vector3f(sx, sy, sx);
		// Scale and turn about the bottom centre of the block: T = pivot + lift - R*S*pivot.
		Vector3f pivot = new Vector3f(0.5F, 0.0F, 0.5F);
		Vector3f rs = new Vector3f(pivot).mul(scale).rotate(rot);
		Vector3f translation = new Vector3f(pivot).add(0.0F, y, 0.0F).sub(rs);
		Display.BlockDisplay d = hop.entity;
		d.setTransformation(new Transformation(translation, rot, scale, null));
		d.setTransformationInterpolationDuration(age == 0 ? 0 : 1);
		d.setTransformationInterpolationDelay(0);
	}

	public static void clear(Minecraft mc) {
		if (mc.level != null) {
			for (Hop hop : HOPS) {
				mc.level.removeEntity(hop.entity.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		HOPS.clear();
	}

	public static void forget() {
		HOPS.clear();
	}
}
