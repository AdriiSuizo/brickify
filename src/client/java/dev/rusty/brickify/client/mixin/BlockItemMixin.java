package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BlockFx;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block the player places on this client snaps into place in the brick world. */
@Mixin(BlockItem.class)
public class BlockItemMixin {
	@Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
	private void bk$snap(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (context.getLevel().isClientSide() && cir.getReturnValue().consumesAction()) {
			BlockFx.placed(context.getClickedPos(), context.getLevel().getBlockState(context.getClickedPos()));
		}
	}
}
