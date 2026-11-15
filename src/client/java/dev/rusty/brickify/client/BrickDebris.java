package dev.rusty.brickify.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loose bricks: small brick blocks (coloured like the mob they came from) that fly, spin, bounce with a click and
 * lie around for a while. Drawn as client-only block displays, so they get the brick model with its studs.
 */
public final class BrickDebris {
	public static int life = 110;
	public static int maxPieces = 48;

	private static final List<Piece> PIECES = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static final Map<Identifier, Block[]> PALETTES = new HashMap<>();
	private static int nextId = -3_000_000;
	private static int clicksThisTick;

	private static final Block[] CONCRETE = {
		concrete(DyeColor.WHITE), concrete(DyeColor.ORANGE), concrete(DyeColor.MAGENTA), concrete(DyeColor.LIGHT_BLUE), concrete(DyeColor.YELLOW), concrete(DyeColor.LIME), concrete(DyeColor.PINK), concrete(DyeColor.GRAY), concrete(DyeColor.LIGHT_GRAY), concrete(DyeColor.CYAN), concrete(DyeColor.PURPLE), concrete(DyeColor.BLUE), concrete(DyeColor.BROWN), concrete(DyeColor.GREEN), concrete(DyeColor.RED), concrete(DyeColor.BLACK)
	};

	private static Block concrete(DyeColor color) {
		return Blocks.CONCRETE.pick(color);
	}

	private static final int[] CONCRETE_RGB = {
		0xCFD5D6, 0xE06101, 0xA9309F, 0x2489C7, 0xF1AF15, 0x5EA918, 0xD6658F, 0x373A3E,
		0x7D7D73, 0x157788, 0x64209C, 0x2D2F8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F
	};

	private static final class Piece {
		Display.BlockDisplay entity;
		double x;
		double y;
		double z;
		double vx;
		double vy;
		double vz;
		Vector3f axis;
		float angle;
		float spin;
		float size;
		int age;
		int hold;
		boolean clicked;
	}

	private BrickDebris() {
	}

	public static int live() {
		return PIECES.size();
	}

	/** The mob falls apart: bricks fill its shape, then tumble down into a heap. */
	public static void shatter(Minecraft mc, LivingEntity entity) {
		Block[] palette = palette(mc, entity);
		AABB box = entity.getBoundingBox();
		double size = 0.32;
		int nx = Mth.clamp(Mth.ceil(box.getXsize() / size), 1, 5);
		int ny = Mth.clamp(Mth.ceil(box.getYsize() / size), 1, 9);
		int nz = Mth.clamp(Mth.ceil(box.getZsize() / size), 1, 5);
		int total = nx * ny * nz;
		double keep = Math.min(1.0, maxPieces / (double) total);
		Vec3 c = box.getCenter();
		Vec3 away = entity.position().subtract(mc.player.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
		for (int ix = 0; ix < nx; ix++) {
			for (int iy = 0; iy < ny; iy++) {
				for (int iz = 0; iz < nz; iz++) {
					if (RANDOM.nextDouble() > keep) {
						continue;
					}
					double x = box.minX + (ix + 0.5) * box.getXsize() / nx;
					double y = box.minY + (iy + 0.5) * box.getYsize() / ny;
					double z = box.minZ + (iz + 0.5) * box.getZsize() / nz;
					double up = (y - box.minY) / Math.max(0.1, box.getYsize());
					Piece p = spawn(mc.level, palette[RANDOM.nextInt(palette.length)], x, y, z,
						(x - c.x) * 0.16 + away.x * 0.07 + (RANDOM.nextDouble() - 0.5) * 0.06,
						0.04 + up * 0.14 + RANDOM.nextDouble() * 0.12,
						(z - c.z) * 0.16 + away.z * 0.07 + (RANDOM.nextDouble() - 0.5) * 0.06,
						0.3F + RANDOM.nextFloat() * 0.1F);
					// a moment in the mob's shape before it gives way
					p.hold = 1 + (int) ((1.0 - up) * 2.0);
				}
			}
		}
		for (int i = 0; i < 5; i++) {
			Hops.playClick(mc, c.x + (RANDOM.nextDouble() - 0.5) * box.getXsize(), c.y + (RANDOM.nextDouble() - 0.5) * box.getYsize(), c.z, 0.7F, 0.8F + RANDOM.nextFloat() * 0.5F);
		}
	}

	/** A hit knocks a few bricks off, flying away from the player. */
	public static void chip(Minecraft mc, LivingEntity entity) {
		Block[] palette = palette(mc, entity);
		AABB box = entity.getBoundingBox();
		Vec3 away = entity.position().subtract(mc.player.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : away.normalize();
		int n = 3 + RANDOM.nextInt(3);
		for (int i = 0; i < n; i++) {
			double y = box.minY + box.getYsize() * (0.45 + RANDOM.nextDouble() * 0.45);
			spawn(mc.level, palette[RANDOM.nextInt(palette.length)], entity.getX() - away.x * 0.3, y, entity.getZ() - away.z * 0.3,
				away.x * (0.12 + RANDOM.nextDouble() * 0.1) + (RANDOM.nextDouble() - 0.5) * 0.12,
				0.15 + RANDOM.nextDouble() * 0.15,
				away.z * (0.12 + RANDOM.nextDouble() * 0.1) + (RANDOM.nextDouble() - 0.5) * 0.12,
				0.22F + RANDOM.nextFloat() * 0.08F);
		}
		Hops.playClick(mc, entity.getX(), box.minY + box.getYsize() * 0.7, entity.getZ(), 0.8F, 1.0F + RANDOM.nextFloat() * 0.3F);
	}

	/** When the wave turns a mob: a click and two or three bricks hopping off like confetti. */
	public static void puff(Minecraft mc, LivingEntity entity) {
		Block[] palette = palette(mc, entity);
		AABB box = entity.getBoundingBox();
		for (int i = 0; i < 3; i++) {
			float a = RANDOM.nextFloat() * Mth.TWO_PI;
			spawn(mc.level, palette[RANDOM.nextInt(palette.length)], entity.getX(), box.maxY - 0.1, entity.getZ(),
				Mth.cos(a) * 0.08, 0.22 + RANDOM.nextDouble() * 0.1, Mth.sin(a) * 0.08, 0.18F + RANDOM.nextFloat() * 0.06F);
		}
		Hops.playClick(mc, entity.getX(), box.maxY, entity.getZ(), 0.75F, 1.1F + RANDOM.nextFloat() * 0.2F);
	}

	private static Piece spawn(ClientLevel level, Block block, double x, double y, double z, double vx, double vy, double vz, float size) {
		Piece p = new Piece();
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setId(nextId--);
		d.setPos(x, y, z);
		d.setBlockState(block.defaultBlockState());
		p.entity = d;
		p.x = x;
		p.y = y;
		p.z = z;
		p.vx = vx;
		p.vy = vy;
		p.vz = vz;
		float a = RANDOM.nextFloat() * Mth.TWO_PI;
		p.axis = new Vector3f(Mth.cos(a), RANDOM.nextFloat() - 0.5F, Mth.sin(a)).normalize();
		p.angle = RANDOM.nextFloat() * Mth.TWO_PI;
		p.spin = (RANDOM.nextFloat() - 0.5F) * 0.9F;
		p.size = size;
		light(level, p);
		pose(p, 1.0F);
		level.addEntity(d);
		PIECES.add(p);
		return p;
	}

	public static void tick(Minecraft mc) {
		clicksThisTick = 0;
		if (PIECES.isEmpty() || mc.level == null) {
			return;
		}
		ClientLevel level = mc.level;
		for (int i = PIECES.size() - 1; i >= 0; i--) {
			Piece p = PIECES.get(i);
			p.age++;
			if (p.age > life || p.entity.isRemoved()) {
				level.removeEntity(p.entity.getId(), Entity.RemovalReason.DISCARDED);
				PIECES.remove(i);
				continue;
			}
			if (p.hold > 0) {
				p.hold--;
				continue;
			}
			step(level, mc, p);
			if (p.age % 5 == 0) {
				light(level, p);
			}
			float shrink = p.age > life - 12 ? (life - p.age) / 12.0F : 1.0F;
			p.entity.setPos(p.x, p.y, p.z);
			pose(p, shrink);
		}
	}

	private static void step(ClientLevel level, Minecraft mc, Piece p) {
		p.vy -= 0.045;
		p.vx *= 0.985;
		p.vz *= 0.985;
		double half = p.size * 0.5;
		double nx = p.x + p.vx;
		double nz = p.z + p.vz;
		if (solidAt(level, nx, p.y, p.z)) {
			p.vx *= -0.3;
			nx = p.x;
		}
		if (solidAt(level, p.x, p.y, nz)) {
			p.vz *= -0.3;
			nz = p.z;
		}
		double ny = p.y + p.vy;
		BlockPos below = BlockPos.containing(nx, ny - half, nz);
		VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
		if (p.vy < 0 && !shape.isEmpty()) {
			double top = below.getY() + shape.max(Direction.Axis.Y);
			if (ny - half < top) {
				ny = top + half;
				if (p.vy < -0.09) {
					if (!p.clicked || p.vy < -0.2) {
						clickAt(mc, nx, ny, nz, (float) Math.min(0.8, -p.vy * 2.2));
						p.clicked = true;
					}
					p.vy = -p.vy * 0.33;
				} else {
					p.vy = 0.0;
				}
				p.vx *= 0.55;
				p.vz *= 0.55;
				p.spin *= 0.5F;
				if (Math.abs(p.vy) < 0.02) {
					// settle flat: snap the turn towards the nearest quarter so bricks come to rest lying down
					p.spin = 0.0F;
				}
			}
		}
		p.x = nx;
		p.y = ny;
		p.z = nz;
		p.angle += p.spin;
	}

	private static boolean solidAt(ClientLevel level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		return level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
	}

	private static void clickAt(Minecraft mc, double x, double y, double z, float volume) {
		if (clicksThisTick >= 6 || volume < 0.15F) {
			return;
		}
		clicksThisTick++;
		Hops.playClick(mc, x, y, z, volume, 0.9F + RANDOM.nextFloat() * 0.5F);
	}

	private static void light(ClientLevel level, Piece p) {
		BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
		p.entity.setBrightnessOverride(new Brightness(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos)));
	}

	private static void pose(Piece p, float shrink) {
		Quaternionf rot = new Quaternionf().rotateAxis(p.angle, p.axis);
		float s = p.size * shrink;
		Vector3f scale = new Vector3f(s, s, s);
		// turn about the brick's centre, which sits at the display's position
		Vector3f centre = new Vector3f(0.5F).mul(scale).rotate(rot);
		Display.BlockDisplay d = p.entity;
		d.setTransformation(new Transformation(new Vector3f(centre).negate(), rot, scale, null));
		d.setTransformationInterpolationDuration(p.age == 0 ? 0 : 1);
		d.setTransformationInterpolationDelay(0);
	}

	/** The brick colours a mob is made of: the main colours of its texture, matched to the coloured bricks. */
	private static Block[] palette(Minecraft mc, LivingEntity entity) {
		Identifier texture = textureOf(mc, entity);
		if (texture == null) {
			return new Block[]{concrete(DyeColor.LIGHT_GRAY)};
		}
		return PALETTES.computeIfAbsent(texture, id -> makePalette(mc, id));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Identifier textureOf(Minecraft mc, LivingEntity entity) {
		try {
			EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
			if (renderer instanceof LivingEntityRenderer living) {
				LivingEntityRenderState state = (LivingEntityRenderState) living.createRenderState(entity, 1.0F);
				return living.getTextureLocation(state);
			}
		} catch (RuntimeException ignored) {
		}
		return null;
	}

	private static Block[] makePalette(Minecraft mc, Identifier texture) {
		NativeImage img = null;
		try {
			Identifier brick = BrickTextures.brickVersion(texture);
			Optional<Resource> res = mc.getResourceManager().getResource(brick);
			if (res.isEmpty()) {
				res = mc.getResourceManager().getResource(texture);
			}
			if (res.isPresent()) {
				try (InputStream in = res.get().open()) {
					img = NativeImage.read(in);
				}
			} else {
				AbstractTexture tex = mc.getTextureManager().getTexture(BrickTextures.swap(texture));
				if (tex instanceof DynamicTexture dyn && dyn.getPixels() != null) {
					img = new NativeImage(dyn.getPixels().getWidth(), dyn.getPixels().getHeight(), true);
					img.copyFrom(dyn.getPixels());
				}
			}
			if (img == null) {
				return new Block[]{concrete(DyeColor.LIGHT_GRAY)};
			}
			int[] counts = new int[CONCRETE.length];
			for (int y = 0; y < img.getHeight(); y++) {
				for (int x = 0; x < img.getWidth(); x++) {
					int c = img.getPixel(x, y);
					if ((c >>> 24) < 128) {
						continue;
					}
					counts[nearest(c)]++;
				}
			}
			List<Block> out = new ArrayList<>();
			int total = 0;
			for (int n : counts) {
				total += n;
			}
			// every colour that covers a fair part of the mob, weighted by how much of it there is
			for (int i = 0; i < counts.length; i++) {
				int copies = total == 0 ? 0 : Math.round(counts[i] * 10.0F / total);
				for (int k = 0; k < copies; k++) {
					out.add(CONCRETE[i]);
				}
			}
			if (out.isEmpty()) {
				out.add(concrete(DyeColor.LIGHT_GRAY));
			}
			return out.toArray(new Block[0]);
		} catch (Exception e) {
			return new Block[]{concrete(DyeColor.LIGHT_GRAY)};
		} finally {
			if (img != null) {
				img.close();
			}
		}
	}

	private static int nearest(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int best = 0;
		int bestD = Integer.MAX_VALUE;
		for (int i = 0; i < CONCRETE_RGB.length; i++) {
			int c = CONCRETE_RGB[i];
			int dr = r - ((c >> 16) & 0xFF);
			int dg = g - ((c >> 8) & 0xFF);
			int db = b - (c & 0xFF);
			int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	public static void clear(Minecraft mc) {
		if (mc.level != null) {
			for (Piece p : PIECES) {
				mc.level.removeEntity(p.entity.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		PIECES.clear();
	}

	public static void forget() {
		PIECES.clear();
		PALETTES.clear();
	}
}
