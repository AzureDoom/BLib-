package com.blib.internal.common.perf;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.blib.mod.BLib;

/**
 * {@code /blib perf} - a built-in server profiler for mob AI, so the cost of BLib, avp_alien, avp_human and
 * avp_predator can be measured without Spark.
 * <p>
 * Oct 5 - performance programme, stage 1, built FIRST so every later optimisation is measured before and after. It
 * records, per mob, for a fixed window:
 * </p>
 * <ul>
 * <li><b>tick</b> - the entity's own tick time, EXCLUSIVE of any passengers (they are timed as themselves). This
 * includes everything below, and the vanilla AI that runs in the same tick.</li>
 * <li><b>AI</b> - time inside the GOAP agent update (sensing, planning, executing actions).</li>
 * <li><b>paths</b> - BLib path searches (time, nodes visited, failed, partial) and vanilla PathFinder searches (marines
 * use vanilla navigation - nodes are not exposed there, so they read "-").</li>
 * <li><b>scans</b> - EntitySenseCache refreshes and how many entities each one walked.</li>
 * <li><b>errors</b> - GOAP failures caught by error isolation.</li>
 * </ul>
 * <p>
 * ⭐ ZERO COST WHEN OFF. Every hook starts with one read of a static volatile boolean. Nothing is allocated, timed or
 * stored unless a session is running, and the session's maps are dropped as soon as the report is written.
 * </p>
 * <p>
 * ⚠ SERVER THREAD ONLY. Every recorder ignores calls from any other thread except the async path search counter - mods
 * that tick entities in parallel would otherwise corrupt the attribution stack. A background path search is only
 * COUNTED: it costs a worker thread, not the server tick.
 * </p>
 */
@ApiStatus.Internal
public final class BLibPerfProfiler {

    private static final int MAX_FRAME_DEPTH = 64;

    private static volatile boolean active;

    private static @Nullable MinecraftServer server;

    private static @Nullable Thread serverThread;

    private static @Nullable CommandSourceStack requester;

    private static int durationTicks;

    private static int ticksSampled;

    private static long sessionStartMillis;

    private static long serverTickStartNanos;

    private static long serverTickNanosTotal;

    private static final AtomicLong ASYNC_PATH_SEARCHES = new AtomicLong();

    private static final Map<Integer, MobStats> STATS_BY_ENTITY_ID = new HashMap<>();

    private static final MobStats[] FRAME_STATS = new MobStats[MAX_FRAME_DEPTH];

    private static final long[] FRAME_START_NANOS = new long[MAX_FRAME_DEPTH];

    private static final long[] FRAME_ACCUMULATED_NANOS = new long[MAX_FRAME_DEPTH];

    private static int frameDepth;

    /** Frames past {@code MAX_FRAME_DEPTH} are not timed; this keeps push and pop balanced. */
    private static int overflowDepth;

    private BLibPerfProfiler() {
        throw new UnsupportedOperationException();
    }

    public static boolean isActive() {
        return active;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Session control (server thread, from the command)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Starts a session of {@code seconds} (20 ticks each), replacing any running one.
     *
     * @return false if a session was already running and has been discarded
     */
    public static boolean start(MinecraftServer minecraftServer, CommandSourceStack source, int seconds) {
        var wasActive = active;
        resetSession();

        server = minecraftServer;
        serverThread = minecraftServer.getRunningThread();
        requester = source;
        durationTicks = seconds * 20;
        sessionStartMillis = Util.getMillis();
        active = true;

        return !wasActive;
    }

    /** Stops the running session now and reports what it has. Does nothing if none is running. */
    public static void stop() {
        if (!active) {
            return;
        }

        finish();
    }

    /** One line describing the running session, or null if none is. */
    public static @Nullable String status() {
        if (!active) {
            return null;
        }

        return "profiling: %d / %d ticks sampled, %d mobs seen".formatted(
            ticksSampled,
            durationTicks,
            STATS_BY_ENTITY_ID.size()
        );
    }

    /*
     * Oct 6 - PATH SEARCH PHASES. The chase searches are the biggest single cost left (~8 ms each), so the report now
     * splits the server-thread searches by phase. The search already had timing points for its debug recorder
     * (PathSearchTimingPhase); with no recorder attached they now feed these counters while a session runs on the
     * server thread. Three extra phases cover the work around the search: evaluator prepare, corridor search, cleanup.
     */
    private static final int PATH_PHASE_ENUM_COUNT =
        com.blib.api.common.pathfinding.v1.debug.PathSearchTimingPhase.values().length;

    /** Index of the evaluator-prepare phase in the path phase counters. */
    public static final int PATH_PHASE_PREPARE = PATH_PHASE_ENUM_COUNT;

    /** Index of the corridor-search phase. */
    public static final int PATH_PHASE_CORRIDOR = PATH_PHASE_ENUM_COUNT + 1;

    /** Index of the evaluator-cleanup phase. */
    public static final int PATH_PHASE_CLEANUP = PATH_PHASE_ENUM_COUNT + 2;

    private static final long[] PATH_PHASE_NANOS = new long[PATH_PHASE_ENUM_COUNT + 3];

    private static final long[] PATH_PHASE_CALLS = new long[PATH_PHASE_ENUM_COUNT + 3];

    /**
     * {@return true when the path search should time its phases: a session is running and this is the server thread}
     */
    public static boolean pathPhasesWanted() {
        return active && onServerThread();
    }

    /** Adds one timed phase of a server-thread path search. */
    public static void recordPathPhase(int phase, long nanos) {
        if (!active || phase < 0 || phase >= PATH_PHASE_NANOS.length || nanos < 0L) {
            return;
        }

        PATH_PHASE_NANOS[phase] += nanos;
        PATH_PHASE_CALLS[phase]++;
    }

    /** {@return the display name of a path phase index} */
    public static String pathPhaseName(int phase) {
        if (phase < PATH_PHASE_ENUM_COUNT) {
            return com.blib.api.common.pathfinding.v1.debug.PathSearchTimingPhase.values()[phase].name().toLowerCase(Locale.ROOT);
        }

        return switch (phase - PATH_PHASE_ENUM_COUNT) {
            case 0 -> "evaluator_prepare";
            case 1 -> "corridor_search";
            default -> "evaluator_cleanup";
        };
    }

    /** Oct 7 - block entity time by type id: [0] nanos, [1] ticks. */
    private static final Map<String, long[]> BLOCK_ENTITY_TIME = new HashMap<>();

    /** Oct 7 - the distinct block entities of each type that ticked, by packed position. */
    private static final Map<String, java.util.Set<Long>> BLOCK_ENTITY_POSITIONS = new HashMap<>();

    /** Oct 7 - one block entity ticked on the server thread. Called by MixinLevel_PerfBlockEntityTick. */
    public static void recordBlockEntity(String type, long packedPos, long nanos) {
        if (!active || !onServerThread()) {
            return;
        }

        var cell = BLOCK_ENTITY_TIME.computeIfAbsent(type, $ -> new long[2]);
        cell[0] += nanos;
        cell[1]++;
        BLOCK_ENTITY_POSITIONS.computeIfAbsent(type, $ -> new java.util.HashSet<>()).add(packedPos);
    }

    /** {@return a copy of the block entity table: type to [nanos, ticks, distinct block entities]} */
    public static Map<String, long[]> blockEntitySnapshot() {
        var copy = new HashMap<String, long[]>();

        for (var entry : BLOCK_ENTITY_TIME.entrySet()) {
            var positions = BLOCK_ENTITY_POSITIONS.get(entry.getKey());
            copy.put(
                entry.getKey(),
                new long[] { entry.getValue()[0], entry.getValue()[1], positions == null ? 0 : positions.size() }
            );
        }

        return copy;
    }

    /** Number of {@code UnifiedTerrainEvaluator.CacheDecision} values; the failed-search cap count sits after them. */
    public static final int PATH_CACHE_DECISIONS = 7;

    /** Index of the "settings changed" decision - a kept cache thrown out once the search's settings were known. */
    private static final int SETTINGS_CHANGED = 6;

    private static final int CAPPED = PATH_CACHE_DECISIONS;

    private static final long[] PATH_CACHE_COUNTS = new long[PATH_CACHE_DECISIONS + 1];

    /**
     * Oct 7 - one server-thread search prepared, and why it did or did not keep the previous search's lookups:
     * {@code decision} is a {@code UnifiedTerrainEvaluator.CacheDecision} ordinal.
     */
    public static void recordPathCacheDecision(int decision) {
        if (!active || !onServerThread() || decision < 0 || decision >= PATH_CACHE_DECISIONS) {
            return;
        }

        PATH_CACHE_COUNTS[decision]++;
    }

    /** Oct 7 - a search first counted as reused turned out to need other settings: move it to SETTINGS_CHANGED. */
    public static void recordPathCacheSettingsMismatch() {
        if (!active || !onServerThread()) {
            return;
        }

        if (PATH_CACHE_COUNTS[0] > 0L) {
            PATH_CACHE_COUNTS[0]--;
        }

        PATH_CACHE_COUNTS[SETTINGS_CHANGED]++;
    }

    /** Oct 6 - one search ran with a node budget shrunk by the failed-search cap. */
    public static void recordFailedSearchCap() {
        if (!active || !onServerThread()) {
            return;
        }

        PATH_CACHE_COUNTS[CAPPED]++;
    }

    /** {@return a copy of the path cache counters: one per CacheDecision ordinal, then the capped-search count} */
    public static long[] pathCacheSnapshot() {
        return PATH_CACHE_COUNTS.clone();
    }

    /** {@return copies of the path phase totals: [0] nanos, [1] calls} */
    public static long[][] pathPhaseSnapshot() {
        return new long[][] { PATH_PHASE_NANOS.clone(), PATH_PHASE_CALLS.clone() };
    }

    private static void resetSession() {
        active = false;
        STATS_BY_ENTITY_ID.clear();
        ASYNC_PATH_SEARCHES.set(0);
        java.util.Arrays.fill(PATH_PHASE_NANOS, 0L);
        java.util.Arrays.fill(PATH_PHASE_CALLS, 0L);
        java.util.Arrays.fill(PATH_CACHE_COUNTS, 0L);
        BLOCK_ENTITY_TIME.clear();
        BLOCK_ENTITY_POSITIONS.clear();
        ticksSampled = 0;
        serverTickNanosTotal = 0;
        clearFrames();
    }

    private static void clearFrames() {
        for (var i = 0; i < MAX_FRAME_DEPTH; i++) {
            FRAME_STATS[i] = null;
        }

        frameDepth = 0;
        overflowDepth = 0;
        openSection = -1;
        sectionStats = null;
        aiStepEntity = null;
    }

    private static void finish() {
        active = false;

        var report = BLibPerfReport.build(
            List.copyOf(STATS_BY_ENTITY_ID.values()),
            ticksSampled,
            serverTickNanosTotal,
            Util.getMillis() - sessionStartMillis,
            ASYNC_PATH_SEARCHES.get()
        );

        var file = writeReport(report.fullText());
        var source = requester;

        if (source != null) {
            for (var line : report.chatLines()) {
                source.sendSuccess(() -> line, false);
            }

            source.sendSuccess(() -> footer(report.fullText(), file), false);
        }

        BLib.LOGGER.info(
            "[BLib] /blib perf finished: {} ticks sampled, report written to {}",
            ticksSampled,
            file == null ? "(could not write)" : file
        );

        requester = null;
        server = null;
        serverThread = null;
        STATS_BY_ENTITY_ID.clear();
        BLOCK_ENTITY_TIME.clear();
        BLOCK_ENTITY_POSITIONS.clear();
        clearFrames();
    }

    private static @Nullable Path writeReport(String text) {
        var currentServer = server;

        if (currentServer == null) {
            return null;
        }

        try {
            var directory = currentServer.getServerDirectory().resolve("logs").resolve("blib-perf");
            Files.createDirectories(directory);
            var file = directory.resolve("perf-" + Util.getFilenameFormattedDateTime() + ".txt");
            Files.writeString(file, text);

            return file;
        } catch (Exception exception) {
            BLib.LOGGER.warn("[BLib] /blib perf could not write its report file", exception);
            return null;
        }
    }

    /**
     * The most report text the "[Copy full report]" button may carry. A click event travels inside the chat packet,
     * and the packet's strings are written with a hard 65,535-byte limit - past it the SERVER fails to encode the
     * packet and drops the player ("Failed to encode packet 'clientbound/minecraft:system_chat'", Oct 7 tester crash
     * right as a 60 s run finished). Since #11 the report has no cut-offs, so a busy world's report is far past that.
     * 20,000 characters leaves room for multi-byte characters and the rest of the message.
     */
    private static final int MAX_CLIPBOARD_CHARS = 20_000;

    private static Component footer(String fullText, @Nullable Path file) {
        if (fullText.length() > MAX_CLIPBOARD_CHARS) {
            // Too big to ship through chat: point at the file instead. The full report is always written there.
            MutableComponent note = Component.literal("Full report is too large for chat - see the file.")
                .withStyle(ChatFormatting.GRAY);

            if (file == null) {
                return note;
            }

            return note.append(Component.literal("  ")).append(copyPathButton(file.toAbsolutePath().toString()));
        }

        MutableComponent copy = Component.literal("[Copy full report]")
            .withStyle(
                style -> style.withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fullText))
                    .withHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copies every table to the clipboard"))
                    )
            );

        if (file == null) {
            return copy;
        }

        return copy.append(Component.literal("  ")).append(copyPathButton(file.toAbsolutePath().toString()));
    }

    private static Component copyPathButton(String path) {
        return Component.literal("[Copy file path]")
            .withStyle(
                style -> style.withColor(ChatFormatting.GRAY)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(path)))
            );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }

    /** MinecraftServer.tickServer HEAD. */
    public static void onServerTickStart() {
        if (!active || !onServerThread()) {
            return;
        }

        // ⚠ An exception that escaped an entity tick (and was swallowed by a loader option that removes erroring
        // entities) would have skipped a pop. Every tick starts from a clean stack.
        frameDepth = 0;
        overflowDepth = 0;
        serverTickStartNanos = System.nanoTime();
    }

    /** MinecraftServer.tickServer RETURN. Also ends the session when its window is full. */
    public static void onServerTickEnd() {
        if (!active || !onServerThread()) {
            return;
        }

        serverTickNanosTotal += System.nanoTime() - serverTickStartNanos;
        ticksSampled++;

        if (ticksSampled >= durationTicks) {
            finish();
        }
    }

    /** ServerLevel.tickNonPassenger / tickPassenger HEAD. */
    public static void pushEntity(Entity entity) {
        if (!active || !onServerThread()) {
            return;
        }

        if (frameDepth >= MAX_FRAME_DEPTH) {
            overflowDepth++;
            return;
        }

        var now = System.nanoTime();

        // Pause the parent: a vehicle is not charged for its passengers.
        if (frameDepth > 0) {
            FRAME_ACCUMULATED_NANOS[frameDepth - 1] += now - FRAME_START_NANOS[frameDepth - 1];
        }

        FRAME_STATS[frameDepth] = stats(entity);
        FRAME_START_NANOS[frameDepth] = now;
        FRAME_ACCUMULATED_NANOS[frameDepth] = 0L;
        frameDepth++;
    }

    /** ServerLevel.tickNonPassenger / tickPassenger RETURN. */
    public static void popEntity(Entity entity) {
        if (!active || !onServerThread()) {
            return;
        }

        if (overflowDepth > 0) {
            overflowDepth--;
            return;
        }

        if (frameDepth == 0) {
            return;
        }

        var now = System.nanoTime();
        frameDepth--;

        var stats = FRAME_STATS[frameDepth];
        FRAME_STATS[frameDepth] = null;
        stats.tickNanos += FRAME_ACCUMULATED_NANOS[frameDepth] + (now - FRAME_START_NANOS[frameDepth]);
        stats.ticks++;
        stats.updatePosition(entity);

        // Resume the parent.
        if (frameDepth > 0) {
            FRAME_START_NANOS[frameDepth - 1] = now;
        }
    }

    /*
     * Oct 5 - WHERE THE TIME GOES. The entity tick used to be one number, so a mob's cost outside its GOAP agent could
     * not be attributed. These sections split the vanilla AI step and movement; the report prints them next to the GOAP
     * time, and whatever is left is the entity's own tick code (and vanilla's base tick).
     */

    /** Sensing plus the vanilla target and goal selectors (a mod's own Goals run here). */
    public static final int SECTION_GOALS = 0;

    /** Vanilla PathNavigation.tick - following a path, not searching for one. */
    public static final int SECTION_NAV = 1;

    /** Mob.customServerAiStep - a mod's own per-tick AI step. */
    public static final int SECTION_MOB_STEP = 2;

    /** Move, look and jump controls. */
    public static final int SECTION_CONTROLS = 3;

    /** LivingEntity.travel - movement physics and collision. */
    public static final int SECTION_MOVE = 4;

    /** Profiler v3: LivingEntity.baseTick - vanilla's per-tick upkeep (fluids, fire, air, portals, effects). */
    public static final int SECTION_BASE = 5;

    public static final int SECTION_COUNT = 6;

    /*
     * Profiler v3 - LABELLED TIME INSIDE THE TICK. Three kinds, each a separate table in the report: GOAL - every
     * vanilla Goal, timed automatically around its canUse/canContinueToUse/start/stop/tick (any mod); ACTION - every
     * BLib GOAP action's perform/onStart/onFinish (part of the AI column); CODE - named laps a mod puts in its own tick
     * code through BLibPerf.lap (part of the "other" column).
     */

    /** Label kind: a vanilla Goal. */
    public static final int LABEL_GOAL = 0;

    /** Label kind: a BLib GOAP action. */
    public static final int LABEL_ACTION = 1;

    /** Label kind: a named lap in a mod's own tick code. */
    public static final int LABEL_CODE = 2;

    /** Label kind: a GOAP sensor, by the key it senses (part of AI, outside actions). Oct 6. */
    public static final int LABEL_SENSOR = 3;

    public static final int LABEL_KINDS = 4;

    /** How many timed BLib actions are running right now (sensors read inside one are not charged twice). */
    private static int actionDepth;

    /** {@return how many timed actions are running} */
    public static int actionDepth() {
        return actionDepth;
    }

    /** Called around a timed action's perform/onStart/onFinish. */
    public static void enterAction() {
        actionDepth++;
    }

    /** See {@link #enterAction()}. */
    public static void exitAction() {
        if (actionDepth > 0) {
            actionDepth--;
        }
    }

    /** The mob whose AI step is running (set while a section is open), for the goal timer. */
    private static @Nullable Entity aiStepEntity;

    /**
     * {@return the mob whose vanilla AI step is running right now, or null} The goal timer charges its goals to it.
     */
    public static @Nullable Entity currentAiStepEntity() {
        return aiStepEntity;
    }

    /**
     * {@return a start time for a timed block, or 0 when no session is running on this thread} Zero means "do not
     * record"; every recorder below treats it as a no-op, so a caller never has to test isActive itself.
     */
    public static long timerStart() {
        return active && onServerThread() ? System.nanoTime() : 0L;
    }

    /**
     * Charges the time since {@code start} to {@code entity} under {@code label}; a no-op when start is 0.
     *
     * @param entity the mob the time belongs to (null: dropped)
     * @param kind   a LABEL_ constant
     * @param label  the label - keep it a constant or a cached string, it is a map key
     * @param start  from {@link #timerStart}
     * @return the current time, so a caller can lap straight into the next block (0 when not recording)
     */
    /**
     * Oct 6 - charges an already-measured {@code nanos} to {@code label} (for callers that subtract nested time
     * themselves, like the exclusive sensor timer). A no-op while no session runs.
     */
    public static void recordLabelNanos(@Nullable Entity entity, int kind, String label, long nanos) {
        if (entity == null || !active || nanos < 0L) {
            return;
        }

        var cell = stats(entity).labels[kind].computeIfAbsent(label, $ -> new long[2]);
        cell[0] += nanos;
        cell[1]++;
    }

    public static long recordLabel(@Nullable Entity entity, int kind, String label, long start) {
        if (start == 0L || entity == null || !active) {
            return 0L;
        }

        var now = System.nanoTime();
        var cell = stats(entity).labels[kind].computeIfAbsent(label, $ -> new long[2]);
        cell[0] += now - start;
        cell[1]++;

        // Re-read the clock so the bookkeeping above is not charged to the next lap.
        return System.nanoTime();
    }

    private static int openSection = -1;

    private static long sectionStartNanos;

    private static @Nullable MobStats sectionStats;

    /**
     * Closes the open section (charging its time to the mob that opened it) and opens {@code section} for
     * {@code entity}; a negative section just closes. Server thread only; a no-op while no session runs.
     *
     * @param entity  the mob whose tick this is
     * @param section a SECTION_ constant, or -1 to close
     */
    public static void markSection(Entity entity, int section) {
        if (!active || !onServerThread()) {
            return;
        }

        var now = System.nanoTime();

        if (openSection >= 0 && sectionStats != null) {
            sectionStats.sectionNanos[openSection] += now - sectionStartNanos;
        }

        if (section < 0) {
            openSection = -1;
            sectionStats = null;
            aiStepEntity = null;
            return;
        }

        openSection = section;
        sectionStats = stats(entity);
        aiStepEntity = entity;
        // Profiler v3 - the lookup above is bookkeeping, not the mob's time: start the clock after it.
        sectionStartNanos = System.nanoTime();
    }

    /** Time spent in one GOAP agent update. */
    public static void recordAi(Entity entity, long nanos) {
        if (!active || !onServerThread()) {
            return;
        }

        var stats = stats(entity);
        stats.aiNanos += nanos;
        stats.aiUpdates++;
    }

    /** One EntitySenseCache refresh. */
    public static void recordScan(Entity owner, int entitiesScanned, long nanos) {
        if (!active || !onServerThread()) {
            return;
        }

        var stats = stats(owner);
        stats.scans++;
        stats.scannedEntities += entitiesScanned;
        stats.scanNanos += nanos;
    }

    /**
     * One BLib block-level search. BLib's path finder does not know its mob, so the search is charged to whichever
     * entity is ticking - which is the mob whose navigator asked for it.
     */
    public static void recordBlibPathSearch(long nanos, int nodesVisited, boolean failed, boolean partial) {
        if (!active) {
            return;
        }

        if (!onServerThread()) {
            ASYNC_PATH_SEARCHES.incrementAndGet();
            return;
        }

        var stats = frameDepth > 0 ? FRAME_STATS[frameDepth - 1] : null;

        if (stats == null) {
            return;
        }

        stats.pathSearches++;
        stats.pathNanos += nanos;
        stats.pathNodes += nodesVisited;

        if (failed) {
            stats.pathFailures++;
        } else if (partial) {
            stats.pathPartials++;
        }
    }

    /** One vanilla PathFinder search (vanilla navigation - marines, and every vanilla mob for comparison). */
    public static void recordVanillaPathSearch(Entity mob, long nanos, boolean failed, boolean partial) {
        if (!active || !onServerThread()) {
            return;
        }

        var stats = stats(mob);
        stats.vanillaPathSearches++;
        stats.pathNanos += nanos;

        if (failed) {
            stats.pathFailures++;
        } else if (partial) {
            stats.pathPartials++;
        }
    }

    /** One GOAP failure caught by error isolation (or by the finish guarantee). */
    public static void recordGoapError(Entity entity) {
        if (!active || !onServerThread()) {
            return;
        }

        stats(entity).errors++;
    }

    private static MobStats stats(Entity entity) {
        var stats = STATS_BY_ENTITY_ID.get(entity.getId());

        if (stats == null || stats.type != entity.getType()) {
            stats = new MobStats(entity);
            STATS_BY_ENTITY_ID.put(entity.getId(), stats);
        }

        return stats;
    }

    /** Everything recorded for one entity during a session. Plain fields: server thread only. */
    static final class MobStats {

        final EntityType<?> type;

        final String typeId;

        final String modId;

        final int entityId;

        String dimension;

        int x;

        int y;

        int z;

        long tickNanos;

        long ticks;

        long aiNanos;

        long aiUpdates;

        long pathSearches;

        long vanillaPathSearches;

        long pathNanos;

        long pathNodes;

        long pathFailures;

        long pathPartials;

        long scans;

        long scannedEntities;

        long scanNanos;

        long errors;

        /** Time per breakdown section (SECTION_ constants). */
        final long[] sectionNanos = new long[SECTION_COUNT];

        /** Profiler v3: per label kind, label -> {nanos, calls}. */
        @SuppressWarnings("unchecked")
        final java.util.Map<String, long[]>[] labels = new java.util.Map[] {
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>() };

        MobStats(Entity entity) {
            this.type = entity.getType();
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            this.typeId = key.toString();
            this.modId = key.getNamespace();
            this.entityId = entity.getId();
            updatePosition(entity);
        }

        void updatePosition(Entity entity) {
            this.dimension = entity.level().dimension().location().toString();
            this.x = entity.getBlockX();
            this.y = entity.getBlockY();
            this.z = entity.getBlockZ();
        }

        long totalPathSearches() {
            return pathSearches + vanillaPathSearches;
        }
    }

    /** Sorts heaviest first; exposed for the report. */
    static final Comparator<MobStats> BY_TICK_NANOS_DESC = Comparator.comparingLong((MobStats stats) -> stats.tickNanos)
        .reversed();

    /** Copies the mob list sorted heaviest first. */
    static List<MobStats> sortedByCost(List<MobStats> mobs) {
        var sorted = new ArrayList<>(mobs);
        sorted.sort(BY_TICK_NANOS_DESC);

        return sorted;
    }

    static String formatLocale(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
