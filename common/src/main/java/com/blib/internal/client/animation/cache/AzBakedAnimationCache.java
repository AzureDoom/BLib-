package com.blib.internal.client.animation.cache;

import com.blib.internal.common.molang.MolangParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.blib.internal.client.animation.primitive.AzBakedAnimations;
import com.blib.internal.client.model.AzResourceCache;
import com.blib.internal.common.io.ResourceFileLoader;
import com.blib.internal.common.io.util.JsonUtil;

/**
 * Caches baked animation files. Animations are loaded into a fresh map on the background executor, then published with
 * {@link #apply(Map)} on the game thread after the reload's preparation barrier, so readers never see a partially
 * loaded map.
 */
public class AzBakedAnimationCache extends AzResourceCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzBakedAnimationCache.class);

    private static final AzBakedAnimationCache INSTANCE = new AzBakedAnimationCache();

    public static AzBakedAnimationCache getInstance() {
        return INSTANCE;
    }

    private volatile Map<ResourceLocation, AzBakedAnimations> bakedAnimations = Map.of();

    private AzBakedAnimationCache() {}

    /**
     * Loads every animation file into a new map without touching the live cache. Publish the result with
     * {@link #apply(Map)} on the game thread. A file that fails to load is logged and left out of the map.
     */
    // TODO: Why is there no default animation file here?
    public CompletableFuture<Map<ResourceLocation, AzBakedAnimations>> loadAnimations(
            Executor backgroundExecutor,
            ResourceManager resourceManager
    ) {
        MolangParser.clearExpressionCache();

        return loadResources(
                backgroundExecutor,
                resourceManager,
                "animations",
                // TODO: Process result here, use default animation fallback as necessary.
                resourceLocation -> {
                    var result = ResourceFileLoader.loadObjectFromFile(
                            JsonUtil.GEO_GSON,
                            AzBakedAnimations.class,
                            resourceLocation,
                            resourceManager
                    );

                    if (result.isErr()) {
                        result.unwrapErr()
                                .log(LOGGER, Level.WARN, "Failed to load animations '{}'.", "", resourceLocation);
                        return null;
                    }

                    return result.unwrap();
                }
        );
    }

    /**
     * Replaces the live cache with a freshly loaded one. Entries from resources that no longer exist are dropped. Call
     * on the game thread.
     */
    public void apply(Map<ResourceLocation, AzBakedAnimations> animations) {
        this.bakedAnimations = animations;
    }

    public @Nullable AzBakedAnimations getOrNull(ResourceLocation resourceLocation) {
        return bakedAnimations.get(resourceLocation);
    }
}