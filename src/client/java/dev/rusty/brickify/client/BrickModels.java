package dev.rusty.brickify.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import dev.rusty.brickify.Brickify;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.resources.model.BlockStateDefinitions;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * The brick look as a second model set. Blockstates under {@code brickify:blockstates/} (made from a brick
 * resource pack by {@code .tools/bk_pack.py}) are loaded next to vanilla's and baked as extra models, one per
 * block state. Every vanilla block model gets wrapped in a {@link BrickModel} that picks one of the two per block.
 */
public final class BrickModels {
	private static final FileToIdConverter STATES = FileToIdConverter.json("blockstates");
	private static volatile Map<BlockState, ExtraModelKey<BlockStateModel>> keys = Map.of();

	private BrickModels() {
	}

	public static void init() {
		PreparableModelLoadingPlugin.register(BrickModels::load, BrickModels::initialize);
	}

	public static int count() {
		return keys.size();
	}

	private static CompletableFuture<Map<BlockState, BlockStateModel.UnbakedRoot>> load(PreparableReloadListener.SharedState shared, Executor executor) {
		ResourceManager manager = shared.resourceManager();
		return CompletableFuture.supplyAsync(() -> {
			Function<Identifier, StateDefinition<Block, BlockState>> mapper = BlockStateDefinitions.definitionLocationToBlockStateMapper();
			Map<BlockState, BlockStateModel.UnbakedRoot> out = new IdentityHashMap<>();
			int files = 0;
			for (Map.Entry<Identifier, Resource> entry : STATES.listMatchingResources(manager).entrySet()) {
				Identifier file = entry.getKey();
				if (!file.getNamespace().equals(Brickify.MOD_ID)) {
					continue;
				}
				Identifier id = STATES.fileToId(file);
				StateDefinition<Block, BlockState> definition = mapper.apply(Identifier.withDefaultNamespace(id.getPath()));
				if (definition == null) {
					continue;
				}
				try (Reader reader = entry.getValue().openAsReader()) {
					JsonElement json = StrictJsonParser.parse(reader);
					BlockStateModelDispatcher dispatcher = BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonParseException::new);
					out.putAll(dispatcher.instantiate(definition, () -> "brickify " + id));
					files++;
				} catch (Exception e) {
					Brickify.LOGGER.warn("Brick blockstate {} could not be read: {}", id, e.toString());
				}
			}
			Brickify.LOGGER.info("Brick model set: {} blockstate files, {} block states", files, out.size());
			return out;
		}, executor);
	}

	private static void initialize(Map<BlockState, BlockStateModel.UnbakedRoot> roots, ModelLoadingPlugin.Context context) {
		Map<BlockState, ExtraModelKey<BlockStateModel>> made = new IdentityHashMap<>();
		roots.forEach((state, root) -> {
			ExtraModelKey<BlockStateModel> key = ExtraModelKey.create(() -> "brickify " + state);
			made.put(state, key);
			context.addModel(key, new Root(state, root));
		});
		keys = made;
		context.modifyBlockModelAfterBake().register((model, ctx) -> new BrickModel(model, ctx.state()));
	}

	/** The brick model for a block state, or null if the brick set has none (or is still loading). */
	public static @Nullable BlockStateModel brickOf(BlockState state) {
		ExtraModelKey<BlockStateModel> key = keys.get(state);
		if (key == null) {
			return null;
		}
		try {
			return Minecraft.getInstance().getModelManager().getModel(key);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private record Root(BlockState state, BlockStateModel.UnbakedRoot root) implements UnbakedExtraModel<BlockStateModel> {
		@Override
		public void resolveDependencies(ResolvableModel.Resolver resolver) {
			this.root.resolveDependencies(resolver);
		}

		@Override
		public BlockStateModel bake(ModelBaker baker) {
			return this.root.bake(this.state, baker);
		}
	}
}
