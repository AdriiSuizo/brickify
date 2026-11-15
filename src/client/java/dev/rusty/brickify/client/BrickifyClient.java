package dev.rusty.brickify.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.rusty.brickify.Brickify;
import dev.rusty.brickify.client.dev.Harness;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.KeyMapping;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BrickifyClient implements ClientModInitializer {
	public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Brickify.id("main"));
	public static final KeyMapping TOGGLE = new KeyMapping("key.brickify.toggle", InputConstants.KEY_B, KEY_CATEGORY);
	public static final KeyMapping BUILD = new KeyMapping("key.brickify.build", InputConstants.KEY_R, KEY_CATEGORY);
	public static final KeyMapping PHYSICS = new KeyMapping("key.brickify.physics", InputConstants.KEY_K, KEY_CATEGORY);
	/** Only there while trying out animation mods (see AnimSwitch). */
	public static final KeyMapping ANIM = new KeyMapping("key.brickify.anim", InputConstants.KEY_J, KEY_CATEGORY);
	public static final KeyMapping CAMERA = new KeyMapping("key.brickify.camera", InputConstants.KEY_V, KEY_CATEGORY);
	public static final KeyMapping EDITOR = new KeyMapping("key.brickify.editor", InputConstants.KEY_O, KEY_CATEGORY);
	public static final KeyMapping EDITOR_VIEW = new KeyMapping("key.brickify.editor_view", InputConstants.KEY_TAB, KEY_CATEGORY);

	@Override
	public void onInitializeClient() {
		dev.rusty.brickify.client.editor.Carry.available();
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		KeyMappingHelper.registerKeyMapping(BUILD);
		KeyMappingHelper.registerKeyMapping(PHYSICS);
		KeyMappingHelper.registerKeyMapping(EDITOR);
		KeyMappingHelper.registerKeyMapping(CAMERA);
		KeyMappingHelper.registerKeyMapping(EDITOR_VIEW);
		if (AnimSwitch.available()) {
			KeyMappingHelper.registerKeyMapping(ANIM);
		}
		// the editor's tools first: with a tool in hand nothing else happens
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(dev.rusty.brickify.client.editor.EditorTools::attack);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(dev.rusty.brickify.client.editor.EditorTools::useOn);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(dev.rusty.brickify.client.editor.LevelLife::useTnt);
		// before the Carry On mod (if it is there): in a level only marked things are lifted
		net.minecraft.resources.Identifier early = net.minecraft.resources.Identifier.fromNamespaceAndPath("brickify", "early");
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(early, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(early, dev.rusty.brickify.client.editor.LevelLife::useLift);
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.addPhaseOrdering(early, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(early, dev.rusty.brickify.client.editor.LevelLife::noCarryEntity);
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(dev.rusty.brickify.client.editor.LevelLife::useEntity);
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(dev.rusty.brickify.client.editor.EditorTools::useAir);
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(Smash::onAttack);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Statics::serverTick);
		BrickModels.init();
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			helper.register(new StudHatLayer(renderer));
		});
		ClientTickEvents.START_CLIENT_TICK.register(BuildKey::tick);
		ClientTickEvents.START_CLIENT_TICK.register(DoubleJump::tick);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Brickify.id("studs"), Coins::hud);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Brickify.id("tutorial"), dev.rusty.brickify.client.editor.Tutorial::hud);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(Brickify.id("dialog"), dev.rusty.brickify.client.editor.Dialog::hud);
		net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register(BlockFx::broken);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null) {
				BrickWorld.reset();
				dev.rusty.brickify.client.editor.Editor.forget();
				SeeThrough.forget();
				return;
			}
			Physics.load();
			while (TOGGLE.consumeClick()) {
				BrickWorld.toggle(mc);
			}
			while (PHYSICS.consumeClick()) {
				Physics.cycle(mc);
			}
			while (CAMERA.consumeClick()) {
				BrickCam.lego = !BrickCam.lego;
				mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable(BrickCam.lego ? "camera.brickify.fixed" : "camera.brickify.behind"), false);
			}
			AnimSwitch.tick(mc);
			while (ANIM.consumeClick()) {
				AnimSwitch.cycle(mc);
			}
			BrickWorld.tick(mc);
			dev.rusty.brickify.client.editor.Editor.tick(mc);
			SeeThrough.tick(mc);
		});
		if (Boolean.getBoolean("bk.harness")) {
			Harness.init();
		}
	}
}
