package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.EditorDraw;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The one place the game visits every frame to collect lines and text for the world: the editor adds its own. */
@Mixin(DebugRenderer.class)
public class DebugRendererMixin {
	@Inject(method = "emitGizmos", at = @At("TAIL"))
	private void bk$editor(Frustum frustum, double camX, double camY, double camZ, float partialTicks, CallbackInfo ci) {
		EditorDraw.draw(camX, camY, camZ);
	}
}
