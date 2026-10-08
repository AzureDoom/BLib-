package com.blib.api.common.pathfinding.v1.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.blib.api.common.pathfinding.v1.terrain.TerrainClassifier;

/**
 * Global registry of terrain classification caches, keyed by Level and classifier identity. Entities with the same
 * classifier share a cache. Block change events invalidate all caches for the affected level.
 */
public final class TerrainCacheRegistry {

    private static final Map<Level, Map<TerrainClassifier, TerrainClassificationCache>> CACHES = new ConcurrentHashMap<>();

    /**
     * Gets or creates a classification cache for the given level and classifier. Entities with the same classifier
     * share the same cache.
     */
    public static TerrainClassificationCache getOrCreate(Level level, TerrainClassifier classifier) {
        return CACHES
            .computeIfAbsent(level, $ -> new ConcurrentHashMap<>())
            .computeIfAbsent(classifier, TerrainClassificationCache::new);
    }

    /**
     * Called when a block changes in the level. Invalidates the affected section in all caches for that level.
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        var levelCaches = CACHES.get(level);

        if (levelCaches == null) {
            return;
        }

        for (var cache : levelCaches.values()) {
            cache.invalidateBlock(pos);
        }
    }

    /**
     * Removes all caches for a level (call on level unload).
     * <p>
     * ⚠ Nothing calls this yet - see {@link #clear()}, which is what actually releases worlds today.
     */
    public static void onLevelUnload(Level level) {
        CACHES.remove(level);
    }

    /**
     * Drops every cache for every level. Called when the server stops (Sep 28).
     * <p>
     * ⚠⚠ WHY: the map holds each Level STRONGLY and {@link #onLevelUnload} is never called, so every world opened in a
     * session stayed in memory - chunks and all - until the game closed. Singleplayer creates new Level objects on each
     * world load, so leaving and re-entering worlds piled them up.
     * <p>
     * ⭐ SAFE AT SERVER STOP: this is only the LOOKUP table. Each navigator keeps its own reference to the cache it was
     * given, so a background path search still finishing after shutdown keeps working on its own cache. Nothing reads
     * the table again until a new world creates fresh caches, exactly as on a first load. During play it is never
     * called, so nothing changes in-game.
     */
    public static void clear() {
        CACHES.clear();
    }

    private TerrainCacheRegistry() {
        throw new UnsupportedOperationException();
    }
}
