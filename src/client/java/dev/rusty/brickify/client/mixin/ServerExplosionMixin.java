package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickExplosions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Explosions in the brick world knock blocks loose as bricks instead of destroying them (single player). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	public abstract ServerLevel level();

	@Shadow
	public abstract Vec3 center();

	@Shadow
	public abstract float radius();

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> bk$bricks(List<BlockPos> targets) {
		return BrickExplosions.capture(this.level(), this.center(), this.radius(), targets, ((ServerExplosion) (Object) this).getDirectSourceEntity());
	}
}
