package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With the fixed camera nobody aims with the mouse: a hit goes to the nearest mob roughly in front of the figure
 * (a half circle, a little more than an arm's length), as in the brick games. The figure turns to it. Villagers are
 * talked to, not hit. Without a mob in reach, the hit goes where it went before (blocks to smash).
 */
@Mixin(Minecraft.class)
public class AutoAimMixin {
	private static final double REACH = 3.6;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void bk$autoAim(CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null || !BrickCam.legoActive() || p.isSpectator()) {
			return;
		}
		if (dev.rusty.brickify.client.editor.Carry.carrying(p)) {
			// arms full: no hitting (Carry On's own rule)
			return;
		}
		Vec3 me = p.position();
		Vec3 facing = Vec3.directionFromRotation(0.0F, p.getYRot());
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (Entity e : mc.level.getEntities(p, new AABB(me, me).inflate(REACH + 1.5, 2.5, REACH + 1.5))) {
			if (!(e instanceof Mob mob) || !mob.isAlive() || e instanceof AbstractVillager || mob.isInvisible()) {
				continue;
			}
			Vec3 to = new Vec3(e.getX() - me.x, 0.0, e.getZ() - me.z);
			double dist = Math.max(0.0, to.length() - e.getBbWidth() * 0.5);
			double dy = e.getY() - me.y;
			if (dist > REACH || dy < -2.0 || dy > 2.5) {
				continue;
			}
			double dot = to.lengthSqr() < 1.0E-4 ? 1.0 : to.normalize().dot(facing);
			if (dot < -0.15) {
				continue;
			}
			// near and in front first
			double score = dist + (1.0 - dot) * 1.2;
			if (score < bestScore) {
				bestScore = score;
				best = (LivingEntity) e;
			}
		}
		if (best == null) {
			this.bk$smashNearby(mc, p, me, facing, cir);
			return;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(best.getZ() - me.z, best.getX() - me.x)) - 90.0F;
		p.setYRot(p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()));
		p.setYBodyRot(p.getYRot());
		p.setYHeadRot(p.getYRot());
		mc.gameMode.attack(p, best);
		p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		cir.setReturnValue(true);
	}

	/**
	 * No mob in reach: the hit goes to the nearest thing that can be smashed in front of the figure (a bush at its
	 * feet, a barrel, a fence), whatever the crosshair is on. Nothing there: the hit goes where it went before.
	 */
	private void bk$smashNearby(Minecraft mc, LocalPlayer p, Vec3 me, Vec3 facing, CallbackInfoReturnable<Boolean> cir) {
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& dev.rusty.brickify.client.Smash.smashable(mc.level, bh.getBlockPos(), mc.level.getBlockState(bh.getBlockPos()))
			&& bh.getLocation().distanceTo(p.getEyePosition()) < 4.5) {
			// the crosshair is on something to smash: that one
			return;
		}
		net.minecraft.core.BlockPos feet = p.blockPosition();
		net.minecraft.core.BlockPos.MutableBlockPos q = new net.minecraft.core.BlockPos.MutableBlockPos();
		net.minecraft.core.BlockPos bestPos = null;
		double bestScore = Double.MAX_VALUE;
		for (int dy = -1; dy <= 2; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					q.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					var state = mc.level.getBlockState(q);
					if (state.isAir() || !dev.rusty.brickify.client.Smash.smashable(mc.level, q, state)) {
						continue;
					}
					Vec3 to = new Vec3(q.getX() + 0.5 - me.x, 0.0, q.getZ() + 0.5 - me.z);
					double dist = to.length();
					if (dist > 2.9 || dist < 0.3 && dy <= 0) {
						continue;
					}
					double dot = dist < 1.0E-3 ? 1.0 : to.scale(1.0 / dist).dot(facing);
					if (dot < -0.1) {
						continue;
					}
					// near, in front, about chest high first
					double score = dist + (1.0 - dot) * 1.4 + Math.abs(dy - 0.5) * 0.35;
					if (score < bestScore) {
						bestScore = score;
						bestPos = q.immutable();
					}
				}
			}
		}
		if (bestPos == null) {
			return;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(bestPos.getZ() + 0.5 - me.z, bestPos.getX() + 0.5 - me.x)) - 90.0F;
		p.setYRot(p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()));
		p.setYBodyRot(p.getYRot());
		p.setYHeadRot(p.getYRot());
		mc.gameMode.startDestroyBlock(bestPos, net.minecraft.core.Direction.UP);
		p.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		cir.setReturnValue(true);
	}
}
