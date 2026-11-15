package dev.rusty.brickify.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;

/**
 * Studs on top of the head of every brick figure that has a head (players, zombies, villagers, pigs, cows ...),
 * sitting exactly on the head box of its own model and coloured like the top of its (plastic) head.
 */
public class StudHatLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends RenderLayer<S, M> {
	private static final Identifier WHITE = dev.rusty.brickify.Brickify.id("textures/entity/white.png");
	private static Model.Simple stud;
	private final LivingEntityRenderer<?, S, M> renderer;

	public StudHatLayer(LivingEntityRenderer<?, S, M> renderer) {
		super((RenderLayerParent<S, M>) renderer);
		this.renderer = renderer;
	}

	/** One stud of 1x1x1 pixel standing on y=0, scaled into place. */
	private static Model.Simple stud() {
		if (stud == null) {
			MeshDefinition mesh = new MeshDefinition();
			mesh.getRoot().addOrReplaceChild("stud", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5F, -1.0F, -0.5F, 1.0F, 1.0F, 1.0F), PartPose.ZERO);
			stud = new Model.Simple(LayerDefinition.create(mesh, 16, 16).bakeRoot(), RenderTypes::entityCutout);
		}
		return stud;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot) {
		if (!((BrickRenderState) state).bk$isBrick() || state.isInvisible) {
			return;
		}
		M model = this.getParentModel();
		ModelInfo info = ModelInfo.of(model);
		ModelPart[] path = info.headPath;
		if (path == null || info.headW < 1.5F || info.headD < 1.5F) {
			return;
		}
		for (ModelPart part : path) {
			if (!part.visible) {
				return;
			}
		}
		Identifier texture = this.renderer.getTextureLocation(state);
		int colour = BrickTextures.topColor(BrickTextures.plasticFor(texture, model));
		float top = info.headTop;
		if (BrickTextures.lastHat() != -1 && !Float.isNaN(info.hatTop)) {
			colour = BrickTextures.lastHat();
			top = info.hatTop;
		}
		if (colour == -1) {
			return;
		}
		int nx = Math.max(1, Math.round(info.headW / 4.0F));
		int nz = Math.max(1, Math.round(info.headD / 4.0F));
		float pitchX = info.headW / nx;
		float pitchZ = info.headD / nz;
		float size = Math.min(pitchX, pitchZ) * 0.6F;
		float height = Math.max(0.8F, Math.min(1.4F, size * 0.55F));
		RenderType type = RenderTypes.entityCutout(WHITE);
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
		poseStack.pushPose();
		for (ModelPart part : path) {
			part.translateAndRotate(poseStack);
		}
		for (int i = 0; i < nx; i++) {
			for (int k = 0; k < nz; k++) {
				float x = info.headCx - info.headW * 0.5F + pitchX * (i + 0.5F);
				float z = info.headCz - info.headD * 0.5F + pitchZ * (k + 0.5F);
				for (int turn = 0; turn < 2; turn++) {
					poseStack.pushPose();
					poseStack.translate(x / 16.0F, (top + 0.02F) / 16.0F, z / 16.0F);
					if (turn == 1) {
						poseStack.rotate(Axis.YP.rotationDegrees(45.0F));
					}
					float s = turn == 0 ? size : size * 0.96F;
					poseStack.scale(s, height, s);
					collector.submitModel(stud(), Unit.INSTANCE, poseStack, type, lightCoords, overlay, 0xFF000000 | colour, null, state.outlineColor);
					poseStack.popPose();
				}
			}
		}
		poseStack.popPose();
	}
}
