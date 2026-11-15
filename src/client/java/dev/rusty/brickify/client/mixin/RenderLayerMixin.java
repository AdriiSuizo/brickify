package dev.rusty.brickify.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rusty.brickify.client.BrickTextures;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Layer textures drawn on a known model (villager clothes, sheep wool ...) are made plastic for that model. */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {
	@Inject(method = "renderColoredCutoutModel", at = @At("HEAD"))
	private static void bk$modelOn(Model<?> model, Identifier texture, PoseStack poseStack, SubmitNodeCollector collector, int light,
		LivingEntityRenderState state, int color, int order, CallbackInfo ci) {
		BrickTextures.MODEL.set(model);
	}

	@Inject(method = "renderColoredCutoutModel", at = @At("RETURN"))
	private static void bk$modelOff(Model<?> model, Identifier texture, PoseStack poseStack, SubmitNodeCollector collector, int light,
		LivingEntityRenderState state, int color, int order, CallbackInfo ci) {
		BrickTextures.MODEL.remove();
	}
}
