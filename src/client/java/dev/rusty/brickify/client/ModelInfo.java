package dev.rusty.brickify.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rusty.brickify.Brickify;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What the brick look needs to know about an entity model, read once from its cubes: the body parts that come
 * apart (head, body, arms, legs ...), where the top of the head is, and the texture rectangle of every face.
 */
public final class ModelInfo {
	private static final Map<Model<?>, ModelInfo> CACHE = new WeakHashMap<>();
	private static final ModelInfo EMPTY = new ModelInfo();

	/** One body part that flies off on its own, with its box in its own coordinates (pixels). */
	public static final class Piece {
		public final ModelPart part;
		public final String name;
		public final int hash;
		public final float minX, minY, minZ, maxX, maxY, maxZ;

		Piece(ModelPart part, String name, float[] box) {
			this.part = part;
			this.name = name;
			this.hash = name.hashCode();
			this.minX = box[0];
			this.minY = box[1];
			this.minZ = box[2];
			this.maxX = box[3];
			this.maxY = box[4];
			this.maxZ = box[5];
		}

		public Vector3f center() {
			return new Vector3f((minX + maxX) * 0.5F, (minY + maxY) * 0.5F, (minZ + maxZ) * 0.5F);
		}

		/** How far the box reaches below its centre (model y points down) once turned by {@code rotation}. */
		public float reachBelow(Quaternionf rotation) {
			Vector3f c = center();
			float low = -Float.MAX_VALUE;
			Vector3f v = new Vector3f();
			for (int i = 0; i < 8; i++) {
				v.set((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ).sub(c);
				rotation.transform(v);
				low = Math.max(low, v.y);
			}
			return low;
		}
	}

	/** One face of one cube: its texture rectangle (0..1) and whether it carries a printed face. */
	public record Face(float u0, float v0, float u1, float v1, boolean print, boolean headTop, boolean hatTop) {
		float area() {
			return (u1 - u0) * (v1 - v0);
		}
	}

	private record Entry(String[] path, ModelPart.Cube cube) {
	}

	public final List<Piece> pieces = new ArrayList<>();
	public final List<Face> faces = new ArrayList<>();
	public ModelPart @Nullable [] headPath;
	public float headCx, headTop, headCz, headW, headD;
	/** Top of the layer worn over the head (villager hats, the skin's hat layer), NaN if the model has none. */
	public float hatTop = Float.NaN;

	private ModelInfo() {
	}

	public static ModelInfo of(Model<?> model) {
		ModelInfo info = CACHE.get(model);
		if (info == null) {
			try {
				info = new ModelInfo(model.root());
			} catch (RuntimeException e) {
				Brickify.LOGGER.warn("Could not read model {}: {}", model.getClass().getSimpleName(), e.toString());
				info = EMPTY;
			}
			CACHE.put(model, info);
		}
		return info;
	}

	private ModelInfo(ModelPart root) {
		List<Entry> entries = new ArrayList<>();
		root.visit(new PoseStack(), (pose, path, index, cube) ->
			entries.add(new Entry(path.isEmpty() ? new String[0] : path.substring(1).split("/"), cube)));

		// the level where the body splits into parts (skip wrapper parts like "root" or "body_root")
		List<String> prefix = new ArrayList<>();
		Set<String> names;
		while (true) {
			names = new LinkedHashSet<>();
			boolean cubesHere = false;
			for (Entry e : entries) {
				if (!startsWith(e.path, prefix)) {
					continue;
				}
				if (e.path.length == prefix.size()) {
					cubesHere = true;
				} else {
					names.add(e.path[prefix.size()]);
				}
			}
			if (!cubesHere && names.size() == 1 && prefix.size() < 4) {
				prefix.add(names.iterator().next());
				continue;
			}
			break;
		}
		ModelPart parent = walk(root, prefix, prefix.size());
		for (String name : names) {
			List<String> piecePath = new ArrayList<>(prefix);
			piecePath.add(name);
			ModelPart part = parent.getChild(name);
			float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (Entry e : entries) {
				if (startsWith(e.path, piecePath)) {
					Vector3f off = offset(part, e.path, piecePath.size());
					include(box, e.cube, off);
				}
			}
			if (box[0] <= box[3]) {
				this.pieces.add(new Piece(part, name, box));
			}
		}

		// the head: the biggest cube of the part called "head"
		int headDepth = -1;
		String[] headSegs = null;
		for (Entry e : entries) {
			int i = Arrays.asList(e.path).indexOf("head");
			if (i >= 0) {
				headDepth = i + 1;
				headSegs = Arrays.copyOf(e.path, headDepth);
				break;
			}
		}
		ModelPart.Cube headCube = null;
		if (headSegs != null) {
			List<String> segs = Arrays.asList(headSegs);
			ModelPart[] chain = new ModelPart[headDepth + 1];
			chain[0] = root;
			for (int i = 0; i < headDepth; i++) {
				chain[i + 1] = chain[i].getChild(headSegs[i]);
			}
			float best = -1.0F;
			for (Entry e : entries) {
				if (e.path.length == headDepth && startsWith(e.path, segs)) {
					ModelPart.Cube c = e.cube;
					float vol = (c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ);
					if (vol > best) {
						best = vol;
						headCube = c;
					}
				}
			}
			if (headCube != null) {
				this.headPath = chain;
				this.headCx = (headCube.minX + headCube.maxX) * 0.5F;
				this.headCz = (headCube.minZ + headCube.maxZ) * 0.5F;
				this.headTop = headCube.minY;
				this.headW = headCube.maxX - headCube.minX;
				this.headD = headCube.maxZ - headCube.minZ;
			}
		}

		// the highest cube worn over the head (a hat), if any
		List<String> headList = headSegs == null ? null : Arrays.asList(headSegs);
		ModelPart.Cube hatCube = null;
		if (headCube != null) {
			float highest = Float.MAX_VALUE;
			for (Entry e : entries) {
				if (e.path.length > headDepth && startsWith(e.path, headList)) {
					Vector3f off = offset(this.headPath[headDepth], e.path, headDepth);
					float topY = Float.MAX_VALUE;
					for (ModelPart.Polygon polygon : e.cube.polygons) {
						for (ModelPart.Vertex v : polygon.vertices()) {
							topY = Math.min(topY, v.y() + off.y);
						}
					}
					float w = e.cube.maxX - e.cube.minX;
					if (topY < highest && topY <= this.headTop && w >= this.headW - 0.01F) {
						highest = topY;
						hatCube = e.cube;
					}
				}
			}
			if (hatCube != null) {
				this.hatTop = highest;
			}
		}

		// faces with their texture rectangles; the front of the head keeps its print
		for (Entry e : entries) {
			boolean onHead = headList != null && startsWith(e.path, headList);
			ModelPart.Cube c = e.cube;
			float midY = (c.minY + c.maxY) * 0.5F;
			float midZ = (c.minZ + c.maxZ) * 0.5F;
			for (ModelPart.Polygon polygon : c.polygons) {
				float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
				boolean front = true;
				boolean top = true;
				for (ModelPart.Vertex v : polygon.vertices()) {
					u0 = Math.min(u0, v.u());
					v0 = Math.min(v0, v.v());
					u1 = Math.max(u1, v.u());
					v1 = Math.max(v1, v.v());
					front &= v.z() < midZ;
					top &= v.y() < midY;
				}
				if (u1 > u0 && v1 > v0) {
					this.faces.add(new Face(u0, v0, u1, v1, onHead && front, c == headCube && top, c == hatCube && top));
				}
			}
		}
		this.faces.sort(Comparator.comparingDouble(Face::area).reversed());
	}

	private static boolean startsWith(String[] path, List<String> prefix) {
		if (path.length < prefix.size()) {
			return false;
		}
		for (int i = 0; i < prefix.size(); i++) {
			if (!path[i].equals(prefix.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static ModelPart walk(ModelPart root, List<String> path, int depth) {
		ModelPart part = root;
		for (int i = 0; i < depth; i++) {
			part = part.getChild(path.get(i));
		}
		return part;
	}

	/** Offset of a nested part relative to the piece (child rotations ignored, they are small). */
	private static Vector3f offset(ModelPart piece, String[] path, int from) {
		Vector3f off = new Vector3f();
		ModelPart part = piece;
		for (int i = from; i < path.length; i++) {
			part = part.getChild(path[i]);
			off.add(part.x, part.y, part.z);
		}
		return off;
	}

	private static void include(float[] box, ModelPart.Cube c, Vector3f off) {
		box[0] = Math.min(box[0], c.minX + off.x);
		box[1] = Math.min(box[1], c.minY + off.y);
		box[2] = Math.min(box[2], c.minZ + off.z);
		box[3] = Math.max(box[3], c.maxX + off.x);
		box[4] = Math.max(box[4], c.maxY + off.y);
		box[5] = Math.max(box[5], c.maxZ + off.z);
	}
}
