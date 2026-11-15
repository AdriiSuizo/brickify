package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.Brickify;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Optional;

/**
 * The tutorial plate: a little village on a raised slab of ground, 64 x 64. A path runs from a flowery meadow in
 * the south over a river (the bridge is the first blueprint), past houses, a fountain and a market stall, to a wall
 * with an iron gate and a lever; behind it a golden pedestal waits (the goal). Trees, bushes and flowers all over,
 * a hedge all round. The houses, the fountain and the trees are the game's own village houses and tree features.
 * <p>
 * Everything is placed by plate coordinates (0..63, x to the east, z to the south), so the places the tutorial
 * points at ({@link LevelData#spots}) are known before anything is built.
 */
public final class TutorialPlate {
	public static final int SIZE = 64;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	/** The river: plate rows z 31..37, the bridge over it z 30..38. */
	private static final int RIVER_Z0 = 31;
	private static final int RIVER_Z1 = 37;
	private static final int WALL_Z = 8;
	/** The three houses west of the square: template, rectangle on the plate, the way the door faces. */
	/** The big houses that get a camera inside: template, rectangle, door side, lift. */
	private static final Object[][] BIG_HOUSES = {
		{"village/plains/houses/plains_medium_house_1", 3, 11, 16, 25, Direction.EAST, 1},
		{"village/plains/houses/plains_big_house_1", 40, 13, 50, 21, Direction.SOUTH, 0},
		{"village/plains/houses/plains_library_2", 51, 10, 60, 19, Direction.WEST, 0}
	};
	private static final Object[][] WEST_HOUSES = {
		{"village/plains/houses/plains_small_house_1", 18, 11, 25, 17, Direction.SOUTH},
		{"village/plains/houses/plains_medium_house_1", 3, 11, 16, 25, Direction.EAST},
		{"village/plains/houses/plains_small_house_6", 18, 22, 25, 29, Direction.NORTH}
	};

	private TutorialPlate() {
	}

	/** The level of a tutorial plate around the player's feet; its ground lies a few blocks higher than theirs. */
	public static LevelData plan(BlockPos feet) {
		int s = feet.getY() + 2;
		LevelData d = LevelData.around(new BlockPos(feet.getX(), s, feet.getZ()), SIZE, s);
		int ox = d.minX;
		int oz = d.minZ;
		d.spots.put("start", new BlockPos(ox + 31, s + 1, oz + 56));
		d.spots.put("goal", new BlockPos(ox + 31, s + 2, oz + 3));
		d.spots.put("bridge", new BlockPos(ox + 31, s, oz + 34));
		d.spots.put("heap", new BlockPos(ox + 27, s, oz + 42));
		d.spots.put("bars", new BlockPos(ox + 31, s + 2, oz + WALL_Z));
		d.spots.put("lever", new BlockPos(ox + 36, s + 2, oz + WALL_Z + 1));
		d.spots.put("cracked", new BlockPos(ox + 36, s + 2, oz + 12));
		d.spots.put("barrels", new BlockPos(ox + 40, s + 1, oz + 27));
		d.spots.put("hay", new BlockPos(ox + 43, s + 1, oz + 27));
		d.spots.put("checkpoint", new BlockPos(ox + 31, s + 1, oz + 28));
		d.spots.put("enemy", new BlockPos(ox + 10, s + 1, oz + 28));
		d.spots.put("pickup", new BlockPos(ox + 9, s + 1, oz + 48));
		d.spots.put("npc", new BlockPos(ox + 49, s + 1, oz + 22));
		d.spots.put("zone", new BlockPos(ox + 31, s + 1, oz + 20));
		cameras(d);
		d.tutorialStep = 0;
		return d;
	}

	/**
	 * Cameras all over the plate, each for a small part, from changing sides and heights, following the figure only a
	 * little: walking on, the view cuts from one angle to the next like in the brick games. The village square has
	 * none (that one is made in the tutorial). Yaw: 0 looks south, 90 west, 180 north, 270 east.
	 */
	private static final float[][] CAMERAS = {
		// x0, z0, x1, z1, yaw, pitch, dist, follow
		{1, 39, 22, 62, 215, 40, 13, 0.4F}, // west meadow, the hidden carrot
		{23, 46, 40, 62, 180, 50, 15, 0.5F}, // the start
		{41, 39, 62, 62, 125, 36, 12, 0.4F}, // the pond
		{23, 39, 40, 45, 95, 30, 11, 0.3F}, // in front of the river, from the side
		{26, 29, 36, 38, 265, 26, 10, 0.25F}, // over the bridge, from the other side
		{1, 29, 25, 38, 180, 46, 13, 0.6F}, // river west
		{37, 29, 62, 38, 180, 46, 13, 0.6F}, // river east
		{1, 9, 25, 28, 225, 45, 14, 0.5F}, // the houses west of the square
		{37, 22, 47, 29, 150, 40, 11, 0.3F}, // the market
		{48, 20, 62, 28, 210, 45, 12, 0.4F}, // the farm and the farmer
		{37, 9, 62, 21, 135, 50, 15, 0.5F}, // the houses east
		{26, 9, 44, 14, 200, 55, 13, 0.2F}, // the wall and the gate
		{34, 9, 38, 12, 180, 72, 8, 0.0F}, // into the shed, from above
		{1, 1, 62, 7, 160, 26, 10, 0.5F}, // behind the wall, the golden pedestal
	};

	/** The plate's cameras (all of them new). */
	public static void cameras(LevelData d) {
		for (float[] c : CAMERAS) {
			zone(d, (int) c[0], (int) c[1], (int) c[2], (int) c[3], c[4], c[5], c[6], c[7]);
		}
	}

	/** Older plates: their first preset cameras (and these) go, the current set comes in. */
	public static void replaceCameras(LevelData d) {
		int[][] old = {{1, 39, 62, 62}, {1, 29, 62, 38}, {1, 9, 25, 28}, {37, 13, 62, 28}, {26, 9, 44, 14}, {1, 1, 62, 7}};
		d.zones.removeIf(z -> {
			for (int[] o : old) {
				if (z.x0 == d.minX + o[0] && z.z0 == d.minZ + o[1] && z.x1 == d.minX + o[2] && z.z1 == d.minZ + o[3]) {
					return true;
				}
			}
			for (float[] c : CAMERAS) {
				if (z.x0 == d.minX + (int) c[0] && z.z0 == d.minZ + (int) c[1] && z.x1 == d.minX + (int) c[2] && z.z1 == d.minZ + (int) c[3]) {
					return true;
				}
			}
			return z.dist < 6.0F;
		});
		cameras(d);
	}

	/**
	 * The big houses get a camera inside: walking in, the view cuts into the room (and back out at the door).
	 * Works out the rooms from the houses as they were placed (server thread); the cameras are added on the client.
	 */
	public static void interiorCameras(ServerLevel sl, LevelData d) {
		Plate p = new Plate(sl, d.minX, d.floorY + 6, d.minZ);
		java.util.List<int[]> rooms = new java.util.ArrayList<>();
		for (Object[] h : BIG_HOUSES) {
			Object[] f = p.fit((String) h[0], (Integer) h[1], (Integer) h[2], (Integer) h[3], (Integer) h[4], (Direction) h[5], (Integer) h[6]);
			if (f == null) {
				continue;
			}
			BoundingBox bb = ((StructureTemplate) f[0]).getBoundingBox((StructurePlaceSettings) f[1], (BlockPos) f[2]);
			rooms.add(new int[] {bb.minX() + 1, bb.minZ() + 1, bb.maxX() - 1, bb.maxZ() - 1});
		}
		net.minecraft.client.Minecraft.getInstance().execute(() -> {
			for (int[] r : rooms) {
				LevelData.CamZone z = new LevelData.CamZone();
				z.id = d.nextId++;
				z.x0 = r[0];
				z.z0 = r[1];
				z.x1 = r[2];
				z.z1 = r[3];
				int w = r[2] - r[0] + 1;
				int l = r[3] - r[1] + 1;
				z.pitch = 30.0F;
				// along the long side of the room, a little from the corner
				z.yaw = l >= w ? 200.0F : 250.0F;
				double half = (l >= w ? l : w) / 2.0 - 0.8;
				z.dist = (float) Math.max(2.5, Math.min(5.0, half / Math.cos(Math.toRadians(z.pitch))));
				z.follow = 0.15F;
				d.zones.add(z);
			}
			Editor.save();
		});
	}

	/** A camera over this part of the plate (plate coordinates), looking this way. */
	private static void zone(LevelData d, int x0, int z0, int x1, int z1, float yaw, float pitch, float dist, float follow) {
		LevelData.CamZone z = new LevelData.CamZone();
		z.id = d.nextId++;
		z.x0 = d.minX + x0;
		z.z0 = d.minZ + z0;
		z.x1 = d.minX + x1;
		z.z1 = d.minZ + z1;
		z.yaw = yaw;
		z.pitch = pitch;
		z.dist = dist;
		z.follow = follow;
		z.ground = d.floorY + 7.02;
		d.zones.add(z);
	}

	/** Puts thick azalea bushes where small bushes are (the plate looked fuller with them). Server thread. */
	public static int softBushes(ServerLevel sl, LevelData d) {
		int n = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = d.floorY; y <= d.floorY + 12; y++) {
			for (int z = d.minZ; z <= d.maxZ; z++) {
				for (int x = d.minX; x <= d.maxX; x++) {
					BlockState s = sl.getBlockState(p.set(x, y, z));
					if (s.is(Blocks.BUSH) || s.is(Blocks.FIREFLY_BUSH)) {
						sl.setBlock(p, (s.is(Blocks.FIREFLY_BUSH) ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState(), FLAGS);
						n++;
					}
				}
			}
		}
		return n;
	}

	/** Lifts the west houses of an older tutorial plate by one block (they were built sunk into the ground). */
	public static void liftWestHouses(ServerLevel sl, LevelData d) {
		new Plate(sl, d.minX, d.floorY + 6, d.minZ).liftWest();
	}

	/** Takes everything out of a box (an old plate), down to its floor. */
	public static void clear(ServerLevel sl, int x0, int y0, int z0, int x1, int y1, int z1) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y1; y >= y0; y--) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					if (!sl.getBlockState(p.set(x, y, z)).isAir()) {
						sl.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
					}
				}
			}
		}
	}

	/** Builds the plate (server thread). */
	public static void build(ServerLevel sl, LevelData d) {
		long started = System.nanoTime();
		Plate p = new Plate(sl, d.minX, d.floorY + 6, d.minZ);
		try {
			p.ground();
			p.river();
			p.paths();
			p.village();
			p.wall();
			p.bridge();
			p.shed();
			p.market();
			p.shrine();
			p.lamps();
			p.pond();
			p.boulders();
			p.trees();
			p.plants();
			p.hedge();
			interiorCameras(sl, d);
		} catch (RuntimeException e) {
			Brickify.LOGGER.warn("Tutorial plate: {}", e.toString(), e);
		}
		Brickify.LOGGER.info("Tutorial plate built in {} ms", (System.nanoTime() - started) / 1_000_000L);
	}

	/** The plate being built: plate coordinates, the ground height {@code s}. */
	private static final class Plate {
		final ServerLevel sl;
		final int ox;
		final int s;
		final int oz;
		final RandomSource rnd = RandomSource.create(20261115L);
		final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		/** Plate cells that are taken (path, houses, water ...): no trees or plants there. */
		final boolean[][] taken = new boolean[SIZE][SIZE];

		Plate(ServerLevel sl, int ox, int s, int oz) {
			this.sl = sl;
			this.ox = ox;
			this.s = s;
			this.oz = oz;
		}

		BlockPos at(int x, int y, int z) {
			return new BlockPos(this.ox + x, this.s + y, this.oz + z);
		}

		void set(int x, int y, int z, BlockState state) {
			this.sl.setBlock(this.m.set(this.ox + x, this.s + y, this.oz + z), state, FLAGS);
		}

		BlockState get(int x, int y, int z) {
			return this.sl.getBlockState(this.m.set(this.ox + x, this.s + y, this.oz + z));
		}

		void take(int x0, int z0, int x1, int z1) {
			for (int x = Math.max(0, x0); x <= Math.min(SIZE - 1, x1); x++) {
				for (int z = Math.max(0, z0); z <= Math.min(SIZE - 1, z1); z++) {
					this.taken[x][z] = true;
				}
			}
		}

		boolean free(int x0, int z0, int x1, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if (x < 1 || z < 1 || x > SIZE - 2 || z > SIZE - 2 || this.taken[x][z]) {
						return false;
					}
				}
			}
			return true;
		}

		/** A slab of ground: stone, dirt, grass; clear air above. */
		void ground() {
			for (int x = 0; x < SIZE; x++) {
				for (int z = 0; z < SIZE; z++) {
					for (int y = -6; y <= 0; y++) {
						BlockState b = y <= -5 ? Blocks.STONE.defaultBlockState() : y < 0 ? Blocks.DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
						this.set(x, y, z, b);
					}
					for (int y = 1; y <= 34; y++) {
						if (!this.get(x, y, z).isAir()) {
							this.set(x, y, z, Blocks.AIR.defaultBlockState());
						}
					}
				}
			}
		}

		/** A river across the plate, two deep and two below the banks, with a sandy bed and lily pads. */
		void river() {
			for (int x = 1; x < SIZE - 1; x++) {
				for (int z = RIVER_Z0; z <= RIVER_Z1; z++) {
					this.set(x, -4, z, this.rnd.nextInt(5) == 0 ? Blocks.GRAVEL.defaultBlockState() : this.rnd.nextInt(6) == 0 ? Blocks.CLAY.defaultBlockState() : Blocks.SAND.defaultBlockState());
					this.set(x, -3, z, Blocks.WATER.defaultBlockState());
					this.set(x, -2, z, Blocks.WATER.defaultBlockState());
					this.set(x, -1, z, Blocks.AIR.defaultBlockState());
					this.set(x, 0, z, Blocks.AIR.defaultBlockState());
					if (this.rnd.nextInt(13) == 0 && Math.abs(x - 31) > 3) {
						this.set(x, -1, z, Blocks.LILY_PAD.defaultBlockState());
					}
				}
				// the banks: a little stone here and there
				for (int z : new int[] {RIVER_Z0 - 1, RIVER_Z1 + 1}) {
					if (this.rnd.nextInt(4) == 0 && (x < 27 || x > 35)) {
						this.set(x, -1, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
					}
				}
			}
			// a way out of the water at both ends, on the south side
			for (int x : new int[] {1, 2, 3, SIZE - 4, SIZE - 3, SIZE - 2}) {
				this.set(x, -1, RIVER_Z1, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
				this.set(x, -2, RIVER_Z1, Blocks.COBBLESTONE.defaultBlockState());
			}
			this.take(0, RIVER_Z0, SIZE - 1, RIVER_Z1);
		}

		void path(int x0, int z0, int x1, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
					BlockState b = edge && this.rnd.nextInt(5) == 0 ? (this.rnd.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState()
						: Blocks.DIRT_PATH.defaultBlockState();
					if (this.get(x, 0, z).is(Blocks.GRASS_BLOCK)) {
						this.set(x, 0, z, b);
					}
				}
			}
			this.take(x0, z0, x1, z1);
		}

		void paths() {
			this.path(30, 39, 32, 61);
			this.path(30, 26, 32, 30);
			this.path(26, 15, 36, 25);
			this.path(30, 9, 32, 14);
			this.path(30, 1, 32, 7);
			this.path(17, 19, 25, 20);
			this.path(37, 22, 48, 22);
		}

		/** The game's own village houses and fountain, doors towards the paths. */
		void village() {
			this.structure("village/plains/town_centers/plains_fountain_01", 27, 16, 35, 24, null);
			for (Object[] h : WEST_HOUSES) {
				this.structure((String) h[0], (Integer) h[1], (Integer) h[2], (Integer) h[3], (Integer) h[4], (Direction) h[5], 1);
			}
			this.structure("village/plains/houses/plains_big_house_1", 40, 13, 50, 21, Direction.SOUTH);
			this.structure("village/plains/houses/plains_small_farm_1", 50, 20, 60, 29, null);
			this.structure("village/plains/houses/plains_library_2", 51, 10, 60, 19, Direction.WEST);
		}

		void structure(String id, int x0, int z0, int x1, int z1, Direction door) {
			this.structure(id, x0, z0, x1, z1, door, 0);
		}

		/**
		 * Where a structure goes inside this plate rectangle, turned so its door faces {@code door} (if it has one),
		 * {@code lift} blocks higher than its door step needs. Null if it does not fit.
		 */
		Object @org.jspecify.annotations.Nullable [] fit(String id, int x0, int z0, int x1, int z1, Direction door, int lift) {
			Optional<StructureTemplate> found = this.sl.getStructureTemplateManager().get(Identifier.withDefaultNamespace(id));
			if (found.isEmpty()) {
				Brickify.LOGGER.warn("Tutorial plate: no structure {}", id);
				return null;
			}
			StructureTemplate t = found.get();
			StructurePlaceSettings best = null;
			int bestScore = Integer.MIN_VALUE;
			int doorY = 1;
			for (Rotation rot : Rotation.values()) {
				StructurePlaceSettings set = new StructurePlaceSettings().setRotation(rot).setIgnoreEntities(true).addProcessor(JigsawReplacementProcessor.INSTANCE);
				BoundingBox bb = t.getBoundingBox(set, BlockPos.ZERO);
				int w = bb.maxX() - bb.minX() + 1;
				int l = bb.maxZ() - bb.minZ() + 1;
				if (w > x1 - x0 + 1 || l > z1 - z0 + 1) {
					continue;
				}
				int score = 0;
				int dy = 1;
				List<StructureTemplate.StructureBlockInfo> doors = t.filterBlocks(BlockPos.ZERO, set, Blocks.OAK_DOOR);
				for (StructureTemplate.StructureBlockInfo info : doors) {
					if (info.state().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && info.state().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
						dy = info.pos().getY();
						if (door != null) {
							double cx = (bb.minX() + bb.maxX()) / 2.0;
							double cz = (bb.minZ() + bb.maxZ()) / 2.0;
							double ddx = info.pos().getX() - cx;
							double ddz = info.pos().getZ() - cz;
							Direction side = Math.abs(ddx) > Math.abs(ddz) ? (ddx > 0 ? Direction.EAST : Direction.WEST) : (ddz > 0 ? Direction.SOUTH : Direction.NORTH);
							score = side == door ? 10 : side == door.getOpposite() ? -10 : 0;
						}
						break;
					}
				}
				if (score > bestScore) {
					bestScore = score;
					best = set;
					doorY = dy;
				}
			}
			if (best == null) {
				Brickify.LOGGER.warn("Tutorial plate: {} does not fit", id);
				return null;
			}
			BoundingBox bb = t.getBoundingBox(best, BlockPos.ZERO);
			int w = bb.maxX() - bb.minX() + 1;
			int l = bb.maxZ() - bb.minZ() + 1;
			int px = x0 + (x1 - x0 + 1 - w) / 2;
			int pz = z0 + (z1 - z0 + 1 - l) / 2;
			// the door stands on the ground: the template goes as deep as the step under its door
			BlockPos pos = this.at(px - bb.minX(), 1 - doorY - bb.minY() + lift, pz - bb.minZ());
			return new Object[] {t, best, pos, px, pz, w, l};
		}

		/** Places a structure (see {@link #fit}). */
		void structure(String id, int x0, int z0, int x1, int z1, Direction door, int lift) {
			Object[] f = this.fit(id, x0, z0, x1, z1, door, lift);
			if (f == null) {
				return;
			}
			StructureTemplate t = (StructureTemplate) f[0];
			StructurePlaceSettings best = (StructurePlaceSettings) f[1];
			BlockPos pos = (BlockPos) f[2];
			int px = (Integer) f[3];
			int pz = (Integer) f[4];
			int w = (Integer) f[5];
			int l = (Integer) f[6];
			t.placeInWorld(this.sl, pos, pos, best, this.rnd, Block.UPDATE_CLIENTS);
			this.take(px - 1, pz - 1, px + w, pz + l);
		}

		/** Plates built before the west houses stood a block higher: lift them now (their whole box, top first). */
		void liftWest() {
			for (Object[] h : WEST_HOUSES) {
				Object[] f = this.fit((String) h[0], (Integer) h[1], (Integer) h[2], (Integer) h[3], (Integer) h[4], (Direction) h[5], 0);
				if (f == null) {
					continue;
				}
				StructureTemplate t = (StructureTemplate) f[0];
				BoundingBox bb = t.getBoundingBox((StructurePlaceSettings) f[1], (BlockPos) f[2]);
				BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos();
				BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();
				for (int y = bb.maxY(); y >= bb.minY(); y--) {
					for (int x = bb.minX(); x <= bb.maxX(); x++) {
						for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
							BlockState b = this.sl.getBlockState(from.set(x, y, z));
							BlockState above = this.sl.getBlockState(to.set(x, y + 1, z));
							if (!b.equals(above)) {
								this.sl.setBlock(to, b, FLAGS);
							}
						}
					}
				}
			}
		}

		/** A stone wall right across the plate; its gate is three iron bars wide, a lever next to it. */
		void wall() {
			for (int x = 1; x < SIZE - 1; x++) {
				for (int y = 1; y <= 3; y++) {
					boolean gate = x >= 30 && x <= 32;
					BlockState b;
					if (gate) {
						b = Blocks.IRON_BARS.defaultBlockState();
					} else if (y == 3) {
						b = this.rnd.nextInt(3) == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
					} else {
						b = this.rnd.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
					}
					this.set(x, y, WALL_Z, b);
				}
				// pillars
				if (x % 8 == 3 && (x < 28 || x > 34)) {
					this.set(x, 4, WALL_Z, Blocks.STONE_BRICK_WALL.defaultBlockState());
					this.set(x, 5, WALL_Z, Blocks.LANTERN.defaultBlockState());
				}
			}
			// the gate posts
			for (int x : new int[] {29, 33}) {
				for (int y = 1; y <= 4; y++) {
					this.set(x, y, WALL_Z, Blocks.STONE_BRICKS.defaultBlockState());
				}
				this.set(x, 5, WALL_Z, Blocks.LANTERN.defaultBlockState());
			}
			for (int x = 30; x <= 32; x++) {
				this.set(x, 4, WALL_Z, Blocks.STONE_BRICKS.defaultBlockState());
			}
			// connect the bars and the wall tops
			for (int x = 1; x < SIZE - 1; x++) {
				for (int y = 1; y <= 4; y++) {
					BlockState b = this.get(x, y, WALL_Z);
					if (b.is(Blocks.IRON_BARS) || b.is(Blocks.STONE_BRICK_WALL)) {
						this.set(x, y, WALL_Z, Block.updateFromNeighbourShapes(b, this.sl, this.at(x, y, WALL_Z)));
					}
				}
			}
			this.take(0, WALL_Z - 1, SIZE - 1, WALL_Z + 1);
		}

		/**
		 * A little walled yard against the wall, east of the gate (open at the top, so the diorama sees in). The lever
		 * that opens the gate is inside, and the way in is a cracked wall: hits do nothing to it, TNT does.
		 */
		void shed() {
			int x0 = 34;
			int x1 = 38;
			int z1 = 12;
			for (int x = x0; x <= x1; x++) {
				for (int z = WALL_Z + 1; z <= z1; z++) {
					this.set(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
					boolean side = x == x0 || x == x1 || z == z1;
					for (int y = 1; y <= 3; y++) {
						BlockState b = Blocks.AIR.defaultBlockState();
						if (side) {
							boolean crack = z == z1 && x > x0 && x < x1;
							b = crack ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
								: this.rnd.nextInt(4) == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
						}
						this.set(x, y, z, b);
					}
					if (side && !(z == z1 && x > x0 && x < x1)) {
						this.set(x, 4, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
					}
				}
			}
			this.set(x0 + 3, 1, WALL_Z + 1, Blocks.LANTERN.defaultBlockState());
			this.set(x0 + 1, 1, WALL_Z + 1, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
			this.set(36, 2, WALL_Z + 1, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			this.take(x0 - 1, WALL_Z, x1 + 1, z1 + 1);
		}

		/** The wooden bridge over the river, with railings and lanterns at the ends. */
		void bridge() {
			for (int z = RIVER_Z0 - 1; z <= RIVER_Z1 + 1; z++) {
				for (int x = 30; x <= 32; x++) {
					this.set(x, 0, z, (x + z) % 4 == 0 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
				}
				this.set(29, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
				this.set(33, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
				this.set(29, 1, z, Blocks.OAK_FENCE.defaultBlockState());
				this.set(33, 1, z, Blocks.OAK_FENCE.defaultBlockState());
			}
			for (int z : new int[] {RIVER_Z0 - 1, RIVER_Z1 + 1}) {
				this.set(29, 2, z, Blocks.OAK_FENCE.defaultBlockState());
				this.set(33, 2, z, Blocks.OAK_FENCE.defaultBlockState());
				this.set(29, 3, z, Blocks.LANTERN.defaultBlockState());
				this.set(33, 3, z, Blocks.LANTERN.defaultBlockState());
			}
			for (int z = RIVER_Z0 - 1; z <= RIVER_Z1 + 1; z++) {
				for (int y = 1; y <= 2; y++) {
					for (int x : new int[] {29, 33}) {
						BlockState b = this.get(x, y, z);
						if (b.is(Blocks.OAK_FENCE)) {
							this.set(x, y, z, Block.updateFromNeighbourShapes(b, this.sl, this.at(x, y, z)));
						}
					}
				}
			}
			this.take(28, RIVER_Z0 - 1, 34, RIVER_Z1 + 1);
		}

		/** A market stall: a striped awning on posts, barrels, pumpkins, melons and hay. */
		void market() {
			int x0 = 38;
			int z0 = 24;
			for (int x = x0; x <= x0 + 6; x++) {
				for (int z = z0; z <= z0 + 4; z++) {
					this.set(x, 0, z, Blocks.DIRT_PATH.defaultBlockState());
				}
			}
			for (int[] post : new int[][] {{x0, z0}, {x0 + 6, z0}, {x0, z0 + 4}, {x0 + 6, z0 + 4}}) {
				for (int y = 1; y <= 3; y++) {
					this.set(post[0], y, post[1], Blocks.SPRUCE_FENCE.defaultBlockState());
				}
			}
			for (int x = x0; x <= x0 + 6; x++) {
				for (int z = z0; z <= z0 + 4; z++) {
					this.set(x, 4, z, (x % 2 == 0 ? Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED) : Blocks.WOOL.white()).defaultBlockState());
				}
			}
			// the counter
			for (int x = x0 + 1; x <= x0 + 5; x++) {
				this.set(x, 1, z0, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
			}
			this.set(x0 + 2, 2, z0, Blocks.MELON.defaultBlockState());
			this.set(x0 + 4, 2, z0, Blocks.PUMPKIN.defaultBlockState());
			// barrels in a stack, the first thing the tutorial makes smashable
			for (int[] b : new int[][] {{x0 + 2, 1, z0 + 3}, {x0 + 3, 1, z0 + 3}, {x0 + 2, 1, z0 + 4}, {x0 + 3, 1, z0 + 4}, {x0 + 2, 2, z0 + 3}}) {
				this.set(b[0], b[1], b[2], Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
			}
			this.set(x0 + 5, 1, z0 + 3, Blocks.HAY_BLOCK.defaultBlockState());
			this.set(x0 + 5, 1, z0 + 4, Blocks.HAY_BLOCK.defaultBlockState());
			this.set(x0 + 5, 2, z0 + 4, Blocks.HAY_BLOCK.defaultBlockState());
			this.set(x0 + 7, 1, z0 + 2, Blocks.PUMPKIN.defaultBlockState());
			this.set(x0 + 7, 1, z0 + 3, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
			this.set(x0 - 1, 1, z0 + 1, Blocks.COMPOSTER.defaultBlockState());
			for (int[] post : new int[][] {{x0, z0}, {x0 + 6, z0}, {x0, z0 + 4}, {x0 + 6, z0 + 4}}) {
				for (int y = 1; y <= 3; y++) {
					BlockState f = this.get(post[0], y, post[1]);
					this.set(post[0], y, post[1], Block.updateFromNeighbourShapes(f, this.sl, this.at(post[0], y, post[1])));
				}
			}
			this.take(x0 - 1, z0 - 1, x0 + 8, z0 + 5);
		}

		/** Behind the wall: a golden pedestal in a ring of stone and flowers. */
		void shrine() {
			int cx = 31;
			int cz = 3;
			for (int x = cx - 2; x <= cx + 2; x++) {
				for (int z = cz - 2; z <= cz + 2; z++) {
					if (Math.abs(x - cx) + Math.abs(z - cz) <= 3) {
						this.set(x, 0, z, (x + z) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
					}
				}
			}
			this.set(cx, 1, cz, Blocks.GOLD_BLOCK.defaultBlockState());
			for (int[] c : new int[][] {{cx - 2, cz - 2}, {cx + 2, cz - 2}, {cx - 2, cz + 2}, {cx + 2, cz + 2}}) {
				this.set(c[0], 1, c[1], Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
				this.set(c[0], 2, c[1], Blocks.LANTERN.defaultBlockState());
			}
			this.take(cx - 3, cz - 3, cx + 3, cz + 3);
		}

		/** Lamp posts along the meadow path. */
		void lamps() {
			int[][] posts = {{28, 44}, {34, 49}, {28, 54}, {34, 59}, {28, 27}, {34, 27}};
			for (int[] p : posts) {
				this.set(p[0], 1, p[1], Blocks.SPRUCE_FENCE.defaultBlockState());
				this.set(p[0], 2, p[1], Blocks.SPRUCE_FENCE.defaultBlockState());
				this.set(p[0], 3, p[1], Blocks.LANTERN.defaultBlockState());
				this.take(p[0], p[1], p[0], p[1]);
			}
		}

		/** A little pond in the meadow with lily pads and reeds. */
		void pond() {
			int cx = 46;
			int cz = 52;
			for (int x = cx - 5; x <= cx + 5; x++) {
				for (int z = cz - 4; z <= cz + 4; z++) {
					double e = (x - cx) * (x - cx) / 25.0 + (z - cz) * (z - cz) / 16.0;
					if (e <= 1.0) {
						this.set(x, -1, z, Blocks.SAND.defaultBlockState());
						this.set(x, 0, z, Blocks.WATER.defaultBlockState());
						if (this.rnd.nextInt(7) == 0) {
							this.set(x, 1, z, Blocks.LILY_PAD.defaultBlockState());
						}
						this.taken[x][z] = true;
					} else if (e <= 1.45) {
						if (this.rnd.nextInt(3) == 0) {
							this.set(x, 0, z, Blocks.SAND.defaultBlockState());
							if (this.rnd.nextInt(2) == 0) {
								int h = 1 + this.rnd.nextInt(3);
								for (int y = 1; y <= h; y++) {
									this.set(x, y, z, Blocks.SUGAR_CANE.defaultBlockState());
								}
							}
						}
						this.taken[x][z] = true;
					}
				}
			}
		}

		/** Mossy boulders in the grass. */
		void boulders() {
			int[][] spots = {{22, 49}, {41, 59}, {9, 40}, {48, 5}, {36, 4}};
			for (int[] b : spots) {
				if (!this.free(b[0] - 1, b[1] - 1, b[0] + 1, b[1] + 1)) {
					continue;
				}
				this.set(b[0], 1, b[1], Blocks.MOSSY_COBBLESTONE.defaultBlockState());
				this.set(b[0] + 1, 1, b[1], Blocks.COBBLESTONE.defaultBlockState());
				this.set(b[0], 1, b[1] + 1, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
				this.set(b[0], 2, b[1], Blocks.MOSSY_COBBLESTONE.defaultBlockState());
				this.take(b[0] - 1, b[1] - 1, b[0] + 2, b[1] + 2);
			}
		}

		/** Trees from the game's own tree features: oaks, birches, a few cherries, a fallen log. */
		void trees() {
			Object[][] trees = {
				{TreeFeatures.FANCY_OAK, 6, 44}, {TreeFeatures.OAK, 12, 51}, {TreeFeatures.BIRCH, 5, 57}, {TreeFeatures.OAK, 18, 58},
				{TreeFeatures.CHERRY, 14, 43}, {TreeFeatures.BIRCH, 22, 54}, {TreeFeatures.FANCY_OAK, 56, 43}, {TreeFeatures.OAK, 51, 45},
				{TreeFeatures.BIRCH, 58, 50}, {TreeFeatures.CHERRY, 57, 58}, {TreeFeatures.OAK, 38, 46}, {TreeFeatures.BIRCH, 39, 56},
				{TreeFeatures.OAK, 5, 3}, {TreeFeatures.BIRCH, 12, 4}, {TreeFeatures.CHERRY, 21, 3}, {TreeFeatures.OAK, 42, 3},
				{TreeFeatures.FANCY_OAK, 52, 3}, {TreeFeatures.BIRCH, 59, 4}, {TreeFeatures.OAK, 26, 12},
				{TreeFeatures.OAK, 3, 28}, {TreeFeatures.OAK, 60, 27}, {TreeFeatures.BIRCH, 46, 29}, {TreeFeatures.FALLEN_OAK_TREE, 10, 58},
				{TreeFeatures.OAK, 24, 41}, {TreeFeatures.BIRCH, 36, 41}
			};
			var registry = this.sl.registryAccess().lookupOrThrow(Registries.FEATURE);
			for (Object[] t : trees) {
				@SuppressWarnings("unchecked")
				ResourceKey<Feature> key = (ResourceKey<Feature>) t[0];
				int x = (Integer) t[1];
				int z = (Integer) t[2];
				if (this.taken[x][z]) {
					continue;
				}
				var feature = registry.get(key);
				if (feature.isEmpty()) {
					continue;
				}
				try {
					feature.get().value().place(this.sl, this.sl.getChunkSource().getGenerator(), this.rnd, this.at(x, 1, z));
				} catch (RuntimeException e) {
					Brickify.LOGGER.warn("Tutorial plate: tree {} failed: {}", key, e.toString());
				}
				this.take(x - 1, z - 1, x + 1, z + 1);
			}
		}

		/** Grass, flowers and bushes on every free bit of meadow, denser away from the paths. */
		void plants() {
			BlockState[] flowers = {
				Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(),
				Blocks.AZURE_BLUET.defaultBlockState(), Blocks.ALLIUM.defaultBlockState(), Blocks.RED_TULIP.defaultBlockState(), Blocks.PINK_TULIP.defaultBlockState(),
				Blocks.LILY_OF_THE_VALLEY.defaultBlockState()
			};
			for (int x = 1; x < SIZE - 1; x++) {
				for (int z = 1; z < SIZE - 1; z++) {
					if (!this.get(x, 0, z).is(Blocks.GRASS_BLOCK) || !this.get(x, 1, z).isAir()) {
						continue;
					}
					boolean nearPath = this.taken[x][z];
					int r = this.rnd.nextInt(100);
					if (nearPath && r < 60) {
						continue;
					}
					// flower patches: a few kinds in clumps, by a slow wave over the plate
					double patch = Math.sin(x * 0.31 + 1.7) * Math.cos(z * 0.27 - 0.4);
					if (r < 26) {
						this.set(x, 1, z, Blocks.SHORT_GRASS.defaultBlockState());
					} else if (r < 31) {
						this.set(x, 1, z, Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
						this.set(x, 2, z, Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
					} else if (r < (patch > 0.35 ? 52 : 37)) {
						int kind = (int) Math.floor((patch + 1.0) * 4.5 + this.rnd.nextInt(2)) % flowers.length;
						this.set(x, 1, z, flowers[Math.max(0, kind)]);
					} else if (r < 40) {
						this.set(x, 1, z, Blocks.BUSH.defaultBlockState());
					} else if (r < 41) {
						this.set(x, 1, z, Blocks.FIREFLY_BUSH.defaultBlockState());
					} else if (r < 44 && patch < -0.3) {
						this.set(x, 1, z, Blocks.WILDFLOWERS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 1 + this.rnd.nextInt(4)));
					} else if (r < 46 && patch > 0.4) {
						this.set(x, 1, z, Blocks.PINK_PETALS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 1 + this.rnd.nextInt(4)));
					} else if (r < 48 && !nearPath) {
						// a thick leafy bush (on a level's plate one walks through it)
						this.set(x, 1, z, (this.rnd.nextInt(3) == 0 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState());
					}
				}
			}
		}

		/** A hedge all round the plate, two leaves high, so nobody walks off. */
		void hedge() {
			BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			for (int i = 0; i < SIZE; i++) {
				for (int[] c : new int[][] {{i, 0}, {i, SIZE - 1}, {0, i}, {SIZE - 1, i}}) {
					int x = c[0];
					int z = c[1];
					boolean river = z >= RIVER_Z0 && z <= RIVER_Z1;
					if (river) {
						// the river ends in a stone wall under the hedge
						for (int y = -3; y <= 0; y++) {
							this.set(x, y, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
						}
					}
					this.set(x, 1, z, leaf);
					this.set(x, 2, z, leaf);
				}
			}
		}
	}
}
