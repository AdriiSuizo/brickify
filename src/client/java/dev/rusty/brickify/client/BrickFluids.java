package dev.rusty.brickify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The brick pack's water and lava textures, swapped in where the world is bricks. */
public final class BrickFluids {
	private static final Map<FluidModel, FluidModel> BRICK = new ConcurrentHashMap<>();

	private BrickFluids() {
	}

	public static FluidModel pick(FluidModel vanilla, BlockAndTintGetter level, BlockPos pos) {
		if (!BrickWorld.isBrick(level, pos)) {
			return vanilla;
		}
		return BRICK.computeIfAbsent(vanilla, BrickFluids::make);
	}

	private static FluidModel make(FluidModel vanilla) {
		Material.Baked still = swap(vanilla.stillMaterial());
		Material.Baked flowing = swap(vanilla.flowingMaterial());
		return new FluidModel(vanilla.layer(), still, flowing, vanilla.overlayMaterial(), vanilla.tintSource());
	}

	private static Material.Baked swap(Material.Baked material) {
		TextureAtlasSprite sprite = material.sprite();
		Identifier name = sprite.contents().name();
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(sprite.atlasLocation());
		if (!(texture instanceof TextureAtlas atlas) || !name.getNamespace().equals("minecraft")) {
			return material;
		}
		TextureAtlasSprite brick = atlas.getSprite(Identifier.fromNamespaceAndPath("brickify", name.getPath()));
		if (brick == atlas.missingSprite()) {
			return material;
		}
		return new Material.Baked(brick, material.forceTranslucent());
	}

	public static void clear() {
		BRICK.clear();
	}
}
