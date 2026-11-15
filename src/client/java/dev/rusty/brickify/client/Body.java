package dev.rusty.brickify.client;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A loose piece in the world (a brick, or a body part of a figure that came apart): falls, bounces off the
 * ground with a click, slides, tumbles, stops at walls, drops off edges and comes to rest lying flat.
 * World space, blocks per tick, a box with half extents {@link #half}.
 */
public final class Body {
	public static float gravity = 0.055F;

	public final Vector3f half;
	public final Vector3d pos = new Vector3d();
	public final Vector3d prevPos = new Vector3d();
	public final Vector3d vel = new Vector3d();
	public final Quaternionf rot = new Quaternionf();
	public final Quaternionf prevRot = new Quaternionf();
	/** Turn per tick: axis times angle, in world space. */
	public final Vector3f spin = new Vector3f();
	public boolean resting;
	private int still;

	public Body(Vector3f half) {
		this.half = half;
	}

	/** Gets a resting piece moving again (it was kicked). */
	public void wake() {
		this.resting = false;
		this.still = 0;
	}

	/**
	 * Something walked into the piece: it gets kicked away along the walker's way. Returns true if it was kicked.
	 */
	public boolean kick(double px, double py, double pz, double mx, double mz, java.util.Random random) {
		double dx = this.pos.x - px;
		double dz = this.pos.z - pz;
		double h = Math.sqrt(dx * dx + dz * dz);
		double reach = 0.45 + Math.max(this.half.x, this.half.z);
		if (h > reach || this.pos.y < py - 0.4 || this.pos.y > py + 1.2) {
			return false;
		}
		double speed = Math.sqrt(mx * mx + mz * mz);
		if (speed < 0.03 || !this.resting && this.vel.lengthSquared() > 0.0025) {
			return false;
		}
		double ax = h < 1.0E-3 ? mx / speed : dx / h;
		double az = h < 1.0E-3 ? mz / speed : dz / h;
		double kx = ax * 0.6 + mx / speed * 0.8;
		double kz = az * 0.6 + mz / speed * 0.8;
		double k = Math.sqrt(kx * kx + kz * kz);
		double push = 0.08 + speed * 1.1;
		this.vel.set(kx / k * push, 0.08 + speed * 0.7 + random.nextDouble() * 0.06, kz / k * push);
		float a = random.nextFloat() * Mth.TWO_PI;
		float s = 0.15F + random.nextFloat() * 0.3F;
		this.spin.set(Mth.cos(a) * s, (random.nextFloat() - 0.5F) * 0.3F, Mth.sin(a) * s);
		wake();
		return true;
	}

	public void place(double x, double y, double z, Quaternionf rotation) {
		this.pos.set(x, y, z);
		this.prevPos.set(this.pos);
		this.rot.set(rotation);
		this.prevRot.set(rotation);
	}

	public Vector3d lerpPos(float partialTicks, Vector3d out) {
		return this.prevPos.lerp(this.pos, partialTicks, out);
	}

	public Quaternionf lerpRot(float partialTicks, Quaternionf out) {
		return out.set(this.prevRot).slerp(this.rot, partialTicks);
	}

	/** Distance from the centre down to the lowest corner. */
	public float reachBelow() {
		Vector3f a = new Vector3f();
		float low = 0.0F;
		low += Math.abs(this.rot.transform(a.set(1.0F, 0.0F, 0.0F)).y) * this.half.x;
		low += Math.abs(this.rot.transform(a.set(0.0F, 1.0F, 0.0F)).y) * this.half.y;
		low += Math.abs(this.rot.transform(a.set(0.0F, 0.0F, 1.0F)).y) * this.half.z;
		return low;
	}

	/** One tick of motion. Returns the speed of a hard landing this tick (for a click), else 0. */
	public float step(Level level, @Nullable LongSet ignore) {
		this.prevPos.set(this.pos);
		this.prevRot.set(this.rot);
		if (this.resting) {
			return 0.0F;
		}
		this.vel.y = Math.max(this.vel.y - gravity, -1.6);
		float side = Math.max(this.half.x, Math.max(this.half.y, this.half.z)) * 0.7F;
		double nx = this.pos.x + this.vel.x;
		if (solidAt(level, ignore, nx + Math.signum(this.vel.x) * side, this.pos.y, this.pos.z)) {
			this.vel.x = -this.vel.x * 0.3;
		} else {
			this.pos.x = nx;
		}
		double nz = this.pos.z + this.vel.z;
		if (solidAt(level, ignore, this.pos.x, this.pos.y, nz + Math.signum(this.vel.z) * side)) {
			this.vel.z = -this.vel.z * 0.3;
		} else {
			this.pos.z = nz;
		}
		this.pos.y += this.vel.y;
		float angle = this.spin.length();
		if (angle > 1.0E-4F) {
			this.rot.premul(new Quaternionf().rotationAxis(angle, this.spin.x / angle, this.spin.y / angle, this.spin.z / angle)).normalize();
		}
		float impact = 0.0F;
		float low = reachBelow();
		double ground = groundBelow(level, ignore, this.pos.x, this.pos.y + 0.25, this.pos.z);
		if (this.pos.y - low <= ground + 0.001) {
			this.pos.y = ground + low;
			if (this.vel.y < -0.13) {
				impact = (float) -this.vel.y;
				this.vel.y = -this.vel.y * 0.3;
				this.vel.x *= 0.72;
				this.vel.z *= 0.72;
				this.spin.mul(0.55F);
			} else {
				this.vel.y = 0.0;
				this.vel.x *= 0.6;
				this.vel.z *= 0.6;
				this.spin.mul(0.4F);
			}
			boolean flat = settle(0.3F);
			double speed = this.vel.x * this.vel.x + this.vel.z * this.vel.z + this.vel.y * this.vel.y;
			if (flat && speed < 1.0E-4 && this.spin.lengthSquared() < 1.0E-4F) {
				if (++this.still > 3) {
					this.resting = true;
					this.pos.y = ground + reachBelow();
				}
			} else {
				this.still = 0;
			}
		}
		return impact;
	}

	/** Tips the piece a bit towards lying on its nearest face. Returns true once it lies flat. */
	private boolean settle(float amount) {
		Vector3f best = null;
		float bestY = -1.0F;
		float bestScore = -1.0F;
		Vector3f a = new Vector3f();
		float longest = Math.max(this.half.x, Math.max(this.half.y, this.half.z));
		for (int i = 0; i < 3; i++) {
			this.rot.transform(a.set(i == 0 ? 1.0F : 0.0F, i == 1 ? 1.0F : 0.0F, i == 2 ? 1.0F : 0.0F));
			float extent = i == 0 ? this.half.x : i == 1 ? this.half.y : this.half.z;
			// long pieces (legs, arms) rather lie down than stand on their end
			float score = Math.abs(a.y) * (extent >= longest * 0.99F && longest > 1.4F * Math.min(this.half.x, Math.min(this.half.y, this.half.z)) ? 0.8F : 1.0F);
			if (score > bestScore) {
				bestScore = score;
				bestY = Math.abs(a.y);
				best = new Vector3f(a);
			}
		}
		if (bestY > 0.9998F) {
			return true;
		}
		Vector3f up = new Vector3f(0.0F, best.y >= 0.0F ? 1.0F : -1.0F, 0.0F);
		Quaternionf turn = new Quaternionf().rotationTo(best, up);
		this.rot.premul(new Quaternionf().slerp(turn, amount)).normalize();
		return false;
	}

	public static boolean solidAt(Level level, @Nullable LongSet ignore, double x, double y, double z) {
		BlockPos p = BlockPos.containing(x, y, z);
		if (ignore != null && ignore.contains(p.asLong())) {
			return false;
		}
		BlockState state = level.getBlockState(p);
		VoxelShape shape = state.getCollisionShape(level, p);
		return !shape.isEmpty() && shape.bounds().move(p).contains(x, y, z);
	}

	/** Top of the highest solid surface at or below {@code top} in this column (or far below if none). */
	public static double groundBelow(Level level, @Nullable LongSet ignore, double x, double top, double z) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int by = Mth.floor(top);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = by; y >= by - 6; y--) {
			p.set(bx, y, bz);
			if (ignore != null && ignore.contains(p.asLong())) {
				continue;
			}
			BlockState state = level.getBlockState(p);
			VoxelShape shape = state.getCollisionShape(level, p);
			if (!shape.isEmpty()) {
				return y + shape.max(Direction.Axis.Y);
			}
		}
		return -1.0E9;
	}
}
