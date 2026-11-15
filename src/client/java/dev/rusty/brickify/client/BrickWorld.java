package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the world looks like bricks, and the wave that turns it.
 *
 * <p>The wave runs out from where the player stood. Its front speeds up: the first blocks click in one by one
 * around the player, then it races to the horizon. Near the player every visible block jumps up as a brick and
 * slams back down ({@link Hops}); the chunk meshes follow a moment later, when the brick lands.
 */
public final class BrickWorld {
	public static final int OFF = 0;
	public static final int WAVE = 1;
	public static final int ON = 2;

	// Tuning, changeable through the harness ("tune").
	/** Blocks per second the front starts with. */
	public static double speed0 = 4.0;
	/** Blocks per second squared it speeds up by. */
	public static double accel = 10.0;
	/** Out to this distance every visible block jumps; further out the world just turns. */
	public static double hopRadius = 22.0;
	/** Near sections are re-meshed at most every this many ticks while the front passes through them. */
	public static int remeshEvery = 2;

	private static volatile int mode = OFF;
	private static volatile double ox;
	private static volatile double oy;
	private static volatile double oz;
	/** Inside this distance from the origin the meshes show bricks while the wave runs. */
	private static volatile double landRadius;
	private static int waveTicks;
	private static double prevLand;
	private static double prevSpawn;
	private static final Long2IntOpenHashMap lastMark = new Long2IntOpenHashMap();
	/** Studs are real geometry only this many sections (sideways) around the player; further out the tops stay flat. */
	public static int studSections = 3;
	/** Studs on single blocks drawn outside the world mesh (displays, items); a switch for measuring. */
	public static boolean displayStuds = true;
	private static volatile int studX = Integer.MIN_VALUE;
	private static volatile int studZ = Integer.MIN_VALUE;

	private BrickWorld() {
	}

	public static int mode() {
		return mode;
	}

	public static boolean on() {
		return mode == ON;
	}

	public static double landRadius() {
		return landRadius;
	}

	public static int waveTicks() {
		return waveTicks;
	}

	/** Called for every block while meshing (worker threads) and for single blocks (displays, items). */
	public static boolean isBrick(BlockAndTintGetter level, BlockPos pos) {
		int m = mode;
		if (m == OFF) {
			return false;
		}
		if (m == ON || !(level instanceof RenderSectionRegion)) {
			return true;
		}
		double dx = pos.getX() + 0.5 - ox;
		double dy = pos.getY() + 0.5 - oy;
		double dz = pos.getZ() + 0.5 - oz;
		double r = landRadius;
		return dx * dx + dy * dy + dz * dz <= r * r;
	}

	/** Whether a block gets 3D studs on its top (near the player only). */
	public static boolean studsAt(BlockAndTintGetter level, BlockPos pos) {
		if (!(level instanceof RenderSectionRegion)) {
			return displayStuds;
		}
		int sx = pos.getX() >> 4;
		int sz = pos.getZ() >> 4;
		return Math.max(Math.abs(sx - studX), Math.abs(sz - studZ)) <= studSections;
	}

	public static boolean texturesOn() {
		int m = mode;
		return m == ON || (m == WAVE && landRadius > 3.0);
	}

	/** Moves the stud area with the player and re-meshes the sections that gain or lose their studs. */
	private static void trackStuds(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		int nx = mc.player.getBlockX() >> 4;
		int nz = mc.player.getBlockZ() >> 4;
		int ox = studX;
		int oz = studZ;
		if (nx == ox && nz == oz) {
			return;
		}
		studX = nx;
		studZ = nz;
		if (mode == OFF || ox == Integer.MIN_VALUE) {
			return;
		}
		int r = studSections;
		int minY = mc.level.getMinSectionY();
		int maxY = mc.level.getMaxSectionY();
		for (int sx = Math.min(ox, nx) - r - 1; sx <= Math.max(ox, nx) + r + 1; sx++) {
			for (int sz = Math.min(oz, nz) - r - 1; sz <= Math.max(oz, nz) + r + 1; sz++) {
				boolean before = Math.max(Math.abs(sx - ox), Math.abs(sz - oz)) <= r;
				boolean after = Math.max(Math.abs(sx - nx), Math.abs(sz - nz)) <= r;
				if (before != after) {
					mc.levelExtractor.setSectionRangeDirty(sx, minY, sz, sx, maxY, sz);
				}
			}
		}
	}

	/** Whether something standing at this point has been reached by the wave (entities turn one by one). */
	public static boolean isBrickAt(double x, double y, double z) {
		int m = mode;
		if (m == OFF) {
			return false;
		}
		if (m == ON) {
			return true;
		}
		double dx = x - ox;
		double dy = y - oy;
		double dz = z - oz;
		double r = landRadius;
		return dx * dx + dy * dy + dz * dz <= r * r;
	}

	/** How far the front has come after t seconds. */
	public static double radiusAt(double t) {
		return t <= 0.0 ? 0.0 : speed0 * t + 0.5 * accel * t * t;
	}

	public static void toggle(Minecraft mc) {
		if (mode == OFF) {
			start(mc, false);
		} else {
			stop(mc);
		}
	}

	public static void start(Minecraft mc, boolean instant) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Hops.clear(mc);
		lastMark.clear();
		if (instant) {
			mode = ON;
			dirtyAll(mc);
			BrickCam.enter(true);
			return;
		}
		Vec3 feet = mc.player.position();
		ox = feet.x;
		oy = feet.y + 0.5;
		oz = feet.z;
		waveTicks = 0;
		prevLand = 0.0;
		prevSpawn = 0.0;
		landRadius = 0.0;
		mode = WAVE;
		BrickCam.enter(false);
	}

	public static void stop(Minecraft mc) {
		mode = OFF;
		Hops.clear(mc);
		BrickDebris.clear(mc);
		dirtyAll(mc);
		BrickCam.leave();
	}

	public static void reset() {
		mode = OFF;
		landRadius = 0.0;
		studX = Integer.MIN_VALUE;
		studZ = Integer.MIN_VALUE;
		Hops.forget();
		BrickDebris.forget();
		BrickEntities.forget();
		BrickRuins.forget();
		Coins.forget(net.minecraft.client.Minecraft.getInstance());
		TreeFall.forget();
		Collapse.forget();
		Wobble.forget();
		DisplayLook.forget();
		AnimSwitch.forget();
		BlockFx.forget();
		BrickCam.reset();
	}

	public static void tick(Minecraft mc) {
		trackStuds(mc);
		Hops.tick(mc);
		BrickEntities.tick(mc);
		BrickDebris.tick(mc);
		Collapse.tick(mc, BrickEntities.ticks());
		Wobble.tick(mc);
		DisplayLook.tick(mc, BrickEntities.ticks());
		BrickRuins.tick(mc, BrickEntities.ticks());
		TreeFall.tick(mc, BrickEntities.ticks());
		BlockFx.tick(mc);
		Coins.tick(mc);
		BuildKey.after(mc);
		if (mode != WAVE) {
			return;
		}
		waveTicks++;
		double t = waveTicks / 20.0;
		double spawn = radiusAt(t);
		double land = radiusAt(t - Hops.LAND_TICK / 20.0);
		if (prevSpawn < hopRadius) {
			Hops.spawnShell(mc, ox, oy, oz, prevSpawn, Math.min(spawn, hopRadius));
		} else {
			Hops.distantClicks(mc, ox, oy, oz, spawn);
		}
		landRadius = land;
		remesh(mc, prevLand, land);
		prevSpawn = spawn;
		prevLand = land;
		double horizon = (mc.options.getEffectiveRenderDistance() + 1) * 16.0;
		if (land > horizon) {
			// The visible world has turned; the rest (deep underground, high sky) goes at once.
			mode = ON;
			remesh(mc, land, Double.MAX_VALUE);
		}
	}

	/** Marks the sections the land front crossed between r0 and r1 for re-meshing. */
	private static void remesh(Minecraft mc, double r0, double r1) {
		int rd = mc.options.getEffectiveRenderDistance() + 1;
		int pcx = Mth.floor(ox) >> 4;
		int pcz = Mth.floor(oz) >> 4;
		int minY = mc.level.getMinSectionY();
		int maxY = mc.level.getMaxSectionY();
		for (int sx = pcx - rd; sx <= pcx + rd; sx++) {
			for (int sz = pcz - rd; sz <= pcz + rd; sz++) {
				for (int sy = minY; sy <= maxY; sy++) {
					double minD = boxDistance(sx, sy, sz, false);
					double maxD = boxDistance(sx, sy, sz, true);
					if (maxD <= r0 || minD > r1) {
						continue;
					}
					boolean passed = maxD <= r1;
					if (minD < hopRadius + 8.0 && r1 != Double.MAX_VALUE) {
						long key = SectionPos.asLong(sx, sy, sz);
						int last = lastMark.getOrDefault(key, -1000);
						if (passed || waveTicks - last >= remeshEvery) {
							lastMark.put(key, waveTicks);
							mc.levelExtractor.setSectionDirty(sx, sy, sz);
						}
					} else if (passed) {
						mc.levelExtractor.setSectionDirty(sx, sy, sz);
					}
				}
			}
		}
	}

	private static double boxDistance(int sx, int sy, int sz, boolean far) {
		double x0 = sx * 16.0;
		double y0 = sy * 16.0;
		double z0 = sz * 16.0;
		double dx = far ? Math.max(Math.abs(ox - x0), Math.abs(ox - x0 - 16.0)) : Math.max(0.0, Math.max(x0 - ox, ox - x0 - 16.0));
		double dy = far ? Math.max(Math.abs(oy - y0), Math.abs(oy - y0 - 16.0)) : Math.max(0.0, Math.max(y0 - oy, oy - y0 - 16.0));
		double dz = far ? Math.max(Math.abs(oz - z0), Math.abs(oz - z0 - 16.0)) : Math.max(0.0, Math.max(z0 - oz, oz - z0 - 16.0));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void dirtyAll(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		int rd = mc.options.getEffectiveRenderDistance() + 1;
		int pcx = mc.player.getBlockX() >> 4;
		int pcz = mc.player.getBlockZ() >> 4;
		mc.levelExtractor.setSectionRangeDirty(pcx - rd, mc.level.getMinSectionY(), pcz - rd, pcx + rd, mc.level.getMaxSectionY(), pcz + rd);
	}
}
