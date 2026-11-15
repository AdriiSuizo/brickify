package dev.rusty.brickify.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rusty.brickify.Brickify;
import dev.rusty.brickify.client.BrickCam;
import dev.rusty.brickify.client.BrickModels;
import dev.rusty.brickify.client.BrickWorld;
import dev.rusty.brickify.client.Hops;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.registries.Registries;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev-only remote control so the mod can be tested in the background (same idea as in Vanilla Factory).
 * Reads run/bk_harness/cmd.json, runs the listed commands in order and answers in result.json.
 * Only active when the game is started with -Dbk.harness=true.
 */
public final class Harness {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
	private static final ArrayDeque<JsonObject> QUEUE = new ArrayDeque<>();
	private static final AtomicInteger PENDING_SHOTS = new AtomicInteger();
	private static final AtomicInteger RECORD_PENDING = new AtomicInteger();

	private static Path dir;
	private static int ticks;
	private static int waitTicks;
	private static int shotTimeout;
	private static long currentId = -1;
	private static JsonArray results = new JsonArray();
	private static boolean worldRequested;
	private static int recordLeft;
	private static int recordIndex;
	private static int recordDownscale = 2;
	private static int recordEvery = 1;
	private static int recordPhase;
	private static File recordDir;
	private static boolean waitingForRecording;
	/** Walking to / turning towards a mob, for staged clips. */
	private static String moveType;
	private static double moveDist;
	private static int moveLeft;
	private static boolean moveWalk;

	private Harness() {
	}

	public static void init() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			try {
				dir = mc.gameDirectory.toPath().resolve(System.getProperty("bk.hdir", "bk_harness"));
				Files.createDirectories(dir);
				Files.deleteIfExists(dir.resolve("cmd.json"));
				Files.deleteIfExists(dir.resolve("result.json"));
			} catch (IOException e) {
				Brickify.LOGGER.error("Harness: cannot create directory", e);
			}
			mc.options.pauseOnLostFocus = false;
			mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(Boolean.getBoolean("bk.hidden") ? 0.0 : 1.0);
			Brickify.LOGGER.info("Harness active (hidden={})", Boolean.getBoolean("bk.hidden"));
		});
		ClientTickEvents.END_CLIENT_TICK.register(Harness::tick);
	}

	/** Every click the wave plays, written next to the frames of a running recording (for the sound of the clip). */
	public static void onClick(double x, double y, double z, float volume, float pitch) {
		onSound("click", x, y, z, volume, pitch);
	}

	public static void onSound(String sound, double x, double y, double z, float volume, float pitch) {
		if (dir == null || recordDir == null || recordLeft <= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.mainCamera();
		String line = String.format(Locale.ROOT, "{\"snd\":\"" + sound + "\",\"t\":%d,\"f\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"cx\":%.2f,\"cy\":%.2f,\"cz\":%.2f,\"yaw\":%.1f,\"vol\":%.3f,\"pitch\":%.3f}%n",
			System.nanoTime(), recordIndex, x, y, z, cam.position().x, cam.position().y, cam.position().z, cam.yRot(), volume, pitch);
		try {
			Files.writeString(recordDir.toPath().resolve("clicks.jsonl"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}

	private static void tick(Minecraft mc) {
		if (dir == null) {
			return;
		}
		ticks++;
		try {
			autoWorld(mc);
			step(mc);
			if (ticks % 20 == 0) {
				write("status.json", state(mc));
			}
		} catch (Throwable t) {
			Brickify.LOGGER.error("Harness error", t);
			JsonObject err = new JsonObject();
			err.addProperty("error", String.valueOf(t));
			results.add(err);
			QUEUE.clear();
			waitTicks = 0;
			PENDING_SHOTS.set(0);
		}
	}

	/** Opens the test world on its own once the title screen shows; creates it on first run. */
	private static void autoWorld(Minecraft mc) {
		if (worldRequested || mc.level != null || !(mc.gui.screen() instanceof TitleScreen)) {
			return;
		}
		worldRequested = true;
		String id = System.getProperty("bk.world", "bk_test");
		if (mc.getLevelSource().levelExists(id)) {
			mc.createWorldOpenFlows().openWorld(id, () -> { });
			return;
		}
		boolean flat = Boolean.getBoolean("bk.flat");
		LevelSettings settings = new LevelSettings(id, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
		String seedText = System.getProperty("bk.seed");
		long seed = seedText == null ? WorldOptions.randomSeed() : parseSeed(seedText);
		if (flat) {
			mc.createWorldOpenFlows().createFreshLevel(
				id,
				settings,
				new WorldOptions(seed, false, false),
				registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
				mc.gui.screen()
			);
		} else {
			mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions, mc.gui.screen());
		}
	}

	private static long parseSeed(String text) {
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return text.hashCode();
		}
	}

	/** Called after every rendered frame; saves the frame while a recording is running. */
	public static void onFrame(Minecraft mc) {
		if (dir == null || recordLeft <= 0 || recordDir == null || recordPhase++ % recordEvery != 0) {
			return;
		}
		recordLeft--;
		File file = new File(recordDir, String.format("f_%04d.png", ++recordIndex));
		try {
			Files.writeString(recordDir.toPath().resolve("frames.txt"), recordIndex + " " + System.nanoTime() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
		RECORD_PENDING.incrementAndGet();
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), recordDownscale, image -> net.minecraft.util.Util.ioPool().execute(() -> {
			try (image) {
				image.writeToFile(file);
			} catch (Exception e) {
				Brickify.LOGGER.warn("Harness: could not save frame", e);
			} finally {
				RECORD_PENDING.decrementAndGet();
			}
		}));
	}

	private static net.minecraft.world.entity.@org.jspecify.annotations.Nullable LivingEntity nearest(Minecraft mc, String type, double max) {
		net.minecraft.world.entity.LivingEntity best = null;
		double bestD = max * max;
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || !(e instanceof net.minecraft.world.entity.LivingEntity living) || living.isDeadOrDying()) {
				continue;
			}
			if (type != null && !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(type)) {
				continue;
			}
			double d = e.distanceToSqr(mc.player);
			if (d < bestD) {
				bestD = d;
				best = living;
			}
		}
		return best;
	}

	/** One tick of walking to / turning towards the staged target. Returns true while still moving. */
	private static boolean move(Minecraft mc) {
		if (moveLeft <= 0 || mc.player == null) {
			return false;
		}
		moveLeft--;
		net.minecraft.world.entity.LivingEntity target = nearest(mc, moveType, 48.0);
		double dist = target == null ? 0.0 : Math.sqrt(target.distanceToSqr(mc.player.getX(), target.getY(), mc.player.getZ()));
		if (target != null) {
			double dx = target.getX() - mc.player.getX();
			double dz = target.getZ() - mc.player.getZ();
			float want = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
			float now = mc.player.getYRot();
			float next = now + net.minecraft.util.Mth.wrapDegrees(want - now) * 0.3F;
			mc.player.setYRot(next);
			mc.player.setXRot(net.minecraft.util.Mth.lerp(0.3F, mc.player.getXRot(), 10.0F));
		}
		boolean arrived = target == null || (moveWalk ? dist <= moveDist : moveLeft <= 0);
		if (arrived) {
			moveLeft = 0;
		}
		mc.options.keyUp.setDown(moveWalk && moveLeft > 0);
		return moveLeft > 0;
	}

	private static void step(Minecraft mc) throws IOException {
		if (move(mc)) {
			return;
		}
		if (waitingForRecording) {
			if (recordLeft > 0 || RECORD_PENDING.get() > 0) {
				return;
			}
			waitingForRecording = false;
		}
		if (PENDING_SHOTS.get() > 0) {
			if (--shotTimeout > 0) {
				return;
			}
			PENDING_SHOTS.set(0);
			JsonObject err = new JsonObject();
			err.addProperty("error", "screenshot timed out");
			results.add(err);
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		if (QUEUE.isEmpty()) {
			if (currentId >= 0) {
				JsonObject out = new JsonObject();
				out.addProperty("id", currentId);
				out.add("results", results);
				out.add("state", state(mc));
				write("result.json", out);
				currentId = -1;
			}
			Path cmd = dir.resolve("cmd.json");
			if (Files.exists(cmd)) {
				String text = Files.readString(cmd, StandardCharsets.UTF_8);
				Files.delete(cmd);
				JsonObject in = JsonParser.parseString(text).getAsJsonObject();
				currentId = in.get("id").getAsLong();
				results = new JsonArray();
				for (JsonElement e : in.getAsJsonArray("cmds")) {
					QUEUE.add(e.getAsJsonObject());
				}
			}
		}
		while (!QUEUE.isEmpty() && waitTicks == 0 && PENDING_SHOTS.get() == 0 && !waitingForRecording) {
			run(mc, QUEUE.poll());
		}
	}

	private static java.util.List<dev.rusty.brickify.client.BrickRuins.Piece> lastPieces = java.util.List.of();

	private static void run(Minecraft mc, JsonObject cmd) {
		String op = cmd.get("op").getAsString();
		JsonObject res = new JsonObject();
		res.addProperty("op", op);
		switch (op) {
			case "wait" -> waitTicks = cmd.get("ticks").getAsInt();
			case "screenshot" -> {
				String name = cmd.get("name").getAsString();
				int downscale = cmd.has("downscale") ? cmd.get("downscale").getAsInt() : 1;
				PENDING_SHOTS.incrementAndGet();
				shotTimeout = 200;
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), downscale, msg -> PENDING_SHOTS.decrementAndGet());
				res.addProperty("file", "screenshots/" + name);
			}
			case "command" -> {
				String text = cmd.get("cmd").getAsString();
				IntegratedServer server = mc.getSingleplayerServer();
				if (server == null) {
					res.addProperty("error", "no world loaded");
				} else {
					server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), text));
				}
			}
			case "state" -> res.add("state", state(mc));
			case "record" -> {
				recordDir = new File(new File(mc.gameDirectory, "clips"), cmd.get("name").getAsString());
				recordDir.mkdirs();
				File[] old = recordDir.listFiles();
				if (old != null) {
					for (File f : old) {
						f.delete();
					}
				}
				recordIndex = 0;
				recordDownscale = cmd.has("downscale") ? cmd.get("downscale").getAsInt() : 2;
				recordEvery = cmd.has("every") ? Math.max(1, cmd.get("every").getAsInt()) : 1;
				recordPhase = 0;
				recordLeft = cmd.get("frames").getAsInt();
			}
			case "recordwait" -> waitingForRecording = true;
			case "recordstop" -> recordLeft = 0;
			case "brick" -> {
				boolean on = !cmd.has("on") || cmd.get("on").getAsBoolean();
				if (on) {
					BrickWorld.start(mc, cmd.has("instant") && cmd.get("instant").getAsBoolean());
				} else {
					BrickWorld.stop(mc);
				}
			}
			case "tune" -> {
				if (cmd.has("speed0")) BrickWorld.speed0 = cmd.get("speed0").getAsDouble();
				if (cmd.has("accel")) BrickWorld.accel = cmd.get("accel").getAsDouble();
				if (cmd.has("hopRadius")) BrickWorld.hopRadius = cmd.get("hopRadius").getAsDouble();
				if (cmd.has("remeshEvery")) BrickWorld.remeshEvery = cmd.get("remeshEvery").getAsInt();
				if (cmd.has("hold")) Hops.hold = cmd.get("hold").getAsInt();
				if (cmd.has("lift")) Hops.lift = cmd.get("lift").getAsFloat();
				if (cmd.has("maxPerTick")) Hops.maxPerTick = cmd.get("maxPerTick").getAsInt();
				if (cmd.has("distance")) BrickCam.distance = cmd.get("distance").getAsDouble();
				if (cmd.has("pitchAdd")) BrickCam.pitchAdd = cmd.get("pitchAdd").getAsDouble();
				if (cmd.has("fov")) BrickCam.fov = cmd.get("fov").getAsDouble();
				if (cmd.has("yawOffset")) BrickCam.yawOffset = cmd.get("yawOffset").getAsFloat();
				if (cmd.has("enterSeconds")) BrickCam.enterSeconds = cmd.get("enterSeconds").getAsDouble();
				if (cmd.has("tilt")) BrickCam.tilt = cmd.get("tilt").getAsBoolean();
				if (cmd.has("look")) BrickCam.tilt = cmd.get("look").getAsBoolean();
				if (cmd.has("studSections")) BrickWorld.studSections = cmd.get("studSections").getAsInt();
			}
			case "look" -> {
				if (mc.player != null) {
					mc.player.setYRot(cmd.get("yaw").getAsFloat());
					mc.player.setXRot(cmd.get("pitch").getAsFloat());
					mc.player.yRotO = mc.player.getYRot();
					mc.player.xRotO = mc.player.getXRot();
				}
			}
			case "approach", "face" -> {
				moveType = cmd.has("type") ? cmd.get("type").getAsString() : null;
				moveDist = cmd.has("dist") ? cmd.get("dist").getAsDouble() : 2.4;
				moveWalk = op.equals("approach");
				moveLeft = cmd.has("ticks") ? cmd.get("ticks").getAsInt() : (moveWalk ? 100 : 10);
			}
			case "mobs" -> {
				JsonArray list = new JsonArray();
				for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
					if (e != mc.player && e instanceof net.minecraft.world.entity.LivingEntity && e.distanceToSqr(mc.player) < 40 * 40) {
						list.add(String.format(Locale.ROOT, "%s %.1f %.1f %.1f d=%.1f", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(),
							e.getX(), e.getY(), e.getZ(), Math.sqrt(e.distanceToSqr(mc.player))));
					}
				}
				res.add("mobs", list);
			}
			case "pieces" -> {
				lastPieces = dev.rusty.brickify.client.BrickRuins.pieces(mc, cmd.has("radius") ? cmd.get("radius").getAsInt() : 6);
				JsonArray arr = new JsonArray();
				for (int i = 0; i < lastPieces.size(); i++) {
					var piece = lastPieces.get(i);
					var b = piece.box();
					arr.add(String.format(Locale.ROOT, "%d %s %d %d %d %dx%dx%d", i, piece.name(), (b.minX() + b.maxX()) / 2, b.minY(), (b.minZ() + b.maxZ()) / 2,
						b.getXSpan(), b.getYSpan(), b.getZSpan()));
				}
				res.add("pieces", arr);
			}
			case "ruin" -> {
				JsonArray done = new JsonArray();
				for (JsonElement e : cmd.getAsJsonArray("indices")) {
					int i = e.getAsInt();
					if (i >= 0 && i < lastPieces.size()) {
						var piece = lastPieces.get(i);
						done.add(piece.name() + ": " + dev.rusty.brickify.client.BrickRuins.ruin(mc, piece.name(), piece.box()));
					}
				}
				res.add("ruined", done);
			}
			case "build" -> res.addProperty("built", dev.rusty.brickify.client.BuildKey.buildNearest(mc));
			case "buildAll" -> res.addProperty("built", dev.rusty.brickify.client.BuildKey.startMega(mc));
			case "hop" -> {
				if (mc.player != null) {
					mc.player.jumpFromGround();
				}
			}
			case "djump" -> dev.rusty.brickify.client.DoubleJump.trigger(mc);
			case "respawn" -> {
				if (mc.player != null) {
					mc.player.respawn();
				}
			}
			case "breakBlock" -> {
				net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
				if (mc.gameMode != null) {
					res.addProperty("broken", mc.gameMode.startDestroyBlock(bp, net.minecraft.core.Direction.UP));
				}
			}
			case "studs" -> res.addProperty("studs", dev.rusty.brickify.client.Coins.total());
			case "placeOn" -> {
				net.minecraft.core.BlockPos on = new net.minecraft.core.BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
				net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
					net.minecraft.world.phys.Vec3.atCenterOf(on).add(0.0, 0.5, 0.0), net.minecraft.core.Direction.UP, on, false);
				res.addProperty("placed", String.valueOf(mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)));
			}
			case "chop" -> {
				// the trunk base nearest to the player: lowest log of the closest log column
				int rad = cmd.has("radius") ? cmd.get("radius").getAsInt() : 10;
				net.minecraft.core.BlockPos best = null;
				double bestD = Double.MAX_VALUE;
				net.minecraft.core.BlockPos c = mc.player.blockPosition();
				for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(c.offset(-rad, -3, -rad), c.offset(rad, 6, rad))) {
					if (!mc.level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS) || mc.level.getBlockState(q.below()).is(net.minecraft.tags.BlockTags.LOGS)) {
						continue;
					}
					double d = q.distSqr(c);
					if (d < bestD) {
						bestD = d;
						best = q.immutable();
					}
				}
				if (best == null) {
					res.addProperty("error", "no tree");
				} else {
					res.addProperty("log", best.toShortString());
					res.addProperty("broken", mc.gameMode.startDestroyBlock(best, net.minecraft.core.Direction.UP));
				}
			}
			case "restoreRuins" -> res.addProperty("restored", dev.rusty.brickify.client.BrickRuins.restoreAll(mc));
			case "punch" -> net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT));
			case "fkey" -> net.minecraft.client.KeyMapping.click(mc.options.keySwapOffhand.getDefaultKey());
			case "useclick" -> net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT));
			case "keys" -> {
				var o = mc.options;
				if (cmd.has("forward")) o.keyUp.setDown(cmd.get("forward").getAsBoolean());
				if (cmd.has("back")) o.keyDown.setDown(cmd.get("back").getAsBoolean());
				if (cmd.has("left")) o.keyLeft.setDown(cmd.get("left").getAsBoolean());
				if (cmd.has("right")) o.keyRight.setDown(cmd.get("right").getAsBoolean());
				if (cmd.has("sprint")) o.keySprint.setDown(cmd.get("sprint").getAsBoolean());
				if (cmd.has("jump")) o.keyJump.setDown(cmd.get("jump").getAsBoolean());
				if (cmd.has("sneak")) o.keyShift.setDown(cmd.get("sneak").getAsBoolean());
				if (cmd.has("attack")) o.keyAttack.setDown(cmd.get("attack").getAsBoolean());
				if (cmd.has("use")) o.keyUse.setDown(cmd.get("use").getAsBoolean());
				if (cmd.has("slot") && mc.player != null) mc.player.getInventory().setSelectedSlot(cmd.get("slot").getAsInt());
			}
			case "anim" -> {
				dev.rusty.brickify.client.AnimSwitch.cycle(mc);
				res.addProperty("anim", dev.rusty.brickify.client.AnimSwitch.name());
			}
			case "physics" -> {
				if (cmd.has("mode")) {
					String m = cmd.get("mode").getAsString();
					dev.rusty.brickify.client.Physics.set(m.equals("off") ? 0 : m.equals("strict") ? 2 : 1);
				}
				res.addProperty("physics", dev.rusty.brickify.client.Physics.name());
			}
			case "smash" -> {
				net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
				res.addProperty("block", mc.level.getBlockState(bp).toString());
				res.addProperty("result", String.valueOf(mc.gameMode.startDestroyBlock(bp, net.minecraft.core.Direction.UP)));
			}
			case "editor" -> {
				var E = dev.rusty.brickify.client.editor.Editor.class;
				String what = cmd.get("do").getAsString();
				net.minecraft.core.BlockPos bp = cmd.has("x") ? new net.minecraft.core.BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt()) : null;
				switch (what) {
					case "toggle" -> dev.rusty.brickify.client.editor.Editor.toggle(mc);
					case "edit" -> dev.rusty.brickify.client.editor.Editor.enterEdit(mc);
					case "play" -> dev.rusty.brickify.client.editor.Editor.enterPlay(mc);
					case "diorama" -> dev.rusty.brickify.client.editor.Editor.openDiorama(mc, cmd.has("instant") && cmd.get("instant").getAsBoolean());
					case "ego" -> dev.rusty.brickify.client.editor.Editor.closeDiorama(mc);
					case "tool" -> dev.rusty.brickify.client.editor.Editor.setTool(dev.rusty.brickify.client.editor.Editor.Tool.valueOf(cmd.get("tool").getAsString()));
					case "cursor" -> {
						dev.rusty.brickify.client.editor.EditorScreen.debugX = cmd.get("nx").getAsDouble();
						dev.rusty.brickify.client.editor.EditorScreen.debugY = cmd.get("ny").getAsDouble();
					}
					case "click" -> {
						if (mc.gui.screen() instanceof dev.rusty.brickify.client.editor.EditorScreen es) {
							es.clickWorld(mc, dev.rusty.brickify.client.editor.Editor.hover(), cmd.has("shift") && cmd.get("shift").getAsBoolean(), cmd.has("alt") && cmd.get("alt").getAsBoolean());
						}
					}
					case "select" -> dev.rusty.brickify.client.editor.Editor.select(mc.level, bp, cmd.has("add") && cmd.get("add").getAsBoolean(), cmd.has("single") && cmd.get("single").getAsBoolean());
					case "box" -> dev.rusty.brickify.client.editor.Editor.selectBox(mc.level, bp, new net.minecraft.core.BlockPos(cmd.get("x2").getAsInt(), cmd.get("y2").getAsInt(), cmd.get("z2").getAsInt()), false, cmd.has("ground") && cmd.get("ground").getAsBoolean());
					case "make" -> {
						var b = dev.rusty.brickify.client.editor.Editor.makeBlueprint(mc);
						res.addProperty("made", b == null ? -1 : b.blocks.size());
					}
					case "pile" -> dev.rusty.brickify.client.editor.Editor.placePile(mc, bp);
					case "start" -> dev.rusty.brickify.client.editor.Editor.setStart(mc, bp, cmd.has("yaw") ? cmd.get("yaw").getAsFloat() : 0.0F);
					case "goal" -> dev.rusty.brickify.client.editor.Editor.setGoal(mc, bp);
					case "gate" -> {
						var g = dev.rusty.brickify.client.editor.Editor.makeGate(mc);
						res.addProperty("made", g == null ? -1 : g.blocks.size());
					}
					case "link" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var kind = dev.rusty.brickify.client.editor.Pick.Kind.valueOf(cmd.get("kind").getAsString());
						int id = cmd.has("id") ? cmd.get("id").getAsInt() : 0;
						dev.rusty.brickify.client.editor.Editor.linkClick(mc, new dev.rusty.brickify.client.editor.Pick(kind, bp, net.minecraft.core.Direction.UP, net.minecraft.world.phys.Vec3.atCenterOf(bp), id, 1.0));
						res.addProperty("switches", lv == null ? -1 : lv.switches.size());
					}
					case "checkpoint" -> dev.rusty.brickify.client.editor.Editor.toggleCheckpoint(mc, bp);
					case "zone" -> {
						var z = dev.rusty.brickify.client.editor.Editor.makeZone(mc, bp.getX(), bp.getZ(), cmd.get("x2").getAsInt(), cmd.get("z2").getAsInt(),
							cmd.get("yaw").getAsFloat(), cmd.get("pitch").getAsFloat(), cmd.get("dist").getAsFloat());
						if (z != null && cmd.has("follow")) z.follow = cmd.get("follow").getAsFloat();
						dev.rusty.brickify.client.editor.Editor.save();
					}
					case "dissolve", "movepile" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var b = lv == null ? null : lv.blueprint(cmd.get("id").getAsInt());
						if (b != null && what.equals("dissolve")) dev.rusty.brickify.client.editor.Editor.dissolve(mc, b);
						if (b != null && what.equals("movepile")) dev.rusty.brickify.client.editor.Editor.movePile(b);
					}
					case "mob" -> dev.rusty.brickify.client.editor.Editor.placeMob(mc, bp);
					case "tutorial" -> dev.rusty.brickify.client.editor.Editor.createLevel(mc, true);
					case "pickup" -> dev.rusty.brickify.client.editor.Editor.placePickup(mc, bp);
					case "softbushes" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var server = mc.getSingleplayerServer();
						if (lv != null && server != null) {
							var dim = mc.level.dimension();
							server.execute(() -> dev.rusty.brickify.client.editor.TutorialPlate.softBushes(server.getLevel(dim), lv));
						}
					}
					case "selzone" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null && !lv.zones.isEmpty()) {
							var z = lv.zones.get(cmd.has("i") ? cmd.get("i").getAsInt() : 0);
							dev.rusty.brickify.client.editor.Editor.selectZone(z.id);
							if (cmd.has("handle")) {
								dev.rusty.brickify.client.editor.Editor.dragZoneHandle(z, cmd.get("handle").getAsInt(), cmd.get("bx").getAsInt(), cmd.get("bz").getAsInt(),
									new int[] {z.x0, z.z0, z.x1, z.z1}, z.x0, z.z0);
							}
							res.addProperty("zone", z.x0 + "," + z.z0 + " .. " + z.x1 + "," + z.z1 + " yaw " + z.yaw + " pitch " + z.pitch + " dist " + z.dist);
						}
					}
					case "lifthouses" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var server = mc.getSingleplayerServer();
						if (lv != null && server != null) {
							var dim = mc.level.dimension();
							server.execute(() -> dev.rusty.brickify.client.editor.TutorialPlate.liftWestHouses(server.getLevel(dim), lv));
						}
					}
					case "tutcams" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var server = mc.getSingleplayerServer();
						if (lv != null) {
							dev.rusty.brickify.client.editor.TutorialPlate.replaceCameras(lv);
							if (server != null) {
								var dim = mc.level.dimension();
								server.execute(() -> dev.rusty.brickify.client.editor.TutorialPlate.interiorCameras(server.getLevel(dim), lv));
							}
							dev.rusty.brickify.client.editor.Editor.save();
							res.addProperty("zones", lv.zones.size());
						}
					}
					case "npc" -> dev.rusty.brickify.client.editor.Editor.placeNpc(mc, bp);
					case "blast" -> dev.rusty.brickify.client.editor.Editor.blastMode = cmd.get("on").getAsBoolean();
					case "hit" -> res.addProperty("hit", mc.hitResult == null ? "null" : mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh ? "block " + bh.getBlockPos().toShortString() + " " + bh.getDirection() + " " + mc.level.getBlockState(bh.getBlockPos()) : mc.hitResult.getType().toString());
					case "dialog" -> {
						net.minecraft.world.entity.LivingEntity v2 = nearest(mc, "villager", 40.0);
						res.addProperty("showing", v2 != null && dev.rusty.brickify.client.editor.Dialog.showingFor(v2));
					}
					case "trigger" -> {
						var t = dev.rusty.brickify.client.editor.Editor.makeTrigger(mc, bp.getX(), bp.getZ(), cmd.get("x2").getAsInt(), cmd.get("z2").getAsInt(), bp);
						res.addProperty("trigger", t == null ? -1 : t.id);
					}
					case "blastat" -> {
						dev.rusty.brickify.client.editor.Editor.placeBlast(mc, bp);
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var b = lv == null ? null : lv.blastAt(bp);
						res.addProperty("blast", b == null ? -1 : b.id);
					}
					case "linkids" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						var list = lv == null ? null : lv.targetsOf(cmd.get("src").getAsInt());
						if (list != null) {
							list.add(cmd.get("dst").getAsInt());
							dev.rusty.brickify.client.editor.Editor.save();
						}
						res.addProperty("linked", list != null);
					}
					case "things" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null) {
							StringBuilder sb = new StringBuilder();
							for (var sp : lv.spawns) sb.append("spawn").append(sp.id).append("@").append(sp.pos.toShortString()).append(" ");
							for (var t : lv.triggers) sb.append("trig").append(t.id).append(t.targets).append(" ");
							for (var b : lv.blasts) sb.append("blast").append(b.id).append(" ");
							for (var n : lv.npcs) sb.append("npc").append(n.id).append(" ");
							for (var p : lv.pickups) sb.append("pick").append(p.id).append(p.targets).append(" ");
							res.addProperty("things", sb.toString());
						}
					}
					case "popup" -> {
						if (mc.gui.screen() instanceof dev.rusty.brickify.client.editor.EditorScreen es) {
							switch (cmd.get("kind").getAsString()) {
								case "item" -> es.pickItem("What lies here?", "minecraft:golden_carrot", false, id -> { });
								case "mob" -> es.pickMob("minecraft:zombie", id -> { });
								default -> es.typeText("What they say first", "Empty: the usual words", "Oh no, I lost my golden carrot!", 200, t -> { });
							}
						}
					}
					case "delthing" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null) {
							int id = cmd.get("id").getAsInt();
							var t = lv.trigger(id);
							var b = lv.blast(id);
							if (t != null) dev.rusty.brickify.client.editor.Editor.deleteTrigger(t);
							if (b != null) dev.rusty.brickify.client.editor.Editor.deleteBlast(b);
							res.addProperty("deleted", t != null || b != null);
						}
					}
					case "floor" -> {
						var a = dev.rusty.brickify.client.editor.Editor.selectedArea();
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (a != null && lv != null) {
							double before = dev.rusty.brickify.client.editor.Editor.areaGround(mc.level, lv, a);
							dev.rusty.brickify.client.editor.Editor.areaFloor(mc, a, cmd.get("step").getAsDouble());
							res.addProperty("floor", before + " -> " + dev.rusty.brickify.client.editor.Editor.areaGround(mc.level, lv, a));
						}
					}
					case "selnpc" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null && !lv.npcs.isEmpty()) dev.rusty.brickify.client.editor.Editor.selectPlace("npc", lv.npcs.get(0).pos);
					}
					case "seltrig" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null && !lv.triggers.isEmpty()) dev.rusty.brickify.client.editor.Editor.selectTrigger(lv.triggers.get(lv.triggers.size() - 1).id);
					}
					case "markmode" -> dev.rusty.brickify.client.editor.Editor.markMode(cmd.get("m").getAsInt());
					case "liftable" -> res.addProperty("liftable", bp != null && dev.rusty.brickify.client.editor.Editor.liftableAt(bp));
					case "talk" -> {
						net.minecraft.world.entity.LivingEntity v = nearest(mc, "villager", 8.0);
						if (v != null) {
							res.addProperty("talk", String.valueOf(dev.rusty.brickify.client.editor.LevelLife.useEntity(mc.player, mc.level, net.minecraft.world.InteractionHand.MAIN_HAND, v, null)));
							res.addProperty("showing", dev.rusty.brickify.client.editor.Dialog.showingFor(v));
						} else {
							res.addProperty("talk", "no villager near");
						}
					}
					case "usetnt" -> res.addProperty("tnt", String.valueOf(dev.rusty.brickify.client.editor.LevelLife.useTnt(mc.player, mc.level, net.minecraft.world.InteractionHand.MAIN_HAND,
						new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(bp), net.minecraft.core.Direction.valueOf(cmd.get("face").getAsString()), bp, false))));
					case "inv" -> {
						res.addProperty("carrying", dev.rusty.brickify.client.editor.Carry.carrying(mc.player));
						var ssp = mc.getSingleplayerServer() == null ? null : mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
						res.addProperty("carryingServer", dev.rusty.brickify.client.editor.Carry.carrying(ssp));
						StringBuilder sb = new StringBuilder();
						var inv = mc.player.getInventory();
						for (int i = 0; i < inv.getContainerSize(); i++) {
							var st = inv.getItem(i);
							if (!st.isEmpty()) sb.append(i).append(":").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()).append("x").append(st.getCount()).append(" ");
						}
						res.addProperty("inv", sb.toString());
						res.addProperty("block", cmd.has("x") ? mc.level.getBlockState(bp).toString() : "");
					}
					case "empty" -> dev.rusty.brickify.client.editor.Editor.createLevel(mc, false);
					case "retutorial" -> dev.rusty.brickify.client.editor.Editor.rebuildAsTutorial(mc);
					case "step" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null) {
							lv.tutorialStep = cmd.get("n").getAsInt();
						}
					}
					case "next" -> dev.rusty.brickify.client.editor.Tutorial.next(mc);
					case "note" -> dev.rusty.brickify.client.editor.Tutorial.note(cmd.get("e").getAsString());
					case "brk" -> res.addProperty("marked", dev.rusty.brickify.client.editor.Editor.breakClick(mc, mc.level, bp, cmd.has("whole") && cmd.get("whole").getAsBoolean(),
						cmd.has("single") && cmd.get("single").getAsBoolean()));
					case "plants" -> res.addProperty("marked", dev.rusty.brickify.client.editor.Editor.markPlants(mc, mc.level));
					case "spots" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null) {
							StringBuilder sb = new StringBuilder();
							for (var e : lv.spots.entrySet()) sb.append(e.getKey()).append("=").append(e.getValue().toShortString()).append(" ");
							res.addProperty("spots", sb.toString());
						}
					}
					case "ids" -> {
						var lv = dev.rusty.brickify.client.editor.Editor.level();
						if (lv != null) {
							StringBuilder sb = new StringBuilder();
							for (var b : lv.blueprints) sb.append("bp").append(b.id).append(" ");
							for (var g : lv.gates) sb.append("gate").append(g.id).append(" ");
							for (var sw : lv.switches) sb.append("sw").append(sw.id).append(sw.targets).append(" ");
							res.addProperty("ids", sb.toString());
						}
					}
					case "cam" -> {
						if (cmd.has("orbitX")) dev.rusty.brickify.client.editor.EditorCam.orbit(cmd.get("orbitX").getAsDouble(), cmd.has("orbitY") ? cmd.get("orbitY").getAsDouble() : 0.0);
						if (cmd.has("zoom")) dev.rusty.brickify.client.editor.EditorCam.zoom(cmd.get("zoom").getAsDouble());
						if (cmd.has("fx")) dev.rusty.brickify.client.editor.EditorCam.lookAt(new net.minecraft.world.phys.Vec3(cmd.get("fx").getAsDouble(), cmd.get("fy").getAsDouble(), cmd.get("fz").getAsDouble()));
					}
					default -> {
					}
				}
				var lvl = dev.rusty.brickify.client.editor.Editor.level();
				res.addProperty("mode", dev.rusty.brickify.client.editor.Editor.mode().name());
				res.addProperty("tool", dev.rusty.brickify.client.editor.Editor.tool().name());
				res.addProperty("selection", dev.rusty.brickify.client.editor.Editor.selection().size());
				res.addProperty("blueprints", lvl == null ? -1 : lvl.blueprints.size());
				res.addProperty("placing", dev.rusty.brickify.client.editor.Editor.placing() != null);
				res.addProperty("tutorial", lvl == null ? -2 : lvl.tutorialStep);
				res.addProperty("breakable", lvl == null ? -1 : lvl.breakable.size());
				res.addProperty("seeThrough", dev.rusty.brickify.client.SeeThrough.active());
				var hv = dev.rusty.brickify.client.editor.Editor.hover();
				res.addProperty("hover", hv == null ? "none" : hv.kind() + " " + hv.pos().toShortString() + " " + hv.face());
				if (lvl != null) {
					res.addProperty("area", lvl.minX + "," + lvl.floorY + "," + lvl.minZ + " .. " + lvl.maxX + "," + lvl.topY + "," + lvl.maxZ);
				}
			}
			case "smashNow" -> {
				net.minecraft.core.BlockPos bp = new net.minecraft.core.BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
				res.addProperty("smashed", dev.rusty.brickify.client.Smash.smash(mc, mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, bp));
			}
			case "tune2" -> {
				if (cmd.has("maxShown")) dev.rusty.brickify.client.BrickRuins.maxShown = cmd.get("maxShown").getAsInt();
				if (cmd.has("maxShownPerBurst")) dev.rusty.brickify.client.BrickRuins.maxShownPerBurst = cmd.get("maxShownPerBurst").getAsInt();
				if (cmd.has("strictSpan")) dev.rusty.brickify.client.Statics.strictSpan = cmd.get("strictSpan").getAsInt();
				if (cmd.has("limit")) dev.rusty.brickify.client.Statics.limit = cmd.get("limit").getAsInt();
				if (cmd.has("smash")) dev.rusty.brickify.client.Smash.enabled = cmd.get("smash").getAsBoolean();
				if (cmd.has("displayStuds")) dev.rusty.brickify.client.BrickWorld.displayStuds = cmd.get("displayStuds").getAsBoolean();
			}
			case "attack" -> {
				net.minecraft.world.entity.LivingEntity target = nearest(mc, cmd.has("type") ? cmd.get("type").getAsString() : null, 6.0);
				if (target == null) {
					res.addProperty("error", "nothing in reach");
				} else {
					mc.gameMode.attack(mc.player, target);
					mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
					res.addProperty("hit", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath());
				}
			}
			case "hud" -> {
				if (mc.gui.hud.isHidden() != cmd.get("hide").getAsBoolean()) {
					mc.gui.hud.toggle();
				}
			}
			case "save" -> {
				IntegratedServer server = mc.getSingleplayerServer();
				if (server != null) {
					server.execute(() -> server.saveEverything(true, true, false));
				}
			}
			case "press" -> {
				String want = cmd.get("text").getAsString().toLowerCase(Locale.ROOT);
				Screen screen = mc.gui.screen();
				boolean pressed = false;
				JsonArray names = new JsonArray();
				if (screen != null) {
					for (var child : screen.children()) {
						if (child instanceof net.minecraft.client.gui.components.AbstractButton button) {
							String text = button.getMessage().getString().toLowerCase(Locale.ROOT);
							String key = button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc ? tc.getKey().toLowerCase(Locale.ROOT) : "";
							names.add(text + " [" + key + "]");
							if (!pressed && (text.contains(want) || (!key.isEmpty() && key.contains(want)))) {
								button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
								pressed = true;
							}
						}
					}
				}
				res.addProperty("pressed", pressed);
				res.add("buttons", names);
			}
			case "quit" -> mc.stop();
			default -> res.addProperty("error", "unknown op");
		}
		results.add(res);
	}

	private static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("tick", ticks);
		Screen screen = mc.gui.screen();
		o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		o.addProperty("inWorld", mc.level != null && mc.player != null);
		o.addProperty("fps", mc.getFps());
		o.addProperty("width", mc.getWindow().getWidth());
		o.addProperty("height", mc.getWindow().getHeight());
		o.addProperty("brickStates", BrickModels.count());
		o.addProperty("mode", BrickWorld.mode());
		o.addProperty("landRadius", Math.round(BrickWorld.landRadius() * 10.0) / 10.0);
		o.addProperty("waveTicks", BrickWorld.waveTicks());
		o.addProperty("hops", Hops.live());
		o.addProperty("debris", dev.rusty.brickify.client.BrickDebris.live());
		o.addProperty("cam", Math.round(BrickCam.blend() * 100.0) / 100.0);
		o.addProperty("recording", recordLeft);
		int[] rs = dev.rusty.brickify.client.BrickRuins.stats();
		o.addProperty("ruins", dev.rusty.brickify.client.BrickRuins.count());
		o.addProperty("ruinBricks", rs[0]);
		o.addProperty("ruinShown", rs[1]);
		o.addProperty("clumps", rs[2]);
		o.addProperty("collapsing", dev.rusty.brickify.client.Collapse.live());
		o.addProperty("physics", dev.rusty.brickify.client.Physics.name());
		o.addProperty("entities", mc.level == null ? 0 : mc.level.getEntityCount());
		if (mc.player != null) {
			o.addProperty("x", mc.player.getX());
			o.addProperty("y", mc.player.getY());
			o.addProperty("z", mc.player.getZ());
			o.addProperty("yaw", mc.player.getYRot());
			o.addProperty("pitch", mc.player.getXRot());
		}
		return o;
	}

	private static void write(String name, JsonObject json) throws IOException {
		Path tmp = dir.resolve(name + ".tmp");
		Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
	}
}
