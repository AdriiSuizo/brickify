package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The crosshair stays on screen with the brick camera, since the figure aims at the middle of the screen. */
@Mixin(Hud.class)
public class HudMixin {
	@Redirect(method = "extractCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
	private boolean bk$crosshair(CameraType type) {
		return type.isFirstPerson() || BrickCam.aiming();
	}
}
