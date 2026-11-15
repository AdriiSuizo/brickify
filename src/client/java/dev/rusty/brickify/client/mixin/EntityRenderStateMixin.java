package dev.rusty.brickify.client.mixin;

import dev.rusty.brickify.client.BrickRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements BrickRenderState {
	@Unique
	private boolean bk$brick;
	@Unique
	private int bk$breakId;
	@Unique
	private int bk$breakSeed;
	@Unique
	private float bk$breakAge = -1.0F;
	@Unique
	private float bk$flip;
	@Unique
	private boolean bk$building;

	@Override
	public float bk$flip() {
		return this.bk$flip;
	}

	@Override
	public boolean bk$building() {
		return this.bk$building;
	}

	@Override
	public void bk$setPlayer(float flip, boolean building) {
		this.bk$flip = flip;
		this.bk$building = building;
	}

	@Override
	public boolean bk$isBrick() {
		return this.bk$brick;
	}

	@Override
	public void bk$setBrick(boolean brick) {
		this.bk$brick = brick;
	}

	@Override
	public int bk$breakId() {
		return this.bk$breakId;
	}

	@Override
	public int bk$breakSeed() {
		return this.bk$breakSeed;
	}

	@Override
	public float bk$breakAge() {
		return this.bk$breakAge;
	}

	@Override
	public void bk$setBreak(int id, int seed, float age) {
		this.bk$breakId = id;
		this.bk$breakSeed = seed;
		this.bk$breakAge = age;
	}
}
