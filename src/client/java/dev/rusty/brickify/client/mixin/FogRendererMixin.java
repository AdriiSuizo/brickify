package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.EditorCam;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No distance fog over the diorama: the plate should be clear to its edges from far out. */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void bk$clearFogInDiorama(CallbackInfoReturnable<FogData> cir) {
		if (!EditorCam.active()) {
			return;
		}
		FogData fog = cir.getReturnValue();
		fog.environmentalStart = 4096.0F;
		fog.environmentalEnd = 8192.0F;
		fog.renderDistanceStart = 4096.0F;
		fog.renderDistanceEnd = 8192.0F;
	}
}
