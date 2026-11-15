package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Asks for the tilt-shift post effect while the brick camera is out. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Shadow
	@Final
	private List<Identifier> requestedPostEffects;

	@Inject(method = "update", at = @At("TAIL"))
	private void bk$tiltShift(DeltaTracker deltaTracker, CallbackInfo ci) {
		Identifier effect = BrickCam.postEffect();
		if (effect != null) {
			this.requestedPostEffects.add(effect);
		}
	}
}
