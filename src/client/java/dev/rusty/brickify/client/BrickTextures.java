package dev.rusty.brickify.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.rusty.brickify.Brickify;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entities wear the brick pack's textures once the world has turned. Player skins have no brick version, so a
 * plastic one is made on the fly: every face of every body part becomes one flat colour, only the face keeps its
 * print (eyes, mouth), like a painted brick figure.
 */
public final class BrickTextures {
	private static final Map<Identifier, Identifier> SWAP = new ConcurrentHashMap<>();
	private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
	private static final Map<String, Identifier> PLASTIC = new ConcurrentHashMap<>();
	private static final Map<Identifier, Integer> TOP_COLOR = new ConcurrentHashMap<>();
	private static final Map<Identifier, Integer> HAT_COLOR = new ConcurrentHashMap<>();
	/** Colour of a hat drawn on the figure being submitted right now (-1: none so far). */
	private static int lastHat = -1;
	/** The model the texture asked for right now is drawn on (set around render type lookups). */
	public static final ThreadLocal<Model<?>> MODEL = new ThreadLocal<>();

	private BrickTextures() {
	}

	private static boolean current;

	/** Set while a brick entity is being submitted (render thread). */
	public static void setCurrent(boolean brick) {
		current = brick;
	}

	public static Identifier forEntity(Identifier location) {
		if (!current) {
			return location;
		}
		Model<?> model = MODEL.get();
		if (model == null) {
			return swap(location);
		}
		Identifier plastic = plasticFor(location, model);
		Integer hat = HAT_COLOR.get(plastic);
		if (hat != null) {
			lastHat = hat;
		}
		return plastic;
	}

	/** Forget the hat of the previous figure (called when a new one starts). */
	public static void newFigure() {
		lastHat = -1;
	}

	public static int lastHat() {
		return lastHat;
	}

	/**
	 * The texture made plastic for one model: every face of every cube becomes one flat colour (its dominant
	 * one), only the front of the head keeps its print, cleaned up.
	 */
	public static Identifier plasticFor(Identifier location, Model<?> model) {
		if (BUSY.get() || !location.getNamespace().equals("minecraft") || !location.getPath().startsWith("textures/entity/")) {
			return swap(location);
		}
		String key = location + "@" + Integer.toHexString(System.identityHashCode(model));
		Identifier cached = PLASTIC.get(key);
		if (cached != null) {
			return cached;
		}
		Identifier result = brickVersion(location);
		BUSY.set(true);
		try {
			ModelInfo info = ModelInfo.of(model);
			if (!info.faces.isEmpty()) {
				result = plasticModel(location, info, key);
			}
		} catch (Exception e) {
			Brickify.LOGGER.warn("Plastic texture for {} failed: {}", location, e.toString());
		} finally {
			BUSY.set(false);
		}
		PLASTIC.put(key, result);
		return result;
	}

	/** Colour (RGB) of the top of the head in a plastic texture (for the studs), or -1 if unknown. */
	public static int topColor(Identifier plastic) {
		return TOP_COLOR.getOrDefault(plastic, -1);
	}

	private static @org.jspecify.annotations.Nullable NativeImage read(Identifier location) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		Optional<Resource> res = mc.getResourceManager().getResource(location);
		if (res.isPresent()) {
			try (InputStream in = res.get().open()) {
				return NativeImage.read(in);
			}
		}
		AbstractTexture tex = mc.getTextureManager().getTexture(location);
		if (tex instanceof DynamicTexture dyn && dyn.getPixels() != null) {
			NativeImage px = dyn.getPixels();
			NativeImage copy = new NativeImage(px.getWidth(), px.getHeight(), true);
			copy.copyFrom(px);
			return copy;
		}
		return null;
	}

	private static Identifier plasticModel(Identifier location, ModelInfo info, String key) throws Exception {
		Identifier source = brickVersion(location);
		NativeImage src = read(source);
		if (src == null) {
			return source;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		NativeImage out = new NativeImage(w, h, true);
		out.copyFrom(src);
		int top = -1;
		int hat = -1;
		boolean hatOpaque = false;
		for (ModelInfo.Face face : info.faces) {
			int x0 = Math.max(0, Math.round(face.u0() * w));
			int y0 = Math.max(0, Math.round(face.v0() * h));
			int x1 = Math.min(w, Math.round(face.u1() * w));
			int y1 = Math.min(h, Math.round(face.v1() * h));
			if (x1 <= x0 || y1 <= y0) {
				continue;
			}
			if (face.hatTop() && opaqueShare(src, x0, y0, x1 - x0, y1 - y0) > 0.6F) {
				hatOpaque = true;
			}
			int colour = flatten(src, out, x0, y0, x1 - x0, y1 - y0, face.print(), false);
			if (face.headTop() && colour != -1) {
				top = colour;
			}
			if (face.hatTop() && hatOpaque && colour != -1) {
				hat = colour;
			}
		}
		src.close();
		String name = location.getPath().replace('.', '_') + "_" + Integer.toHexString(key.hashCode());
		Identifier id = Brickify.id("plastic/" + name);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, out));
		if (top != -1) {
			TOP_COLOR.put(id, top);
		}
		if (hat != -1) {
			HAT_COLOR.put(id, hat);
		}
		return id;
	}

	private static float opaqueShare(NativeImage src, int x0, int y0, int w, int h) {
		int opaque = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				if ((src.getPixel(x, y) >>> 24) >= 128) {
					opaque++;
				}
			}
		}
		return opaque / (float) Math.max(1, w * h);
	}

	public static Identifier swap(Identifier location) {
		if (BUSY.get() || !location.getNamespace().equals("minecraft")) {
			return location;
		}
		String path = location.getPath();
		boolean skin = path.startsWith("textures/entity/player/") || path.startsWith("skins/");
		if (!skin && !path.startsWith("textures/entity/")) {
			return location;
		}
		Identifier cached = SWAP.get(location);
		if (cached != null) {
			return cached;
		}
		Identifier result = location;
		BUSY.set(true);
		try {
			result = skin ? plasticSkin(location) : brickVersion(location);
		} catch (Exception e) {
			Brickify.LOGGER.warn("Brick texture for {} failed: {}", location, e.toString());
		} finally {
			BUSY.set(false);
		}
		SWAP.put(location, result);
		return result;
	}

	public static Identifier brickVersion(Identifier location) {
		Identifier brick = Identifier.fromNamespaceAndPath("brickify", location.getPath());
		return Minecraft.getInstance().getResourceManager().getResource(brick).isPresent() ? brick : location;
	}

	private static Identifier plasticSkin(Identifier location) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		NativeImage src = null;
		Optional<Resource> res = mc.getResourceManager().getResource(location);
		if (res.isPresent()) {
			try (InputStream in = res.get().open()) {
				src = NativeImage.read(in);
			}
		} else {
			AbstractTexture tex = mc.getTextureManager().getTexture(location);
			if (tex instanceof DynamicTexture dyn && dyn.getPixels() != null) {
				NativeImage px = dyn.getPixels();
				src = new NativeImage(px.getWidth(), px.getHeight(), true);
				src.copyFrom(px);
			}
		}
		if (src == null || src.getWidth() != 64 || src.getHeight() != 64) {
			if (src != null) {
				src.close();
			}
			return location;
		}
		NativeImage out = plastic(src);
		src.close();
		Identifier id = Brickify.id("plastic/" + location.getPath().replace('.', '_'));
		mc.getTextureManager().register(id, new DynamicTexture(id::toString, out));
		TOP_COLOR.put(id, out.getPixel(12, 4) & 0xFFFFFF);
		return id;
	}

	// UV boxes of the skin parts: u, v, width, height, depth (64x64 layout, wide arms).
	private static final int[][] PARTS = {
		{0, 0, 8, 8, 8}, {32, 0, 8, 8, 8},          // head, hat
		{16, 16, 8, 12, 4}, {16, 32, 8, 12, 4},      // body, jacket
		{40, 16, 4, 12, 4}, {40, 32, 4, 12, 4},      // right arm, sleeve
		{32, 48, 4, 12, 4}, {48, 48, 4, 12, 4},      // left arm, sleeve
		{0, 16, 4, 12, 4}, {0, 32, 4, 12, 4},        // right leg, pants
		{16, 48, 4, 12, 4}, {0, 48, 4, 12, 4}        // left leg, pants
	};

	private static NativeImage plastic(NativeImage src) {
		NativeImage out = new NativeImage(64, 64, true);
		out.copyFrom(src);
		for (int p = 0; p < PARTS.length; p++) {
			int[] b = PARTS[p];
			int u = b[0];
			int v = b[1];
			int w = b[2];
			int h = b[3];
			int d = b[4];
			int[][] faces = {
				{u + d, v, w, d}, {u + d + w, v, w, d},                 // top, bottom
				{u, v + d, d, h}, {u + d, v + d, w, h},                 // right, front
				{u + d + w, v + d, d, h}, {u + d + w + d, v + d, w, h}  // left, back
			};
			boolean overlay = p % 2 == 1;
			for (int f = 0; f < faces.length; f++) {
				boolean face = (p == 0 || p == 1) && f == 3;
				flatten(src, out, faces[f][0], faces[f][1], faces[f][2], faces[f][3], face, overlay);
			}
		}
		return out;
	}

	/** One colour for the whole face; a printed face keeps the pixels that differ clearly from it. */
	private static int flatten(NativeImage src, NativeImage out, int x0, int y0, int w, int h, boolean print, boolean overlay) {
		Map<Integer, int[]> bins = new HashMap<>();
		int opaque = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int c = src.getPixel(x, y);
				if ((c >>> 24) < 128) {
					continue;
				}
				opaque++;
				int key = ((c >> 20) & 0xF) << 8 | ((c >> 12) & 0xF) << 4 | ((c >> 4) & 0xF);
				int[] acc = bins.computeIfAbsent(key, k -> new int[4]);
				acc[0] += (c >> 16) & 0xFF;
				acc[1] += (c >> 8) & 0xFF;
				acc[2] += c & 0xFF;
				acc[3]++;
			}
		}
		if (opaque == 0) {
			return -1;
		}
		int[] best = null;
		for (int[] acc : bins.values()) {
			if (best == null || acc[3] > best[3]) {
				best = acc;
			}
		}
		int r = best[0] / best[3];
		int g = best[1] / best[3];
		int bl = best[2] / best[3];
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int c = src.getPixel(x, y);
				int a = c >>> 24;
				if (a < 128) {
					continue;
				}
				int cr = (c >> 16) & 0xFF;
				int cg = (c >> 8) & 0xFF;
				int cb = c & 0xFF;
				int diff = Math.abs(cr - r) + Math.abs(cg - g) + Math.abs(cb - bl);
				int nr = r;
				int ng = g;
				int nb = bl;
				if (print && diff > 90) {
					// keep the print, but in clean plastic colours
					nr = (cr / 48) * 48 + 24;
					ng = (cg / 48) * 48 + 24;
					nb = (cb / 48) * 48 + 24;
				}
				out.setPixel(x, y, 0xFF000000 | Math.min(255, nr) << 16 | Math.min(255, ng) << 8 | Math.min(255, nb));
			}
		}
		return r << 16 | g << 8 | bl;
	}

	public static void clear() {
		SWAP.clear();
		PLASTIC.clear();
		TOP_COLOR.clear();
		HAT_COLOR.clear();
	}
}
