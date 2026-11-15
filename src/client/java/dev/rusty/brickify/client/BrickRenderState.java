package dev.rusty.brickify.client;

/** Added to every entity render state: whether this entity is drawn as bricks this frame, and its break-up. */
public interface BrickRenderState {
	boolean bk$isBrick();

	void bk$setBrick(boolean brick);

	/** Id of the breaking copy this state belongs to, 0 when it is not coming apart. */
	int bk$breakId();

	int bk$breakSeed();

	float bk$breakAge();

	void bk$setBreak(int id, int seed, float age);

	/** The local player's flip (radians, 0 when not flipping) and whether it is building something. */
	float bk$flip();

	boolean bk$building();

	void bk$setPlayer(float flip, boolean building);
}
