package dev.rusty.brickify.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The blueprints while building: their blocks drawn as the real (brick) models, see-through and a little blue,
 * like the ghost parts of a building instruction. The quads go to the game every frame and never touch the world.
 * Faces between two ghosts, or against a solid block, are left out. The chosen blueprint is drawn a little golden.
 */
public final class EditorGhosts {
	public static final int TINT = 0x9CC4E2FF;
	public static final int CHOSEN = 0xB8FFE6A0;
	private static final int BUDGET = 8000;
	private static final int MIN_LIGHT = 11;
	private static final double RANGE = 160.0;
	private static final Direction[] DIRECTIONS = Direction.values();

	private static final long[] POS = new long[BUDGET];
	private static final BlockState[] STATES = new BlockState[BUDGET];
	private static final int[] TINTS = new int[BUDGET];
	private static final double[] DISTANCE = new double[BUDGET];
	private static final int[] ORDER = new int[BUDGET];
	private static int count;
	private static double camX;
	private static double camY;
	private static double camZ;
	private static final Long2ObjectOpenHashMap<BlockState> DRAWN = new Long2ObjectOpenHashMap<>();
	private static final RandomSource RANDOM = RandomSource.create(0L);
	/** Bricks of the heaps: small real bricks in a pile (place, turn, state). */
	private static final int HEAP_MAX = 600;
	private static final double[] HX = new double[HEAP_MAX];
	private static final double[] HY = new double[HEAP_MAX];
	private static final double[] HZ = new double[HEAP_MAX];
	private static final float[] HYAW = new float[HEAP_MAX];
	private static final float[] HTILT = new float[HEAP_MAX];
	private static final BlockState[] HSTATE = new BlockState[HEAP_MAX];
	private static int heapCount;
	/** Where the bricks of a heap lie (x, y, z offsets in blocks), lower layer first. */
	private static final float[][] HEAP = {
		{0.0F, 0.0F, 0.0F}, {-0.55F, 0.0F, 0.18F}, {0.5F, 0.0F, -0.25F}, {0.12F, 0.0F, 0.55F}, {-0.2F, 0.0F, -0.55F},
		{0.55F, 0.0F, 0.42F}, {-0.6F, 0.0F, -0.35F}, {-0.15F, 0.32F, 0.12F}, {0.28F, 0.32F, -0.08F}, {-0.38F, 0.32F, -0.2F},
		{0.18F, 0.32F, 0.38F}, {0.02F, 0.62F, 0.04F}
	};
	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();
	private static final QuadInstance QUAD = new QuadInstance();

	private EditorGhosts() {
	}

	/** Called once a frame where the game submits its moving blocks. */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera) {
		DRAWN.clear();
		count = 0;
		heapCount = 0;
		LevelData d = Editor.level();
		if (!Editor.editing() || d == null || d.blueprints.isEmpty()) {
			return;
		}
		LevelData.Blueprint placing = Editor.placing();
		for (LevelData.Blueprint b : d.blueprints) {
			if (b == placing) {
				Pick h = Editor.hover();
				if (h != null && h.kind() == Pick.Kind.BLOCK) {
					BlockPos ground = h.face() == Direction.UP ? h.pos() : h.standing().below();
					heap(b, new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5));
				}
			} else if (b.pileCentre().distanceTo(camera) < RANGE) {
				heap(b, b.pileCentre());
			}
		}
		LevelData.Blueprint chosen = Editor.selectedBlueprint();
		for (LevelData.Blueprint b : d.blueprints) {
			if (b.centre().distanceTo(camera) > RANGE + 32.0) {
				continue;
			}
			int tint = b == chosen ? CHOSEN : TINT;
			for (int i = 0; i < b.blocks.size() && count < BUDGET; i++) {
				BlockPos p = b.blocks.get(i);
				long k = p.asLong();
				if (Editor.cuts(p.getX(), p.getY(), p.getZ()) || DRAWN.putIfAbsent(k, b.states.get(i)) != null) {
					continue;
				}
				POS[count] = k;
				STATES[count] = b.states.get(i);
				TINTS[count] = tint;
				double dx = p.getX() + 0.5 - camera.x;
				double dy = p.getY() + 0.5 - camera.y;
				double dz = p.getZ() + 0.5 - camera.z;
				DISTANCE[count] = dx * dx + dy * dy + dz * dz;
				ORDER[count] = count;
				count++;
			}
		}
		if (count == 0 && heapCount == 0) {
			return;
		}
		// far ones first, so the near ones are laid over them
		IntArrays.quickSort(ORDER, 0, count, (a, b) -> Double.compare(DISTANCE[b], DISTANCE[a]));
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;
		collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), EditorGhosts::render);
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
		renderHeaps(mc, level, stack, buffer, colors, lighting);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
		for (int k = 0; k < count; k++) {
			int i = ORDER[k];
			BlockState state = STATES[i];
			at.set(POS[i]);
			PARTS.clear();
			try {
				RANDOM.setSeed(state.getSeed(at));
				mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RANDOM, PARTS);
			} catch (RuntimeException e) {
				continue;
			}
			stack.pushPose();
			stack.translate(at.getX() - camX, at.getY() - camY, at.getZ() - camZ);
			int ownLight = light(level, at);
			for (BlockStateModelPart part : PARTS) {
				for (Direction direction : DIRECTIONS) {
					List<BakedQuad> quads = part.getQuads(direction);
					if (quads.isEmpty()) {
						continue;
					}
					near.setWithOffset(at, direction);
					if (!Block.shouldRenderFace(state, neighbour(level, near), direction)) {
						continue;
					}
					int light = light(level, near);
					for (BakedQuad quad : quads) {
						put(buffer, stack.last(), quad, state, level, at, light, TINTS[i], lighting, colors);
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					put(buffer, stack.last(), quad, state, level, at, ownLight, TINTS[i], lighting, colors);
				}
			}
			stack.popPose();
		}
	}

	/** The bricks of one heap, in the blueprint's own blocks (the most used ones). */
	private static void heap(LevelData.Blueprint b, Vec3 at) {
		int n = b.states.size();
		for (int i = 0; i < HEAP.length && heapCount < HEAP_MAX; i++) {
			BlockState s = b.states.get((i * 7919 + b.id * 31) % Math.max(1, n));
			if (s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
				s = b.states.get(0);
			}
			HX[heapCount] = at.x + HEAP[i][0] * 1.25;
			HY[heapCount] = at.y + HEAP[i][1] * 1.3;
			HZ[heapCount] = at.z + HEAP[i][2] * 1.25;
			HYAW[heapCount] = (i * 47 + b.id * 13) % 360;
			HTILT[heapCount] = HEAP[i][1] > 0.0F ? ((i * 29) % 30 - 15) : 0.0F;
			HSTATE[heapCount] = s;
			heapCount++;
		}
	}

	private static void renderHeaps(Minecraft mc, ClientLevel level, PoseStack stack, VertexConsumer buffer, BlockColors colors, CardinalLighting lighting) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int i = 0; i < heapCount; i++) {
			BlockState state = HSTATE[i];
			at.set(HX[i], HY[i] + 0.2, HZ[i]);
			PARTS.clear();
			try {
				RANDOM.setSeed(42L);
				mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RANDOM, PARTS);
			} catch (RuntimeException e) {
				continue;
			}
			int light = light(level, at);
			stack.pushPose();
			stack.translate(HX[i] - camX, HY[i] - camY, HZ[i] - camZ);
			stack.rotateDegrees(com.mojang.math.Axis.YP, HYAW[i]);
			stack.rotateDegrees(com.mojang.math.Axis.XP, HTILT[i]);
			stack.scale(0.52F, 0.52F, 0.52F);
			stack.translate(-0.5F, 0.0F, -0.5F);
			for (BlockStateModelPart part : PARTS) {
				for (Direction direction : DIRECTIONS) {
					for (BakedQuad quad : part.getQuads(direction)) {
						put(buffer, stack.last(), quad, state, level, at, light, 0xFFFFFFFF, lighting, colors);
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					put(buffer, stack.last(), quad, state, level, at, light, 0xFFFFFFFF, lighting, colors);
				}
			}
			stack.popPose();
		}
	}

	static void put(VertexConsumer buffer, PoseStack.Pose pose, BakedQuad quad, BlockState state, ClientLevel level, BlockPos at, int light, int tint,
		CardinalLighting lighting, BlockColors colors) {
		Direction shade = quad.materialInfo().shadeDirectionOverride();
		QUAD.setColor(ARGB.gray(lighting.byFace(shade != null ? shade : quad.direction())));
		int tintIndex = quad.materialInfo().tintIndex();
		if (tintIndex != -1) {
			BlockTintSource source = colors.getTintSource(state, tintIndex);
			if (source != null) {
				QUAD.multiplyColor(0xFF000000 | source.colorInWorld(state, level, at));
			}
		}
		QUAD.multiplyColor(tint);
		QUAD.setLightCoords(light);
		buffer.putBakedQuad(pose, quad, QUAD);
	}

	private static BlockState neighbour(ClientLevel level, BlockPos pos) {
		BlockState ghost = DRAWN.get(pos.asLong());
		return ghost != null ? ghost : level.getBlockState(pos);
	}

	static int light(ClientLevel level, BlockPos pos) {
		int coords = LightCoordsUtil.getLightCoords(level, pos);
		return LightCoordsUtil.max(coords, LightCoordsUtil.pack(MIN_LIGHT, 0));
	}
}
