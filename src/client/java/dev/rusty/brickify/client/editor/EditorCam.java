package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The diorama camera: the level lies on the table, the camera circles around it. It flies out from wherever the
 * normal camera is and back into it, and follows every move softly.
 */
public final class EditorCam {
	public static float fov = 45.0F;
	public static double enterSeconds = 0.9;
	public static double leaveSeconds = 0.7;
	public static float minPitch = 12.0F;
	public static float maxPitch = 88.0F;

	private static double blend;
	private static double target;
	private static long lastNanos;
	private static Vec3 focus = Vec3.ZERO;
	private static float yaw;
	private static float pitch = 50.0F;
	private static double dist = 60.0;
	private static Vec3 sFocus = Vec3.ZERO;
	private static float sYaw;
	private static float sPitch = 50.0F;
	private static double sDist = 60.0;
	private static BrickCam.@Nullable Pose last;

	private EditorCam() {
	}

	/** Out to the table, looking at the whole plate from the side the player looks from. */
	public static void enter(LevelData d, float playerYaw, boolean instant) {
		focus = d.centre();
		yaw = Math.round(playerYaw / 45.0F) * 45.0F + 0.0F;
		pitch = 52.0F;
		dist = Math.max(d.sizeX(), d.sizeZ()) * 1.25 + 8.0;
		if (blend <= 0.0 || instant) {
			sFocus = focus;
			sYaw = yaw;
			sPitch = pitch;
			sDist = dist;
		}
		target = 1.0;
		if (instant) {
			blend = 1.0;
		}
	}

	public static void leave() {
		target = 0.0;
	}

	public static void reset() {
		blend = 0.0;
		target = 0.0;
		last = null;
		lastNanos = 0L;
	}

	public static boolean active() {
		return blend > 0.0 || target > 0.0;
	}

	/** Fully out on the table (input to the diorama makes sense). */
	public static boolean settled() {
		return blend >= 1.0 && target >= 1.0;
	}

	public static BrickCam.@Nullable Pose last() {
		return last;
	}

	/** The pose this frame, blended from the normal one; null when the diorama is not shown. */
	public static BrickCam.@Nullable Pose update(BrickCam.Pose base) {
		long now = System.nanoTime();
		double dt = lastNanos == 0L ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		if (target > blend) {
			blend = Math.min(target, blend + dt / enterSeconds);
		} else if (target < blend) {
			blend = Math.max(target, blend - dt / leaveSeconds);
		}
		if (blend <= 0.0) {
			last = null;
			return null;
		}
		keys(dt);
		float k = (float) (1.0 - Math.exp(-dt * 12.0));
		sFocus = sFocus.add(focus.subtract(sFocus).scale(k));
		sYaw += Mth.wrapDegrees(yaw - sYaw) * k;
		sPitch += (pitch - sPitch) * k;
		sDist += (dist - sDist) * k;
		Vec3 dir = Vec3.directionFromRotation(sPitch, sYaw);
		Vec3 table = sFocus.subtract(dir.scale(sDist));
		double e = ease(blend);
		Vec3 pos = base.position().add(table.subtract(base.position()).scale(e));
		float y = base.yaw() + Mth.wrapDegrees(sYaw - base.yaw()) * (float) e;
		float p = (float) Mth.lerp(e, base.pitch(), sPitch);
		float f = (float) Mth.lerp(e, base.fov(), fov);
		BrickCam.Pose pose = new BrickCam.Pose(pos, y, p, f, true);
		last = pose;
		return pose;
	}

	// ---------------------------------------------------------------- moving it

	/** Mouse drag with the right button: around the plate and up and down. */
	public static void orbit(double dx, double dy) {
		if (Math.abs(dx) + Math.abs(dy) > 0.5) {
			Tutorial.note("orbit");
		}
		yaw += (float) dx * 0.35F;
		pitch = Mth.clamp(pitch + (float) dy * 0.3F, minPitch, maxPitch);
	}

	/** Drag with the middle button (or WASD): the table slides under the camera. */
	public static void pan(double right, double forward) {
		Tutorial.note("pan");
		double s = dist * 0.0018;
		Vec3 fwd = Vec3.directionFromRotation(0.0F, yaw);
		Vec3 side = new Vec3(-fwd.z, 0.0, fwd.x);
		focus = focus.add(side.scale(-right * s)).add(fwd.scale(forward * s));
		clampFocus();
	}

	public static void zoom(double steps) {
		Tutorial.note("zoom");
		dist = Mth.clamp(dist * Math.pow(0.88, steps), 5.0, 220.0);
	}

	public static void turn(float degrees) {
		yaw += degrees;
	}

	public static void lookAt(Vec3 at) {
		focus = at;
		clampFocus();
	}

	public static Vec3 focus() {
		return focus;
	}

	public static double distance() {
		return dist;
	}

	/** The diorama camera goes to where the camera of this area will be while playing. */
	public static void preview(LevelData.CamZone z) {
		Tutorial.note("camedit");
		LevelData d = Editor.level();
		double y = d != null ? d.floorY + 7.0 : focus.y;
		focus = z.centre(y);
		yaw = z.yaw;
		pitch = z.pitch;
		dist = z.dist;
	}

	private static void clampFocus() {
		LevelData d = Editor.level();
		if (d == null) {
			return;
		}
		focus = new Vec3(Mth.clamp(focus.x, d.minX - 8, d.maxX + 9), Mth.clamp(focus.y, d.floorY - 4, d.topY), Mth.clamp(focus.z, d.minZ - 8, d.maxZ + 9));
	}

	/**
	 * WASD fly over the plate (relative to the view), Space and C up and down, Q and E turn (only while the
	 * diorama screen is open). The keys are asked by their scan codes (the game's own KEY_ constants).
	 */
	private static void keys(double dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.gui.screen() instanceof EditorScreen) || target <= 0.0) {
			return;
		}
		var w = mc.getWindow();
		double speed = 330.0 * dt;
		double r = 0.0;
		double f = 0.0;
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_W)) {
			f += speed;
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_S)) {
			f -= speed;
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_D)) {
			r -= speed;
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_A)) {
			r += speed;
		}
		if (r != 0.0 || f != 0.0) {
			pan(r, f);
		}
		double up = 0.0;
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_SPACE)) {
			up += 1.0;
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_C)) {
			up -= 1.0;
		}
		if (up != 0.0) {
			focus = focus.add(0.0, up * Math.max(4.0, dist * 0.45) * dt, 0.0);
			clampFocus();
			Tutorial.note("pan");
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_Q)) {
			yaw -= (float) (90.0 * dt);
			Tutorial.note("orbit");
		}
		if (EditorScreen.down(w, com.mojang.blaze3d.platform.InputConstants.KEY_E)) {
			yaw += (float) (90.0 * dt);
			Tutorial.note("orbit");
		}
	}

	/** Ray through a point of the screen (0..1 from the top left) with the pose of the last frame. */
	public static Vec3 @Nullable [] ray(double nx, double ny) {
		BrickCam.Pose p = last;
		if (p == null) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 up = right.cross(fwd).normalize();
		double t = Math.tan(Math.toRadians(p.fov()) / 2.0);
		double x = (nx * 2.0 - 1.0) * t * aspect;
		double y = (1.0 - ny * 2.0) * t;
		Vec3 dir = fwd.add(right.scale(x)).add(up.scale(y)).normalize();
		return new Vec3[] {p.position(), dir};
	}

	/** Where a point of the world is on the screen (0..1), or null behind the camera. */
	public static double @Nullable [] toScreen(Vec3 at) {
		BrickCam.Pose p = last;
		if (p == null) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
		Vec3 right = fwd.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 up = right.cross(fwd).normalize();
		Vec3 d = at.subtract(p.position());
		double z = d.dot(fwd);
		if (z < 0.1) {
			return null;
		}
		double t = Math.tan(Math.toRadians(p.fov()) / 2.0);
		double x = d.dot(right) / z / (t * aspect);
		double y = d.dot(up) / z / t;
		return new double[] {(x + 1.0) / 2.0, (1.0 - y) / 2.0};
	}

	private static double ease(double t) {
		return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
	}
}
