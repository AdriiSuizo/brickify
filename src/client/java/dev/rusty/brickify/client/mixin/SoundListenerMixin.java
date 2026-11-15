package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import dev.rusty.brickify.client.editor.EditorCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The game hears from where the camera is. The brick camera stands ten or twenty blocks away, so quiet things
 * (steps, clicks, a smashed flower) faded out. The ears stay with the figure instead; which way they face still
 * comes from the camera, so left and right match the screen. In the diorama they are where the camera looks.
 */
@Mixin(SoundEngine.class)
public class SoundListenerMixin {
	@ModifyArg(method = "updateSource", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/audio/ListenerTransform;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"), index = 0)
	private Vec3 bk$earsAtTheFigure(Vec3 camera) {
		Minecraft mc = Minecraft.getInstance();
		if (EditorCam.active()) {
			return EditorCam.focus();
		}
		if (mc.player != null && BrickCam.active() && BrickCam.lastCamera() != null) {
			double k = Math.min(1.0, BrickCam.blend());
			return camera.add(mc.player.getEyePosition().subtract(camera).scale(k));
		}
		return camera;
	}
}
