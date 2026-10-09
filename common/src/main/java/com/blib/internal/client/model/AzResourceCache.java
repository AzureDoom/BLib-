package com.blib.internal.client.model;

import com.blib.mod.BLib;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

public abstract class AzResourceCache {

    public static final Set<String> EXCLUDED_NAMESPACES = ObjectOpenHashSet.of(
        "moreplayermodels",
        "customnpcs",
        "creeperoverhaul",
        "geckolib",
        "gunsrpg",
        "born_in_chaos_v1",
        "neoforge",
        "brutality",
        "crazythings"
    );

    protected final <T> CompletableFuture<Map<ResourceLocation, T>> loadResources(
        Executor executor,
        ResourceManager resourceManager,
        String type,
        Function<ResourceLocation, T> loader
    ) {
        return CompletableFuture.supplyAsync(
                        () -> resourceManager.listResources(type, fileName -> fileName.toString().endsWith(".json")),
                        executor
                )
                .thenCompose(resources -> {
                    var tasks = new Object2ObjectOpenHashMap<ResourceLocation, CompletableFuture<T>>();

                    for (var resource : resources.keySet()) {
                        if (EXCLUDED_NAMESPACES.contains(resource.getNamespace().toLowerCase(Locale.ROOT)))
                            continue;

                        tasks.put(
                                resource,
                                CompletableFuture.supplyAsync(() -> loader.apply(resource), executor)
                                        .exceptionally(throwable -> {
                                            BLib.LOGGER.error("Failed to load {}: skipping", resource, throwable);
                                            return null;
                                        })
                        );
                    }

                    return CompletableFuture.allOf(tasks.values().toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> {
                                var loaded = new Object2ObjectOpenHashMap<ResourceLocation, T>(tasks.size());

                                for (var entry : tasks.entrySet()) {
                                    T value = entry.getValue().join();

                                    if (value != null)
                                        loaded.put(entry.getKey(), value);
                                }

                                return loaded;
                            });
                });
    }
}
