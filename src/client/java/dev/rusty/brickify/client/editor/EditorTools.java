package dev.rusty.brickify.client.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The editor in the normal view: its tools are items in the hotbar, used with the right mouse button on what the
 * crosshair is on. They do the same as in the diorama, for whatever is easier to reach from close up (a bridge
 * deep in a gorge, blocks in the ground). Vanilla does nothing with them (no map is drawn, no banner placed).
 */
public final class EditorTools {
	private static final String KEY = "brickify_tool";
	private static final Editor.Tool[] EGO = {Editor.Tool.BLUEPRINT, Editor.Tool.LINK, Editor.Tool.START, Editor.Tool.CHECKPOINT, Editor.Tool.GOAL, Editor.Tool.ENEMY, Editor.Tool.CAMERA,
		Editor.Tool.BREAKABLE, Editor.Tool.ITEM, Editor.Tool.NPC, Editor.Tool.TRIGGER, Editor.Tool.BLAST};
	private static int hintAt;
	private static String lastHint = "";

	private EditorTools() {
	}

	public static ItemStack stack(Editor.Tool t) {
		Item item = switch (t) {
			case BLUEPRINT -> Items.MAP;
			case LINK -> Items.LEAD;
			case START -> Items.BANNER.pick(net.minecraft.world.item.DyeColor.LIME);
			case CHECKPOINT -> Items.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE);
			case GOAL -> Items.NETHER_STAR;
			case ENEMY -> Items.ZOMBIE_SPAWN_EGG;
			case CAMERA -> Items.SPYGLASS;
			case BREAKABLE -> Items.MACE;
			case ITEM -> Items.EMERALD;
			case NPC -> Items.VILLAGER_SPAWN_EGG;
			case TRIGGER -> Items.TRIPWIRE_HOOK;
			case BLAST -> Items.TNT;
			case POINTER -> Items.ARROW;
		};
		ItemStack s = new ItemStack(item);
		CompoundTag tag = new CompoundTag();
		tag.putString(KEY, t.name());
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ITEM_NAME, Component.literal(switch (t) {
			case BLUEPRINT -> "Blueprint";
			case LINK -> "Link";
			case START -> "Start Flag";
			case CHECKPOINT -> "Checkpoint";
			case GOAL -> "Goal";
			case ENEMY -> "Enemy";
			case CAMERA -> "Camera Zone";
			case BREAKABLE -> "Smashable";
			case ITEM -> "Hidden Item";
			case NPC -> "Villager";
			case TRIGGER -> "Trigger Area";
			case BLAST -> "Explosion";
			case POINTER -> "Pointer";
		}).withStyle(ChatFormatting.AQUA));
		List<Component> lore = switch (t) {
			case BLUEPRINT -> List.of(line("Right-click: mark a thing"), line("Sneak + right-click: a single block"),
				line("Right-click into the air: make the blueprint"), line("Sneak + right-click into the air: make a gate"),
				line("Right-click a heap: move the heap"));
			case LINK -> List.of(line("Right-click a lever, button or pressure plate,"), line("then a blueprint or a gate"));
			case START -> List.of(line("Right-click: the level starts here"));
			case CHECKPOINT -> List.of(line("Right-click: checkpoint here (again: remove)"));
			case GOAL -> List.of(line("Right-click: the goal is here"));
			case ENEMY -> List.of(line("Right-click: enemy here"), line("Right-click an enemy: another kind"));
			case CAMERA -> List.of(line("Right-click: first corner, then the other"), line("The camera then looks the way you look"),
				line("Sneak + right-click in a zone: set its view again"));
			case BREAKABLE -> List.of(line("In play, nothing breaks unless marked here"), line("Right-click: make a thing smashable (again: holds)"),
				line("Sneak + right-click: a single block"), line("Right-click into the air: switch smash / TNT only"));
			case ITEM -> List.of(line("Right-click: something to find lies here"), line("Right-click it again: another thing"));
			case NPC -> List.of(line("Right-click: a villager stands here"), line("Name, words, what they want and give: on the card (Tab)"));
			case TRIGGER -> List.of(line("Right-click: first corner, then the other"), line("Walking in sets off what it is linked to (Link tool)"));
			case BLAST -> List.of(line("Right-click: an explosion goes off here"), line("when what it is linked to goes off"));
			case POINTER -> List.of();
		};
		s.set(DataComponents.LORE, new ItemLore(lore));
		return s;
	}

	private static Component line(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static Editor.@Nullable Tool toolOf(ItemStack s) {
		if (s.isEmpty()) {
			return null;
		}
		CustomData cd = s.get(DataComponents.CUSTOM_DATA);
		if (cd == null) {
			return null;
		}
		String v = cd.copyTag().getStringOr(KEY, "");
		if (v.isEmpty()) {
			return null;
		}
		try {
			return Editor.Tool.valueOf(v);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The tools go into the player's inventory (once each). */
	public static void give(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp == null) {
				return;
			}
			Inventory inv = sp.getInventory();
			for (Editor.Tool t : EGO) {
				boolean has = false;
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (toolOf(inv.getItem(i)) == t) {
						has = true;
						break;
					}
				}
				if (!has) {
					inv.add(stack(t));
				}
			}
		});
	}

	// ---------------------------------------------------------------- the mouse

	public static InteractionResult useOn(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		Editor.Tool t = toolOf(player.getItemInHand(hand));
		if (t == null) {
			return InteractionResult.PASS;
		}
		if (!world.isClientSide() || !Editor.editing()) {
			return InteractionResult.FAIL;
		}
		Minecraft mc = Minecraft.getInstance();
		Pick far = egoPick(mc);
		BlockPos pos = hit.getBlockPos();
		BlockPos stand = hit.getDirection() == Direction.UP ? pos.above() : pos.relative(hit.getDirection());
		switch (t) {
			case BLUEPRINT -> {
				if (Editor.placing() != null) {
					Editor.placePile(mc, hit.getDirection() == Direction.UP ? pos : stand.below());
					Editor.say(mc, "Heap placed. In play, R builds it.");
				} else if (far != null && far.kind() == Pick.Kind.PILE && far.distance() < hit.getLocation().distanceTo(player.getEyePosition()) + 0.5) {
					grabPile(mc, far.id());
				} else {
					Editor.select(world, pos, true, player.isShiftKeyDown());
				}
			}
			case LINK -> Editor.linkClick(mc, far);
			case START -> Editor.setStart(mc, stand, player.getYRot());
			case CHECKPOINT -> Editor.toggleCheckpoint(mc, far != null && far.kind() == Pick.Kind.CHECKPOINT ? far.pos() : stand);
			case GOAL -> Editor.setGoal(mc, stand);
			case ENEMY -> Editor.placeMob(mc, far != null && far.kind() == Pick.Kind.SPAWN ? far.pos() : stand);
			case CAMERA -> Editor.zoneClick(mc, pos);
			case BREAKABLE -> Editor.breakClick(mc, world, pos, false, player.isShiftKeyDown());
			case ITEM -> Editor.placePickup(mc, far != null && far.kind() == Pick.Kind.PICKUP ? far.pos() : stand);
			case NPC -> Editor.placeNpc(mc, far != null && far.kind() == Pick.Kind.NPC ? far.pos() : stand);
			case TRIGGER -> Editor.triggerClick(mc, pos);
			case BLAST -> Editor.placeBlast(mc, far != null && far.kind() == Pick.Kind.BLAST ? far.pos() : stand);
			default -> {
			}
		}
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		hintAt = 0;
		return InteractionResult.FAIL;
	}

	public static InteractionResult useAir(Player player, Level world, InteractionHand hand) {
		Editor.Tool t = toolOf(player.getItemInHand(hand));
		if (t == null) {
			return InteractionResult.PASS;
		}
		if (!world.isClientSide() || !Editor.editing()) {
			return InteractionResult.FAIL;
		}
		Minecraft mc = Minecraft.getInstance();
		if (t == Editor.Tool.BLUEPRINT) {
			Pick far = egoPick(mc);
			if (Editor.placing() != null) {
				Editor.say(mc, "Right-click the ground where the heap should lie");
			} else if (far != null && far.kind() == Pick.Kind.PILE) {
				grabPile(mc, far.id());
			} else if (!Editor.selection().isEmpty() && player.isShiftKeyDown()) {
				LevelData.Gate g = Editor.makeGate(mc);
				if (g != null) {
					Editor.say(mc, "Gate with " + g.blocks.size() + " blocks · link it to a switch with the lead");
				}
			} else if (!Editor.selection().isEmpty()) {
				LevelData.Blueprint b = Editor.makeBlueprint(mc);
				if (b != null) {
					Editor.say(mc, "Blueprint with " + b.blocks.size() + " bricks · now right-click the ground for its heap");
				}
			}
			player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		} else if (t == Editor.Tool.LINK) {
			Editor.linkClick(mc, egoPick(mc));
			player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		} else if (t == Editor.Tool.BREAKABLE) {
			int next = (Editor.markMode() + 1) % 3;
			Editor.markMode(next);
			Editor.say(mc, next == 2 ? "Marking: it can be lifted and carried" : next == 1 ? "Marking: only TNT breaks it" : "Marking: a hit smashes it");
			Editor.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), next == 1 ? 0.8F : next == 2 ? 1.0F : 1.3F);
		}
		hintAt = 0;
		return InteractionResult.FAIL;
	}

	public static InteractionResult attack(Player player, Level world, InteractionHand hand, BlockPos pos, Direction dir) {
		if (world.isClientSide() && Editor.playing() && Editor.level() != null && !player.isSpectator() && !Editor.breakableAt(pos)
			&& player instanceof net.minecraft.client.player.LocalPlayer) {
			// a level holds, except what its builder marked smashable
			player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
			Editor.knock(Minecraft.getInstance(), pos, world.getBlockState(pos));
			return InteractionResult.FAIL;
		}
		Editor.Tool t = toolOf(player.getItemInHand(hand));
		if (t == null) {
			return InteractionResult.PASS;
		}
		if (!world.isClientSide() || !Editor.editing()) {
			return InteractionResult.FAIL;
		}
		Minecraft mc = Minecraft.getInstance();
		if (t == Editor.Tool.BLUEPRINT) {
			if (Editor.placing() != null) {
				Editor.cancelPlacing(mc);
				Editor.say(mc, "Cancelled");
			} else if (!Editor.selection().remove(pos.asLong()) && !Editor.selection().isEmpty() && player.isShiftKeyDown()) {
				Editor.clearSelection();
				Editor.say(mc, "Marking cleared");
			}
		}
		hintAt = 0;
		return InteractionResult.FAIL;
	}

	private static void grabPile(Minecraft mc, int id) {
		LevelData d = Editor.level();
		LevelData.Blueprint b = d == null ? null : d.blueprint(id);
		if (b != null) {
			Editor.movePile(b);
			Editor.say(mc, "Heap picked up · right-click the ground to put it down");
		}
	}

	private static @Nullable Pick egoPick(Minecraft mc) {
		LevelData d = Editor.level();
		if (d == null || mc.player == null || mc.level == null) {
			return null;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		return Pick.cast(mc.level, d, eye, look, 48.0, false);
	}

	// ---------------------------------------------------------------- every tick in the normal view

	public static void tickEgo(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		Editor.Tool t = toolOf(mc.player.getMainHandItem());
		if (t == null) {
			Editor.setHover(null);
			if (Editor.placing() != null && --hintAt <= 0) {
				hint(mc, "A heap is waiting for its place · take the blueprint tool");
			}
			return;
		}
		Editor.setHover(egoPick(mc));
		if (--hintAt > 0) {
			return;
		}
		String text = switch (t) {
			case BLUEPRINT -> Editor.placing() != null ? "Right-click the ground: heap goes here · left-click: cancel"
				: !Editor.selection().isEmpty() ? Editor.selection().size() + " marked · right-click into the air: make blueprint · left-click: unmark block"
				: "Right-click: mark a thing · sneak + right-click: single block · Tab: diorama";
			case LINK -> Editor.pendingSource() >= 0 ? "Right-click what it sets off: blueprint, gate, enemy, explosion, camera (again: unlink)"
				: "Right-click a lever, button, plate, villager, trigger area or thing to find";
			case START -> "Right-click: the level starts here";
			case CHECKPOINT -> "Right-click: checkpoint here (again: remove)";
			case GOAL -> "Right-click: the goal is here";
			case ENEMY -> "Right-click: enemy here · on an enemy: another kind";
			case CAMERA -> Editor.zoneCorner() != null ? "Right-click: the other corner of the camera zone" : "Right-click: first corner of a camera zone · sneak: set view";
			case BREAKABLE -> (Editor.liftMode ? "Lift" : Editor.blastMode ? "TNT only" : "Smash") + " · right-click: mark (again: holds) · sneak: one block · right-click into the air: switch";
			case ITEM -> "Right-click: something to find lies here · again: another thing";
			case NPC -> "Right-click: a villager stands here · Tab: change name, words and deal on the card";
			case TRIGGER -> Editor.triggerCorner() != null ? "Right-click: the other corner of the trigger area" : "Right-click: first corner of a trigger area";
			case BLAST -> "Right-click: an explosion place · link something to it";
			default -> "";
		};
		hint(mc, text);
	}

	private static void hint(Minecraft mc, String text) {
		hintAt = 30;
		if (!text.isEmpty()) {
			mc.gui.hud.setOverlayMessage(Component.literal(text), false);
		}
		lastHint = text;
	}
}
