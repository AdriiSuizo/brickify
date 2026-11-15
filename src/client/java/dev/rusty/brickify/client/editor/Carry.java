package dev.rusty.brickify.client.editor;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * When the "Carry On" mod is installed, the figure carries a box of TNT in both arms instead of keeping it in a
 * pocket: the villager puts it straight into its arms, it walks over (a little slower), sets it down with a click and
 * it lights. Carry On is not needed: without it everything works the old way, so it is reached by name only.
 */
public final class Carry {
	private static volatile @Nullable Boolean loaded;
	private static Method getData;
	private static Method setData;
	private static Method setBlock;
	private static Method isCarrying;

	private Carry() {
	}

	public static synchronized boolean available() {
		if (loaded == null) {
			boolean ok = false;
			if (FabricLoader.getInstance().isModLoaded("carryon")) {
				try {
					Class<?> manager = Class.forName("tschipp.carryon.common.carry.CarryOnDataManager");
					Class<?> data = Class.forName("tschipp.carryon.common.carry.CarryOnData");
					getData = manager.getMethod("getCarryData", Player.class);
					setData = manager.getMethod("setCarryData", Player.class, data);
					setBlock = data.getMethod("setBlock", BlockState.class, BlockEntity.class, ServerPlayer.class, BlockPos.class);
					isCarrying = data.getMethod("isCarrying");
					ok = true;
				} catch (Throwable t) {
					dev.rusty.brickify.Brickify.LOGGER.warn("Carry On is there, but not as expected: {}", t.toString());
				}
			}
			// set only once everything is looked up (the server thread asks at the same time)
			loaded = ok;
		}
		return loaded;
	}

	/** Whether the figure has something in its arms right now. */
	public static boolean carrying(@Nullable Player player) {
		if (player == null || !available()) {
			return false;
		}
		try {
			return (boolean) isCarrying.invoke(getData.invoke(null, player));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Puts the block into the figure's arms (server side). False if that is not possible (arms full, no Carry On). */
	public static boolean intoArms(ServerPlayer player, BlockState state) {
		return intoArms(player, state, null);
	}

	public static boolean intoArms(ServerPlayer player, BlockState state, @Nullable BlockEntity entity) {
		if (!available()) {
			return false;
		}
		try {
			Object data = getData.invoke(null, player);
			if ((boolean) isCarrying.invoke(data)) {
				return false;
			}
			setBlock.invoke(data, state, entity, player, entity != null ? entity.getBlockPos() : player.blockPosition());
			setData.invoke(null, player, data);
			return true;
		} catch (Throwable t) {
			dev.rusty.brickify.Brickify.LOGGER.warn("Could not hand over: {}", t.toString());
			return false;
		}
	}
}
