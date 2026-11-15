package dev.rusty.brickify.client.editor;

import dev.rusty.brickify.client.BrickEntities;
import dev.rusty.brickify.client.Coins;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What lives in a level while it is played: things lying around to pick up (they float and turn), villagers who
 * talk and trade one thing for another, and TNT that the figure puts down (right-click) to blow up walls that hits
 * cannot break. A done deal sets off what the villager is linked to, like a switch.
 */
public final class LevelLife {
	/** The things a level can hide, want or give (cycled through in the editor). */
	public static final String[] ITEMS = {
		"minecraft:golden_carrot", "minecraft:tnt", "minecraft:trial_key", "minecraft:apple", "minecraft:cookie", "minecraft:bone", "minecraft:emerald",
		"minecraft:diamond", "minecraft:gold_ingot", "minecraft:cake", "minecraft:poppy", "minecraft:cod", "minecraft:bell", "minecraft:book"
	};
	/** What villagers there are: their profession and what they are called. */
	public static final String[] KINDS = {"farmer", "librarian", "fisherman", "shepherd", "armorer", "cleric", "toolsmith", "nitwit"};
	public static final String[] KIND_NAMES = {"Farmer", "Librarian", "Fisher", "Shepherd", "Armorer", "Cleric", "Toolsmith", "Villager"};

	private static final Int2ObjectOpenHashMap<Display.ItemDisplay> SHOWN = new Int2ObjectOpenHashMap<>();
	private static final IntOpenHashSet COLLECTED = new IntOpenHashSet();
	private static final IntOpenHashSet DONE = new IntOpenHashSet();
	private static final Map<UUID, Integer> NPC_BY_UUID = new HashMap<>();
	private static int nextId = -17_000_000;

	private LevelLife() {
	}

	// ---------------------------------------------------------------- names

	public static Item item(String id) {
		if (id == null || id.isEmpty()) {
			return Items.AIR;
		}
		return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
	}

	public static String itemName(String id) {
		Item it = item(id);
		return it == Items.AIR ? "nothing" : new ItemStack(it).getHoverName().getString();
	}

	/** The next thing in the list ({@code orNothing}: "nothing" is one of the choices, too). */
	public static String nextItem(String id, boolean orNothing) {
		int i = 0;
		for (; i < ITEMS.length; i++) {
			if (ITEMS[i].equals(id)) {
				break;
			}
		}
		if (orNothing && i == ITEMS.length - 1) {
			return "";
		}
		if (id == null || id.isEmpty()) {
			return ITEMS[0];
		}
		return ITEMS[(i + 1) % ITEMS.length];
	}

	public static String kindName(String kind) {
		for (int i = 0; i < KINDS.length; i++) {
			if (KINDS[i].equals(kind)) {
				return KIND_NAMES[i];
			}
		}
		return "Villager";
	}

	public static String nextKind(String kind) {
		for (int i = 0; i < KINDS.length; i++) {
			if (KINDS[i].equals(kind)) {
				return KINDS[(i + 1) % KINDS.length];
			}
		}
		return KINDS[0];
	}

	/** What the villager says before the deal. */
	public static String displayName(LevelData.Npc n) {
		return n.name.isBlank() ? kindName(n.kind) : n.name.strip();
	}

	public static String askLine(LevelData.Npc n) {
		if (!n.ask.isBlank()) {
			return n.ask.strip();
		}
		if (n.wants.isEmpty()) {
			return "Here, take this " + itemName(n.gives) + ". You'll need it!";
		}
		return "Oh no, I lost my " + itemName(n.wants) + "! Bring it back to me and I'll give you " + article(n.gives) + " for it.";
	}

	public static String thanksLine(LevelData.Npc n) {
		if (!n.thanks.isBlank()) {
			return n.thanks.strip();
		}
		if (n.gives.equals("minecraft:tnt") && Carry.available()) {
			return n.wants.isEmpty() ? "Here, a box of TNT. Careful! Carry it to a cracked wall and set it down (right-click). Then run!"
				: "My " + itemName(n.wants) + "! Thank you! Here, a box of TNT. Carry it to a cracked wall and set it down (right-click). Then run!";
		}
		if (n.wants.isEmpty()) {
			return "Here, take this " + itemName(n.gives) + ". You'll need it!";
		}
		return "My " + itemName(n.wants) + "! Thank you so much! Here, take " + article(n.gives) + ".";
	}

	public static String doneLine(LevelData.Npc n) {
		if (!n.after.isBlank()) {
			return n.after.strip();
		}
		if (n.gives.equals("minecraft:tnt")) {
			return Carry.available() ? "Set the TNT down right next to a cracked wall (right-click). It lights by itself. Then run!" : "Put the TNT down right next to a cracked wall. Then run!";
		}
		return "Thanks again! Good luck out there!";
	}

	private static String article(String id) {
		String name = itemName(id);
		if (id.equals("minecraft:tnt")) {
			return "some TNT";
		}
		return ("aeiouAEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
	}

	// ---------------------------------------------------------------- start and stop

	/** Playing starts: the things appear, the villagers move in. */
	public static void start(Minecraft mc, LevelData d) {
		stop(mc);
		ClientLevel cl = mc.level;
		if (cl == null) {
			return;
		}
		for (LevelData.Pickup p : d.pickups) {
			Display.ItemDisplay disp = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, cl);
			disp.setId(nextId--);
			disp.setPos(p.pos.getX() + 0.5, p.pos.getY() + 0.35, p.pos.getZ() + 0.5);
			disp.setItemStack(new ItemStack(item(p.item)));
			disp.setItemTransform(ItemDisplayContext.FIXED);
			disp.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
			cl.addEntity(disp);
			SHOWN.put(p.id, disp);
		}
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || d.npcs.isEmpty()) {
			return;
		}
		var dim = cl.dimension();
		var npcs = new java.util.ArrayList<>(d.npcs);
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			if (sl == null) {
				return;
			}
			for (LevelData.Npc n : npcs) {
				Villager v = EntityTypes.VILLAGER.create(sl, EntitySpawnReason.COMMAND);
				if (v == null) {
					continue;
				}
				v.snapTo(n.pos.getX() + 0.5, n.pos.getY(), n.pos.getZ() + 0.5, 180.0F, 0.0F);
				ResourceKey<VillagerProfession> prof = ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace(n.kind));
				try {
					v.setVillagerData(v.getVillagerData().withProfession(sl.registryAccess(), prof).withLevel(2));
				} catch (RuntimeException e) {
					// an unknown kind stays a plain villager
				}
				v.setNoAi(true);
				v.setPermanentlyInvulnerable(true);
				v.setPersistenceRequired();
				v.addTag("brickify_level");
				v.setCustomName(Component.literal(displayName(n)));
				v.setCustomNameVisible(true);
				sl.addFreshEntity(v);
				UUID u = v.getUUID();
				int id = n.id;
				mc.execute(() -> NPC_BY_UUID.put(u, id));
			}
		});
	}

	/** Back to building: the things go (the villagers go with the other level mobs). */
	public static void stop(Minecraft mc) {
		if (mc.level != null) {
			for (Display.ItemDisplay disp : SHOWN.values()) {
				mc.level.removeEntity(disp.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		SHOWN.clear();
		COLLECTED.clear();
		DONE.clear();
		NPC_BY_UUID.clear();
		Dialog.hide();
	}

	/** This villager's deal is done (while playing). */
	public static boolean isDone(int npcId) {
		return DONE.contains(npcId);
	}

	public static void forget() {
		SHOWN.clear();
		COLLECTED.clear();
		DONE.clear();
		NPC_BY_UUID.clear();
		Dialog.hide();
	}

	// ---------------------------------------------------------------- while playing

	public static void tick(Minecraft mc, LevelData d) {
		if (mc.player == null) {
			return;
		}
		int t = BrickEntities.ticks();
		Vec3 me = mc.player.position();
		for (LevelData.Pickup p : d.pickups) {
			Display.ItemDisplay disp = SHOWN.get(p.id);
			if (disp == null || COLLECTED.contains(p.id)) {
				continue;
			}
			Quaternionf rot = new Quaternionf().rotationY(t * 0.09F + p.id);
			float bob = (float) Math.sin(t * 0.12 + p.id) * 0.12F;
			disp.setTransformation(new Transformation(new Vector3f(0.0F, 0.25F + bob, 0.0F), rot, new Vector3f(0.75F), null));
			disp.setTransformationInterpolationDuration(1);
			double dx = me.x - (p.pos.getX() + 0.5);
			double dz = me.z - (p.pos.getZ() + 0.5);
			double dy = me.y - p.pos.getY();
			if (dx * dx + dz * dz < 1.3 * 1.3 && dy > -1.5 && dy < 1.8) {
				collect(mc, p, disp);
			}
		}
		// the villagers turn towards the figure when it is near
		if (t % 4 == 0 && !NPC_BY_UUID.isEmpty()) {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server != null && mc.level != null) {
				var dim = mc.level.dimension();
				UUID pid = mc.player.getUUID();
				var ids = new java.util.ArrayList<>(NPC_BY_UUID.keySet());
				server.execute(() -> {
					ServerLevel sl = server.getLevel(dim);
					ServerPlayer sp = server.getPlayerList().getPlayer(pid);
					if (sl == null || sp == null) {
						return;
					}
					for (UUID u : ids) {
						Entity e = sl.getEntity(u);
						if (e == null || e.distanceTo(sp) > 9.0F) {
							continue;
						}
						double dx = sp.getX() - e.getX();
						double dz = sp.getZ() - e.getZ();
						float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
						e.setYRot(yaw);
						e.setYHeadRot(yaw);
						e.setYBodyRot(yaw);
					}
				});
			}
		}
	}

	private static void collect(Minecraft mc, LevelData.Pickup p, Display.ItemDisplay disp) {
		COLLECTED.add(p.id);
		if (mc.level != null) {
			mc.level.removeEntity(disp.getId(), Entity.RemovalReason.DISCARDED);
		}
		SHOWN.remove(p.id);
		if (item(p.item) == Items.TNT && Carry.available()) {
			intoArms(mc, 0);
		} else {
			give(mc, new ItemStack(item(p.item)));
		}
		Editor.play(mc, SoundEvents.ITEM_PICKUP, 1.2F);
		Editor.play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.6F);
		Coins.burst(mc, p.pos.getX() + 0.5, p.pos.getY() + 0.8, p.pos.getZ() + 0.5, 6);
		mc.gui.hud.setOverlayMessage(Component.literal("Found: " + itemName(p.item) + "!"), false);
		Tutorial.note("found");
		LevelData d = Editor.level();
		if (d != null) {
			Editor.sourceFired(mc, d, p.id, Vec3.atCenterOf(p.pos));
		}
	}

	/** A box of TNT into the figure's arms (Carry On); if the arms are full, into its pockets. */
	private static void intoArms(Minecraft mc, int unused) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp != null && !Carry.intoArms(sp, net.minecraft.world.level.block.Blocks.TNT.defaultBlockState()) && !sp.getInventory().add(new ItemStack(Items.TNT))) {
				sp.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(sp.level(), sp.getX(), sp.getY() + 0.5, sp.getZ(), new ItemStack(Items.TNT)));
			}
		});
	}

	/** Into the figure's pockets (on the server; what does not fit lands at its feet). */
	private static void give(Minecraft mc, ItemStack stack) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || stack.isEmpty()) {
			return;
		}
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp != null && !sp.getInventory().add(stack)) {
				sp.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(sp.level(), sp.getX(), sp.getY() + 0.5, sp.getZ(), stack));
			}
		});
	}

	private static int count(Inventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	// ---------------------------------------------------------------- talking

	/** Fabric's use-entity event: using a villager of the level talks to it (no trading screen). */
	public static InteractionResult useEntity(Player player, Level world, InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
		if (!world.isClientSide() || !Editor.playing()) {
			return InteractionResult.PASS;
		}
		Integer id = NPC_BY_UUID.get(entity.getUUID());
		LevelData d = Editor.level();
		if (id == null || d == null) {
			return InteractionResult.PASS;
		}
		LevelData.Npc n = d.npc(id);
		if (n != null && hand == InteractionHand.MAIN_HAND) {
			talk(Minecraft.getInstance(), d, n, entity);
		}
		return InteractionResult.FAIL;
	}

	private static int talkTick = -100;
	private static int talkTo = -1;

	private static void talk(Minecraft mc, LevelData d, LevelData.Npc n, Entity e) {
		// holding the button repeats every few ticks: that is still one click
		int now = BrickEntities.ticks();
		boolean again = now - talkTick <= 5 && e.getId() == talkTo;
		talkTick = now;
		talkTo = e.getId();
		if (again) {
			return;
		}
		// clicking again while they talk ends it; the next click starts over
		if (Dialog.showingFor(e)) {
			Dialog.close();
			return;
		}
		String name = displayName(n);
		ItemStack icon = new ItemStack(item(n.wants.isEmpty() ? n.gives : n.wants));
		if (DONE.contains(n.id)) {
			Dialog.show(name, doneLine(n), new ItemStack(item(n.gives)), e);
			play(mc, e, SoundEvents.VILLAGER_AMBIENT, 1.0F);
			return;
		}
		Item want = item(n.wants);
		if (want != Items.AIR && (mc.player == null || count(mc.player.getInventory(), want) == 0)) {
			Dialog.show(name, askLine(n), icon, e);
			play(mc, e, SoundEvents.VILLAGER_NO, 1.0F);
			return;
		}
		// the deal
		DONE.add(n.id);
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null && mc.player != null) {
			UUID pid = mc.player.getUUID();
			Item give = item(n.gives);
			int amount = Math.max(1, n.givesCount);
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(pid);
				if (sp == null) {
					return;
				}
				Inventory inv = sp.getInventory();
				if (want != Items.AIR) {
					for (int i = 0; i < inv.getContainerSize(); i++) {
						if (inv.getItem(i).is(want)) {
							inv.getItem(i).shrink(1);
							break;
						}
					}
				}
				int left = amount;
				if (give == Items.TNT && Carry.intoArms(sp, net.minecraft.world.level.block.Blocks.TNT.defaultBlockState())) {
					left--;
				}
				if (give != Items.AIR && left > 0) {
					ItemStack stack = new ItemStack(give, left);
					if (!inv.add(stack)) {
						sp.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(sp.level(), sp.getX(), sp.getY() + 0.5, sp.getZ(), stack));
					}
				}
			});
		}
		Dialog.show(name, thanksLine(n), new ItemStack(item(n.gives)), e);
		play(mc, e, SoundEvents.VILLAGER_YES, 1.0F);
		Editor.play(mc, SoundEvents.NOTE_BLOCK_BELL.value(), 1.4F);
		Coins.burst(mc, e.getX(), e.getY() + 2.0, e.getZ(), 8);
		Editor.fireTargets(mc, d, n.targets, e.position());
		Tutorial.note("deal");
	}

	private static void play(Minecraft mc, Entity e, net.minecraft.sounds.SoundEvent sound, float pitch) {
		mc.getSoundManager().play(new net.minecraft.client.resources.sounds.SimpleSoundInstance(sound, SoundSource.NEUTRAL, 1.0F, pitch,
			net.minecraft.util.RandomSource.create(), e.getX(), e.getY() + 1.6, e.getZ()));
	}

	// ---------------------------------------------------------------- talking without aiming

	/** A villager of the level near the figure: within {@code reach}, in front ({@code minDot}; -1: anywhere). */
	public static @Nullable Entity npcNear(Player player, double reach, double minDot) {
		if (NPC_BY_UUID.isEmpty() || !(player.level() instanceof ClientLevel cl)) {
			return null;
		}
		Vec3 me = player.position();
		Vec3 facing = Vec3.directionFromRotation(0.0F, player.getYRot());
		Entity best = null;
		double bestScore = Double.MAX_VALUE;
		for (Entity e : cl.getEntities(player, player.getBoundingBox().inflate(reach + 1.0, 2.0, reach + 1.0))) {
			if (!NPC_BY_UUID.containsKey(e.getUUID())) {
				continue;
			}
			Vec3 to = new Vec3(e.getX() - me.x, 0.0, e.getZ() - me.z);
			double dist = to.length();
			if (dist > reach || Math.abs(e.getY() - me.y) > 2.5) {
				continue;
			}
			double dot = dist < 0.01 ? 1.0 : (to.x * facing.x + to.z * facing.z) / dist;
			if (dot < minDot) {
				continue;
			}
			double score = dist + (1.0 - dot) * 1.5;
			if (score < bestScore) {
				bestScore = score;
				best = e;
			}
		}
		return best;
	}

	/** Talks to this villager of the level (the same as clicking them). */
	public static void talkTo(Minecraft mc, Entity e) {
		if (mc.player != null && mc.level != null) {
			useEntity(mc.player, mc.level, InteractionHand.MAIN_HAND, e, null);
		}
	}

	// ---------------------------------------------------------------- lifting (Carry On)

	/**
	 * Runs before Carry On. In a level only what the builder marked liftable (and TNT) can be lifted: sneak + right-click
	 * with empty hands puts it into the figure's arms, a right-click sets it down again (Carry On places it). Nothing else
	 * of the level is carried away.
	 */
	public static InteractionResult useLift(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (!Editor.playing() || !Carry.available() || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		LevelData d = Editor.level();
		if (d == null) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		if (Carry.carrying(player)) {
			// setting down: where Carry On puts it, it can be lifted again
			BlockPos at = world.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
			if (!d.inside(at)) {
				return InteractionResult.FAIL;
			}
			if (!world.isClientSide()) {
				Editor.setDown(at.immutable());
				Tutorial.note("setdown");
			}
			return InteractionResult.PASS;
		}
		if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		if (!canLift(world, d, pos)) {
			// generous, like hitting: the nearest liftable thing right in front of the figure
			BlockPos near = liftNear(player, world, d);
			if (near == null) {
				return InteractionResult.FAIL;
			}
			pos = near;
		}
		net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
		if (world.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer sp && Carry.intoArms(sp, state, world.getBlockEntity(pos))) {
			world.removeBlockEntity(pos);
			world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
			Editor.lifted(pos);
			world.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
			Tutorial.note("lift");
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.FAIL;
	}

	private static boolean canLift(Level world, LevelData d, BlockPos pos) {
		return d.inside(pos) && !world.getBlockState(pos).isAir() && (Editor.liftableAt(pos) || world.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.TNT));
	}

	private static @Nullable BlockPos liftNear(Player player, Level world, LevelData d) {
		Vec3 me = player.position();
		Vec3 facing = Vec3.directionFromRotation(0.0F, player.getYRot());
		BlockPos base = player.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dx = -3; dx <= 3; dx++) {
					BlockPos p = base.offset(dx, dy, dz);
					if (!canLift(world, d, p)) {
						continue;
					}
					Vec3 to = new Vec3(p.getX() + 0.5 - me.x, 0.0, p.getZ() + 0.5 - me.z);
					double dist = to.length();
					if (dist > 2.6) {
						continue;
					}
					double dot = dist < 0.01 ? 1.0 : (to.x * facing.x + to.z * facing.z) / dist;
					if (dot < -0.1) {
						continue;
					}
					double score = dist + (1.0 - dot) * 1.2;
					if (score < bestScore) {
						bestScore = score;
						best = p.immutable();
					}
				}
			}
		}
		return best;
	}

	/** Runs before Carry On: in a level nobody is picked up and carried off (sneaking at a villager still talks). */
	public static InteractionResult noCarryEntity(Player player, Level world, InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
		if (!Editor.playing() || !Carry.available() || !player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (world.isClientSide()) {
			useEntity(player, world, hand, entity, hit);
		}
		return InteractionResult.FAIL;
	}

	// ---------------------------------------------------------------- TNT

	/** Fabric's use-block event: TNT in the hand is put down lit, the way the brick games do it. */
	public static InteractionResult useTnt(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
		if (!world.isClientSide() || !Editor.playing() || !player.getItemInHand(hand).is(Items.TNT)) {
			return InteractionResult.PASS;
		}
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		if (server == null || !world.getBlockState(at).canBeReplaced()) {
			return InteractionResult.FAIL;
		}
		var dim = world.dimension();
		UUID pid = player.getUUID();
		boolean creative = player.getAbilities().instabuild;
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			ServerPlayer sp = server.getPlayerList().getPlayer(pid);
			if (sl == null || sp == null) {
				return;
			}
			PrimedTnt tnt = new PrimedTnt(sl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, sp);
			tnt.setFuse(60);
			sl.addFreshEntity(tnt);
			sl.playSound(null, at, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
			if (!creative) {
				ItemStack held = sp.getItemInHand(hand);
				if (held.is(Items.TNT)) {
					held.shrink(1);
				}
			}
		});
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		Tutorial.note("tnt");
		return InteractionResult.FAIL;
	}
}
