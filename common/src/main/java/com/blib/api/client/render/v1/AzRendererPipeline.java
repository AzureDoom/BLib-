package com.blib.api.client.render.v1;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;

import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.client.profiling.v1.AzProfileStage;
import com.blib.api.client.profiling.v1.AzProfiler;

public abstract class AzRendererPipeline<K, T> implements AzPhasedRenderer<K, T> {

    protected final AzRendererConfig<K, T> config;

    private final AzRendererPipelineContext<K, T> context;

    private final AzLayerRenderer<K, T> layerRenderer;

    private final AzModelRenderer<K, T> modelRenderer;

    protected AzRendererPipeline(AzRendererConfig<K, T> config) {
        this.config = config;
        this.context = createContext(this);
        this.layerRenderer = createLayerRenderer(config);
        this.modelRenderer = createModelRenderer(layerRenderer);
    }

    protected abstract AzRendererPipelineContext<K, T> createContext(AzRendererPipeline<K, T> rendererPipeline);

    protected abstract AzModelRenderer<K, T> createModelRenderer(AzLayerRenderer<K, T> layerRenderer);

    protected abstract AzLayerRenderer<K, T> createLayerRenderer(AzRendererConfig<K, T> config);

    protected abstract void updateAnimatedTextureFrame(T animatable);

    public void render(
        PoseStack poseStack,
        AzBakedModel model,
        T animatable,
        MultiBufferSource bufferSource,
        @Nullable RenderType renderType,
        @Nullable VertexConsumer buffer,
        float yaw,
        float partialTick,
        int packedLight
    ) {
        AzProfiler.begin(AzProfileStage.RENDER, animatable);
        renderType = context.getDefaultRenderType(
            animatable,
            config.textureLocation(context.currentEntity, animatable),
            bufferSource,
            partialTick,
            config.getRenderType(context.currentEntity, animatable),
            config.alpha(animatable)
        );
        context.populate(
            animatable,
            model,
            bufferSource,
            packedLight,
            partialTick,
            poseStack,
            renderType,
            buffer
        );

        poseStack.pushPose();

        AzProfiler.begin(AzProfileStage.PRE_RENDER, animatable);
        preRender(context, false);
        AzProfiler.end(AzProfileStage.PRE_RENDER);

        layerRenderer.preApplyRenderLayers(context);
        modelRenderer.cacheTexture(context);
        AzProfiler.begin(AzProfileStage.MODEL_RENDER, animatable);
        modelRenderer.render(context, false);
        AzProfiler.end(AzProfileStage.MODEL_RENDER);
        modelRenderer.clearCacheTexture();
        AzProfiler.begin(AzProfileStage.RENDER_LAYERS, animatable);
        layerRenderer.applyRenderLayers(context);
        AzProfiler.end(AzProfileStage.RENDER_LAYERS);
        postRender(context, false);

        poseStack.popPose();

        renderFinal(context);
        doPostRenderCleanup(context);
        AzProfiler.end(AzProfileStage.RENDER);
    }

    public void reRender(AzRendererPipelineContext<K, T> context) {
        reRender(context, false);
    }

    /**
     * Re-renders the model for a render layer.
     *
     * @param applyAnimation whether the animator should run again for this pass. Normally {@code false}: the bones
     *                       are already posed from the main pass. Pass {@code true} when the layer has swapped in a
     *                       different model (see {@link AzRendererPipelineContext#setBakedModel}) that still needs
     *                       posing.
     */
    public void reRender(AzRendererPipelineContext<K, T> context, boolean applyAnimation) {
        AzProfiler.begin(AzProfileStage.RE_RENDER, context.animatable());
        var poseStack = context.poseStack();
        var oldFlag = context.applyAnimationOnReRender();

        context.setApplyAnimationOnReRender(applyAnimation);

        poseStack.pushPose();

        try {
            preRender(context, true);
            modelRenderer.render(context, true);
            postRender(context, true);
        } finally {
            poseStack.popPose();
            context.setApplyAnimationOnReRender(oldFlag);
            AzProfiler.end(AzProfileStage.RE_RENDER);
        }
    }

    protected void renderFinal(AzRendererPipelineContext<K, T> context) {}

    protected void doPostRenderCleanup(AzRendererPipelineContext<K, T> context) {}

    protected void scaleModelForRender(
        AzRendererPipelineContext<K, T> context,
        float widthScale,
        float heightScale,
        boolean isReRender
    ) {
        if (!isReRender && (widthScale != 1 || heightScale != 1)) {
            var poseStack = context.poseStack();
            poseStack.scale(widthScale, heightScale, widthScale);
        }
    }

    public AzRendererConfig<K, T> config() {
        return config;
    }

    public AzRendererPipelineContext<K, T> context() {
        return context;
    }
}
