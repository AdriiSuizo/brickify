package dev.rusty.brickify.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * When a house (or a hill, or a tree) stands between the fixed camera and the figure, a round window opens in it:
 * the blocks inside a soft circle around the line from the camera to the figure fade out, the middle almost to
 * nothing, the rim only a little. It opens like a camera iris and closes again when the figure walks back out.
 * <p>
 * The faded blocks leave the chunk meshes (they report air to the meshing threads, see {@link #hides}) and are
 * drawn again every frame as see-through models instead. A block that comes back is still drawn for a moment while
 * its section is rebuilt, so nothing flickers.
 */
public final class SeeThrough {
	public static boolean enabled = true;
	/** Radius of the window at the figure, in blocks. */
	public static float radius = 3.6F;
	/** Width of the soft rim, in blocks. */
	public static float soft = 1.9F;
	/** How much is left of the blocks in the middle of the window. */
	public static float minAlpha = 0.06F;
	private static final int BUDGET = 6000;
	private static final int LINGER_TICKS = 5;
	private static final Direction[] DIRECTIONS = Direction.values();

	/** Read by the chunk meshing threads; replaced as a whole, never changed. */
	private static volatile LongOpenHashSet hidden = new LongOpenHashSet();
	private static final Long2FloatOpenHashMap TARGET = new Long2FloatOpenHashMap();
	private static final Long2FloatOpenHashMap ALPHA = new Long2FloatOpenHashMap();
	private static final Long2IntOpenHashMap LINGER = new Long2IntOpenHashMap();
	private static final Long2ObjectOpenHashMap<BlockState> STATES = new Long2ObjectOpenHashMap<>();
	private static float open;
	private static int ticks;
	private static int lastHidden = -1000;
	private static long lastNanos;

	private static final long[] POS = new long[BUDGET];
	private static final float[] DRAW_ALPHA = new float[BUDGET];
	private static final double[] DISTANCE = new double[BUDGET];
	private static final int[] ORDER = new int[BUDGET];
	private static int count;
	private static double camX;
	private static double camY;
	private static double camZ;
	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();
	private static final QuadInstance QUAD = new QuadInstance();
	private static final RandomSource RANDOM = RandomSource.create(0L);

	static {
		ALPHA.defaultReturnValue(1.0F);
		TARGET.defaultReturnValue(1.0F);
	}

	private SeeThrough() {
	}

	/** Meshing threads: this block is drawn see-through, not in the chunk mesh. */
	public static boolean hides(int x, int y, int z) {
		LongOpenHashSet h = hidden;
		return !h.isEmpty() && h.contains(BlockPos.asLong(x, y, z));
	}

	public static boolean active() {
		return open > 0.0F || !hidden.isEmpty();
	}

	public static void forget() {
		hidden = new LongOpenHashSet();
		TARGET.clear();
		ALPHA.clear();
		LINGER.clear();
		STATES.clear();
		open = 0.0F;
	}

	/** Once a client tick: is the figure hidden, how far is the window open, which blocks fade how far. */
	public static void tick(Minecraft mc) {
		ticks++;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		Vec3 cam = BrickCam.lastCamera();
		boolean on = enabled && player != null && level != null && cam != null && BrickWorld.mode() != BrickWorld.OFF && BrickCam.blend() > 0.6
			&& !dev.rusty.brickify.client.editor.Editor.editing() && !mc.options.getCameraType().isFirstPerson() && !BrickCam.closeCamera();
		Vec3 body = on ? player.position().add(0.0, 0.9, 0.0) : null;
		if (on && hiddenFrom(level, player, cam)) {
			lastHidden = ticks;
		}
		float want = on && ticks - lastHidden < 14 ? 1.0F : 0.0F;
		open += (want - open) * (want > open ? 0.22F : 0.16F);
		if (want == 0.0F && open < 0.02F) {
			open = 0.0F;
		}
		TARGET.clear();
		if (open > 0.0F && body != null) {
			collect(level, player, body, cam);
		}
		// which blocks stay out of the meshes: those fading, and those not yet fully back
		LongOpenHashSet next = new LongOpenHashSet();
		for (long k : TARGET.keySet()) {
			next.add(k);
		}
		var it = ALPHA.long2FloatEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			long k = e.getLongKey();
			BlockState was = STATES.get(k);
			boolean changed = level == null || was == null || level.getBlockState(BlockPos.of(k)) != was;
			if (TARGET.containsKey(k) || e.getFloatValue() < 0.985F && !changed) {
				next.add(k);
			} else {
				it.remove();
			}
		}
		LongOpenHashSet before = hidden;
		if (next.equals(before)) {
			tickLinger();
			return;
		}
		// sections to build again: every block that went in or came out, with the neighbours at section edges
		LongOpenHashSet sections = new LongOpenHashSet();
		for (long k : next) {
			if (!before.contains(k)) {
				addSections(sections, k);
			}
		}
		for (long k : before) {
			if (!next.contains(k)) {
				addSections(sections, k);
				LINGER.put(k, LINGER_TICKS);
			}
		}
		hidden = next;
		for (long s : sections) {
			mc.levelExtractor.setSectionDirty(SectionPos.x(s), SectionPos.y(s), SectionPos.z(s));
		}
		tickLinger();
	}

	private static void tickLinger() {
		var it = LINGER.long2IntEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (hidden.contains(e.getLongKey()) || e.getIntValue() <= 1) {
				it.remove();
			} else {
				e.setValue(e.getIntValue() - 1);
			}
		}
		if (LINGER.isEmpty() && hidden.isEmpty()) {
			STATES.clear();
		}
	}

	private static void addSections(LongOpenHashSet out, long k) {
		int x = BlockPos.getX(k);
		int y = BlockPos.getY(k);
		int z = BlockPos.getZ(k);
		for (int dx = -1; dx <= 1; dx += 2) {
			out.add(SectionPos.asLong(SectionPos.blockToSectionCoord(x + dx), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z)));
		}
		for (int dy = -1; dy <= 1; dy += 2) {
			out.add(SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y + dy), SectionPos.blockToSectionCoord(z)));
		}
		for (int dz = -1; dz <= 1; dz += 2) {
			out.add(SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z + dz)));
		}
		out.add(SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z)));
	}

	/** Something solid between the camera and the feet, the middle or the head of the figure. */
	private static boolean hiddenFrom(ClientLevel level, LocalPlayer player, Vec3 cam) {
		Vec3 p = player.position();
		double[] heights = {0.35, 0.95, 1.65};
		for (double h : heights) {
			Vec3 from = p.add(0.0, h, 0.0);
			HitResult hit = level.clip(new ClipContext(from, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(from) > 0.04) {
				return true;
			}
		}
		return false;
	}

	/** The blocks inside the window and how far each fades (the middle most, the rim least). */
	private static void collect(ClientLevel level, LocalPlayer player, Vec3 body, Vec3 cam) {
		Vec3 axis = cam.subtract(body);
		double length = Math.min(48.0, axis.length());
		if (length < 1.5) {
			return;
		}
		axis = axis.scale(1.0 / axis.length());
		double t = 1.0 - Math.pow(1.0 - open, 3.0);
		double r = radius * t;
		double inner = Math.max(0.0, r - soft);
		Vec3 end = body.add(axis.scale(length));
		int feet = (int) Math.floor(player.getY() + 0.01);
		int x0 = (int) Math.floor(Math.min(body.x, end.x) - r - 1);
		int x1 = (int) Math.floor(Math.max(body.x, end.x) + r + 1);
		int y0 = Math.max(feet, (int) Math.floor(Math.min(body.y, end.y) - r - 1));
		int y1 = (int) Math.floor(Math.max(body.y, end.y) + r + 1);
		int z0 = (int) Math.floor(Math.min(body.z, end.z) - r - 1);
		int z1 = (int) Math.floor(Math.max(body.z, end.z) + r + 1);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					double qx = x + 0.5 - body.x;
					double qy = y + 0.5 - body.y;
					double qz = z + 0.5 - body.z;
					double along = qx * axis.x + qy * axis.y + qz * axis.z;
					if (along < 0.45 || along > length - 0.8) {
						continue;
					}
					double px = qx - axis.x * along;
					double py = qy - axis.y * along;
					double pz = qz - axis.z * along;
					double off = Math.sqrt(px * px + py * py + pz * pz);
					if (off > r) {
						continue;
					}
					BlockState s = level.getBlockState(p.set(x, y, z));
					if (s.isAir() || s.getRenderShape() != RenderShape.MODEL || !s.getFluidState().isEmpty() || s.hasBlockEntity()
						|| s.getCollisionShape(level, p).isEmpty()) {
						continue;
					}
					double edge = r - inner < 1.0E-3 ? 1.0 : (off - inner) / (r - inner);
					edge = Math.max(0.0, Math.min(1.0, edge));
					edge = edge * edge * (3.0 - 2.0 * edge);
					float a = (float) (minAlpha + (1.0 - minAlpha) * edge);
					if (a > 0.97F) {
						continue;
					}
					long k = p.asLong();
					TARGET.put(k, a);
					STATES.put(k, s);
				}
			}
		}
	}

	// ---------------------------------------------------------------- drawing

	/** Once a frame where the game submits its moving blocks: the faded blocks, see-through. */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera) {
		count = 0;
		long now = System.nanoTime();
		double dt = lastNanos == 0L ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		LongOpenHashSet h = hidden;
		if (h.isEmpty() && LINGER.isEmpty()) {
			return;
		}
		float k = (float) (1.0 - Math.exp(-dt * 7.0));
		for (long key : h) {
			float target = TARGET.get(key);
			float a = ALPHA.get(key);
			a += (target - a) * k;
			if (!TARGET.containsKey(key) && a > 0.985F) {
				a = 0.99F;
			}
			ALPHA.put(key, a);
			add(key, a, camera);
		}
		for (long key : LINGER.keySet()) {
			if (!h.contains(key)) {
				add(key, 1.0F, camera);
			}
		}
		if (count == 0) {
			return;
		}
		IntArrays.quickSort(ORDER, 0, count, (a, b) -> Double.compare(DISTANCE[b], DISTANCE[a]));
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;
		collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(), SeeThrough::render);
	}

	private static void add(long key, float a, Vec3 camera) {
		if (count >= BUDGET || a < 0.015F || !STATES.containsKey(key)) {
			return;
		}
		POS[count] = key;
		DRAW_ALPHA[count] = a;
		double dx = BlockPos.getX(key) + 0.5 - camera.x;
		double dy = BlockPos.getY(key) + 0.5 - camera.y;
		double dz = BlockPos.getZ(key) + 0.5 - camera.z;
		DISTANCE[count] = dx * dx + dy * dy + dz * dz;
		ORDER[count] = count;
		count++;
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
		LongOpenHashSet h = hidden;
		for (int n = 0; n < count; n++) {
			int i = ORDER[n];
			long key = POS[i];
			BlockState state = STATES.get(key);
			if (state == null || level.getBlockState(at.set(key)) != state) {
				continue;
			}
			float alpha = DRAW_ALPHA[i];
			at.set(key);
			PARTS.clear();
			try {
				RANDOM.setSeed(state.getSeed(at));
				mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RANDOM, PARTS);
			} catch (RuntimeException e) {
				continue;
			}
			int best = 0;
			for (Direction d : DIRECTIONS) {
				near.setWithOffset(at, d);
				best = LightCoordsUtil.max(best, LightCoordsUtil.getLightCoords(level, near));
			}
			best = LightCoordsUtil.max(best, LightCoordsUtil.pack(6, 0));
			int tint = ((int) (alpha * 255.0F) & 255) << 24 | 0xFFFFFF;
			stack.pushPose();
			stack.translate(at.getX() - camX, at.getY() - camY, at.getZ() - camZ);
			for (BlockStateModelPart part : PARTS) {
				for (Direction direction : DIRECTIONS) {
					List<BakedQuad> quads = part.getQuads(direction);
					if (quads.isEmpty()) {
						continue;
					}
					near.setWithOffset(at, direction);
					long nk = near.asLong();
					BlockState other = level.getBlockState(near);
					int light;
					if (h.contains(nk) || LINGER.containsKey(nk)) {
						// towards another faded block: only where that one is clearer (the inside of the window's rim)
						if (ALPHA.get(nk) >= alpha - 0.04F || LINGER.containsKey(nk) && !h.contains(nk)) {
							continue;
						}
						light = best;
					} else {
						if (!Block.shouldRenderFace(state, other, direction)) {
							continue;
						}
						light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, near), LightCoordsUtil.pack(6, 0));
					}
					for (BakedQuad quad : quads) {
						put(buffer, stack.last(), quad, state, level, at, light, tint, lighting, colors);
					}
				}
				for (BakedQuad quad : part.getQuads(null)) {
					put(buffer, stack.last(), quad, state, level, at, best, tint, lighting, colors);
				}
			}
			stack.popPose();
		}
	}

	private static void put(VertexConsumer buffer, PoseStack.Pose pose, BakedQuad quad, BlockState state, ClientLevel level, BlockPos at, int light, int tint,
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
}
