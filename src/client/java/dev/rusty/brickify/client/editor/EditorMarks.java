package dev.rusty.brickify.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * With the smashable tool in hand, what the figure may smash glows orange: each such block is drawn once more over
 * itself, a hair bigger and see-through, so flowers glow as flowers and fences as fences. The glow breathes a little.
 */
public final class EditorMarks {
	private static final int BUDGET = 9000;
	private static final double RANGE = 140.0;
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final long[] POS = new long[BUDGET];
	private static final double[] DISTANCE = new double[BUDGET];
	private static final int[] ORDER = new int[BUDGET];
	private static int count;
	private static double camX;
	private static double camY;
	private static double camZ;
	private static int tint;
	private static int blastTint;
	private static final byte[] BLAST = new byte[BUDGET];
	private static int liftTint;
	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create(0L);

	private EditorMarks() {
	}

	/** The smashable tool is in use: in the diorama, or in the hand in the normal view. */
	public static boolean showing() {
		Minecraft mc = Minecraft.getInstance();
		if (!Editor.editing() || mc.player == null) {
			return false;
		}
		if (mc.gui.screen() instanceof EditorScreen) {
			return Editor.tool() == Editor.Tool.BREAKABLE;
		}
		return EditorTools.toolOf(mc.player.getMainHandItem()) == Editor.Tool.BREAKABLE;
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera) {
		count = 0;
		LevelData d = Editor.level();
		if (d == null || d.breakable.isEmpty() && d.blastable.isEmpty() && d.liftable.isEmpty() || !showing()) {
			return;
		}
		double r2 = RANGE * RANGE;
		collect(d.breakable, (byte) 0, camera, r2);
		collect(d.blastable, (byte) 1, camera, r2);
		collect(d.liftable, (byte) 2, camera, r2);
		if (count == 0) {
			return;
		}
		IntArrays.quickSort(ORDER, 0, count, (a, b) -> Double.compare(DISTANCE[b], DISTANCE[a]));
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;
		double t = System.currentTimeMillis() / 1000.0;
		int alpha = (int) (0x88 + 0x30 * Math.sin(t * 3.2));
		tint = alpha << 24 | 0xFF9A30;
		blastTint = (int) (0x90 + 0x30 * Math.sin(t * 5.0)) << 24 | 0xE04848;
		liftTint = (int) (0x88 + 0x30 * Math.sin(t * 2.4)) << 24 | 0x4FB8FF;
		collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), EditorMarks::render);
	}

	private static void collect(it.unimi.dsi.fastutil.longs.LongOpenHashSet set, byte blast, Vec3 camera, double r2) {
		for (long k : set) {
			if (count >= BUDGET) {
				break;
			}
			int x = BlockPos.getX(k);
			int y = BlockPos.getY(k);
			int z = BlockPos.getZ(k);
			if (Editor.cuts(x, y, z)) {
				continue;
			}
			double dx = x + 0.5 - camera.x;
			double dy = y + 0.5 - camera.y;
			double dz = z + 0.5 - camera.z;
			double dist = dx * dx + dy * dy + dz * dz;
			if (dist > r2) {
				continue;
			}
			POS[count] = k;
			BLAST[count] = blast;
			DISTANCE[count] = dist;
			ORDER[count] = count;
			count++;
		}
	}

	private static final com.mojang.blaze3d.vertex.QuadInstance QUAD = new com.mojang.blaze3d.vertex.QuadInstance();

	/** A quad in one colour over its texture (no grass or leaf colour), so the glow reads the same on everything. */
	private static void flat(VertexConsumer buffer, PoseStack.Pose pose, BakedQuad quad, int light, int col, CardinalLighting lighting) {
		Direction shade = quad.materialInfo().shadeDirectionOverride();
		QUAD.setColor(net.minecraft.util.ARGB.gray(Math.min(1.0F, lighting.byFace(shade != null ? shade : quad.direction()) + 0.25F)));
		QUAD.multiplyColor(col);
		QUAD.setLightCoords(light);
		buffer.putBakedQuad(pose, quad, QUAD);
	}

	private static void render(PoseStack.Pose pose, VertexConsumer buffer) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		BlockColors colors = mc.getBlockColors();
		CardinalLighting lighting = level.cardinalLighting();
		PoseStack stack = new PoseStack();
		stack.mulPose(pose.pose());
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
		for (int n = 0; n < count; n++) {
			at.set(POS[ORDER[n]]);
			byte kind = BLAST[ORDER[n]];
			int col = kind == 2 ? liftTint : kind == 1 ? blastTint : tint;
			BlockState state = level.getBlockState(at);
			if (state.isAir()) {
				continue;
			}
			PARTS.clear();
			try {
				RANDOM.setSeed(state.getSeed(at));
				mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RANDOM, PARTS);
			} catch (RuntimeException e) {
				continue;
			}
			stack.pushPose();
			stack.translate(at.getX() + 0.5 - camX, at.getY() + 0.5 - camY, at.getZ() + 0.5 - camZ);
			stack.scale(1.012F, 1.012F, 1.012F);
			stack.translate(-0.5F, -0.5F, -0.5F);
			int light = net.minecraft.util.LightCoordsUtil.pack(15, 15);
			for (BlockStateModelPart part : PARTS) {
				for (Direction direction : DIRECTIONS) {
					List<BakedQuad> quads = part.getQuads(direction);
					if (quads.isEmpty()) {
						continue;
					}
					near.setWithOffset(at, direction);
					if (!Block.shouldRenderFace(state, level.getBlockState(near), direction)) {
						continue;
					}
					for (BakedQuad quad : quads) {
						flat(buffer, stack.last(), quad, light, col, lighting);
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					flat(buffer, stack.last(), quad, light, col, lighting);
				}
			}
			stack.popPose();
		}
	}
}
