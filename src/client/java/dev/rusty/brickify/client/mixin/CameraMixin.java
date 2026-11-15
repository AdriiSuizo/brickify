package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the brick camera take over once the game has worked out its normal pose. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;
	@Shadow
	private float fov;
	@Shadow
	private float depthFar;
	@Shadow
	private Vec3 position;
	@Shadow
	private Entity entity;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	@Final
	private Matrix4f cachedViewRotMatrix;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);

	@Shadow
	public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

	@Shadow
	private void prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos) {
		throw new AssertionError();
	}

	@Shadow
	private Matrix4f createProjectionMatrixForCulling() {
		throw new AssertionError();
	}

	@Shadow
	private void setupPerspective(float zNear, float zFar, float fov, float width, float height) {
		throw new AssertionError();
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void bk$brickCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (this.entity == null) {
			return;
		}
		BrickCam.Pose pose = BrickCam.update(this.entity, this.getCameraEntityPartialTicks(deltaTracker), this.fov);
		// the level editor's diorama flies out of whatever the camera is doing
		BrickCam.Pose diorama = dev.rusty.brickify.client.editor.EditorCam.update(
			pose != null ? pose : new BrickCam.Pose(this.position, this.yRot, this.xRot, this.fov, this.detached));
		if (diorama != null) {
			pose = diorama;
		}
		if (pose == null) {
			return;
		}
		this.setRotation(pose.yaw(), pose.pitch());
		this.setPosition(pose.position());
		this.detached = pose.detached();
		this.fov = pose.fov();
		this.prepareCullFrustum(this.getViewRotationMatrix(this.cachedViewRotMatrix), this.createProjectionMatrixForCulling(), this.position);
		var window = Minecraft.getInstance().getWindow();
		this.setupPerspective(0.05F, this.depthFar, this.fov, window.getWidth(), window.getHeight());
	}
}
