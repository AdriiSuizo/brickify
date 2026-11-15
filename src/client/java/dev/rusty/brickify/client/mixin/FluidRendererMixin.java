package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickFluids;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Water and lava turn into clear plastic with the rest of the world. */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Redirect(
		method = "tesselate",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidStateModelSet;get(Lnet/minecraft/world/level/material/FluidState;)Lnet/minecraft/client/renderer/block/FluidModel;")
	)
	private FluidModel bk$brickFluid(FluidStateModelSet models, FluidState state, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState, FluidState fluidState) {
		return BrickFluids.pick(models.get(state), level, pos);
	}
}
