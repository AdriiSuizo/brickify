package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.Editor;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk meshes are built from this snapshot of the world. While the editor shows its diorama, everything outside the
 * base plate (and below it) reports air: the plate stands alone, its cut sides drawn like a slice of cake. Blocks
 * faded by the see-through window ({@link dev.rusty.brickify.client.SeeThrough}) report air too.
 */
@Mixin(RenderSectionRegion.class)
public class RenderSectionRegionMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void bk$cutBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (Editor.cuts(pos.getX(), pos.getY(), pos.getZ()) || dev.rusty.brickify.client.SeeThrough.hides(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void bk$cutFluids(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (Editor.cuts(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}

	@Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
	private void bk$cutBlockEntities(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
		if (Editor.cuts(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(null);
		}
	}
}
