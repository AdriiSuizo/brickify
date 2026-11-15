package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.Carry;
import dev.rusty.brickify.client.editor.Editor;
import dev.rusty.brickify.client.editor.LevelLife;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a level is played, talking to its villagers is easy: a right-click talks to the one right in front of the
 * figure even when the crosshair is beside them, and F (swap hands) talks to the nearest one.
 */
@Mixin(Minecraft.class)
public class NpcTalkMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void bk$talkGenerously(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player == null || mc.level == null || !Editor.playing() || Carry.carrying(mc.player) || mc.hitResult instanceof EntityHitResult) {
			return;
		}
		if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK && Editor.isSwitch(mc.level.getBlockState(bh.getBlockPos()))) {
			// a lever right in the crosshair is meant
			return;
		}
		Entity v = LevelLife.npcNear(mc.player, 3.2, 0.15);
		if (v != null) {
			LevelLife.talkTo(mc, v);
			this.rightClickDelay = 4;
			ci.cancel();
		}
	}

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void bk$talkWithF(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player == null || !Editor.playing() || mc.gui.screen() != null) {
			return;
		}
		while (mc.options.keySwapOffhand.consumeClick()) {
			// swapping hands means nothing in a level: F talks
			Entity v = LevelLife.npcNear(mc.player, 4.5, -1.0);
			if (v != null) {
				LevelLife.talkTo(mc, v);
			}
		}
	}
}
