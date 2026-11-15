package dev.rusty.brickify.client.mixin.dev;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: a hidden test client must never grab the real mouse cursor. */
@Mixin(MouseHandler.class)
public class HarnessMouseMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void bk$noGrabWhenHidden(CallbackInfo ci) {
		if (Boolean.getBoolean("bk.hidden")) {
			ci.cancel();
		}
	}
}
