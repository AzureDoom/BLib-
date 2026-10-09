package com.blib.api.client.render.v1.layer;

import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.internal.client.animation.AzAnimatorAccessor;

public interface AzRenderLayer<K, T> {

    void preRender(AzRendererPipelineContext<K, T> context);

    void render(AzRendererPipelineContext<K, T> context);

    void renderForBone(AzRendererPipelineContext<K, T> context, AzBone bone);

    /**
     * Re-renders the animatable with a different baked model (e.g. an overlay or attachment model that shares bone
     * names with the main one), posed by the animatable's own animator.
     * <p>
     * The animator's bone cache is pointed at {@code model} for the duration of the pass, so its tracks pose the new
     * model's bones, then restored. Without an animator the model is rendered as-is.
     *
     * @param model the model to render, or {@code null} for {@link AzBakedModel#getDefault()}
     */
    default void reRenderWithBakedModel(AzRendererPipelineContext<K, T> context, AzBakedModel model) {
        var pipeline = context.rendererPipeline();
        var previousContextModel = context.bakedModel();
        var targetModel = model != null ? model : AzBakedModel.getDefault();

        var animator = AzAnimatorAccessor.getOrNull(context.animatable());

        if (animator == null || animator.context() == null || animator.context().boneCache() == null) {
            context.setBakedModel(targetModel);

            try {
                pipeline.reRender(context, true);
            } finally {
                context.setBakedModel(previousContextModel);
            }
            return;
        }

        var boneCache = animator.context().boneCache();
        // Restored as-is afterwards, so the main model keeps its per-instance copy and bone snapshots.
        var previousState = boneCache.saveState();

        try {
            boneCache.setActiveModel(targetModel);
            context.setBakedModel(boneCache.getBakedModel());
            pipeline.reRender(context, true);
        } finally {
            boneCache.restoreState(previousState);
            context.setBakedModel(previousContextModel);
        }
    }
}
