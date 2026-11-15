package dev.rusty.brickify.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rusty.brickify.client.BrickEntities;
import dev.rusty.brickify.client.BrickRenderState;
import dev.rusty.brickify.client.BrickTextures;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a brick entity is submitted, every texture it asks for is swapped for the brick one (see RenderTypesMixin).
 * Mobs that fell apart into bricks are not drawn any more.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void bk$beginEntity(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		BrickTextures.setCurrent(((BrickRenderState) state).bk$isBrick());
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void bk$endEntity(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		BrickTextures.setCurrent(false);
	}

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void bk$hideShattered(Entity entity, Frustum culler, double camX, double camY, double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
		if (BrickEntities.hidden(entity) || dev.rusty.brickify.client.Breakup.assembling(entity)
			|| dev.rusty.brickify.client.editor.Editor.cuts(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ())) {
			cir.setReturnValue(false);
		} else if (dev.rusty.brickify.client.Breakup.isCopy(entity)) {
			cir.setReturnValue(true);
		}
	}
}
