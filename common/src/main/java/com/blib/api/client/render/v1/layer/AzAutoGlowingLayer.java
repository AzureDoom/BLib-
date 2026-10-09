package com.blib.api.client.render.v1.layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

import com.blib.api.client.model.v1.AzBone;
import com.blib.api.client.render.v1.AzQuadFilter;
import com.blib.api.client.render.v1.AzRendererPipelineContext;
import com.blib.api.client.texture.v1.AzAbstractTexture;
import com.blib.api.client.texture.v1.AzGlowCoverage;
import com.blib.api.common.color.v1.Color;

/**
 * A {@link AzRenderLayer} dedicated to rendering the auto-generated glow layer. This utilizes texture files with the
 * <i>_glowmask</i> suffix to create glowing effects for models.
 * <p>
 * The glow can be tinted by passing a color (or a function of the render context) to the constructor:
 * </p>
 *
 * <pre>{@code
 * .addRenderLayer(new AzAutoGlowingLayer<>(Color.RED))
 * .addRenderLayer(new AzAutoGlowingLayer<>(context -> isAngry(context.animatable()) ? 0xFFFF4040 : 0xFFFFFFFF))
 * }</pre>
 * <p>
 * The tint is an ARGB color multiplied with the renderer's own color, so white leaves the glow untouched and lower
 * alpha fades the glow out. Tinting works best on white or gray glow textures, since the tint multiplies the texture's
 * colors.
 * </p>
 * <p>
 * Quads whose UV area is fully transparent in the glowmask are skipped (see {@link AzGlowCoverage}), and the layer is
 * skipped entirely when the glowmask has no visible pixels. Subclasses that override {@link #determineRenderType}
 * opt out of that filtering, since their texture may not be the glowmask.
 * </p>
 */
public class AzAutoGlowingLayer<K, T> implements AzRenderLayer<K, T> {

    private static final int NO_TINT = 0xFFFFFFFF;

    /** Whether a subclass picks its own render type, in which case the glowmask coverage may not apply. */
    private final boolean customRenderType = overridesDetermineRenderType(getClass());

    @Nullable
    private final ToIntFunction<AzRendererPipelineContext<K, T>> glowColor;

    /**
     * Creates a glow layer that renders the glow texture with the renderer's normal color.
     */
    public AzAutoGlowingLayer() {
        this.glowColor = null;
    }

    /**
     * Creates a glow layer tinted with a fixed color.
     */
    public AzAutoGlowingLayer(Color glowColor) {
        var argb = glowColor.argbInt();
        this.glowColor = argb == NO_TINT ? null : ignored -> argb;
    }

    /**
     * Creates a glow layer whose tint is computed per render, as an ARGB int. Return {@code 0xFFFFFFFF} for no tint.
     */
    public AzAutoGlowingLayer(@Nullable ToIntFunction<AzRendererPipelineContext<K, T>> glowColor) {
        this.glowColor = glowColor;
    }

    @Override
    public void preRender(AzRendererPipelineContext<K, T> context) {}

    @Override
    public void render(AzRendererPipelineContext<K, T> context) {
        var renderPipeline = context.rendererPipeline();
        var renderType = determineRenderType(context);

        var prevRenderType = context.renderType();
        var prevVertexConsumer = context.vertexConsumer();
        var prevRenderColor = context.renderColor();
        var prevPackedLight = context.packedLight();

        if (renderType != null) {
            var tint = getGlowColor(context);

            if (tint != NO_TINT) {
                context.setRenderColor(multiplyColors(prevRenderColor, tint));
            }

            var filter = glowFilter(context);

            // A glowmask with no visible pixels draws nothing; skip the whole re-render.
            if (!(filter instanceof AzGlowCoverage coverage && coverage.isNothing())) {
                context.setRenderType(renderType);
                context.setPackedLight(getPackedLight(context));
                context.setVertexConsumer(context.multiBufferSource().getBuffer(renderType));

                var prevFilter = context.quadFilter();
                context.setQuadFilter(filter);

                try {
                    renderPipeline.reRender(context);
                } finally {
                    context.setQuadFilter(prevFilter);
                }
            }
        }

        // Leave the context as later layers expect to find it.
        context.setRenderType(prevRenderType);
        context.setVertexConsumer(prevVertexConsumer);
        context.setRenderColor(prevRenderColor);
        context.setPackedLight(prevPackedLight);
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<K, T> context, AzBone bone) {}

    /**
     * The quad filter for the glow pass: the glowmask's coverage, or {@code null} to draw every quad.
     */
    protected @Nullable AzQuadFilter glowFilter(AzRendererPipelineContext<K, T> context) {
        if (customRenderType) {
            return null;
        }

        var texture = context.rendererPipeline()
            .config()
            .textureLocation(context.currentEntity(), context.animatable());
        return texture == null ? null : AzGlowCoverage.get(AzAbstractTexture.getEmissiveResource(texture));
    }

    private static boolean overridesDetermineRenderType(Class<?> type) {
        for (
            var current = type; current != null && current != AzAutoGlowingLayer.class; current = current
                .getSuperclass()
        ) {
            try {
                current.getDeclaredMethod("determineRenderType", AzRendererPipelineContext.class);
                return true;
            } catch (NoSuchMethodException ignored) {}
        }

        return false;
    }

    /**
     * The ARGB tint for this render. {@code 0xFFFFFFFF} means no tint.
     */
    protected int getGlowColor(AzRendererPipelineContext<K, T> context) {
        return glowColor == null ? NO_TINT : glowColor.applyAsInt(context);
    }

    private static int multiplyColors(int a, int b) {
        var alpha = ((a >>> 24) * (b >>> 24) + 127) / 255;
        var red = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) + 127) / 255;
        var green = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) + 127) / 255;
        var blue = ((a & 0xFF) * (b & 0xFF) + 127) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

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
