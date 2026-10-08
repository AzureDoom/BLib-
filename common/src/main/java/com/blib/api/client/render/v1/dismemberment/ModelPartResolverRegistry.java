package com.blib.api.client.render.v1.dismemberment;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a vanilla model class (or interface) to a {@link ModelPartResolver}. The registry's
 * {@link #resolve(EntityModel, String)} method walks the model's class hierarchy and then its implemented interfaces,
 * picking the most specific registered resolver. Lookup results are cached per concrete model class so the hot render
 * path is a single map lookup once warm.
 * <p>
 * BLib registers built-in resolvers for {@code HumanoidModel}, {@code HierarchicalModel}, and {@code HeadedModel} (the
 * head-only fallback) at client init. Mods that use custom model classes can register additional resolvers here.
 */
public final class ModelPartResolverRegistry {

    private static final ModelPartResolver<Object> NONE = (model, name) -> null;

    private static final ConcurrentHashMap<Class<?>, ModelPartResolver<?>> REGISTERED = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Class<?>, ModelPartResolver<?>> RESOLVED_CACHE = new ConcurrentHashMap<>();

    private ModelPartResolverRegistry() {}

    public static <M> void register(Class<M> modelClass, ModelPartResolver<M> resolver) {
        REGISTERED.put(modelClass, resolver);
        // Invalidate the cache so previously-cached "no resolver" answers don't stick around after a late registration.
        RESOLVED_CACHE.clear();
    }

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger(ModelPartResolverRegistry.class);

    /** Model classes already reported as unresolvable - warn once, not once per frame. */
    private static final java.util.Set<Class<?>> WARNED_MODELS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static @Nullable ModelPart resolve(EntityModel<?> model, String partName) {
        var resolver = resolverFor(model.getClass());

        if (resolver == NONE) {
            return null;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        var typed = (ModelPartResolver) resolver;

        // 🚨🚨 A MODEL WE DO NOT RECOGNISE MUST NEVER CRASH THE CLIENT. Every built-in resolver walks a hard-coded
        // part hierarchy - model.root().getChild("root").getChild("body") and so on - and ModelPart.getChild THROWS
        // NoSuchElementException when a part is missing. Any mod or pack that supplies its own version of a vanilla
        // model therefore took the whole render thread down with "Can't find part root".
        //
        // ⚠⚠ Reported on an ALLAY HOLDING AN ITEM: ItemInHandLayer asked whether the arm was detached, the allay
        // resolver assumed vanilla's hierarchy, and the client crashed every time one came into view. Two separate
        // clients, with resource packs already ruled out.
        //
        // ⭐ FAILING TO RESOLVE A PART IS NOT AN ERROR CONDITION - it simply means this model cannot be dismembered,
        // which is the same answer as having no resolver at all. Caught here rather than in eleven separate
        // resolvers so nothing added later can reintroduce it.
        try {
            return typed.find(model, partName);
        } catch (RuntimeException exception) {
            if (WARNED_MODELS.add(model.getClass())) {
                LOGGER.warn(
                    "BLib: {} does not match the expected part layout - dismemberment is disabled for it.",
                    model.getClass().getName(),
                    exception
                );
            }

            return null;
        }
    }

    private static ModelPartResolver<?> resolverFor(Class<?> modelClass) {
        var cached = RESOLVED_CACHE.get(modelClass);

        if (cached != null) {
            return cached;
        }

        var found = lookup(modelClass);
        var toCache = found != null ? found : NONE;
        RESOLVED_CACHE.put(modelClass, toCache);
        return toCache;
    }

    private static @Nullable ModelPartResolver<?> lookup(Class<?> modelClass) {
        // Class chain first — most specific match wins.
        for (Class<?> c = modelClass; c != null && c != Object.class; c = c.getSuperclass()) {
            var r = REGISTERED.get(c);

            if (r != null) {
                return r;
            }
        }

        // Then interfaces (BFS so closer interfaces win over further ancestors).
        var visited = new HashSet<Class<?>>();
        var queue = new ArrayDeque<Class<?>>();
        queue.add(modelClass);

        while (!queue.isEmpty()) {
            var c = queue.poll();

            if (!visited.add(c)) {
                continue;
            }

            for (var iface : c.getInterfaces()) {
                var r = REGISTERED.get(iface);

                if (r != null) {
                    return r;
                }

                queue.add(iface);
            }

            var sup = c.getSuperclass();

            if (sup != null) {
                queue.add(sup);
            }
        }

        return null;
    }
}
