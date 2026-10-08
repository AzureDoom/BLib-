package com.blib.mod.common.registry.init;

import net.minecraft.world.level.GameRules;

import com.blib.internal.mixin.MixinGameRulesAccessor;
import com.blib.internal.mixin.MixinGameRulesBooleanValueAccessor;

public final class BLibGameRules {

    public static final GameRules.Key<GameRules.BooleanValue> TERRITORY_BUILD_PROTECTION = MixinGameRulesAccessor
        .blib$register(
            "blibTerritoryBuildProtection",
            GameRules.Category.MISC,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /** ⚠ Aug 28, his ruling: "a gamerule turning each option off or on is good" — one per feature. */
    public static final GameRules.Key<GameRules.BooleanValue> CLAIM_MAP_OVERLAY = MixinGameRulesAccessor
        .blib$register(
            "blibClaimMapOverlay",
            GameRules.Category.MISC,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    public static final GameRules.Key<GameRules.BooleanValue> CLAIM_HUD_MESSAGES = MixinGameRulesAccessor
        .blib$register(
            "blibClaimHudMessages",
            GameRules.Category.MISC,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Sep 27 - the just-goap 0.4.1 sensing optimisations (a {@code null} sensor reading is remembered for the rest of
     * the tick instead of re-sensed on every read, and sensors wrapped in {@code Sensors.every} hold their value for
     * their refresh window). {@code false} restores the exact just-goap 0.4.0 behaviour on the next agent tick, live,
     * with no restart - the fallback if the optimisations ever misbehave, and an A/B switch for Spark comparisons.
     */
    public static final GameRules.Key<GameRules.BooleanValue> GOAP_SENSING_OPTIMIZATIONS = MixinGameRulesAccessor
        .blib$register(
            "blibGoapSensingOptimizations",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 5 - performance programme, stage 1. just-goap only calls an action's {@code onFinish} when its plan
     * completes, goes INVALID or the action's own effects are met. It does NOT call it when the action signals ABORT,
     * when the plan is displaced by a better one (the resolver's REPLACE_ACTIVE, which every interruptible wander
     * hits), or when the agent abandons all plans - so any cleanup in {@code onFinish} (stopping navigation, releasing
     * a reservation) was silently skipped on those paths. {@code true} makes BLib call it for every action that had
     * started and then vanished, and when an agent dies with plans running. {@code false} restores the old behaviour on
     * the next tick.
     */
    public static final GameRules.Key<GameRules.BooleanValue> GOAP_FINISH_GUARANTEE = MixinGameRulesAccessor
        .blib$register(
            "blibGoapFinishGuarantee",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 5 - an exception thrown from inside a GOAP agent update (a sensor, the planner, an action) used to escape
     * into the entity tick and crash the server. {@code true} catches it, logs it ONCE per mob type and cause, abandons
     * the agent's plans and rests that agent for one second. {@code false} lets it propagate exactly as before - the
     * switch to flip when a crash report is wanted instead of a log line.
     */
    public static final GameRules.Key<GameRules.BooleanValue> GOAP_ERROR_ISOLATION = MixinGameRulesAccessor
        .blib$register(
            "blibGoapErrorIsolation",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 5 - two EntitySenseCache bugs. {@code getByItem} never refreshed the scan, so it returned whatever the last
     * OTHER query had loaded (empty, or up to a refresh window stale, depending on which sensor happened to run first).
     * {@code getByClass} stored superclass query results in the same map as the scan buckets, so a later wider query
     * counted the same entity twice. {@code true} uses the fixed behaviour; {@code false} the old one (takes effect
     * from the next refresh of each cache).
     */
    public static final GameRules.Key<GameRules.BooleanValue> SENSE_CACHE_FIXES = MixinGameRulesAccessor
        .blib$register(
            "blibSenseCacheFixes",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 5 - performance programme, found by {@code /blib perf}. A mob whose target is out of reach gets a PARTIAL
     * path every search, and partial paths used to count as success, so it searched again as soon as it reached the end
     * - one trapped yautja ran 589 searches a minute and took 44% of the server tick. {@code true} rests a mob that
     * keeps getting the same partial path from the same spot (10 ticks, doubling to 5 seconds), clearing the moment the
     * mob or its target moves two blocks or a search reaches. {@code false} restores search-every-time.
     */
    public static final GameRules.Key<GameRules.BooleanValue> PATH_PARTIAL_BACKOFF = MixinGameRulesAccessor
        .blib$register(
            "blibPathPartialBackoff",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 5 - Stage 2. {@code MoveToPosAction} reported FINISHED ("arrived") whenever vanilla navigation could not find
     * a path at all - vanilla clears its path on a failed search, and an idle navigator reads as done - and a mob at
     * the end of a partial path re-pathed in place forever as MOVING. {@code true}: FINISHED means arrived, a missing
     * path or a dead-end partial path reports NO_PATH, and a mob rests a moment before searching the same failed
     * destination again. {@code false} restores the old results exactly.
     */
    public static final GameRules.Key<GameRules.BooleanValue> MOVE_TO_POS_RESULT_FIX = MixinGameRulesAccessor
        .blib$register(
            "blibMoveToPosResultFix",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 6 - performance. A mob chasing a moving target re-planned its whole route every time the target shifted a few
     * blocks, and block-breaking searches always run on the server thread. {@code true}: within 16 blocks nothing
     * changes; beyond 16 the re-plan waits 10 ticks (20 beyond 32) and the target must move 15% of the distance first.
     * {@code false} restores re-planning on every small move.
     */
    public static final GameRules.Key<GameRules.BooleanValue> PATH_CHASE_BUDGET = MixinGameRulesAccessor
        .blib$register(
            "blibPathChaseBudget",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 6 - performance. A wander that found no spot, or no path to it, gave up - and was chosen again the next tick,
     * searching again every tick. {@code true}: after a failed wander the mob stands for two seconds before trying
     * again (anything more urgent still interrupts). {@code false} restores retrying every tick.
     */
    public static final GameRules.Key<GameRules.BooleanValue> WANDER_FAILURE_PAUSE = MixinGameRulesAccessor
        .blib$register(
            "blibWanderFailurePause",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 6 - performance. A mob's pathfinder keeps its block lookups from one search to the next instead of re-reading
     * every block each time, as long as nothing changed nearby (see BlockChangeLog). {@code false} clears them before
     * every search, the old behaviour.
     */
    public static final GameRules.Key<GameRules.BooleanValue> PATH_CACHE_REUSE = MixinGameRulesAccessor
        .blib$register(
            "blibPathCacheReuse",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    /**
     * Oct 6 - performance. A search to a target the mob just failed to reach gets a smaller node budget each time it
     * repeats (half, a quarter, then an eighth), so one mob chasing something it cannot reach no longer runs every
     * search to the full node limit. {@code false} gives every search the full budget.
     */
    public static final GameRules.Key<GameRules.BooleanValue> PATH_FAILED_SEARCH_CAP = MixinGameRulesAccessor
        .blib$register(
            "blibPathFailedSearchCap",
            GameRules.Category.MOBS,
            MixinGameRulesBooleanValueAccessor.blib$create(true)
        );

    private BLibGameRules() {}

    public static void initialize() {
        // Loads the static game rule registration.
    }
}
