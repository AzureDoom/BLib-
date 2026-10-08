package com.blib.api.common.pathfinding.v1.search;

/**
 * Runtime tuning values for pathfinding behavior that are not part of the terrain evaluator or A* search config.
 *
 * @param corridorDistanceThreshold minimum Manhattan distance before section-corridor planning is used
 * @param sectionSearchNodeBudget   maximum section-level nodes evaluated while finding a corridor
 * @param corridorBufferRadius      number of sections to buffer around each section in the corridor path
 * @param asyncChunkMargin          chunk margin preloaded around async path searches
 * @param minImprovement            minimum A* cost improvement required before replacing a node's parent
 * @param maxPreloadChunks          hard ceiling on chunks preloaded for one search; beyond it the search fails
 */
public record PathfindingTuning(
    int corridorDistanceThreshold,
    int sectionSearchNodeBudget,
    int corridorBufferRadius,
    int asyncChunkMargin,
    float minImprovement,

    /**
     * \u2b50\u2b50\u2b50 HARD CEILING ON THE PRELOAD BOX. THE FIX FOR 40-SECOND SERVER TICKS.
     * <p>
     * \u26a0\u26a0 {@code findPathAsync} pre-populates its terrain cache by walking the ENTIRE chunk box between start
     * and target with a BLOCKING, GENERATE-IF-ABSENT {@code level.getChunk}. That is fine for a target ten blocks away
     * and catastrophic for one a few thousand blocks away: the box grows with the square of the distance, and every
     * missing chunk in it is generated synchronously on the server thread.
     * </p>
     * <p>
     * \u26a0 C2ME SHARPENS THIS RATHER THAN CAUSING IT. Under vanilla a blocking fetch of a resident chunk returns
     * quickly; C2ME reroutes chunk work through its own scheduler, so blocking the main thread inside it stalls a
     * system built to be asynchronous. Live captures show the server thread parked in exactly this loop for over forty
     * seconds.
     * </p>
     * <p>
     * \u26a0 EXCEEDING IT MEANS NO PATH, NOT A TRUNCATED ONE. A partial path toward a target thousands of blocks away
     * is an actor walking hopefully in a direction forever; every caller already has a NO_PATH branch, and honest
     * failure is what lets them use it.
     * </p>
     */
    int maxPreloadChunks,

    /**
     * Fractional discount on an edge whose destination lies on the path the entity is ALREADY following.
     * <p>
     * ⚠⚠ THE ANTI-PING-PONG DIAL. It biases the SEARCH rather than refusing to replan. The churn it fixes: a chasing
     * mob re-searches every recalculate interval because its target keeps moving, and when two routes round an obstacle
     * cost about the same, whichever is momentarily cheaper wins — so the answer flips as the target jiggles and the
     * mob visibly turns around and back again.
     * </p>
     * <p>
     * ⚠ Keeping the OLD PATH instead would be wrong: the target moved, so the old path leads to where the target used
     * to be. Discounting edges that follow the old route means the new path still aims at the new target but prefers
     * the shape it already committed to, and abandons it only when the alternative is better by more than this margin.
     * 0 disables it and restores the previous behaviour exactly.
     * </p>
     */
    float replanCorridorBias
) {

    public static final int DEFAULT_CORRIDOR_DISTANCE_THRESHOLD = 32;

    public static final int DEFAULT_SECTION_SEARCH_NODE_BUDGET = 128;

    public static final int DEFAULT_CORRIDOR_BUFFER_RADIUS = 1;

    public static final int DEFAULT_ASYNC_CHUNK_MARGIN = 2;

    public static final float DEFAULT_MIN_IMPROVEMENT = 0.01f;

    /**
     * 1024 chunks - a 32x32 box, comfortably past any legitimate local search and far short of the millions an
     * unbounded box reaches. Sized to be invisible in normal play and to bite only where the old behaviour froze.
     */
    public static final int DEFAULT_MAX_PRELOAD_CHUNKS = 1024;

    /** Default for {@link #replanCorridorBias}. Tuned to steady a chase without distorting genuinely better routes. */
    public static final float DEFAULT_REPLAN_CORRIDOR_BIAS = 0.25f;

    /** Above this the discount could drive an edge close to free, which distorts routes rather than steadying them. */
    public static final float MAX_REPLAN_CORRIDOR_BIAS = 0.9f;

    public static final PathfindingTuning DEFAULT = new PathfindingTuning(
        DEFAULT_CORRIDOR_DISTANCE_THRESHOLD,
        DEFAULT_SECTION_SEARCH_NODE_BUDGET,
        DEFAULT_CORRIDOR_BUFFER_RADIUS,
        DEFAULT_ASYNC_CHUNK_MARGIN,
        DEFAULT_MIN_IMPROVEMENT,
        DEFAULT_MAX_PRELOAD_CHUNKS,
        DEFAULT_REPLAN_CORRIDOR_BIAS
    );

    public PathfindingTuning {
        corridorDistanceThreshold = Math.max(0, corridorDistanceThreshold);
        sectionSearchNodeBudget = Math.max(1, sectionSearchNodeBudget);
        corridorBufferRadius = Math.max(0, corridorBufferRadius);
        asyncChunkMargin = Math.max(0, asyncChunkMargin);
        minImprovement = finiteAtLeast(minImprovement, 0.0f, DEFAULT_MIN_IMPROVEMENT);
        maxPreloadChunks = Math.max(1, maxPreloadChunks);
        replanCorridorBias = clamp(replanCorridorBias, 0.0f, MAX_REPLAN_CORRIDOR_BIAS, DEFAULT_REPLAN_CORRIDOR_BIAS);
    }

    /**
     * ⚠ Source compatibility for callers written against the six-component record. A record's canonical constructor is
     * positional, so adding a component silently breaks every existing {@code new PathfindingTuning(...)} call outside
     * this repo. Keeping the old arity means those keep compiling and simply get the default bias.
     */
    public PathfindingTuning(
        int corridorDistanceThreshold,
        int sectionSearchNodeBudget,
        int corridorBufferRadius,
        int asyncChunkMargin,
        float minImprovement,
        int maxPreloadChunks
    ) {
        this(
            corridorDistanceThreshold,
            sectionSearchNodeBudget,
            corridorBufferRadius,
            asyncChunkMargin,
            minImprovement,
            maxPreloadChunks,
            DEFAULT_REPLAN_CORRIDOR_BIAS
        );
    }

    private static float clamp(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }

        return Math.min(maximum, Math.max(minimum, value));
    }

    private static float finiteAtLeast(float value, float minimum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }

        return Math.max(minimum, value);
    }
}
