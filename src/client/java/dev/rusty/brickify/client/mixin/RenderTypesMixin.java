package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickTextures;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The texture an entity render type is made for, swapped to the brick one while a brick entity is submitted. */
@Mixin(RenderTypes.class)
public class RenderTypesMixin {
	@ModifyVariable(
		method = {"entitySolid", "entitySolidZOffsetForward", "entityCutoutCull", "entityCutout", "entityCutoutZOffset", "entityTranslucentCull",
			"entityTranslucent", "entityTranslucentEmissive", "eyes", "outline", "armorCutoutNoCull"},
		at = @At("HEAD"), argsOnly = true, ordinal = 0
	)
	private static Identifier bk$brickTexture(Identifier texture) {
		return BrickTextures.forEntity(texture);
	}
}
