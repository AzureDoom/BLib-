package com.blib.internal.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.internal.client.animation.AzAnimatorAccessor;
import com.blib.internal.client.model.AzBakedModelCache;

public class AzProvider<K, T> {

    protected final Supplier<AzAnimator<K, T>> animatorSupplier;

    protected final BiFunction<Entity, T, ResourceLocation> modelLocationProvider;

    protected final Function<T, K> UUIDProvider;

    public AzProvider(
        Supplier<AzAnimator<K, T>> animatorSupplier,
        BiFunction<Entity, T, ResourceLocation> modelLocationProvider,
        Function<T, K> UUIDProvider
    ) {
        this.animatorSupplier = animatorSupplier;
        this.modelLocationProvider = modelLocationProvider;
        this.UUIDProvider = UUIDProvider;
    }

    public @Nullable AzBakedModel provideBakedModel(@Nullable Entity entity, @NotNull T animatable) {
        // Always have a safe fallback
        var modelLocation = modelLocationProvider.apply(entity, animatable);
        var shared = AzBakedModelCache.getInstance().getOrNull(modelLocation);

        if (shared == null) {
            return AzBakedModel.getDefault();
        }

        // Try to return the per-instance model if an animator/context already exists
        var animator = AzAnimatorAccessor.getOrNull(animatable);
        if (animator == null) {
            return shared; // <- avoid NPE: animator not created yet
        }

        var ctx = animator.context();
        if (ctx == null) {
            return shared; // <- avoid NPE: context not set yet this frame
        }

        var cache = ctx.boneCache();
        if (cache == null || cache.isEmpty()) {
            return shared; // <- cache isn't initialized yet
        }

        return cache.getBakedModel(); // <- the deep-copied, per-instance model
    }

    /**
     * Provides the {@link AzAnimator} for {@code animatable}, creating and caching one if needed.
     * <p>
     * If the shared baked model for the animatable has been replaced since its animator was created (for example by a
     * resource reload), the animator is rebuilt against the new model so its per-instance bone copy and tracks don't
     * keep pointing at stale bones.
     */
    public @Nullable AzAnimator<K, T> provideAnimator(@Nullable Entity entity, T animatable) {
        var accessor = AzAnimatorAccessor.<K, T>cast(animatable);
        var cachedAnimator = accessor.getAnimatorOrNull();

        var modelLocation = modelLocationProvider.apply(entity, animatable);
        var shared = AzBakedModelCache.getInstance().getOrNull(modelLocation);

        if (cachedAnimator == null) {
            return cacheAnimator(accessor, shared, animatable);
        }

        var ctx = cachedAnimator.context();

        if (ctx != null && shared != null) {
            var baked = ctx.boneCache().getBakedModel();

            if (!baked.getModelUUID().equals(shared.getModelUUID())) {
                // The model changed under this animator; rebuild it against the new one.
                return cacheAnimator(accessor, shared, animatable);
            }
        }

        return cachedAnimator;
    }

    private @Nullable AzAnimator<K, T> cacheAnimator(
        AzAnimatorAccessor<K, T> accessor,
        @Nullable AzBakedModel shared,
        T animatable
    ) {
        var cachedAnimator = animatorSupplier.get();

        if (cachedAnimator != null) {
            var ctx = cachedAnimator.getOrCreateContext(UUIDProvider.apply(animatable));

            if (shared != null) {
                ctx.boneCache().setActiveModel(shared); // setActiveModel deep-copies internally
            }

            cachedAnimator.registerTracks(cachedAnimator.getAnimationTrackContainer());
            accessor.setAnimator(cachedAnimator);
        }

        return cachedAnimator;
    }
}
