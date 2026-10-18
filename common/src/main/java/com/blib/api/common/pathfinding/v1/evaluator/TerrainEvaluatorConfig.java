package com.blib.api.common.pathfinding.v1.evaluator;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.blib.api.common.pathfinding.v1.terrain.TerrainClassifier;
import com.blib.api.common.pathfinding.v1.terrain.TerrainClassifiers;
import com.blib.api.common.pathfinding.v1.terrain.TerrainType;

/**
 * Describes an entity's pathfinding capabilities: supported terrain types with costs and physical constraints.
 */
public final class TerrainEvaluatorConfig {

    private static final float DEFAULT_COST = 1.0f;

    private static final int DEFAULT_MAX_FALL_DISTANCE = 3;

    private static final int DEFAULT_MAX_STEP_HEIGHT = 1;

    private static final int DEFAULT_ENTITY_WIDTH = 1;

    private static final int DEFAULT_ENTITY_HEIGHT = 2;

    private static final float DEFAULT_WALL_ADJACENCY_MALUS = 0.5f;

    private final Map<TerrainType, Supplier<Float>> terrainCostSuppliers;

    private final TerrainClassifier terrainClassifier;

    private final int entityWidth;

    private final int entityHeight;

    private final int maxFallDistance;

    private final int maxStepHeight;

    private final boolean canOpenDoors;

    private final boolean canWalkOverFences;

    private final boolean canFly;

    private final PathCrawlConfig crawlConfig;

    private final PathWaterConfig waterConfig;

    private final PathBlockBreakingConfig blockBreakingConfig;

    private final PathLeapConfig leapConfig;

    private final boolean stepUpBlockBreaking;

    private final float wallAdjacencyMalus;

    private TerrainEvaluatorConfig(
        Map<TerrainType, Supplier<Float>> terrainCostSuppliers,
        TerrainClassifier terrainClassifier,
        int entityWidth,
        int entityHeight,
        int maxFallDistance,
        int maxStepHeight,
        boolean canOpenDoors,
        boolean canWalkOverFences,
        boolean canFly,
        PathCrawlConfig crawlConfig,
        PathWaterConfig waterConfig,
        PathBlockBreakingConfig blockBreakingConfig,
        PathLeapConfig leapConfig,
        boolean stepUpBlockBreaking,
        float wallAdjacencyMalus
    ) {
        this.terrainCostSuppliers = Map.copyOf(terrainCostSuppliers);
        this.terrainClassifier = terrainClassifier;
        this.entityWidth = entityWidth;
        this.entityHeight = entityHeight;
        this.maxFallDistance = maxFallDistance;
        this.maxStepHeight = maxStepHeight;
        this.canOpenDoors = canOpenDoors;
        this.canWalkOverFences = canWalkOverFences;
        this.canFly = canFly;
        this.crawlConfig = crawlConfig != null ? crawlConfig : PathCrawlConfig.DISABLED;
        this.waterConfig = waterConfig != null ? waterConfig : PathWaterConfig.DISABLED;
        this.blockBreakingConfig = blockBreakingConfig != null ? blockBreakingConfig : PathBlockBreakingConfig.DISABLED;
        this.leapConfig = leapConfig != null ? leapConfig : PathLeapConfig.DISABLED;
        this.stepUpBlockBreaking = stepUpBlockBreaking;
        this.wallAdjacencyMalus = Math.max(0.0f, wallAdjacencyMalus);
    }

    public static Builder builder() {
        return new Builder();
    }

    public float getCost(TerrainType terrainType) {
        var supplier = terrainCostSuppliers.get(terrainType);

        if (supplier == null) {
            return Float.MAX_VALUE;
        }

        return supplier.get();
    }

    public Set<TerrainType> getSupportedTerrains() {
        return terrainCostSuppliers.keySet();
    }

    public TerrainClassifier getTerrainClassifier() {
        return terrainClassifier;
    }

    public int getEntityWidth() {
        return entityWidth;
    }

    public int getEntityHeight() {
        return entityHeight;
    }

    public int getMaxFallDistance() {
        return maxFallDistance;
    }

    public int getMaxStepHeight() {
        return maxStepHeight;
    }

    /**
     * Soft per-side cost added to standing ground nodes that sit directly beside a two-high solid wall, biasing paths
     * toward the center of walkways and away from inner corner faces. Zero disables the bias entirely.
     */
    public float getWallAdjacencyMalus() {
        return wallAdjacencyMalus;
    }

    public boolean canOpenDoors() {
        return canOpenDoors;
    }

    public boolean canWalkOverFences() {
        return canWalkOverFences;
    }

    public boolean canFly() {
        return canFly;
    }

    public PathCrawlConfig getCrawlConfig() {
        return crawlConfig;
    }

    public PathWaterConfig getWaterConfig() {
        return waterConfig;
    }

    public PathBlockBreakingConfig getBlockBreakingConfig() {
        return blockBreakingConfig;
    }

    /** {@return how far this mob may leap across a gap} {@link PathLeapConfig#DISABLED} unless the mob set one. */
    public PathLeapConfig getLeapConfig() {
        return leapConfig;
    }

    /**
     * {@return whether a blocked one-block step UP may be dug} Off unless the mob asks: a mob that breaks blocks has
     * only ever dug level moves, and turning this on for every breaker would have it carving steps into hillsides.
     */
    public boolean isStepUpBlockBreaking() {
        return stepUpBlockBreaking;
    }

    public static final class Builder {

        private final Map<TerrainType, Supplier<Float>> terrainCostSuppliers;

        private TerrainClassifier terrainClassifier;

        private int entityWidth;

        private int entityHeight;

        private int maxFallDistance;

        private int maxStepHeight;

        private boolean canOpenDoors;

        private boolean canWalkOverFences;

        private boolean canFly;

        private PathCrawlConfig crawlConfig;

        private PathWaterConfig waterConfig;

        private PathBlockBreakingConfig blockBreakingConfig;

        private PathLeapConfig leapConfig = PathLeapConfig.DISABLED;

        private boolean stepUpBlockBreaking;

        private float wallAdjacencyMalus = DEFAULT_WALL_ADJACENCY_MALUS;

        private Builder() {
            this.terrainCostSuppliers = new EnumMap<>(TerrainType.class);
            this.terrainClassifier = TerrainClassifiers.GROUND_ONLY;
            this.entityWidth = DEFAULT_ENTITY_WIDTH;
            this.entityHeight = DEFAULT_ENTITY_HEIGHT;
            this.maxFallDistance = DEFAULT_MAX_FALL_DISTANCE;
            this.maxStepHeight = DEFAULT_MAX_STEP_HEIGHT;
            this.crawlConfig = PathCrawlConfig.DISABLED;
            this.waterConfig = PathWaterConfig.DISABLED;
            this.blockBreakingConfig = PathBlockBreakingConfig.DISABLED;
        }

        public Builder addTerrain(TerrainType type, float cost) {
            terrainCostSuppliers.put(type, () -> cost);
            return this;
        }

        public Builder addTerrain(TerrainType type, Supplier<Float> costSupplier) {
            terrainCostSuppliers.put(type, costSupplier);
            return this;
        }

        public Builder addTerrainFromSpeedRatio(TerrainType type, float groundSpeed, float terrainSpeed) {
            terrainCostSuppliers.put(type, () -> groundSpeed / terrainSpeed);
            return this;
        }

        public Builder addTerrainFromSpeedRatio(
            TerrainType type,
            Supplier<Float> groundSpeedSupplier,
            Supplier<Float> terrainSpeedSupplier
        ) {
            terrainCostSuppliers.put(type, () -> groundSpeedSupplier.get() / terrainSpeedSupplier.get());
            return this;
        }

        public Builder withEntitySize(int width, int height) {
            this.entityWidth = width;
            this.entityHeight = height;
            return this;
        }

        public Builder withTerrainClassifier(TerrainClassifier classifier) {
            this.terrainClassifier = classifier;
            return this;
        }

        public Builder withMaxFallDistance(int distance) {
            this.maxFallDistance = distance;
            return this;
        }

        public Builder withMaxStepHeight(int height) {
            this.maxStepHeight = height;
            return this;
        }

        public Builder withCanOpenDoors(boolean canOpenDoors) {
            this.canOpenDoors = canOpenDoors;
            return this;
        }

        public Builder withCanWalkOverFences(boolean canWalkOverFences) {
            this.canWalkOverFences = canWalkOverFences;
            return this;
        }

        public Builder withCanFly(boolean canFly) {
            this.canFly = canFly;
            return this;
        }

        public Builder withCrawlConfig(PathCrawlConfig crawlConfig) {
            this.crawlConfig = crawlConfig != null ? crawlConfig : PathCrawlConfig.DISABLED;
            return this;
        }

        public Builder withCrawling(int crawlHeight) {
            this.crawlConfig = PathCrawlConfig.enabled(crawlHeight);
            return this;
        }

        public Builder withWaterConfig(PathWaterConfig waterConfig) {
            this.waterConfig = waterConfig != null ? waterConfig : PathWaterConfig.DISABLED;
            return this;
        }

        public Builder withWaterSwimming(int swimHeight) {
            this.waterConfig = PathWaterConfig.enabled(swimHeight);
            return this;
        }

        public Builder withBlockBreakingConfig(PathBlockBreakingConfig blockBreakingConfig) {
            this.blockBreakingConfig = blockBreakingConfig != null ? blockBreakingConfig : PathBlockBreakingConfig.DISABLED;
            return this;
        }

        public Builder withBlockBreaking(PathBlockBreakPolicy breakPolicy) {
            this.blockBreakingConfig = PathBlockBreakingConfig.enabled(breakPolicy);
            return this;
        }

        /**
         * Sets the soft per-side cost for standing ground nodes directly beside a two-high solid wall. Defaults to
         * {@value #DEFAULT_WALL_ADJACENCY_MALUS}; pass 0 to disable the center-of-walkway bias entirely. Negative
         * values are clamped to 0.
         */
        /** Lets the pathfinder plan leaps across gaps. See {@link PathLeapConfig}. */
        public Builder withLeapConfig(PathLeapConfig leapConfig) {
            this.leapConfig = leapConfig != null ? leapConfig : PathLeapConfig.DISABLED;
            return this;
        }

        /**
         * Lets a mob that breaks blocks DIG a blocked one-block step up - its body's space at the higher cell plus the
         * headroom it rises through - so it can tunnel a staircase. Needs block breaking enabled to do anything.
         */
        public Builder withStepUpBlockBreaking(boolean stepUpBlockBreaking) {
            this.stepUpBlockBreaking = stepUpBlockBreaking;
            return this;
        }

        public Builder withWallAdjacencyMalus(float wallAdjacencyMalus) {
            this.wallAdjacencyMalus = wallAdjacencyMalus;
            return this;
        }

        public TerrainEvaluatorConfig build() {
            if (terrainCostSuppliers.isEmpty()) {
                terrainCostSuppliers.put(TerrainType.GROUND, () -> DEFAULT_COST);
            }

            return new TerrainEvaluatorConfig(
                terrainCostSuppliers,
                terrainClassifier,
                entityWidth,
                entityHeight,
                maxFallDistance,
                maxStepHeight,
                canOpenDoors,
                canWalkOverFences,
                canFly,
                crawlConfig,
                waterConfig,
                blockBreakingConfig,
                leapConfig,
                stepUpBlockBreaking,
                wallAdjacencyMalus
            );
        }
    }
}
