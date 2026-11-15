package dev.rusty.brickify.client.mixin.dev;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Dev only: with -Dbk.hidden=true the game window is created hidden, so background tests never pop up or steal focus. */
@Mixin(Window.class)
public class HarnessWindowMixin {
	private static final long SDL_WINDOW_HIDDEN = 0x8L;

	@ModifyArg(
		method = "createWindow",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),
		index = 3
	)
	private long bk$createHidden(long flags) {
		return Boolean.getBoolean("bk.hidden") ? flags | SDL_WINDOW_HIDDEN : flags;
	}
}
