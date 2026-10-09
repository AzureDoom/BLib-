package com.blib.api.client.render.v1.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.blib.api.client.animation.v1.animator.AzEntityAnimator;
import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.client.render.v1.entity.pipeline.AzEntityRendererPipeline;
import com.blib.api.client.render.v1.lod.AzLodConfig;
import com.blib.api.client.render.v1.lod.AzLodManager;
import com.blib.internal.client.render.AzProvider;
import com.blib.internal.client.render.entity.AzEntityNameRenderUtil;

public abstract class AzEntityRenderer<T extends Entity> extends EntityRenderer<T> {

    protected final AzEntityRendererConfig<T> config;

    protected final AzProvider<UUID, T> provider;

    protected final AzEntityRendererPipeline<T> rendererPipeline;

    @Nullable
    private AzEntityAnimator<T> reusedAzEntityAnimator;

    /**
     * Per-entity LOD state. Weakly keyed so entries go away with their entity instead of accumulating for every entity
     * this renderer has ever drawn.
     */
    private final Map<T, AzLodManager> lodManagers = new WeakHashMap<>();

    private boolean animateThisFrame = true;

    protected AzEntityRenderer(AzEntityRendererConfig<T> config, EntityRendererProvider.Context context) {
        super(context);
        this.config = config;
        this.provider = new AzProvider<>(config::createAnimator, config::modelLocation, Entity::getUUID);
        this.rendererPipeline = createPipeline(config);
    }

    private static boolean ownsModel(@Nullable AzEntityAnimator<?> animator, AzBakedModel model) {
        if (animator == null) {
            return false;
        }

        var context = animator.context();
        return context != null && context.boneCache().getBakedModel() == model;
    }

    public AzEntityRendererPipeline<T> createPipeline(AzEntityRendererConfig<T> config) {
        return new AzEntityRendererPipeline<>(config, this);
    }

    @Override
    public final @NotNull ResourceLocation getTextureLocation(@NotNull T animatable) {
        return config.textureLocation(animatable, animatable);
    }

    public void superRender(
        @NotNull T entity,
        float entityYaw,
        float partialTick,
        @NotNull PoseStack poseStack,
        @NotNull MultiBufferSource bufferSource,
        int packedLight
    ) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public void render(
        @NotNull T entity,
        float entityYaw,
        float partialTick,
        @NotNull PoseStack poseStack,
        @NotNull MultiBufferSource bufferSource,
        int packedLight
    ) {
        var cachedEntityAnimator = (AzEntityAnimator<T>) provider.provideAnimator(entity, entity);
        var azBakedModel = provider.provideBakedModel(entity, entity);

        // Point the renderer's current animator reference to the cached entity animator before rendering.
        reusedAzEntityAnimator = cachedEntityAnimator;

        animateThisFrame = updateLod(entity, cachedEntityAnimator, azBakedModel);

        // Execute the render pipeline.
        rendererPipeline.render(
            poseStack,
            azBakedModel,
            entity,
            bufferSource,
            null,
            null,
            entityYaw,
            partialTick,
            packedLight
        );
    }

    protected boolean updateLod(T entity, @Nullable AzEntityAnimator<T> animator, @Nullable AzBakedModel bakedModel) {
        var lodConfig = config.lodConfig();

        if (lodConfig == AzLodConfig.DISABLED || bakedModel == null || animator == null) {
            return true;
        }

        var context = animator.context();

        if (context == null || context.boneCache().isEmpty() || context.boneCache().getBakedModel() != bakedModel) {
            return true;
        }

        return lodManagers.computeIfAbsent(entity, ignored -> new AzLodManager(lodConfig)).update(entity, bakedModel);
    }

    @Override
    protected float getShadowRadius(@NotNull T entity) {
        return config.shadowRadius(entity);
    }

    @Override
    public boolean shouldShowName(@NotNull T entity) {
        return AzEntityNameRenderUtil.shouldShowName(entityRenderDispatcher, entity);
    }

    // Proxy method override for super.getBlockLightLevel external access.
    @Override
    public int getBlockLightLevel(@NotNull T entity, @NotNull BlockPos pos) {
        return super.getBlockLightLevel(entity, pos);
    }

    public AzEntityAnimator<T> getAnimator() {
        return reusedAzEntityAnimator;
    }

    /**
     * Whether the animator should run for the entity currently being rendered. {@code false} when animation LOD is
     * holding the entity's last pose this frame.
     */
    public boolean shouldAnimateThisFrame() {
        return animateThisFrame;
    }

    public AzEntityRendererConfig<T> config() {
        return config;
    }
}
