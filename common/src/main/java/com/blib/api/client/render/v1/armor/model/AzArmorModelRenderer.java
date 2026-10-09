package com.blib.api.client.render.v1.armor.model;

import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.UUID;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzLayerRenderer;
import com.blib.api.client.render.v1.AzModelRenderer;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.api.client.render.v1.armor.pipeline.AzArmorRendererPipeline;
import com.blib.internal.client.render.util.RenderUtil;

public class AzArmorModelRenderer extends AzModelRenderer<UUID, ItemStack> {

    protected final AzArmorRendererPipeline armorRendererPipeline;

    private final Matrix4f scratchPoseState = new Matrix4f();

    private final Matrix4f scratchMatrix = new Matrix4f();

    public AzArmorModelRenderer(
        AzArmorRendererPipeline armorRendererPipeline,
        AzLayerRenderer<UUID, ItemStack> layerRenderer
    ) {
        super(armorRendererPipeline, layerRenderer);
        this.armorRendererPipeline = armorRendererPipeline;
    }

    @Override
    public void render(AzRendererPipelineContext<UUID, ItemStack> context, boolean isReRender) {
        var poseStack = context.poseStack();

        poseStack.pushPose();
        poseStack.translate(0, 24 / 16f, 0);
        poseStack.scale(-1, -1, 1);

        if (!isReRender || context.applyAnimationOnReRender()) {
            var animatable = context.animatable();
            var animator = armorRendererPipeline.renderer().animator();

            if (animator != null) {
                handleAnimation(animator, animatable, context.partialTick());
            }
        }

        armorRendererPipeline.getModelRenderTranslations().set(poseStack.last().pose());

        super.render(context, isReRender);
        poseStack.popPose();
    }

    @Override
    public void renderRecursively(AzRendererPipelineContext<UUID, ItemStack> context, AzBone bone, boolean isReRender) {
        var poseStack = context.poseStack();
        // TODO: This is dangerous.
        var ctx = armorRendererPipeline.context();

        if (bone.isTrackingMatrices()) {
            var poseState = scratchPoseState.set(poseStack.last().pose());

            bone.setModelSpaceMatrix(
                RenderUtil.invertAndMultiplyMatrices(
                    poseState,
                    armorRendererPipeline.getModelRenderTranslations(),
                    scratchMatrix
                )
            );

            var localMatrix = RenderUtil.invertAndMultiplyMatrices(
                poseState,
                armorRendererPipeline.getEntityRenderTranslations(),
                scratchMatrix
            );
            bone.setLocalSpaceMatrix(localMatrix);

            var entity = ctx.currentEntity();

            if (entity != null) {
                RenderUtil.translateMatrixInPlace(localMatrix, entity.position().toVector3f());
            }

            bone.setWorldSpaceMatrix(localMatrix);
        }

        context.setVertexConsumer(getOrRefreshRenderBuffer(isReRender, context, bone));

        // The base class saves and restores the pose around the bone, so no pushPose here.
        super.renderRecursively(context, bone, isReRender);
    }
}
