package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickEntities;
import dev.rusty.brickify.client.Breakup;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mob that fell apart into bricks leaves no white death cloud: the heap of bricks is the point. */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	@Inject(method = "makePoofParticles", at = @At("HEAD"), cancellable = true)
	private void bk$noPoof(CallbackInfo ci) {
		if (BrickEntities.hidden((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}

	/** The parts of a figure that came apart can't be hit (they only exist on this client). */
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void bk$notPickable(CallbackInfoReturnable<Boolean> cir) {
		if (Breakup.isCopy((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	/** In a level being played, a blast only knocks the figure about a bit: its own TNT must not end the run. */
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float bk$softBlasts(float damage, net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
		if ((Object) this instanceof net.minecraft.world.entity.player.Player && dev.rusty.brickify.client.editor.Editor.playing()
			&& source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
			return Math.min(damage, 3.0F);
		}
		return damage;
	}
}
