package com.blib.api.client.render.v1;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.layer.AzRenderLayer;

public class AzLayerRenderer<K, T> {

    private final Supplier<Collection<AzRenderLayer<K, T>>> renderLayerSupplier;

    public AzLayerRenderer(Supplier<Collection<AzRenderLayer<K, T>>> renderLayerSupplier) {
        this.renderLayerSupplier = renderLayerSupplier;
    }

    protected void preApplyRenderLayers(AzRendererPipelineContext<K, T> context) {
        for (var renderLayer : renderLayerSupplier.get()) {
            renderLayer.preRender(context);
        }
    }

    /**
     * Called once per bone per frame, so it walks list-backed layer collections by index rather than allocating an
     * iterator each time.
     */
    public void applyRenderLayersForBone(AzRendererPipelineContext<K, T> context, AzBone bone) {
        var layers = renderLayerSupplier.get();

        if (layers instanceof List<AzRenderLayer<K, T>> list) {
            for (int i = 0, size = list.size(); i < size; i++) {
                list.get(i).renderForBone(context, bone);
            }
        } else {
            for (var renderLayer : layers) {
                renderLayer.renderForBone(context, bone);
            }
        }
    }

    protected void applyRenderLayers(AzRendererPipelineContext<K, T> context) {
        for (var renderLayer : renderLayerSupplier.get()) {
            renderLayer.render(context);
        }
    }
}
