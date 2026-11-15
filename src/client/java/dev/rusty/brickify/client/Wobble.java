package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A hit tree (or bush) shakes as a whole: its blocks are hidden in the world mesh for a moment and drawn as displays
 * that rock about the foot of the trunk, swinging out and settling.
 */
public final class Wobble {
	public static int length = 12;
	private static final List<Shake> SHAKES = new ArrayList<>();
	private static int nextId = -14_000_000;

	private static final class Shake {
		final List<BlockPos> blocks;
		final List<Display.BlockDisplay> displays = new ArrayList<>();
		final Vec3 pivot;
		final Vector3f axis;
		final float amplitude;
		final long key;
		int age;

		Shake(List<BlockPos> blocks, Vec3 pivot, Vector3f axis, float amplitude, long key) {
			this.blocks = blocks;
			this.pivot = pivot;
			this.axis = axis;
			this.amplitude = amplitude;
			this.key = key;
		}
	}

	private Wobble() {
	}

	/** Shakes these blocks about the pivot (bottom of the trunk), away from where the hit came from. */
	public static void start(Minecraft mc, long key, List<BlockPos> blocks, Vec3 pivot, Vec3 from, float amplitude) {
		ClientLevel level = mc.level;
		if (level == null || blocks.isEmpty()) {
			return;
		}
		stop(mc, key);
		Vec3 away = new Vec3(pivot.x - from.x, 0.0, pivot.z - from.z);
		if (away.lengthSqr() < 1.0E-4) {
			away = new Vec3(1.0, 0.0, 0.0);
		}
		away = away.normalize();
		Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F).cross(new Vector3f((float) away.x, 0.0F, (float) away.z)).normalize();
		Shake s = new Shake(new ArrayList<>(blocks), pivot, axis, amplitude, key);
		List<BlockState> states = new ArrayList<>();
		for (BlockPos p : s.blocks) {
			BlockState state = level.getBlockState(p);
			states.add(state);
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setId(nextId--);
			d.setBlockState(state.getRenderShape() == RenderShape.MODEL ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			d.setBrightnessOverride(BrickRuins.lightAround(level, p));
			s.displays.add(d);
		}
		DisplayLook.group(s.blocks, states, s.displays, level);
		pose(s, 0.0F, true);
		for (Display.BlockDisplay d : s.displays) {
			level.addEntity(d);
		}
		for (BlockPos p : s.blocks) {
			BlockFx.hide(mc, p);
		}
		SHAKES.add(s);
	}

	private static void pose(Shake s, float angle, boolean jump) {
		Quaternionf rot = new Quaternionf().rotationAxis(angle, s.axis);
		for (int i = 0; i < s.blocks.size(); i++) {
			BlockPos p = s.blocks.get(i);
			Display.BlockDisplay d = s.displays.get(i);
			Vector3f corner = new Vector3f((float) (p.getX() - s.pivot.x), (float) (p.getY() - s.pivot.y), (float) (p.getZ() - s.pivot.z));
			DisplayPose.set(d, s.pivot.x, s.pivot.y, s.pivot.z, new Transformation(rot.transform(corner), new Quaternionf(rot), new Vector3f(1.0F), null), jump);
		}
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || SHAKES.isEmpty()) {
			return;
		}
		for (int i = SHAKES.size() - 1; i >= 0; i--) {
			Shake s = SHAKES.get(i);
			s.age++;
			if (s.age == length) {
				for (BlockPos p : s.blocks) {
					BlockFx.unhide(mc, p);
				}
			}
			if (s.age > length + 2) {
				for (Display.BlockDisplay d : s.displays) {
					level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
				}
				SHAKES.remove(i);
				continue;
			}
			float t = Math.min(s.age, length);
			float angle = s.amplitude * Mth.sin(t * 1.25F) * (float) Math.exp(-t * 0.23F);
			pose(s, s.age >= length ? 0.0F : angle, false);
		}
	}

	/** Ends a shake at once (the tree is about to fall). */
	public static void stop(Minecraft mc, long key) {
		ClientLevel level = mc.level;
		for (int i = SHAKES.size() - 1; i >= 0; i--) {
			Shake s = SHAKES.get(i);
			if (s.key != key) {
				continue;
			}
			for (BlockPos p : s.blocks) {
				BlockFx.unhide(mc, p);
			}
			if (level != null) {
				for (Display.BlockDisplay d : s.displays) {
					level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
				}
			}
			SHAKES.remove(i);
		}
	}

	public static void forget() {
		SHAKES.clear();
	}
}
