package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A brick figure that dies comes apart at the joints: head, body, arms and legs (whatever parts its model has)
 * fly off as real pieces that bounce, tumble and come to rest on the ground around it. They lie there for 15
 * seconds; stand next to them and press the build key and they click back together, one after the other, and the
 * mob is back. Drawn on a client-only copy of the mob whose parts are moved to where the pieces are.
 */
public final class Breakup {
	public static int life = 300;
	public static int buildStagger = 4;
	public static int buildFlight = 9;

	private static final int FALL = 0;
	private static final int BUILD = 1;
	private static final int DONE = 2;

	private static final class Part {
		final Body body;
		final Vector3d restPos = new Vector3d();
		final Quaternionf restRot = new Quaternionf();
		final Vector3d fromPos = new Vector3d();
		final Quaternionf fromRot = new Quaternionf();
		int order;
		boolean snapped;

		Part(Body body) {
			this.body = body;
		}
	}

	private static final class Break {
		final int seed;
		final int start;
		final double x, y, z;
		final float bodyRot;
		final Vec3 hitFrom;
		final net.minecraft.world.entity.EntityType<?> type;
		final Map<String, Part> parts = new LinkedHashMap<>();
		float scale = -1.0F;
		Quaternionf toWorld;
		int phase = FALL;
		int phaseStart;
		volatile @Nullable String nbt;
		/** Not a death but a birth: the parts lie around and click together into the new mob. */
		boolean assemble;
		/** Only to look at (the player's own figure): not built back. */
		boolean cosmetic;
		int realId;

		Break(int seed, int start, LivingEntity dead, Vec3 hitFrom) {
			this.seed = seed;
			this.start = start;
			this.x = dead.getX();
			this.y = dead.getY();
			this.z = dead.getZ();
			this.bodyRot = dead.yBodyRot;
			this.hitFrom = hitFrom;
			this.type = dead.getType();
		}
	}

	private static final Int2ObjectOpenHashMap<Break> BREAKS = new Int2ObjectOpenHashMap<>();
	private static int nextId = -4_000_000;
	private static int clicksThisTick;
	private static final java.util.Random KICK = new java.util.Random();

	private Breakup() {
	}

	/** A mob just appeared near the player in the brick world: it clicks itself together from loose parts. */
	public static void assemble(Minecraft mc, LivingEntity born, int ticks) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		Entity created = born.getType().create(mc.level, EntitySpawnReason.LOAD);
		if (!(created instanceof LivingEntity copy)) {
			return;
		}
		copy.setId(nextId--);
		List<SynchedEntityData.DataValue<?>> values = born.getEntityData().getNonDefaultValues();
		if (values != null) {
			copy.getEntityData().assignValues(values);
		}
		copy.setSilent(true);
		copy.snapTo(born.getX(), born.getY(), born.getZ(), born.getYRot(), born.getXRot());
		copy.yBodyRot = copy.yBodyRotO = born.yBodyRot;
		copy.yHeadRot = copy.yHeadRotO = born.yHeadRot;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			copy.setItemSlot(slot, born.getItemBySlot(slot).copy());
		}
		mc.level.addEntity(copy);
		Break br = new Break(born.getId() * 17 + ticks, ticks, born, mc.player.position());
		br.assemble = true;
		br.realId = born.getId();
		br.phase = BUILD;
		br.phaseStart = ticks + 1000;
		BREAKS.put(copy.getId(), br);
		ASSEMBLING.add(born.getId());
		BrickEntities.markTurned(copy.getId());
	}

	private static final it.unimi.dsi.fastutil.ints.IntOpenHashSet ASSEMBLING = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

	/** A mob that just came back from being built (it must not click together a second time). */
	public static boolean isRespawn(Entity entity) {
		for (Break br : BREAKS.values()) {
			if (!br.assemble && br.phase == DONE && br.type == entity.getType() && entity.distanceToSqr(br.x, br.y, br.z) < 1.5) {
				return true;
			}
		}
		return false;
	}

	/** The real mob is hidden while its copy clicks together. */
	public static boolean assembling(Entity entity) {
		return !ASSEMBLING.isEmpty() && ASSEMBLING.contains(entity.getId());
	}

	/** The player's own figure falls apart when they die in the brick world (the parts lie there for a while). */
	public static void startPlayer(Minecraft mc, int ticks) {
		var player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		net.minecraft.client.player.RemotePlayer copy = new net.minecraft.client.player.RemotePlayer(mc.level, player.getGameProfile());
		copy.setId(nextId--);
		List<SynchedEntityData.DataValue<?>> values = player.getEntityData().getNonDefaultValues();
		if (values != null) {
			copy.getEntityData().assignValues(values);
		}
		copy.setHealth(copy.getMaxHealth());
		copy.setPose(Pose.STANDING);
		copy.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		copy.yBodyRot = copy.yBodyRotO = player.yBodyRot;
		copy.yHeadRot = copy.yHeadRotO = player.yHeadRot;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			copy.setItemSlot(slot, player.getItemBySlot(slot).copy());
		}
		mc.level.addEntity(copy);
		Vec3 from = player.getLastDamageSource() != null && player.getLastDamageSource().getSourcePosition() != null
			? player.getLastDamageSource().getSourcePosition() : player.position().add(player.getLookAngle().scale(2.0));
		Break br = new Break(player.getId() * 13 + ticks, ticks, player, from);
		br.cosmetic = true;
		BREAKS.put(copy.getId(), br);
		BrickEntities.markTurned(copy.getId());
		Hops.playClick(mc, player.getX(), player.getY() + 1.0, player.getZ(), 1.0F, 0.7F);
		Hops.playClick(mc, player.getX(), player.getY() + 1.0, player.getZ(), 0.8F, 1.1F);
	}

	public static void start(Minecraft mc, LivingEntity dead, int ticks) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		Entity created = dead.getType().create(mc.level, EntitySpawnReason.LOAD);
		if (!(created instanceof LivingEntity copy)) {
			return;
		}
		copy.setId(nextId--);
		List<SynchedEntityData.DataValue<?>> values = dead.getEntityData().getNonDefaultValues();
		if (values != null) {
			copy.getEntityData().assignValues(values);
		}
		copy.setSilent(true);
		copy.setHealth(copy.getMaxHealth());
		copy.setPose(Pose.STANDING);
		copy.snapTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), dead.getXRot());
		copy.yBodyRot = copy.yBodyRotO = dead.yBodyRot;
		copy.yHeadRot = copy.yHeadRotO = dead.yHeadRot;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			copy.setItemSlot(slot, dead.getItemBySlot(slot).copy());
		}
		mc.level.addEntity(copy);
		Break br = new Break(dead.getId() * 31 + ticks, ticks, dead, mc.player.position());
		BREAKS.put(copy.getId(), br);
		BrickEntities.markTurned(copy.getId());
		rememberNbt(mc, dead, br);
		double cy = dead.getY() + dead.getBbHeight() * 0.5;
		Coins.burst(mc, dead.getX(), cy, dead.getZ(), 6 + (int) Math.min(10.0, dead.getMaxHealth() / 3.0));
		Hops.playClick(mc, dead.getX(), cy, dead.getZ(), 1.0F, 0.75F);
		Hops.playClick(mc, dead.getX(), cy, dead.getZ(), 0.8F, 1.15F);
	}

	/** In single player: keep what the mob was (NoAI, villager job, equipment ...) to bring the same one back. */
	private static void rememberNbt(Minecraft mc, LivingEntity dead, Break br) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null) {
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		int id = dead.getId();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dim);
			Entity entity = level == null ? null : level.getEntity(id);
			if (entity == null) {
				return;
			}
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			entity.saveWithoutId(out);
			CompoundTag tag = out.buildResult();
			for (String key : new String[]{"UUID", "Pos", "Motion", "Health", "DeathTime", "HurtTime", "HurtByTimestamp", "Fire", "FallDistance", "fall_distance", "Brain", "Passengers"}) {
				tag.remove(key);
			}
			br.nbt = tag.toString();
		});
	}

	public static boolean isCopy(Entity entity) {
		return BREAKS.containsKey(entity.getId());
	}

	/** The nearest heap of parts that can be built back, within reach of the player. */
	public static int nearestBuildable(Vec3 at, double reach) {
		int best = 0;
		double bestD = reach * reach;
		for (Int2ObjectMap.Entry<Break> e : BREAKS.int2ObjectEntrySet()) {
			Break br = e.getValue();
			if (br.phase != FALL || br.parts.isEmpty() || br.cosmetic) {
				continue;
			}
			for (Part p : br.parts.values()) {
				double d = at.distanceToSqr(p.body.pos.x, p.body.pos.y, p.body.pos.z);
				if (d < bestD) {
					bestD = d;
					best = e.getIntKey();
				}
			}
		}
		return best;
	}

	public static int buildAll(Vec3 origin, double radius, int ticks) {
		int n = 0;
		for (Int2ObjectMap.Entry<Break> e : BREAKS.int2ObjectEntrySet()) {
			Break br = e.getValue();
			if (br.phase == FALL && !br.parts.isEmpty() && !br.cosmetic && origin.distanceTo(new Vec3(br.x, br.y, br.z)) < radius) {
				build(e.getIntKey(), ticks);
				n++;
			}
		}
		return n;
	}

	public static boolean anyBuilding() {
		for (Break br : BREAKS.values()) {
			if (br.phase == BUILD) {
				return true;
			}
		}
		return false;
	}

	public static void build(int id, int ticks) {
		Break br = BREAKS.get(id);
		if (br == null || br.phase != FALL || br.parts.isEmpty()) {
			return;
		}
		br.phase = BUILD;
		br.phaseStart = ticks;
		List<Part> parts = new ArrayList<>(br.parts.values());
		parts.sort(Comparator.comparingDouble(p -> p.restPos.y));
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			p.order = i;
			p.fromPos.set(p.body.pos);
			p.fromRot.set(p.body.rot);
		}
	}

	public static void tick(Minecraft mc, int ticks) {
		clicksThisTick = 0;
		if (BREAKS.isEmpty() || mc.level == null) {
			return;
		}
		var it = BREAKS.int2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			Int2ObjectMap.Entry<Break> e = it.next();
			Break br = e.getValue();
			int age = ticks - br.start;
			boolean remove = false;
			if (br.phase == FALL) {
				for (Part p : br.parts.values()) {
					if (mc.player != null && p.body.kick(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z, KICK)) {
						click(mc, p.body.pos, 0.45F, 1.0F + KICK.nextFloat() * 0.4F);
					}
					float impact = p.body.step(mc.level, null);
					if (impact > 0.14F) {
						click(mc, p.body.pos, Math.min(0.9F, 0.35F + impact * 1.5F), 0.8F + (p.order % 4) * 0.12F);
					}
				}
				remove = age > life + 10;
			} else if (br.phase == BUILD) {
				if (br.assemble && br.parts.isEmpty()) {
					if (age > 40) {
						ASSEMBLING.remove(br.realId);
						mc.level.removeEntity(e.getIntKey(), Entity.RemovalReason.DISCARDED);
						it.remove();
					}
					continue;
				}
				int t = ticks - br.phaseStart;
				boolean all = true;
				for (Part p : br.parts.values()) {
					p.body.prevPos.set(p.body.pos);
					p.body.prevRot.set(p.body.rot);
					float f = (t - p.order * buildStagger) / (float) buildFlight;
					if (f <= 0.0F) {
						all = false;
						continue;
					}
					if (f >= 1.0F) {
						p.body.pos.set(p.restPos);
						p.body.rot.set(p.restRot);
						if (!p.snapped) {
							p.snapped = true;
							Hops.playClick(mc, p.restPos.x, p.restPos.y, p.restPos.z, 0.9F, 0.85F + p.order * 0.14F);
						}
						continue;
					}
					all = false;
					float k = ease(f);
					p.fromPos.lerp(p.restPos, k, p.body.pos);
					p.body.pos.y += Mth.sin(f * Mth.PI) * 0.9;
					p.fromRot.slerp(p.restRot, k, p.body.rot);
				}
				if (all) {
					br.phase = DONE;
					br.phaseStart = ticks;
					if (br.assemble) {
						ASSEMBLING.remove(br.realId);
					} else {
						Coins.burst(mc, br.x, br.y + 1.2, br.z, 3);
						respawn(mc, br);
					}
				}
			} else {
				int t = ticks - br.phaseStart;
				remove = br.assemble ? t > 1 : t > 40 || (t > 2 && realOneBack(mc, br, e.getIntKey()));
			}
			if (remove) {
				mc.level.removeEntity(e.getIntKey(), Entity.RemovalReason.DISCARDED);
				it.remove();
			}
		}
	}

	private static boolean realOneBack(Minecraft mc, Break br, int copyId) {
		for (Entity entity : mc.level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(br.x - 0.6, br.y - 0.5, br.z - 0.6, br.x + 0.6, br.y + 2.0, br.z + 0.6))) {
			if (entity.getId() != copyId && entity.getType() == br.type && !isCopy(entity)) {
				return true;
			}
		}
		return false;
	}

	/** Single player: the built figure comes back to life as the same mob. */
	private static void respawn(Minecraft mc, Break br) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(br.type).toString();
		String nbt = br.nbt == null ? "" : " " + br.nbt;
		String cmd = String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f%s", type, br.x, br.y, br.z, nbt);
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	private static void click(Minecraft mc, Vector3d at, float volume, float pitch) {
		if (clicksThisTick >= 6) {
			return;
		}
		clicksThisTick++;
		Hops.playClick(mc, at.x, at.y, at.z, volume, pitch);
	}

	private static float ease(float t) {
		return t < 0.5F ? 2.0F * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 2.0F) / 2.0F;
	}

	public static void clear(Minecraft mc) {
		ASSEMBLING.clear();
		if (mc.level != null) {
			for (int id : BREAKS.keySet()) {
				mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
			}
		}
		BREAKS.clear();
	}

	/** Copies the break of this entity (if it is a breaking copy) into its render state. */
	public static void fill(Entity entity, float partialTicks, BrickRenderState state, int ticks) {
		Break br = BREAKS.get(entity.getId());
		if (br == null) {
			state.bk$setBreak(0, 0, -1.0F);
		} else {
			state.bk$setBreak(entity.getId(), br.seed, ticks - br.start + partialTicks);
			if (state instanceof net.minecraft.client.renderer.entity.state.EntityRenderState s) {
				s.shadowRadius = 0.0F;
			}
		}
	}

	// model space (pixels, y down, after the renderer's flip) <-> world space around the copy
	private static Vector3d toWorld(Break br, Vector3f m) {
		Vector3f u = new Vector3f(m.x / 16.0F, m.y / 16.0F - 1.501F, m.z / 16.0F);
		br.toWorld.transform(u).mul(br.scale);
		return new Vector3d(br.x + u.x, br.y + u.y, br.z + u.z);
	}

	private static Vector3f toModel(Break br, Vector3d w) {
		Vector3f u = new Vector3f((float) (w.x - br.x), (float) (w.y - br.y), (float) (w.z - br.z)).div(br.scale);
		new Quaternionf(br.toWorld).conjugate().transform(u);
		return new Vector3f(u.x * 16.0F, (u.y + 1.501F) * 16.0F, u.z * 16.0F);
	}

	private static float rnd(int seed, int hash, int k) {
		int h = seed * 0x9E3779B1 ^ hash * 0x85EBCA77 ^ k * 0xC2B2AE3D;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		h ^= h >>> 15;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	/** First frame of the break: the parts become pieces where they are, and get thrown. */
	private static void init(Break br, ModelInfo info, float scale) {
		br.scale = scale;
		br.toWorld = new Quaternionf().rotationY((180.0F - br.bodyRot) * Mth.DEG_TO_RAD).mul(new Quaternionf().rotationZ(Mth.PI));
		Vec3 away = new Vec3(br.x - br.hitFrom.x, 0.0, br.z - br.hitFrom.z);
		away = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
		int order = 0;
		for (ModelInfo.Piece piece : info.pieces) {
			ModelPart part = piece.part;
			Vector3f c = piece.center();
			Quaternionf qm = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
			Vector3f cm = qm.transform(new Vector3f(c)).add(part.x, part.y, part.z);
			Vector3f half = new Vector3f(piece.maxX - piece.minX, piece.maxY - piece.minY, piece.maxZ - piece.minZ).mul(scale / 32.0F);
			half.max(new Vector3f(0.03F));
			Body body = new Body(half);
			Vector3d cw = toWorld(br, cm);
			Quaternionf qw = new Quaternionf(br.toWorld).mul(qm);
			body.place(cw.x, cw.y, cw.z, qw);
			Part p = new Part(body);
			p.restPos.set(cw);
			p.restRot.set(qw);
			p.order = order++;
			boolean head = piece.name.contains("head");
			boolean torso = piece.name.contains("body") || piece.name.contains("torso");
			Vec3 out = new Vec3(cw.x - br.x, 0.0, cw.z - br.z);
			out = out.lengthSqr() < 1.0E-4 ? Vec3.ZERO : out.normalize();
			float a = (rnd(br.seed, piece.hash, 1) - 0.5F) * 1.4F;
			Vec3 dir = away.scale(0.75).add(out.scale(0.6)).yRot(a);
			dir = dir.lengthSqr() < 1.0E-4 ? away : dir.normalize();
			float speed = torso ? 0.05F + 0.04F * rnd(br.seed, piece.hash, 2) : 0.09F + 0.09F * rnd(br.seed, piece.hash, 2);
			float up = torso ? 0.2F + 0.08F * rnd(br.seed, piece.hash, 3) : (head ? 0.42F : 0.32F) + 0.12F * rnd(br.seed, piece.hash, 3);
			body.vel.set(dir.x * speed, up, dir.z * speed);
			float sa = rnd(br.seed, piece.hash, 4) * Mth.TWO_PI;
			float spin = 0.18F + 0.35F * rnd(br.seed, piece.hash, 5);
			body.spin.set(Mth.cos(sa) * spin, (rnd(br.seed, piece.hash, 6) - 0.5F) * 0.3F, Mth.sin(sa) * spin);
			br.parts.put(piece.name, p);
		}
		if (br.assemble) {
			Minecraft mc = Minecraft.getInstance();
			int i = 0;
			List<Part> sorted = new ArrayList<>(br.parts.values());
			sorted.sort(Comparator.comparingDouble(q -> q.restPos.y));
			for (Part p : sorted) {
				float a = rnd(br.seed, i * 31 + 7, 1) * Mth.TWO_PI;
				double r = 0.8 + 1.2 * rnd(br.seed, i * 31 + 7, 2);
				double x = br.x + Mth.cos(a) * r;
				double z = br.z + Mth.sin(a) * r;
				Quaternionf lie = new Quaternionf().rotationY(rnd(br.seed, i * 31 + 7, 3) * Mth.TWO_PI).rotateX(Mth.HALF_PI);
				p.body.place(x, br.y, z, lie);
				double ground = mc.level == null ? br.y : Body.groundBelow(mc.level, null, x, br.y + 1.0, z);
				p.body.place(x, (ground < -1.0E8 ? br.y : ground) + p.body.reachBelow(), z, lie);
				p.fromPos.set(p.body.pos);
				p.fromRot.set(p.body.rot);
				p.order = i++;
			}
			br.phaseStart = BrickEntities.ticks() + 6;
		}
	}

	/** Called right after a model was posed for this frame: puts its parts where the pieces are. */
	public static void apply(Model<?> model, Object renderState) {
		if (!(renderState instanceof BrickRenderState state) || state.bk$breakId() == 0) {
			return;
		}
		Break br = BREAKS.get(state.bk$breakId());
		if (br == null) {
			return;
		}
		ModelInfo info = ModelInfo.of(model);
		if (br.parts.isEmpty()) {
			if (!(renderState instanceof LivingEntityRenderState living) || info.pieces.isEmpty()) {
				return;
			}
			init(br, info, living.scale);
		}
		float age = state.bk$breakAge();
		float pt = age - Mth.floor(age);
		float scale = 1.0F;
		if (br.phase == FALL && age > life) {
			float k = Math.min(1.0F, (age - life) / 9.0F);
			scale = Math.max(0.001F, 1.0F - k * k);
		}
		Vector3d pos = new Vector3d();
		Quaternionf rot = new Quaternionf();
		Quaternionf toModel = new Quaternionf(br.toWorld).conjugate();
		Vector3f euler = new Vector3f();
		for (ModelInfo.Piece piece : info.pieces) {
			Part p = br.parts.get(piece.name);
			if (p == null) {
				continue;
			}
			p.body.lerpPos(pt, pos);
			p.body.lerpRot(pt, rot);
			Quaternionf qm = new Quaternionf(toModel).mul(rot);
			Vector3f cm = toModel(br, pos);
			Vector3f off = qm.transform(piece.center().mul(scale));
			ModelPart part = piece.part;
			qm.getEulerAnglesZYX(euler);
			part.x = cm.x - off.x;
			part.y = cm.y - off.y;
			part.z = cm.z - off.z;
			part.xRot = euler.x;
			part.yRot = euler.y;
			part.zRot = euler.z;
			part.xScale = scale;
			part.yScale = scale;
			part.zScale = scale;
		}
	}
}
