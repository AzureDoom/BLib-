package com.blib.api.client.render.v1.block.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzLayerRenderer;
import com.blib.api.client.render.v1.AzModelRenderer;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.api.client.render.v1.block.pipeline.AzBlockEntityRendererPipeline;
import com.blib.internal.client.render.util.RenderUtil;

public class AzBlockEntityModelRenderer<T extends BlockEntity> extends AzModelRenderer<Long, T> {

    protected final AzBlockEntityRendererPipeline<T> blockEntityRendererPipeline;

    private final Matrix4f scratchPoseState = new Matrix4f();

    private final Matrix4f scratchMatrix = new Matrix4f();

    private final Vector3f scratchVector = new Vector3f();

    public AzBlockEntityModelRenderer(
        AzBlockEntityRendererPipeline<T> blockEntityRendererPipeline,
        AzLayerRenderer<Long, T> layerRenderer
    ) {
        super(blockEntityRendererPipeline, layerRenderer);
        this.blockEntityRendererPipeline = blockEntityRendererPipeline;
    }

    @Override
    public void render(AzRendererPipelineContext<Long, T> context, boolean isReRender) {
        var entity = context.animatable();
        var poseStack = context.poseStack();

        if (!isReRender) {
            poseStack.translate(0.5, 0, 0.5);
            rotateBlock(getFacing(entity), poseStack);
        }

        if (!isReRender || context.applyAnimationOnReRender()) {
            var animator = blockEntityRendererPipeline.getRenderer().getAnimator();

            if (animator != null) {
                handleAnimation(animator, entity, context.partialTick());
            }
        }

        blockEntityRendererPipeline.getModelRenderTranslations().set(poseStack.last().pose());

        var textureLocation = blockEntityRendererPipeline.config().textureLocation(context.currentEntity(), entity);
        RenderSystem.setShaderTexture(0, textureLocation);
        super.render(context, isReRender);
    }

    @Override
    public void renderRecursively(AzRendererPipelineContext<Long, T> context, AzBone bone, boolean isReRender) {
        if (isBoneFiltered(context, bone)) {
            return;
        }

        var buffer = context.vertexConsumer();
        var bufferSource = context.multiBufferSource();
        var entity = context.animatable();
        var poseStack = context.poseStack();

        var slot = saveBonePose(poseStack);

        try {
            RenderUtil.translateMatrixToBone(poseStack, bone);
            RenderUtil.translateToPivotPoint(poseStack, bone);
            RenderUtil.rotateMatrixAroundBone(poseStack, bone);
            RenderUtil.scaleMatrixForBone(poseStack, bone);

            if (bone.isTrackingMatrices()) {
                var poseState = scratchPoseState.set(poseStack.last().pose());

                bone.setModelSpaceMatrix(
                    RenderUtil.invertAndMultiplyMatrices(
                        poseState,
                        blockEntityRendererPipeline.getModelRenderTranslations(),
                        scratchMatrix
                    )
                );

                var localMatrix = RenderUtil.invertAndMultiplyMatrices(
                    poseState,
                    blockEntityRendererPipeline.getEntityRenderTranslations(),
                    scratchMatrix
                );
                var blockPos = entity.getBlockPos();

                bone.setLocalSpaceMatrix(localMatrix);
                bone.setWorldSpaceMatrix(
                    RenderUtil.translateMatrixInPlace(
                        localMatrix,
                        scratchVector.set(blockPos.getX(), blockPos.getY(), blockPos.getZ())
                    )
                );
            }

            RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

            context.setVertexConsumer(getOrRefreshRenderBuffer(isReRender, context, bone));

            if (
                !boneRenderOverride(
                    poseStack,
                    bone,
                    bufferSource,
                    buffer,
                    context.partialTick(),
                    context.packedLight(),
                    context.packedOverlay(),
                    context.renderColor()
                )
            )
                super.renderCubesOfBone(context, bone);

            if (!isReRender) {
                layerRenderer.applyRenderLayersForBone(context, bone);
            }

            renderChildBones(context, bone, isReRender);
        } finally {
            restoreBonePose(poseStack, slot);
        }
    }

    protected Direction getFacing(T block) {
        BlockState blockState = block.getBlockState();

        if (blockState.hasProperty(HorizontalDirectionalBlock.FACING))
            return blockState.getValue(HorizontalDirectionalBlock.FACING);

        if (blockState.hasProperty(DirectionalBlock.FACING))
            return blockState.getValue(DirectionalBlock.FACING);

        return Direction.NORTH;
    }

    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(90));
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(0));
            case EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(270));
            case UP -> poseStack.mulPose(Axis.XP.rotationDegrees(90));
            case DOWN -> poseStack.mulPose(Axis.XN.rotationDegrees(90));
        }
    }
}
