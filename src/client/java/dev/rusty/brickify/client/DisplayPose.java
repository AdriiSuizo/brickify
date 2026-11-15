package dev.rusty.brickify.client;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;

/**
 * Moves a block display and turns it in the same frame.
 *
 * <p>Vanilla only picks up a new transformation on the display's next tick, one tick after a new position, and with a
 * start delay that never changes it does not blend between ticks at all. Something re-anchored in one go (a falling
 * tree turning into loose bricks: the position jumps from the stump to each clump, the offset shrinks to match) then
 * flies apart for a tick and snaps back. Here the new transformation is applied right away and blends over the same
 * tick as the position; {@code jump} also skips the slide from the old place.
 */
final class DisplayPose {
	private DisplayPose() {
	}

	static void set(Display.BlockDisplay d, double x, double y, double z, Transformation t, boolean jump) {
		d.setPos(x, y, z);
		if (jump) {
			d.setOldPosAndRot();
		}
		Display.RenderState rs = d.renderState();
		if (!jump && rs != null && t.equals(rs.transformation().get(1.0F))) {
			// unchanged: restarting the blend would replay the last step
			return;
		}
		d.setTransformation(t);
		d.setTransformationInterpolationDuration(jump || rs == null ? 0 : 1);
		// a changed start delay makes the display start its blend from this tick
		d.setTransformationInterpolationDelay(-1);
		d.setTransformationInterpolationDelay(0);
		d.tick();
	}
}
