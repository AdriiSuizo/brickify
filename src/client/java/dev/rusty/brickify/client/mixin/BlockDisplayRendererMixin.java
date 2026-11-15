package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.DisplayLook;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block displays of a falling tree (and the like) are drawn without the sides they share, in their own leaf colour. */
@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public class BlockDisplayRendererMixin {
	private static final String EXTRACT = "extractRenderState(Lnet/minecraft/world/entity/Display$BlockDisplay;Lnet/minecraft/client/renderer/entity/state/BlockDisplayEntityRenderState;F)V";

	@Inject(method = EXTRACT, at = @At("HEAD"))
	private void bk$lookBegin(Display.BlockDisplay entity, BlockDisplayEntityRenderState state, float partialTicks, CallbackInfo ci) {
		DisplayLook.begin(entity.getId());
	}

	@Inject(method = EXTRACT, at = @At("RETURN"))
	private void bk$lookEnd(Display.BlockDisplay entity, BlockDisplayEntityRenderState state, float partialTicks, CallbackInfo ci) {
		DisplayLook.end(entity, state);
	}
}
