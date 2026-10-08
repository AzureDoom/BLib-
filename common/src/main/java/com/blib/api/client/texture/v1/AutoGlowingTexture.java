package com.blib.api.client.texture.v1;

import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.blib.api.BLibAPI;
import com.blib.mod.BLib;

public class AutoGlowingTexture extends AzAbstractTexture {

    protected final ResourceLocation textureBase;

    protected final ResourceLocation glowLayer;

    /** AzureLib 3.1.13 - {@return whether the texture has an animation section in its .mcmeta} */
    private static boolean hasAnimationMetadata(ResourceManager resourceManager, ResourceLocation texture) {
        return resourceManager.getResource(texture).flatMap(resource -> {
            try {
                return resource.metadata()
                    .getSection(net.minecraft.client.resources.metadata.animation.AnimationMetadataSection.SERIALIZER);
            } catch (java.io.IOException exception) {
                return java.util.Optional.empty();
            }
        }).isPresent();
    }

    public AutoGlowingTexture(ResourceLocation originalLocation, ResourceLocation location) {
        super(originalLocation);
        this.textureBase = originalLocation;
        this.glowLayer = location;
    }

    @Nullable
    @Override
    protected RenderCall loadTexture(ResourceManager resourceManager, Minecraft mc) throws IOException {
        AbstractTexture originalTexture;

        try {
            // AzureLib 3.1.13: if another mod (GeckoLib, for one) replaced the base texture's animated wrapper with its
            // own, the glowmask was built from the whole strip of frames squashed together and the glow parts stayed
            // visible on the base. An animated base that is not ours is re-registered as ours first.
            originalTexture = mc.submit(() -> {
                var textureManager = mc.getTextureManager();
                var texture = textureManager.getTexture(this.textureBase);

                if (!(texture instanceof AnimatableTexture) && hasAnimationMetadata(resourceManager, this.textureBase)) {
                    var ours = new AnimatableTexture(this.textureBase);
                    textureManager.register(this.textureBase, ours);
                    texture = ours;
                }

                return texture;
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to load original texture: " + this.textureBase, e);
        }

        Resource textureBaseResource = resourceManager.getResource(this.textureBase).get();
        NativeImage baseImage = originalTexture instanceof DynamicTexture dynamicTexture
            ? dynamicTexture.getPixels()
            : NativeImage.read(textureBaseResource.open());
        NativeImage glowImage = null;
        Optional<TextureMetadataSection> textureBaseMeta = textureBaseResource.metadata()
            .getSection(TextureMetadataSection.SERIALIZER);
        boolean blur = textureBaseMeta.isPresent() && textureBaseMeta.get().isBlur();
        boolean clamp = textureBaseMeta.isPresent() && textureBaseMeta.get().isClamp();

        try {
            Optional<Resource> glowLayerResource = resourceManager.getResource(this.glowLayer);
            GeoGlowingTextureMeta glowLayerMeta = null;

            if (glowLayerResource.isPresent()) {
                glowImage = NativeImage.read(glowLayerResource.get().open());

                if (baseImage.getWidth() != glowImage.getWidth() || baseImage.getHeight() != glowImage.getHeight()) {
                    BLib.LOGGER.error(
                        "Glowmask size mismatch with base texture. Base size: {}x{}, Glowmask size: {}x{}, Location: {}",
                        baseImage.getWidth(),
                        baseImage.getHeight(),
                        glowImage.getWidth(),
                        glowImage.getHeight(),
                        this.glowLayer
                    );
                    return null;
                }

                glowLayerMeta = GeoGlowingTextureMeta.fromExistingImage(glowImage);
            } else {
                Optional<GeoGlowingTextureMeta> meta = textureBaseResource.metadata()
                    .getSection(GeoGlowingTextureMeta.DESERIALIZER);

                if (meta.isPresent()) {
                    glowLayerMeta = meta.get();
                    glowImage = new NativeImage(baseImage.getWidth(), baseImage.getHeight(), true);
                }
            }

            if (glowLayerMeta != null) {
                glowLayerMeta.createImageMask(baseImage, glowImage);

                if (BLibAPI.isDevelopmentEnvironment()) {
                    printDebugImageToDisk(this.textureBase, baseImage);
                    printDebugImageToDisk(this.glowLayer, glowImage);
                }
            }
        } catch (IOException e) {
            BLib.LOGGER.warn("Resource failed to open for glowlayer meta: {}", this.glowLayer, e);
        }

        NativeImage mask = glowImage != null ? glowImage : copyImage(baseImage);

        boolean animated = originalTexture instanceof AnimatableTexture animatableTexture
            && animatableTexture
                .isAnimated();

        if (animated)
            ((AnimatableTexture) originalTexture).animationContents.animatedTexture.setGlowMaskTexture(
                this,
                baseImage,
                mask
            );

        return () -> {
            if (!animated)
                uploadSimple(getId(), mask, blur, clamp);

            if (originalTexture instanceof DynamicTexture dynamicTexture) {
                dynamicTexture.upload();
            } else {
                uploadSimple(originalTexture.getId(), baseImage, blur, clamp);
            }
        };
    }

    private static NativeImage copyImage(NativeImage image) {
        NativeImage copy = new NativeImage(image.getWidth(), image.getHeight(), true);
        copy.copyFrom(image);
        return copy;
    }
}
