package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickTextures;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every entity texture is fetched here when it is drawn; in the brick world the brick version is handed out. */
@Mixin(TextureManager.class)
public class TextureManagerMixin {
	@ModifyVariable(method = "getTexture", at = @At("HEAD"), argsOnly = true)
	private Identifier bk$brickTexture(Identifier location) {
		return BrickTextures.swap(location);
	}
}
