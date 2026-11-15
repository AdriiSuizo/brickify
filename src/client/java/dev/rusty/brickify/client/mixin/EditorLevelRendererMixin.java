package dev.rusty.brickify.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rusty.brickify.client.editor.EditorGhosts;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ghost blocks of the level editor are submitted right after the game's own moving blocks. */
@Mixin(LevelRenderer.class)
public class EditorLevelRendererMixin {
	@Inject(method = "submitTransientBlocks", at = @At("TAIL"))
	private void bk$editorGhosts(PoseStack poseStack, SubmitNodeCollector collector, LevelRenderState state, CallbackInfo ci) {
		EditorGhosts.submit(poseStack, collector, state.cameraRenderState.pos);
		dev.rusty.brickify.client.editor.EditorMarks.submit(poseStack, collector, state.cameraRenderState.pos);
		dev.rusty.brickify.client.SeeThrough.submit(poseStack, collector, state.cameraRenderState.pos);
	}
}
