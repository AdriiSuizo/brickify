package dev.rusty.brickify.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The camera of the brick games: a bit above and behind the figure, looking down onto it, following softly.
 * When the world turns it glides there from the eyes; when it turns back it glides home. While it is out, the
 * mouse tilts the camera (not the head), and the figure looks at whatever is in the middle of the screen, so you
 * hit what you aim at.
 */
public final class BrickCam {
	// Tuning, changeable through the harness ("tune").
	public static double distance = 6.5;
	/** Dev only: looks at the figure from the side (degrees), for recording animations. */
	public static float yawOffset = 0.0F;
	public static double pitchAdd = 24.0;
	public static double fov = 58.0;
	public static double enterSeconds = 1.7;
	public static double leaveSeconds = 0.9;
	public static double follow = 7.0;
	/** The brick look (post shader) while the camera is out. */
	public static boolean tilt = true;
	/** How far above the eyes the camera aims, so the figure sits a bit below the middle of the screen. */
	public static double lift = 0.9;
	public static float minPitch = -8.0F;
	public static float maxPitch = 72.0F;

	private static double blend;
	private static double target;
	private static long lastNanos;
	private static float camYaw;
	private static boolean yawSet;
	private static boolean typeSet;
	private static float camPitch;
	private static boolean pitchSet;
	private static boolean aiming;
	/** Bird's eye view while everything around is being built (0..1, eased). */
	private static double ov;
	private static double ovTarget;
	private static float shake;
	/**
	 * The fixed camera of the brick games: it stays where it is in space (it does not turn with the figure), a bit
	 * further out, gliding after the figure. The figure walks the way the keys point on the screen. In a level the
	 * camera areas say where it looks from. Off: the camera behind the figure, turned with the mouse.
	 */
	public static boolean lego = true;
	public static double legoDistance = 10.5;
	public static float legoPitch = 38.0F;
	/** Head tilt of the figure while the fixed camera is on (what it hits is in front of it, a bit down). */
	public static float legoAimPitch = 26.0F;
	private static float freeYaw;
	private static float freePitch = 38.0F;
	private static boolean freeSet;
	private static Vec3 sFocus = Vec3.ZERO;
	private static float sYaw;
	private static float sPitch;
	private static double sDist;
	private static boolean legoInit;
	/** The camera area the figure was in last: between areas the camera keeps its view (and follows fully). */
	private static dev.rusty.brickify.client.editor.LevelData.@Nullable CamZone lastZone;
	private static final java.util.Random SHAKE = new java.util.Random();

	public record Pose(Vec3 position, float yaw, float pitch, float fov, boolean detached) {
	}

	private BrickCam() {
	}

	public static void enter(boolean instant) {
		freeSet = false;
		legoInit = false;
		target = 1.0;
		if (instant) {
			blend = 1.0;
		}
	}

	public static void leave() {
		target = 0.0;
	}

	public static void reset() {
		ov = 0.0;
		ovTarget = 0.0;
		shake = 0.0F;
		blend = 0.0;
		target = 0.0;
		yawSet = false;
		pitchSet = false;
		aiming = false;
		lastNanos = 0L;
		freeSet = false;
		legoInit = false;
	}

	/** The fixed camera is on (not while building a level: that needs the mouse to aim). */
	public static boolean legoActive() {
		return lego && target > 0.0 && !dev.rusty.brickify.client.editor.Editor.editing();
	}

	/** A level starts: the free camera looks the way the start looks, and snaps there. */
	public static void startView(float yaw) {
		freeYaw = yaw;
		freePitch = legoPitch;
		freeSet = true;
		legoInit = false;
		lastZone = null;
	}

	/** Which way the fixed camera looks right now (the keys walk relative to it). */
	public static float legoYaw() {
		return legoInit ? sYaw : freeYaw;
	}

	private static float inputYaw;
	private static boolean inputHeld;
	private static boolean closeCamera;

	/** The camera is one of the close ones inside a house: nothing needs to be seen through then. */
	public static boolean closeCamera() {
		return closeCamera;
	}

	/**
	 * The way the walking keys point. While they are held it stays put through a camera cut (else the figure would
	 * turn round at the edge of an area and walk back out); small turns of the camera are still followed.
	 */
	public static float inputYaw(boolean moving) {
		float now = legoYaw();
		if (!moving || !inputHeld) {
			inputYaw = now;
			inputHeld = moving;
			return now;
		}
		float diff = Mth.wrapDegrees(now - inputYaw);
		if (Math.abs(diff) < 50.0F) {
			inputYaw += diff * 0.2F;
		}
		return inputYaw;
	}

	public static boolean active() {
		return blend > 0.0 || target > 0.0;
	}

	private static @Nullable Vec3 lastCamera;

	/** Where the brick camera stood in the last frame (null while it is not out). */
	public static @Nullable Vec3 lastCamera() {
		return lastCamera;
	}

	public static double blend() {
		return blend;
	}

	/** The camera is out and the figure aims at the middle of the screen (crosshair shown). */
	public static boolean aiming() {
		return aiming;
	}

	/** A short camera shake (0..1), for impacts. */
	public static void shake(float amount) {
		shake = Math.max(shake, amount);
	}

	/** Lifts the camera high up over the figure (or brings it back down). */
	public static void overview(boolean on) {
		ovTarget = on ? 1.0 : 0.0;
	}

	private static float initialPitch(float playerPitch) {
		return Mth.clamp(playerPitch * 0.45F + (float) pitchAdd, 6.0F, maxPitch);
	}

	/** Mouse movement while the camera is out: up/down tilts the camera, left/right turns the figure. */
	public static boolean turn(Entity entity, double xo, double yo) {
		if (target <= 0.0 || entity != Minecraft.getInstance().player) {
			return false;
		}
		if (legoActive()) {
			// the mouse turns the camera around the figure (slowly); a level's camera areas keep their own view
			var d = dev.rusty.brickify.client.editor.Editor.level();
			boolean zone = dev.rusty.brickify.client.editor.Editor.playing() && d != null && (dev.rusty.brickify.client.editor.Editor.liveZoneAt(entity.getX(), entity.getZ()) != null || lastZone != null);
			if (!zone) {
				freeYaw += (float) xo * 0.12F;
				freePitch = Mth.clamp(freePitch + (float) yo * 0.08F, 15.0F, 70.0F);
			}
			return true;
		}
		if (!pitchSet) {
			camPitch = initialPitch(entity.getXRot());
			pitchSet = true;
		}
		camPitch = Mth.clamp(camPitch + (float) yo * 0.15F, minPitch, maxPitch);
		float yDelta = (float) xo * 0.15F;
		entity.setYRot(entity.getYRot() + yDelta);
		entity.yRotO += yDelta;
		if (entity.getVehicle() != null) {
			entity.getVehicle().onPassengerTurned(entity);
		}
		return true;
	}

	public static net.minecraft.resources.@Nullable Identifier postEffect() {
		if (!tilt || blend < 0.2) {
			return null;
		}
		int level = Math.min(4, Math.max(1, (int) Math.ceil(ease(blend) * 4.0)));
		return dev.rusty.brickify.Brickify.id("brick_look_" + level);
	}

	public static @Nullable Pose update(Entity entity, float partialTicks, float vanillaFov) {
		long now = System.nanoTime();
		double dt = lastNanos == 0L ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		if (target > blend) {
			blend = Math.min(target, blend + dt / enterSeconds);
		} else if (target < blend) {
			blend = Math.max(target, blend - dt / leaveSeconds);
		}
		Minecraft mc = Minecraft.getInstance();
		aiming = false;
		if (blend <= 0.0) {
			if (typeSet) {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				typeSet = false;
			}
			yawSet = false;
			pitchSet = false;
			lastCamera = null;
			closeCamera = false;
			return null;
		}
		double e = ease(blend);
		double ex = Mth.lerp(partialTicks, entity.xo, entity.getX());
		double ey = Mth.lerp(partialTicks, entity.yo, entity.getY()) + entity.getEyeHeight();
		double ez = Mth.lerp(partialTicks, entity.zo, entity.getZ());
		float playerYaw = entity.getViewYRot(partialTicks);
		float playerPitch = entity.getViewXRot(partialTicks);
		if (legoActive() || legoInit && target == 0.0) {
			return legoPose(mc, entity, partialTicks, dt, e, new Vec3(ex, ey, ez), playerYaw, playerPitch, vanillaFov);
		}
		if (!yawSet) {
			camYaw = playerYaw;
			yawSet = true;
		} else {
			float k = (float) (1.0 - Math.exp(-dt * follow));
			camYaw = camYaw + Mth.wrapDegrees(playerYaw - camYaw) * k;
		}
		if (target > 0.0 && !pitchSet) {
			camPitch = initialPitch(playerPitch);
			pitchSet = true;
		}
		float legoPitch = pitchSet || target == 0.0 ? camPitch : initialPitch(playerPitch);
		float pitch = (float) Mth.lerp(e, playerPitch, legoPitch);
		float yaw = playerYaw + Mth.wrapDegrees(camYaw - playerYaw) * (float) e + yawOffset * (float) e;
		// Back first (out of the head quickly), then the turn and the zoom settle.
		double out = 1.0 - Math.pow(1.0 - blend, 3.0);
		double dist = distance * out;
		if (ovTarget > ov) {
			ov = Math.min(ovTarget, ov + dt / 1.6);
		} else if (ovTarget < ov) {
			ov = Math.max(ovTarget, ov - dt / 1.2);
		}
		double oe = ease(ov);
		pitch = (float) Mth.lerp(oe, pitch, 58.0);
		dist = Mth.lerp(oe, dist, 30.0);
		double lifted = Mth.lerp(oe, lift, 2.0);
		Vec3 eye = new Vec3(ex, ey, ez);
		Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
		Vec3 pivot = eye.add(0.0, lifted * out, 0.0);
		if (lift > 0.0) {
			HitResult up = entity.level().clip(new ClipContext(eye, pivot, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			if (up.getType() != HitResult.Type.MISS) {
				pivot = up.getLocation().add(0.0, -0.2, 0.0);
			}
		}
		Vec3 pos = pivot.subtract(dir.scale(dist));
		if (dist > 0.3 && oe < 0.5) {
			HitResult hit = entity.level().clip(new ClipContext(pivot, pos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
			if (hit.getType() != HitResult.Type.MISS) {
				pos = hit.getLocation().add(dir.scale(0.25));
			}
		}
		if (target > 0.0 && e > 0.98 && oe < 0.05 && entity == mc.player) {
			aimAt(entity, eye, pos, dir);
		}
		if (shake > 0.01F) {
			float s = shake * shake * 0.35F;
			pos = pos.add((SHAKE.nextFloat() - 0.5F) * s, (SHAKE.nextFloat() - 0.5F) * s, (SHAKE.nextFloat() - 0.5F) * s);
			pitch += (SHAKE.nextFloat() - 0.5F) * s * 6.0F;
			shake *= (float) Math.exp(-dt * 6.0);
		}
		if (e > 0.12 && !typeSet && mc.options.getCameraType().isFirstPerson()) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			typeSet = true;
		} else if (e <= 0.12 && typeSet && target == 0.0) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			typeSet = false;
		}
		float f = (float) Mth.lerp(e, vanillaFov, fov);
		lastCamera = pos;
		return new Pose(pos, yaw, pitch, f, pos.distanceTo(eye) > 2.2);
	}

	private static Pose legoPose(Minecraft mc, Entity entity, float partialTicks, double dt, double e, Vec3 eye, float playerYaw, float playerPitch, float vanillaFov) {
		if (!freeSet) {
			freeYaw = playerYaw;
			freePitch = legoPitch;
			freeSet = true;
		}
		Vec3 body = eye.add(0.0, 1.0 - entity.getEyeHeight(), 0.0);
		var d = dev.rusty.brickify.client.editor.Editor.level();
		boolean inLevel = dev.rusty.brickify.client.editor.Editor.playing() && d != null;
		var zone = inLevel ? dev.rusty.brickify.client.editor.Editor.liveZoneAt(body.x, body.z) : null;
		var before = lastZone;
		if (zone != null) {
			lastZone = zone;
		}
		var view = zone != null ? zone : inLevel && lastZone != null && d.zones.contains(lastZone) ? lastZone : null;
		closeCamera = view != null && view.dist < 6.0F;
		float tYaw = view != null ? view.yaw : freeYaw;
		float tPitch = view != null ? view.pitch : freePitch;
		double tDist = view != null ? view.dist : legoDistance;
		Vec3 tFocus = zone != null ? zone.centre(body.y).add(body.subtract(zone.centre(body.y)).scale(zone.follow)) : body;
		if (!legoInit) {
			sYaw = tYaw;
			sPitch = tPitch;
			sDist = tDist;
			sFocus = tFocus;
			legoInit = true;
		}
		// into a room (a close camera) or back out: a cut, not a glide through the wall
		if (zone != null && before != null && zone != before && (zone.dist < 6.0F || before.dist < 6.0F)) {
			sYaw = tYaw;
			sPitch = tPitch;
			sDist = tDist;
			sFocus = tFocus;
		}
		float slow = (float) (1.0 - Math.exp(-dt * (view != null ? 2.2 : 6.0)));
		float quick = (float) (1.0 - Math.exp(-dt * 5.0));
		sYaw += Mth.wrapDegrees(tYaw - sYaw) * slow;
		sPitch += (tPitch - sPitch) * slow;
		sDist += (tDist - sDist) * slow;
		sFocus = sFocus.add(tFocus.subtract(sFocus).scale(quick));
		if (ovTarget > ov) {
			ov = Math.min(ovTarget, ov + dt / 1.6);
		} else if (ovTarget < ov) {
			ov = Math.max(ovTarget, ov - dt / 1.2);
		}
		double oe = ease(ov);
		float pitch = (float) Mth.lerp(oe, sPitch, 58.0);
		double dist = Mth.lerp(oe, sDist, 30.0);
		Vec3 legoPos = sFocus.subtract(Vec3.directionFromRotation(pitch, sYaw).scale(dist));
		// out of the head along the way, as with the other camera
		double out = 1.0 - Math.pow(1.0 - blend, 3.0);
		Vec3 pos = eye.add(legoPos.subtract(eye).scale(out));
		float yaw = playerYaw + Mth.wrapDegrees(sYaw - playerYaw) * (float) e;
		pitch = (float) Mth.lerp(e, playerPitch, pitch);
		if (shake > 0.01F) {
			float sh = shake * shake * 0.35F;
			pos = pos.add((SHAKE.nextFloat() - 0.5F) * sh, (SHAKE.nextFloat() - 0.5F) * sh, (SHAKE.nextFloat() - 0.5F) * sh);
			pitch += (SHAKE.nextFloat() - 0.5F) * sh * 6.0F;
			shake *= (float) Math.exp(-dt * 6.0);
		}
		if (e > 0.12 && !typeSet && mc.options.getCameraType().isFirstPerson()) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			typeSet = true;
		} else if (e <= 0.12 && typeSet && target == 0.0) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			typeSet = false;
		}
		if (blend <= 0.0) {
			legoInit = false;
		}
		float f = (float) Mth.lerp(e, vanillaFov, fov);
		lastCamera = pos;
		return new Pose(pos, yaw, pitch, f, pos.distanceTo(eye) > 2.2);
	}

	/** Turns the head (pitch) of the figure towards what is in the middle of the screen. */
	private static void aimAt(Entity entity, Vec3 eye, Vec3 camera, Vec3 dir) {
		double past = eye.subtract(camera).dot(dir) + 0.7;
		Vec3 from = camera.add(dir.scale(past));
		Vec3 to = camera.add(dir.scale(past + 48.0));
		HitResult block = entity.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
		Vec3 end = block.getType() != HitResult.Type.MISS ? block.getLocation() : to;
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(entity, from, end, new AABB(from, end).inflate(1.0),
			e -> e != entity && !e.isSpectator() && e.isPickable(), from.distanceToSqr(end));
		Vec3 aim = hit != null ? hit.getLocation() : end;
		Vec3 d = aim.subtract(eye);
		float headPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		headPitch = Mth.clamp(headPitch, -90.0F, 90.0F);
		entity.setXRot(headPitch);
		entity.xRotO = headPitch;
		aiming = true;
	}

	private static double ease(double t) {
		return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
	}
}
