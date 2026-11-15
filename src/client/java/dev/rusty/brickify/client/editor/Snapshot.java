package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.Brickify;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The level as it was built, block for block, taken when play starts. Going back to building puts every block
 * that was smashed, blown up or built while playing back the way it was. Written next to the world, so this also
 * works after the game was closed in the middle of playing. Runs on the server thread.
 */
public final class Snapshot {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private Snapshot() {
	}

	public static void capture(ServerLevel sl, LevelData d, Path file) {
		List<BlockState> palette = new ArrayList<>();
		Reference2IntOpenHashMap<BlockState> index = new Reference2IntOpenHashMap<>();
		index.defaultReturnValue(-1);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			// first pass: palette and runs
			List<int[]> runs = new ArrayList<>();
			int last = -1;
			int count = 0;
			for (int y = d.floorY; y <= d.topY; y++) {
				for (int z = d.minZ; z <= d.maxZ; z++) {
					for (int x = d.minX; x <= d.maxX; x++) {
						BlockState s = sl.getBlockState(p.set(x, y, z));
						int i = index.getInt(s);
						if (i < 0) {
							i = palette.size();
							palette.add(s);
							index.put(s, i);
						}
						if (i == last) {
							count++;
						} else {
							if (count > 0) {
								runs.add(new int[] {count, last});
							}
							last = i;
							count = 1;
						}
					}
				}
			}
			if (count > 0) {
				runs.add(new int[] {count, last});
			}
			out.writeInt(0x424B4C31);
			out.writeInt(d.minX);
			out.writeInt(d.floorY);
			out.writeInt(d.minZ);
			out.writeInt(d.maxX);
			out.writeInt(d.topY);
			out.writeInt(d.maxZ);
			out.writeInt(palette.size());
			for (BlockState s : palette) {
				out.writeUTF(BlockStateParser.serialize(s));
			}
			out.writeInt(runs.size());
			for (int[] r : runs) {
				out.writeInt(r[0]);
				out.writeInt(r[1]);
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not keep the level for later: {}", e.toString());
		}
	}

	/** Puts the level back as it was; returns how many blocks changed, or -1 without a snapshot. */
	/** Blocks are being put back right now (TNT put back must not light). */
	public static volatile boolean restoring;

	public static int restore(ServerLevel sl, Path file) {
		restoring = true;
		try {
			return restoreNow(sl, file);
		} finally {
			restoring = false;
		}
	}

	private static int restoreNow(ServerLevel sl, Path file) {
		if (!Files.isRegularFile(file)) {
			return -1;
		}
		var blocks = sl.registryAccess().lookupOrThrow(Registries.BLOCK);
		int changed = 0;
		int minX;
		int floorY;
		int minZ;
		int maxX;
		int topY;
		int maxZ;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != 0x424B4C31) {
				return -1;
			}
			minX = in.readInt();
			floorY = in.readInt();
			minZ = in.readInt();
			maxX = in.readInt();
			topY = in.readInt();
			maxZ = in.readInt();
			int n = in.readInt();
			BlockState[] palette = new BlockState[n];
			for (int i = 0; i < n; i++) {
				palette[i] = BlockStateParser.parseForBlock(blocks, in.readUTF(), false).blockState();
			}
			int runs = in.readInt();
			int sx = maxX - minX + 1;
			int sz = maxZ - minZ + 1;
			long at = 0;
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int r = 0; r < runs; r++) {
				int len = in.readInt();
				BlockState s = palette[in.readInt()];
				for (int k = 0; k < len; k++, at++) {
					int x = (int) (at % sx);
					int z = (int) (at / sx % sz);
					int y = (int) (at / ((long) sx * sz));
					p.set(minX + x, floorY + y, minZ + z);
					if (sl.getBlockState(p) != s) {
						sl.setBlock(p, s, FLAGS);
						changed++;
					}
				}
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Could not put the level back: {}", e.toString());
			return -1;
		}
		// what flew off while playing: items, falling sand, lit TNT
		AABB box = new AABB(minX - 8, floorY - 16, minZ - 8, maxX + 9, topY + 16, maxZ + 9);
		for (Entity e : sl.getEntities((Entity) null, box, e -> e instanceof ItemEntity || e instanceof FallingBlockEntity || e instanceof PrimedTnt)) {
			e.discard();
		}
		return changed;
	}
}
