package com.blib.api.client.render.v1.layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.api.client.texture.v1.AzAbstractTexture;

public class AzAutoGlowingLayer<K, T> implements AzRenderLayer<K, T> {

    @Override
    public void preRender(AzRendererPipelineContext<K, T> context) {}

    @Override
    public void render(AzRendererPipelineContext<K, T> context) {
        var renderPipeline = context.rendererPipeline();
        var renderType = determineRenderType(context);

        if (renderType != null) {
            context.setRenderType(renderType);
            context.setPackedLight(getPackedLight(context));
            context.setVertexConsumer(context.multiBufferSource().getBuffer(renderType));

            renderPipeline.reRender(context);
        }
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<K, T> context, AzBone bone) {}

    protected int getPackedLight(AzRendererPipelineContext<K, T> context) {
        return LightTexture.FULL_SKY;
    }

    protected RenderType determineRenderType(AzRendererPipelineContext<K, T> context) {
        var animatable = context.animatable();
        var config = context.rendererPipeline().config();
        var textureLocation = config.textureLocation(context.currentEntity(), animatable);

        if (!(animatable instanceof Entity entity)) {
            // ⚠⚠ EMISSIVE, NOT THE BASE TEXTURE. This branch handles BLOCK ENTITIES, and it used to return
            // getRenderType(textureLocation) - the plain texture - while every entity branch below correctly uses
            // getEmissiveResource. The layer therefore redrew the WHOLE model with its base texture at FULL_SKY
            // light, straight over the properly lit model. FULL_SKY carries no BLOCK light, so at night, indoors or
            // in shade that overdraw is DARKER than the real lighting: every glowing block entity visibly dimmed the
            // moment its glowmask existed, which for mode-switched textures meant "it goes dark when it turns on".
            return AzAbstractTexture.getRenderType(AzAbstractTexture.getEmissiveResource(textureLocation));
        }

        var isInvisible = entity.isInvisible();
        var appearsGlowing = Minecraft.getInstance().shouldEntityAppearGlowing(entity);
        var player = Minecraft.getInstance().player;
        var isPlayerInvisible = entity.isInvisibleTo(player);

        if (isInvisible) {
            if (!isPlayerInvisible) {
                return RenderType.itemEntityTranslucentCull(AzAbstractTexture.getEmissiveResource(textureLocation));
            }
            if (appearsGlowing) {
                return RenderType.outline(AzAbstractTexture.getEmissiveResource(textureLocation));
            }
            return null;
        }

        if (appearsGlowing) {
            return AzAbstractTexture.getOutlineRenderType(textureLocation);
        }

        return AzAbstractTexture.getRenderType(textureLocation);
    }
}
