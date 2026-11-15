package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game only re-checks which sections are on screen when the camera turns. While the brick camera glides
 * out it moves without turning much, so the check is forced every frame then (edges would stay empty).
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow
	private double prevCamRotX;

	@Inject(method = "extract", at = @At("HEAD"))
	private void bk$alwaysApplyFrustum(CallbackInfo ci) {
		if (BrickCam.active() || dev.rusty.brickify.client.editor.EditorCam.active()) {
			this.prevCamRotX = Double.MIN_VALUE;
		}
	}
}
