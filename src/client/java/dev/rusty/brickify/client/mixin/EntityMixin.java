package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the brick camera is out, the mouse tilts the camera instead of the head. */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void bk$camTurn(double xo, double yo, CallbackInfo ci) {
		if (BrickCam.turn((Entity) (Object) this, xo, yo)) {
			ci.cancel();
		}
	}
}
