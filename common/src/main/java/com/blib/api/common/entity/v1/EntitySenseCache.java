package com.blib.api.common.entity.v1;

import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.blib.api.common.util.v1.RefreshPolicy;
import com.blib.internal.common.perf.BLibPerfProfiler;
import com.blib.mod.common.registry.init.BLibGameRules;

public class EntitySenseCache {

    private final Entity entity;

    private final Map<Class<? extends Entity>, List<Entity>> entitiesByClassMap;

    /**
     * Results of {@link #getByClass} for a class the scan did not bucket on its own (a superclass or interface).
     * <p>
     * ⚠⚠ Oct 5 - THESE USED TO LIVE IN {@code entitiesByClassMap}, beside the per-concrete-class scan buckets. A later,
     * wider query walks that map collecting every assignable key - and a cached superclass result IS assignable - so
     * {@code getByClass(Mob)} followed by {@code getByClass(LivingEntity)} returned every mob TWICE. Kept apart, the
     * bucket map only ever holds what the scan put in it. (Old behaviour: {@code /gamerule blibSenseCacheFixes false}.)
     * </p>
     */
    private final Map<Class<?>, List<Entity>> queryResultsByClassMap;

    private final Map<TagKey<EntityType<?>>, List<Entity>> entitiesByTagMap;

    private final Map<EntityType<?>, List<Entity>> entitiesByTypeMap;

    private final Map<Item, List<ItemEntity>> itemEntitiesByItemMap;

    private final RefreshPolicy<EntitySenseCache> refreshPolicy;

    private final ToIntFunction<EntitySenseCache> scanRadiusFunction;

    private final Set<TagKey<EntityType<?>>> trackedTags;

    private int lastSenseTick;

    /** blibSenseCacheFixes, sampled at each refresh so a per-query lookup is not paid on every read. */
    private boolean fixesEnabled = true;

    private EntitySenseCache(
        Entity entity,
        RefreshPolicy<EntitySenseCache> refreshPolicy,
        ToIntFunction<EntitySenseCache> scanRadiusFunction,
        Set<TagKey<EntityType<?>>> trackedTags
    ) {
        this.entity = entity;
        this.entitiesByClassMap = new HashMap<>();
        this.queryResultsByClassMap = new HashMap<>();
        this.entitiesByTagMap = new HashMap<>();
        this.entitiesByTypeMap = new HashMap<>();
        this.itemEntitiesByItemMap = new HashMap<>();
        this.refreshPolicy = refreshPolicy;
        this.scanRadiusFunction = scanRadiusFunction;
        this.trackedTags = trackedTags;
        this.lastSenseTick = 0;
    }

    public static Builder builder(Entity entity) {
        return new Builder(entity);
    }

    public void clear() {
        entitiesByClassMap.clear();
        queryResultsByClassMap.clear();
        entitiesByTypeMap.clear();
        itemEntitiesByItemMap.clear();
        entitiesByTagMap.clear();
    }

    /**
     * Returns all nearby entities that are instances of the given class. Supports querying by superclass (e.g.,
     * {@code LivingEntity.class} will return all living entities). Results are cached for O(1) subsequent lookups.
     */
    @SuppressWarnings("unchecked")
    public <T extends Entity> List<T> getByClass(Class<T> entityClass) {
        tryPopulateCache();

        if (fixesEnabled) {
            return (List<T>) getByClassSeparated(entityClass);
        }

        // Check if we already have a cached result for this class.
        var cached = entitiesByClassMap.get(entityClass);

        if (cached != null) {
            return (List<T>) cached;
        }

        // Collect all entities that are assignable to the queried class.
        List<Entity> collectedEntities = null;

        for (var entry : entitiesByClassMap.entrySet()) {
            if (!entityClass.isAssignableFrom(entry.getKey())) {
                continue;
            }

            if (collectedEntities == null) {
                collectedEntities = new ArrayList<>();
            }

            collectedEntities.addAll(entry.getValue());
        }

        // Cache the result for future lookups.
        if (collectedEntities == null) {
            collectedEntities = List.of();
        }

        entitiesByClassMap.put(entityClass, collectedEntities);

        return (List<T>) collectedEntities;
    }

    private List<Entity> getByClassSeparated(Class<?> entityClass) {
        // ⚠ No shortcut to the exact-class bucket: a query for a concrete class must still include its subclasses
        // (getByClass(Zombie) wants zombie villagers too), which the old code silently dropped whenever the exact
        // class had a bucket of its own.
        var cached = queryResultsByClassMap.get(entityClass);

        if (cached != null) {
            return cached;
        }

        List<Entity> collectedEntities = null;

        for (var entry : entitiesByClassMap.entrySet()) {
            if (!entityClass.isAssignableFrom(entry.getKey())) {
                continue;
            }

            if (collectedEntities == null) {
                collectedEntities = new ArrayList<>();
            }

            collectedEntities.addAll(entry.getValue());
        }

        if (collectedEntities == null) {
            collectedEntities = List.of();
        }

        queryResultsByClassMap.put(entityClass, collectedEntities);

        return collectedEntities;
    }

    /**
     * Returns nearby dropped items of the given item.
     * <p>
     * ⚠⚠ Oct 5 - THIS NEVER REFRESHED THE SCAN. Every other query calls {@code tryPopulateCache} first; this one read
     * the map as it stood. So its answer depended on which sensor happened to run before it: empty if nothing else had
     * queried yet, and otherwise as old as that other query's refresh. avp_human's torch, water-bucket and totem
     * sensors read through here. (Old behaviour: {@code /gamerule blibSenseCacheFixes false}.)
     * </p>
     */
    public List<ItemEntity> getByItem(Item item) {
        if (entity.level().getGameRules().getBoolean(BLibGameRules.SENSE_CACHE_FIXES)) {
            tryPopulateCache();
        }

        return itemEntitiesByItemMap.getOrDefault(item, List.of());
    }

    public List<Entity> getByTag(TagKey<EntityType<?>> tagKey) {
        tryPopulateCache();

        if (trackedTags.contains(tagKey)) {
            return entitiesByTagMap.getOrDefault(tagKey, List.of());
        }

        return getByTagMatch(tagKey);
    }

    private @NotNull List<Entity> getByTagMatch(TagKey<EntityType<?>> tagKey) {
        List<Entity> collectedEntities = null;

        for (var entityType : entitiesByTypeMap.keySet()) {
            if (!entityType.is(tagKey)) {
                continue;
            }

            var entities = entitiesByTypeMap.getOrDefault(entityType, List.of());

            if (collectedEntities == null) {
                collectedEntities = new ArrayList<>();
            }

            collectedEntities.addAll(entities);
        }

        return collectedEntities == null
            ? List.of()
            : collectedEntities;
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> List<T> getByType(EntityType<T> entityType) {
        tryPopulateCache();

        return (List<T>) entitiesByTypeMap.getOrDefault(entityType, List.of());
    }

    public Entity getEntity() {
        return entity;
    }

    public int getLastSenseTick() {
        return lastSenseTick;
    }

    private void tryPopulateCache() {
        if (!refreshPolicy.shouldRefresh(this)) {
            return;
        }

        clear();

        var profiling = BLibPerfProfiler.isActive();
        var scanStartNanos = profiling ? System.nanoTime() : 0L;
        fixesEnabled = entity.level().getGameRules().getBoolean(BLibGameRules.SENSE_CACHE_FIXES);

        var scanRadius = scanRadiusFunction.applyAsInt(this);
        var diameter = scanRadius * 2;
        var scanArea = AABB.ofSize(entity.getEyePosition(), diameter, diameter, diameter);

        var entities = entity.level().getEntitiesOfClass(Entity.class, scanArea);

        for (var scanned : entities) {
            entitiesByClassMap.computeIfAbsent(scanned.getClass(), $ -> new ArrayList<>())
                .add(scanned);
            entitiesByTypeMap.computeIfAbsent(scanned.getType(), $ -> new ArrayList<>())
                .add(scanned);

            if (scanned instanceof ItemEntity itemEntity) {
                itemEntitiesByItemMap.computeIfAbsent(itemEntity.getItem().getItem(), $ -> new ArrayList<>())
                    .add(itemEntity);
            }

            for (var tagKey : trackedTags) {
                if (scanned.getType().is(tagKey)) {
                    entitiesByTagMap.computeIfAbsent(tagKey, $ -> new ArrayList<>())
                        .add(scanned);
                }
            }
        }

        this.lastSenseTick = entity.tickCount;

        if (profiling) {
            BLibPerfProfiler.recordScan(entity, entities.size(), System.nanoTime() - scanStartNanos);
        }
    }

    public static class Builder {

        private final Entity entity;

        private final Set<TagKey<EntityType<?>>> trackedTags;

        private RefreshPolicy<EntitySenseCache> refreshPolicy;

        private ToIntFunction<EntitySenseCache> scanRadiusFunction;

        private Builder(Entity entity) {
            this.entity = entity;
            this.trackedTags = new HashSet<>();

            this.refreshPolicy = context -> context.getEntity().tickCount > context.getLastSenseTick() + 20;
            this.scanRadiusFunction = $ -> 16;
        }

        public Builder withRefreshPolicy(RefreshPolicy<EntitySenseCache> refreshPolicy) {
            this.refreshPolicy = refreshPolicy;
            return this;
        }

        public Builder withScanRadius(int scanRadius) {
            this.scanRadiusFunction = $ -> scanRadius;
            return this;
        }

        public Builder withScanRadius(ToIntFunction<EntitySenseCache> scanRadiusFunction) {
            this.scanRadiusFunction = scanRadiusFunction;
            return this;
        }

        public Builder addTrackedTag(TagKey<EntityType<?>> tagKey) {
            this.trackedTags.add(tagKey);
            return this;
        }

        public EntitySenseCache build() {
            return new EntitySenseCache(entity, refreshPolicy, scanRadiusFunction, trackedTags);
        }
    }
}
