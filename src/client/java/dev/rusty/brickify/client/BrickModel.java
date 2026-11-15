package dev.rusty.brickify.client;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Wraps a vanilla block model and draws the brick model instead wherever {@link BrickWorld} says so. */
public final class BrickModel implements BlockStateModel {
	private final BlockStateModel vanilla;
	private final BlockState state;
	private volatile @Nullable BlockStateModel brick;
	private volatile @Nullable StudInfo studs;

	/** What the studs on top of this block look like: the colour of its flat top, or none. */
	private record StudInfo(@Nullable TextureAtlasSprite sprite, int tint, @Nullable ChunkSectionLayer layer) {
	}

	private static final StudInfo NO_STUDS = new StudInfo(null, -1, null);

	public BrickModel(BlockStateModel vanilla, BlockState state) {
		this.vanilla = vanilla;
		this.state = state;
	}

	private @Nullable BlockStateModel brick() {
		BlockStateModel b = this.brick;
		if (b == null) {
			b = BrickModels.brickOf(this.state);
			this.brick = b;
		}
		return b;
	}

	private StudInfo studs() {
		StudInfo info = this.studs;
		if (info != null) {
			return info;
		}
		BlockStateModel b = this.brick();
		if (b == null) {
			return NO_STUDS;
		}
		info = NO_STUDS;
		List<BlockStateModelPart> parts = new ArrayList<>();
		b.collectParts(RandomSource.create(42L), parts);
		for (BlockStateModelPart part : parts) {
			for (BakedQuad quad : part.getQuads(Direction.UP)) {
				if (!fullTop(quad)) {
					continue;
				}
				TextureAtlasSprite sprite = quad.materialInfo().sprite();
				String path = sprite.contents().name().getPath();
				// Flat one-colour tops are plates in the brick world; tiles, planks and prints stay smooth.
				if (path.startsWith("plasticraft/colors/") && !path.contains("trans_")) {
					info = new StudInfo(sprite, quad.materialInfo().tintIndex(), quad.materialInfo().layer());
				}
			}
		}
		this.studs = info;
		return info;
	}

	private static boolean fullTop(BakedQuad quad) {
		float minX = 1;
		float maxX = 0;
		float minZ = 1;
		float maxZ = 0;
		for (int i = 0; i < 4; i++) {
			var p = quad.position(i);
			if (Math.abs(p.y() - 1.0F) > 1.0E-4F) {
				return false;
			}
			minX = Math.min(minX, p.x());
			maxX = Math.max(maxX, p.x());
			minZ = Math.min(minZ, p.z());
			maxZ = Math.max(maxZ, p.z());
		}
		return minX < 1.0E-4F && minZ < 1.0E-4F && maxX > 0.9999F && maxZ > 0.9999F;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
		if (BlockFx.hidden(pos)) {
			return;
		}
		int cull = level instanceof net.minecraft.client.renderer.chunk.RenderSectionRegion ? 0 : DisplayLook.cull();
		if (cull != 0) {
			// a display that is part of a group: the sides touching the group stay hidden, as in the world
			Predicate<@Nullable Direction> outer = cullTest;
			cullTest = dir -> dir != null && (cull & 1 << dir.ordinal()) != 0 || outer.test(dir);
		}
		BlockStateModel b = BrickWorld.isBrick(level, pos) ? this.brick() : null;
		(b != null ? b : this.vanilla).emitQuads(emitter, level, pos, state, random, cullTest);
		if (b != null && !cullTest.test(Direction.UP) && BrickWorld.studsAt(level, pos)) {
			StudInfo info = this.studs();
			if (info.sprite() != null) {
				Studs.emit(emitter, info.sprite(), info.tint(), info.layer());
			}
		}
	}

	@Override
	public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		// The same block can look two ways, so nothing may be cached by geometry.
		return null;
	}

	@Override
	public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
		BlockStateModel b = BrickWorld.on() ? this.brick() : null;
		int start = output.size();
		(b != null ? b : this.vanilla).collectParts(random, output);
		int cull = DisplayLook.cull();
		if (cull != 0) {
			for (int i = start; i < output.size(); i++) {
				output.set(i, new CulledPart(output.get(i), cull));
			}
		}
	}

	/** A model part without the faces that would be hidden against a neighbour on the culled sides. */
	private record CulledPart(BlockStateModelPart part, int cull) implements BlockStateModelPart {
		@Override
		public List<BakedQuad> getQuads(@Nullable Direction direction) {
			return direction != null && (this.cull & 1 << direction.ordinal()) != 0 ? List.of() : this.part.getQuads(direction);
		}

		@Override
		public boolean useAmbientOcclusion() {
			return this.part.useAmbientOcclusion();
		}

		@Override
		public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
			return this.part.particleMaterial();
		}

		@Override
		public int materialFlags() {
			return this.part.materialFlags();
		}
	}

	@Override
	public Material.Baked particleMaterial() {
		return this.vanilla.particleMaterial();
	}

	@Override
	public int materialFlags() {
		BlockStateModel b = this.brick();
		return this.vanilla.materialFlags() | (b == null ? 0 : b.materialFlags());
	}
}
