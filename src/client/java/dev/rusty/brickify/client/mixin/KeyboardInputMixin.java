package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With the fixed camera the keys point on the screen: W walks away from the camera, D to its right. The figure turns
 * that way (quickly, not at once) and walks forward; it looks a little down, at what is in front of it.
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void bk$screenRelative(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || !BrickCam.legoActive() || p.isPassenger() || p.getAbilities().flying) {
			return;
		}
		Input k = ((ClientInput) (Object) this).keyPresses;
		float f = (k.forward() ? 1.0F : 0.0F) - (k.backward() ? 1.0F : 0.0F);
		float r = (k.right() ? 1.0F : 0.0F) - (k.left() ? 1.0F : 0.0F);
		p.setXRot(BrickCam.legoAimPitch);
		if (f == 0.0F && r == 0.0F) {
			BrickCam.inputYaw(false);
			return;
		}
		// the keys keep their meaning while held, even when the camera cuts to another angle
		float want = BrickCam.inputYaw(true) + (float) Math.toDegrees(Math.atan2(r, f));
		float cur = p.getYRot();
		float diff = Mth.wrapDegrees(want - cur);
		p.setYRot(cur + Mth.clamp(diff, -38.0F, 38.0F));
		// turning round on the spot first, then off
		float speed = Math.abs(diff) > 100.0F ? 0.35F : 1.0F;
		((ClientInputAccessor) this).bk$setMoveVector(new Vec2(0.0F, speed));
	}
}
