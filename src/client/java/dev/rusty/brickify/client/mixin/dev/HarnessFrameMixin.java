package dev.rusty.brickify.client.mixin.dev;

import dev.rusty.brickify.client.dev.Harness;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: lets the test harness grab every rendered frame while it records a clip. */
@Mixin(Minecraft.class)
public class HarnessFrameMixin {
	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void bk$afterFrame(boolean advanceGameTime, CallbackInfo ci) {
		Harness.onFrame((Minecraft) (Object) this);
	}
}
