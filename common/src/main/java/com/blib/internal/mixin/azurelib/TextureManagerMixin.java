package com.blib.internal.mixin.azurelib;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

import com.blib.api.client.texture.v1.AnimatableTexture;

@Mixin(value = TextureManager.class, priority = 900)
public abstract class TextureManagerMixin {

    @Shadow
    @Final
    private Map<ResourceLocation, AbstractTexture> byPath;

    @Shadow
    public abstract void register(ResourceLocation resourceLocation, AbstractTexture abstractTexture);

    @Inject(
        method = "getTexture(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/texture/AbstractTexture;",
        at = @At("HEAD")
    )
    private void wrapAnimatableTexture(ResourceLocation path, CallbackInfoReturnable<AbstractTexture> callback) {
        AbstractTexture existing = this.byPath.get(path);

        if (existing == null) {
            AnimatableTexture animatableTexture = new AnimatableTexture(path);

            register(path, animatableTexture);

            if (!animatableTexture.isAnimated())
                this.byPath.remove(path);
        }
    }

    /**
     * AzureLib 3.1.13 - textures that GAIN an animation from a resource pack now animate without a restart.
     * <p>
     * A texture first loaded without an animation is registered as a plain SimpleTexture, and a reload only reloads it
     * in place, so the wrapper above never sees it again. At the start of a reload, every plain SimpleTexture whose
     * resource now has an animation section is released; the next lookup wraps it as an AnimatableTexture. Done on the
     * render thread, which owns the texture map and the GPU textures.
     */
    @Inject(method = "reload", at = @At("HEAD"))
    private void blib$rewrapNewlyAnimated(
        net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier barrier,
        net.minecraft.server.packs.resources.ResourceManager resourceManager,
        net.minecraft.util.profiling.ProfilerFiller preparationProfiler,
        net.minecraft.util.profiling.ProfilerFiller reloadProfiler,
        java.util.concurrent.Executor backgroundExecutor,
        java.util.concurrent.Executor gameExecutor,
        CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> callback
    ) {
        com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> {
            var stale = new java.util.ArrayList<ResourceLocation>();

            for (var entry : this.byPath.entrySet()) {
                if (
                    entry.getValue().getClass() == net.minecraft.client.renderer.texture.SimpleTexture.class
                        && resourceManager.getResource(entry.getKey()).flatMap(resource -> {
                            try {
                                return resource.metadata()
                                    .getSection(
                                        net.minecraft.client.resources.metadata.animation.AnimationMetadataSection.SERIALIZER
                                    );
                            } catch (java.io.IOException exception) {
                                return java.util.Optional.empty();
                            }
                        }).isPresent()
                ) {
                    stale.add(entry.getKey());
                }
            }

            for (var location : stale) {
                var texture = this.byPath.remove(location);

                if (texture != null) {
                    texture.close();
                }
            }
        });
    }
}
