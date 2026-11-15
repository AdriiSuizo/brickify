package dev.rusty.brickify.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;

/** While something is being built, the player's figure waves both hands over it like in the brick games. */
public final class BrickAnim {
	private BrickAnim() {
	}

	public static void apply(Model<?> model, Object renderState) {
		if (!(renderState instanceof BrickRenderState state) || !state.bk$building() || !(renderState instanceof LivingEntityRenderState living)) {
			return;
		}
		float t = living.ageInTicks;
		for (ModelInfo.Piece piece : ModelInfo.of(model).pieces) {
			boolean right = piece.name.equals("right_arm");
			boolean left = piece.name.equals("left_arm");
			if (!right && !left) {
				continue;
			}
			float phase = right ? 0.0F : Mth.PI;
			piece.part.xRot = -1.75F + Mth.sin(t * 0.9F + phase) * 0.45F;
			piece.part.yRot = 0.0F;
			piece.part.zRot = (right ? 1.0F : -1.0F) * (0.2F + Mth.cos(t * 0.9F + phase) * 0.12F);
		}
	}
}
