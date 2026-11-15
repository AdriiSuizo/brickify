package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.Editor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a level is played, TNT that is set down (carried there and put on the ground) lights at once, like in the
 * brick games: carry it to the cracked wall, put it down, step back.
 */
@Mixin(TntBlock.class)
public class TntPlaceMixin {
	@Inject(method = "onPlace", at = @At("TAIL"))
	private void bk$lightWhenSetDown(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
		if (!level.isClientSide() && Editor.playing() && !dev.rusty.brickify.client.editor.Snapshot.restoring && Editor.walkThrough(pos) && !oldState.is(state.getBlock()) && level.getBlockState(pos).is(state.getBlock())) {
			TntBlock.prime(level, pos);
			level.removeBlock(pos, false);
		}
	}
}
