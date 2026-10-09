package com.blib.internal.client.model;

import com.just.core.functional.result.Err;
import com.just.core.functional.result.Result;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.internal.common.io.ResourceFileLoader;
import com.blib.internal.common.io.util.JsonUtil;
import com.blib.mod.BLib;

/**
 * Caches baked geo models. Models are loaded and baked into a fresh map on the background executor, then published with
 * {@link #apply(Map)} on the game thread after the reload's preparation barrier, so readers never see a partially
 * loaded map.
 */
public class AzBakedModelCache extends AzResourceCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzBakedModelCache.class);

    private static final AzBakedModelCache INSTANCE = new AzBakedModelCache();

    private static final String MODELS_PATH = "geo";

    private static final String DEFAULT_MODEL_PATH = MODELS_PATH + "/default_model.geo.json";

    private static final ResourceLocation DEFAULT_MODEL_RESOURCE_LOCATION = BLib.MOD.resources().createLocation(DEFAULT_MODEL_PATH);

    public static AzBakedModelCache getInstance() {
        return INSTANCE;
    }

    private volatile Map<ResourceLocation, AzBakedModel> bakedModels = Map.of();

    /**
     * The default model baked by the most recent {@link #loadModels} call, published as the global default in
     * {@link #apply(Map)}. Written on the background executor; the reload future's completion makes it visible to the
     * game thread that calls {@code apply}.
     */
    private @Nullable AzBakedModel pendingDefaultModel;

    private AzBakedModelCache() {}

    /**
     * Loads and bakes every geo file into a new map without touching the live cache or the global default model.
     * Publish the result with {@link #apply(Map)} on the game thread.
     * <p>
     * A model that fails to load falls back to the default model when that loaded; otherwise it is left out of the map.
     * </p>
     */
    public CompletableFuture<Map<ResourceLocation, AzBakedModel>> loadModels(
            Executor backgroundExecutor,
            ResourceManager resourceManager
    ) {
        return CompletableFuture.supplyAsync(() -> loadDefaultBakedModel(resourceManager), backgroundExecutor)
                .thenCompose(defaultBakedModelResult -> {
                    pendingDefaultModel = defaultBakedModelResult.isOk() ? defaultBakedModelResult.unwrap() : null;

                    return loadResources(
                            backgroundExecutor,
                            resourceManager,
                            MODELS_PATH,
                            resourceLocation -> {
                                // The default model is published through AzBakedModel.setDefault, not looked up by id.
                                if (Objects.equals(DEFAULT_MODEL_RESOURCE_LOCATION, resourceLocation)) {
                                    return null;
                                }

                                var result = loadAndBakeModel(resourceManager, resourceLocation, defaultBakedModelResult);

                                // Errors were already logged; null leaves the model out of the map.
                                return result.isOk() ? result.unwrap() : null;
                            }
                    );
                });
    }

    /**
     * Replaces the live cache with a freshly loaded one and publishes the default model baked alongside it. Entries from
     * resources that no longer exist are dropped. Call on the game thread.
     */
    public void apply(Map<ResourceLocation, AzBakedModel> models) {
        var defaultModel = pendingDefaultModel;
        pendingDefaultModel = null;

        // TODO: This is semantically misleading at best and brittle at worst. "Defaults" should never change.
        if (defaultModel != null) {
            AzBakedModel.setDefault(defaultModel);
        }

        this.bakedModels = models;
    }

    public @Nullable AzBakedModel getOrNull(ResourceLocation resourceLocation) {
        return bakedModels.get(resourceLocation);
    }

    /**
     * Snapshot of every resource id currently baked into the cache. Returned as a sorted list so picker UIs display
     * entries deterministically across runs. The cache is populated at resource-reload time, so the list reflects the
     * models available to the current resource packs.
     */
    public List<ResourceLocation> allModelIds() {
        var ids = new ArrayList<>(bakedModels.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        return ids;
    }

    private Result<AzBakedModel, ResourceFileLoader.ObjectLoadError> loadDefaultBakedModel(ResourceManager resourceManager) {
        var defaultModelResult = ResourceFileLoader.loadObjectFromFile(
                JsonUtil.GEO_GSON,
                Model.class,
                DEFAULT_MODEL_RESOURCE_LOCATION,
                resourceManager
        );

        var defaultBakedModelResult = defaultModelResult.map(
                model -> bakeModel(DEFAULT_MODEL_RESOURCE_LOCATION, model)
        );

        if (defaultBakedModelResult instanceof Err<AzBakedModel, ResourceFileLoader.ObjectLoadError> err) {
            err.unwrapErr()
                    .log(LOGGER, Level.ERROR, "Failed to load default model '{}'.", "", DEFAULT_MODEL_RESOURCE_LOCATION);
        }

        return defaultBakedModelResult;
    }

    private Result<AzBakedModel, ResourceFileLoader.ObjectLoadError> loadAndBakeModel(
            ResourceManager resourceManager,
            ResourceLocation resourceLocation,
            Result<AzBakedModel, ResourceFileLoader.ObjectLoadError> defaultBakedModelResult
    ) {
        var result = ResourceFileLoader.loadObjectFromFile(JsonUtil.GEO_GSON, Model.class, resourceLocation, resourceManager);

        if (result.isErr()) {
            var error = result.unwrapErr();
            var postMessage = defaultBakedModelResult.isOk() ? "Using default baked model as a fallback." : "";

            error.log(LOGGER, Level.WARN, "Failed to load model '{}'.", postMessage, resourceLocation);

            return defaultBakedModelResult;
        }

        var model = result.unwrap();

        return Result.ok(bakeModel(resourceLocation, model));
    }

    private AzBakedModel bakeModel(ResourceLocation resource, Model model) {
        return AzBakedModelFactoryRegistry
                .getForNamespace(resource.getNamespace())
                .constructGeoModel(GeometryTree.fromModel(model));
    }
}