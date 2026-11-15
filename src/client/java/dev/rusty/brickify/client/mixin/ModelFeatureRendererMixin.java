package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.Breakup;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after a model is posed for drawing: a figure that is coming apart gets its parts moved. */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
	private void bk$breakup(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		Breakup.apply(submit.model(), submit.state());
		dev.rusty.brickify.client.BrickAnim.apply(submit.model(), submit.state());
	}
}
