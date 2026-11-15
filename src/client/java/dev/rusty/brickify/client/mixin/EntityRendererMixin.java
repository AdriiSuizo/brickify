package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickEntities;
import dev.rusty.brickify.client.BrickRenderState;
import dev.rusty.brickify.client.Breakup;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks each entity as brick or not once its render state is filled in, lets it bounce, and passes on its break-up. */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
	private void bk$brickState(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
		EntityRenderState state = cir.getReturnValue();
		BrickRenderState brickState = (BrickRenderState) state;
		boolean brick = BrickEntities.isBrick(entity);
		brickState.bk$setBrick(brick);
		Breakup.fill(entity, partialTicks, brickState, BrickEntities.ticks());
		if (entity == net.minecraft.client.Minecraft.getInstance().player) {
			brickState.bk$setPlayer(dev.rusty.brickify.client.DoubleJump.flipAngle(partialTicks), brick && dev.rusty.brickify.client.BuildKey.building());
		} else {
			brickState.bk$setPlayer(0.0F, false);
		}
		if (brick && state instanceof LivingEntityRenderState living) {
			living.scale *= BrickEntities.popScale(entity, partialTicks);
		}
	}
}
