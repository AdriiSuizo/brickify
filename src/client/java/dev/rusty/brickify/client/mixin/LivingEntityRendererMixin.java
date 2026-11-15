package dev.rusty.brickify.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rusty.brickify.client.BrickTextures;
import dev.rusty.brickify.client.Breakup;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Layers (held items, head studs, clothes) follow the parts of a figure that is coming apart, and the main
 * texture of a brick mob is made plastic for its own model.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD")
	)
	private void bk$newFigure(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		BrickTextures.newFigure();
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER)
	)
	private void bk$breakupLayers(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		Breakup.apply(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), state);
		dev.rusty.brickify.client.BrickAnim.apply(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(), state);
	}

	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void bk$flip(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale, CallbackInfo ci) {
		float flip = ((dev.rusty.brickify.client.BrickRenderState) state).bk$flip();
		if (flip != 0.0F) {
			float h = state.boundingBoxHeight * 0.5F;
			poseStack.translate(0.0F, h, 0.0F);
			poseStack.rotate(new org.joml.Quaternionf().rotationX(flip));
			poseStack.translate(0.0F, -h, 0.0F);
		}
	}

	@Inject(method = "getRenderType", at = @At("HEAD"))
	private void bk$modelOn(LivingEntityRenderState state, boolean bodyVisible, boolean transparent, boolean glowing, CallbackInfoReturnable<RenderType> cir) {
		BrickTextures.MODEL.set(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel());
	}

	@Inject(method = "getRenderType", at = @At("RETURN"))
	private void bk$modelOff(LivingEntityRenderState state, boolean bodyVisible, boolean transparent, boolean glowing, CallbackInfoReturnable<RenderType> cir) {
		BrickTextures.MODEL.remove();
	}

	/** The figure aims a little down with the fixed camera, but its head shows it looking straight ahead. */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void bk$headLevel(net.minecraft.world.entity.LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (entity == net.minecraft.client.Minecraft.getInstance().player && dev.rusty.brickify.client.BrickCam.legoActive()) {
			state.xRot = 0.0F;
		}
	}
}
