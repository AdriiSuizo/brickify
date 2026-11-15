package dev.rusty.brickify.client.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * What a ray from the camera (or the eyes) hits in the editor: a block of the level, a ghost of a blueprint, a heap,
 * the start or the goal. With the diorama cut out, blocks outside the plate are not there for the ray either.
 */
public record Pick(Kind kind, BlockPos pos, Direction face, Vec3 at, int id, double distance) {
	public enum Kind {
		BLOCK, GHOST, PILE, START, GOAL, GATE, SWITCH, CHECKPOINT, SPAWN, PICKUP, NPC, CAMERA, HANDLE, TRIGGER, BLAST
	}

	/** The block a thing put "on" this hit stands in: above a top face, else in front of the face. */
	public BlockPos standing() {
		return this.kind == Kind.BLOCK || this.kind == Kind.GATE || this.kind == Kind.SWITCH ? this.pos.relative(this.face) : this.pos;
	}

	public static @Nullable Pick cast(Level level, LevelData d, Vec3 from, Vec3 dir, double max, boolean onlyInside) {
		Vec3 to = from.add(dir.scale(max));
		Pick best = null;
		// markers first (their boxes): the nearest of everything wins
		for (LevelData.Blueprint b : d.blueprints) {
			Vec3 c = b.pileCentre();
			best = closer(best, box(new AABB(c.x - 0.8, c.y, c.z - 0.8, c.x + 0.8, c.y + 1.0, c.z + 0.8), from, to, Kind.PILE, b.pile, b.id));
		}
		if (d.start != null) {
			BlockPos s = d.start;
			best = closer(best, box(new AABB(s.getX() + 0.15, s.getY(), s.getZ() + 0.15, s.getX() + 0.85, s.getY() + 1.9, s.getZ() + 0.85), from, to, Kind.START, s, 0));
		}
		if (d.goal != null) {
			BlockPos g = d.goal;
			best = closer(best, box(new AABB(g.getX() + 0.1, g.getY(), g.getZ() + 0.1, g.getX() + 0.9, g.getY() + 1.6, g.getZ() + 0.9), from, to, Kind.GOAL, g, 0));
		}
		for (BlockPos c : d.checkpoints) {
			best = closer(best, box(new AABB(c.getX() + 0.15, c.getY(), c.getZ() + 0.15, c.getX() + 0.85, c.getY() + 1.9, c.getZ() + 0.85), from, to, Kind.CHECKPOINT, c, 0));
		}
		for (LevelData.Spawn sp : d.spawns) {
			BlockPos c = sp.pos;
			best = closer(best, box(new AABB(c.getX() + 0.15, c.getY(), c.getZ() + 0.15, c.getX() + 0.85, c.getY() + 1.9, c.getZ() + 0.85), from, to, Kind.SPAWN, c, 0));
		}
		for (LevelData.Pickup pk : d.pickups) {
			BlockPos c = pk.pos;
			best = closer(best, box(new AABB(c.getX() + 0.2, c.getY(), c.getZ() + 0.2, c.getX() + 0.8, c.getY() + 1.0, c.getZ() + 0.8), from, to, Kind.PICKUP, c, pk.id));
		}
		for (LevelData.Npc n : d.npcs) {
			BlockPos c = n.pos;
			best = closer(best, box(new AABB(c.getX() + 0.15, c.getY(), c.getZ() + 0.15, c.getX() + 0.85, c.getY() + 1.95, c.getZ() + 0.85), from, to, Kind.NPC, c, n.id));
		}
		for (LevelData.Blast b : d.blasts) {
			BlockPos c = b.pos;
			best = closer(best, box(new AABB(c.getX() + 0.15, c.getY() + 0.1, c.getZ() + 0.15, c.getX() + 0.85, c.getY() + 0.9, c.getZ() + 0.85), from, to, Kind.BLAST, c, b.id));
		}
		for (LevelData.Trigger t : d.triggers) {
			// the flag in its middle
			Vec3 c = t.centre(Editor.triggerGround(level, d, t));
			best = closer(best, box(new AABB(c.x - 0.35, c.y, c.z - 0.35, c.x + 0.35, c.y + 1.8, c.z + 0.35), from, to, Kind.TRIGGER, BlockPos.containing(c), t.id));
		}
		LevelData.AreaBox chosen = Editor.selectedArea();
		if (chosen != null) {
			double gy = Editor.areaGround(level, d, chosen);
			double[][] hs = Editor.zoneHandles(chosen);
			for (int i = 0; i < hs.length; i++) {
				double r = i == 8 ? 0.45 : 0.38;
				best = closer(best, box(new AABB(hs[i][0] - r, gy - 0.1, hs[i][1] - r, hs[i][0] + r, gy + 0.55, hs[i][1] + r), from, to, Kind.HANDLE,
					BlockPos.containing(hs[i][0], gy, hs[i][1]), i));
			}
		}
		for (LevelData.CamZone z : d.zones) {
			Vec3 eye = Editor.zoneEye(level, d, z);
			best = closer(best, box(new AABB(eye.x - 0.55, eye.y - 0.45, eye.z - 0.55, eye.x + 0.55, eye.y + 0.45, eye.z + 0.55), from, to, Kind.CAMERA,
				BlockPos.containing(eye), z.id));
		}
		Pick hit = march(level, d, from, dir, best != null ? best.distance : max, onlyInside);
		return closer(best, hit);
	}

	private static @Nullable Pick closer(@Nullable Pick a, @Nullable Pick b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return b.distance < a.distance ? b : a;
	}

	private static @Nullable Pick box(AABB box, Vec3 from, Vec3 to, Kind kind, BlockPos pos, int id) {
		Optional<Vec3> hit = box.clip(from, to);
		if (hit.isEmpty()) {
			return box.contains(from) ? new Pick(kind, pos, Direction.UP, from, id, 0.0) : null;
		}
		return new Pick(kind, pos, Direction.UP, hit.get(), id, hit.get().distanceTo(from));
	}

	/** Steps through the blocks along the ray (also the ghosts, which are air in the world). */
	private static @Nullable Pick march(Level level, LevelData d, Vec3 from, Vec3 dir, double max, boolean onlyInside) {
		Vec3 to = from.add(dir.scale(max));
		int x = Mth.floor(from.x);
		int y = Mth.floor(from.y);
		int z = Mth.floor(from.z);
		int stepX = dir.x > 0 ? 1 : -1;
		int stepY = dir.y > 0 ? 1 : -1;
		int stepZ = dir.z > 0 ? 1 : -1;
		double deltaX = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
		double deltaY = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.y);
		double deltaZ = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
		double nextX = dir.x == 0 ? Double.MAX_VALUE : (dir.x > 0 ? x + 1 - from.x : from.x - x) * deltaX;
		double nextY = dir.y == 0 ? Double.MAX_VALUE : (dir.y > 0 ? y + 1 - from.y : from.y - y) * deltaY;
		double nextZ = dir.z == 0 ? Double.MAX_VALUE : (dir.z > 0 ? z + 1 - from.z : from.z - z) * deltaZ;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		double travelled = 0.0;
		int steps = 0;
		while (travelled <= max && steps++ < 2000) {
			if (y >= level.getMinY() && y <= level.getMaxY() && (!onlyInside || d.inside(x, y, z))) {
				at.set(x, y, z);
				LevelData.Blueprint ghost = Editor.ghostAt(at);
				if (ghost != null) {
					BlockHitResult h = Shapes.block().clip(from, to, at.immutable());
					if (h != null) {
						return new Pick(Kind.GHOST, h.getBlockPos(), h.getDirection(), h.getLocation(), ghost.id, h.getLocation().distanceTo(from));
					}
				}
				BlockState state = level.getBlockState(at);
				if (!state.isAir() && state.getFluidState().isEmpty()) {
					var shape = state.getShape(level, at);
					// things without an outline of their own are clicked as the whole cell
					BlockHitResult h = (shape.isEmpty() ? Shapes.block() : shape).clip(from, to, at.immutable());
					if (h != null) {
						LevelData.Gate g = d.gateAt(h.getBlockPos());
						if (g != null) {
							return new Pick(Kind.GATE, h.getBlockPos(), h.getDirection(), h.getLocation(), g.id, h.getLocation().distanceTo(from));
						}
						LevelData.Switch sw = d.switchAt(h.getBlockPos());
						if (sw != null) {
							return new Pick(Kind.SWITCH, h.getBlockPos(), h.getDirection(), h.getLocation(), sw.id, h.getLocation().distanceTo(from));
						}
						return new Pick(Kind.BLOCK, h.getBlockPos(), h.getDirection(), h.getLocation(), 0, h.getLocation().distanceTo(from));
					}
				} else if (!state.getFluidState().isEmpty()) {
					// water and lava: the ray stops on their surface
					BlockHitResult h = Shapes.block().clip(from, to, at.immutable());
					if (h != null) {
						return new Pick(Kind.BLOCK, h.getBlockPos(), h.getDirection(), h.getLocation(), 0, h.getLocation().distanceTo(from));
					}
				}
			}
			if (nextX < nextY && nextX < nextZ) {
				x += stepX;
				travelled = nextX;
				nextX += deltaX;
			} else if (nextY < nextZ) {
				y += stepY;
				travelled = nextY;
				nextY += deltaY;
			} else {
				z += stepZ;
				travelled = nextZ;
				nextZ += deltaZ;
			}
		}
		return null;
	}
}
