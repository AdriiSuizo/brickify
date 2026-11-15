package dev.rusty.brickify.client;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Real studs on the flat tops of the brick blocks: four round studs per block (two by two, like a plate), each
 * an eight-sided cylinder with a lid, cut from the block's own top colour.
 */
public final class Studs {
	public static final int SIDES = 8;
	public static final float RADIUS = 0.15F;
	public static final float HEIGHT = 0.1F;
	private static final float[][] CENTRES = {{0.25F, 0.25F}, {0.75F, 0.25F}, {0.25F, 0.75F}, {0.75F, 0.75F}};
	private static final float[] COS = new float[SIDES];
	private static final float[] SIN = new float[SIDES];

	static {
		for (int k = 0; k < SIDES; k++) {
			double a = (k + 0.5) * Math.PI * 2.0 / SIDES;
			COS[k] = (float) Math.cos(a);
			SIN[k] = (float) Math.sin(a);
		}
	}

	private Studs() {
	}

	public static void emit(QuadEmitter e, TextureAtlasSprite sprite, int tint, ChunkSectionLayer layer) {
		float u0 = sprite.getU0();
		float du = sprite.getU1() - u0;
		float v0 = sprite.getV0();
		float dv = sprite.getV1() - v0;
		float top = 1.0F + HEIGHT;
		Vector3f[] q = new Vector3f[4];
		for (int i = 0; i < 4; i++) {
			q[i] = new Vector3f();
		}
		for (float[] c : CENTRES) {
			float cx = c[0];
			float cz = c[1];
			for (int k = 0; k < SIDES; k++) {
				int n = (k + 1) % SIDES;
				float x0 = cx + RADIUS * COS[k];
				float z0 = cz + RADIUS * SIN[k];
				float x1 = cx + RADIUS * COS[n];
				float z1 = cz + RADIUS * SIN[n];
				q[0].set(x0, 1.0F, z0);
				q[1].set(x0, top, z0);
				q[2].set(x1, top, z1);
				q[3].set(x1, 1.0F, z1);
				float mx = (COS[k] + COS[n]) * 0.5F;
				float mz = (SIN[k] + SIN[n]) * 0.5F;
				float len = Mth.sqrt(mx * mx + mz * mz);
				quad(e, q, mx / len, 0.0F, mz / len, sprite, u0, du, v0, dv, tint, layer);
			}
			// Lid: an octagon as three quads fanned from corner 0.
			for (int f = 0; f < 3; f++) {
				int a = 0;
				int b = 1 + f * 2;
				int cc = 2 + f * 2;
				int d = Math.min(3 + f * 2, SIDES - 1);
				q[0].set(cx + RADIUS * COS[a], top, cz + RADIUS * SIN[a]);
				q[1].set(cx + RADIUS * COS[b], top, cz + RADIUS * SIN[b]);
				q[2].set(cx + RADIUS * COS[cc], top, cz + RADIUS * SIN[cc]);
				q[3].set(cx + RADIUS * COS[d], top, cz + RADIUS * SIN[d]);
				quad(e, q, 0.0F, 1.0F, 0.0F, sprite, u0, du, v0, dv, tint, layer);
			}
		}
	}

	/** Emits one quad, turned so it faces along (nx, ny, nz). */
	private static void quad(QuadEmitter e, Vector3f[] q, float nx, float ny, float nz, TextureAtlasSprite sprite, float u0, float du, float v0, float dv,
							 int tint, ChunkSectionLayer layer) {
		Vector3f a = new Vector3f(q[1]).sub(q[0]);
		Vector3f b = new Vector3f(q[2]).sub(q[0]);
		Vector3f cross = a.cross(b);
		boolean flip = cross.x * nx + cross.y * ny + cross.z * nz < 0.0F;
		for (int i = 0; i < 4; i++) {
			Vector3f p = q[flip ? 3 - i : i];
			e.pos(i, p.x, p.y, p.z);
			// The brick tops are one colour: sample the middle of the sprite, the shading makes the studs round.
			float u = u0 + du * (i == 0 || i == 1 ? 0.45F : 0.55F);
			float v = v0 + dv * (i == 0 || i == 3 ? 0.45F : 0.55F);
			e.uv(i, u, v);
			e.color(i, 0xFFFFFFFF);
			if (ny == 0.0F) {
				e.normal(i, nx, 0.0F, nz);
			} else {
				e.normal(i, 0.0F, 1.0F, 0.0F);
			}
		}
		e.cullFace(Direction.UP);
		e.nominalFace(ny > 0.5F ? Direction.UP : Math.abs(nx) > Math.abs(nz) ? (nx > 0.0F ? Direction.EAST : Direction.WEST) : (nz > 0.0F ? Direction.SOUTH : Direction.NORTH));
		e.tintIndex(tint);
		e.chunkLayer(layer);
		e.emit();
	}
}
