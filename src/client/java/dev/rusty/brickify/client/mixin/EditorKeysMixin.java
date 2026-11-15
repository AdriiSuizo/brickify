package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.editor.Editor;
import dev.rusty.brickify.client.editor.EditorHistory;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ctrl+Z / Ctrl+Y while building in the normal view (the diorama screen handles them itself). By letter, not key position. */
@Mixin(KeyboardHandler.class)
public class EditorKeysMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void bk$undo(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (action != 1 || !Editor.editing() || mc.gui.screen() != null || !event.hasControlDownWithQuirk()) {
			return;
		}
		if (event.shortcutKey() == 'z') {
			if (event.hasShiftDown()) {
				EditorHistory.redo(mc);
			} else {
				EditorHistory.undo(mc);
			}
			ci.cancel();
		} else if (event.shortcutKey() == 'y') {
			EditorHistory.redo(mc);
			ci.cancel();
		}
	}
}
