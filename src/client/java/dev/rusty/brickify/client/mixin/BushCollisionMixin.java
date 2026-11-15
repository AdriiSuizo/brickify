package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.Editor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On a level's plate the thick azalea bushes are only for the eye: the figure (and the mobs) walk through them. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BushCollisionMixin {
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void bk$walkThrough(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		Block b = ((BlockBehaviour.BlockStateBase) (Object) this).getBlock();
		if ((b == Blocks.AZALEA || b == Blocks.FLOWERING_AZALEA) && Editor.walkThrough(pos)) {
			cir.setReturnValue(Shapes.empty());
		}
	}
}
