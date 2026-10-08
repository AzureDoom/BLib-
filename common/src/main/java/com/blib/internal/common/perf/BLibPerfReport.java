package com.blib.internal.common.perf;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.ApiStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.blib.internal.common.perf.BLibPerfProfiler.MobStats;

/**
 * Turns one {@code /blib perf} session into the text file and the chat summary.
 * <p>
 * ⭐ THE UNITS, so numbers compare across sessions and against the Sep 1 baseline (drone 0.796 ms per tick, chicken
 * 0.034 ms): "per mob" columns are microseconds per ENTITY-tick (total time / ticks that mob type was ticked), "total"
 * columns are milliseconds per SERVER tick summed over every mob of that kind, and "% tick" is that total against the
 * measured server tick (MinecraftServer.tickServer, the work part only - never the sleep).
 * </p>
 */
@ApiStatus.Internal
public final class BLibPerfReport {

    /*
     * ⭐ Oct 6 - [stated] "can you please just let it show you everything?" The FILE report now has no cut-offs at all:
     * every mob type, every breakdown row, every named part inside the tick, every individual mob. Only the chat
     * summary stays short; the file is where the answers are.
     */
    private static final int TYPE_ROWS_IN_FILE = Integer.MAX_VALUE;

    private static final int ROWS_IN_CHAT = 5;

    private static final int TOP_MOBS = 5;

    private final String fullText;

    private final List<Component> chatLines;

    private BLibPerfReport(String fullText, List<Component> chatLines) {
        this.fullText = fullText;
        this.chatLines = chatLines;
    }

    public String fullText() {
        return fullText;
    }

    public List<Component> chatLines() {
        return chatLines;
    }

    static BLibPerfReport build(
        List<MobStats> mobs,
        int ticksSampled,
        long serverTickNanosTotal,
        long elapsedMillis,
        long asyncPathSearches
    ) {
        var ticks = Math.max(1, ticksSampled);
        var serverTickNanos = Math.max(1L, serverTickNanosTotal);

        var byType = aggregate(mobs, stats -> stats.typeId);
        var byMod = aggregate(mobs, stats -> stats.modId);
        var topMobs = BLibPerfProfiler.sortedByCost(mobs);

        long entityNanos = 0;

        for (var stats : mobs) {
            entityNanos += stats.tickNanos;
        }

        var text = new StringBuilder();
        var mspt = nanosToMillis(serverTickNanos) / ticks;

        text.append("BLib perf report - ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append('\n');
        text.append(
            BLibPerfProfiler.formatLocale(
                "Sampled %d server ticks over %.1f s | server tick %.2f ms (MSPT) | all entity ticks %.2f ms/tick (%.1f%%)%n",
                ticksSampled,
                elapsedMillis / 1000.0,
                mspt,
                nanosToMillis(entityNanos) / ticks,
                percent(entityNanos, serverTickNanos)
            )
        );
        var blockEntities = BLibPerfProfiler.blockEntitySnapshot();
        long blockEntityNanos = 0;

        for (var cell : blockEntities.values()) {
            blockEntityNanos += cell[0];
        }

        text.append(
            BLibPerfProfiler.formatLocale(
                "Block entities %.2f ms/tick (%.1f%%) | everything else (chunks, fluids, redstone, other mods' world work)"
                    + " %.2f ms/tick (%.1f%%)%n",
                nanosToMillis(blockEntityNanos) / ticks,
                percent(blockEntityNanos, serverTickNanos),
                nanosToMillis(Math.max(0L, serverTickNanos - entityNanos - blockEntityNanos)) / ticks,
                percent(Math.max(0L, serverTickNanos - entityNanos - blockEntityNanos), serverTickNanos)
            )
        );
        text.append(
            BLibPerfProfiler.formatLocale(
                "Background (async) path searches: %d - counted only, they run off the server thread.%n",
                asyncPathSearches
            )
        );
        text.append(
            BLibPerfProfiler.formatLocale(
                "GOAP failures caught by error isolation this session: %d (see latest.log for the first of each)%n",
                totalErrors(mobs)
            )
        );
        text.append(
            "Units: per mob = microseconds per entity-tick. total = ms per server tick for all of them. "
                + "tick includes AI, which includes paths.\n"
        );

        text.append("\n== BY MOD ==\n");
        appendTable(text, byMod, ticks, serverTickNanos, Integer.MAX_VALUE);

        text.append("\n== BY MOB TYPE (all) ==\n");
        appendTable(text, byType, ticks, serverTickNanos, TYPE_ROWS_IN_FILE);

        appendBreakdown(text, "BY MOD", byMod, Integer.MAX_VALUE);
        appendBreakdown(text, "BY MOB TYPE (all)", byType, BREAKDOWN_ROWS);
        appendInsideTheTick(text, byType, ticks);
        appendPathPhases(text, ticks);
        appendPathCaches(text, ticks);
        appendBlockEntities(text, blockEntities, ticks, serverTickNanos);

        text.append("\n== EVERY INDIVIDUAL MOB, COSTLIEST FIRST ==\n");

        for (var i = 0; i < topMobs.size(); i++) {
            text.append(describeMob(i + 1, topMobs.get(i), ticks)).append('\n');
        }

        var chat = new ArrayList<Component>();
        chat.add(
            Component.literal(
                BLibPerfProfiler.formatLocale(
                    "BLib perf: %d ticks, MSPT %.2f ms, entities %.2f ms/tick (%.1f%%)",
                    ticksSampled,
                    mspt,
                    nanosToMillis(entityNanos) / ticks,
                    percent(entityNanos, serverTickNanos)
                )
            ).withStyle(ChatFormatting.GOLD)
        );

        chat.add(Component.literal("Mods (ms/tick, % tick, AI ms, paths/s):").withStyle(ChatFormatting.YELLOW));

        for (var row : byMod.subList(0, Math.min(ROWS_IN_CHAT, byMod.size()))) {
            chat.add(Component.literal(" " + summaryLine(row, ticks, serverTickNanos)));
        }

        chat.add(Component.literal("Mob types (per mob µs/tick):").withStyle(ChatFormatting.YELLOW));

        for (var row : byType.subList(0, Math.min(ROWS_IN_CHAT, byType.size()))) {
            chat.add(
                Component.literal(
                    BLibPerfProfiler.formatLocale(
                        " %s x%d: %.0f µs/mob, %.2f ms/tick (%.1f%%)",
                        row.name,
                        row.mobCount,
                        perEntityTickMicros(row.tickNanos, row.entityTicks),
                        nanosToMillis(row.tickNanos) / ticks,
                        percent(row.tickNanos, serverTickNanos)
                    )
                )
            );
        }

        chat.add(Component.literal("Costliest mobs (click to teleport):").withStyle(ChatFormatting.YELLOW));

        for (var i = 0; i < Math.min(TOP_MOBS, topMobs.size()); i++) {
            chat.add(teleportLine(i + 1, topMobs.get(i), ticks));
        }

        return new BLibPerfReport(text.toString(), chat);
    }

    /**
     * Oct 7 - every block entity type that ticked, heaviest first: how many, total cost, and cost per block entity.
     * Machines, generators, pipes and storage all land here.
     */
    private static void appendBlockEntities(
        StringBuilder text,
        java.util.Map<String, long[]> blockEntities,
        long ticks,
        long serverTickNanos
    ) {
        if (blockEntities.isEmpty()) {
            return;
        }

        var rows = new java.util.ArrayList<>(blockEntities.entrySet());
        rows.sort((left, right) -> Long.compare(right.getValue()[0], left.getValue()[0]));

        text.append("\n== BLOCK ENTITIES (server), heaviest first ==\n");
        text.append(
            BLibPerfProfiler.formatLocale(
                "%-48s %7s %10s %7s %12s%n",
                "type",
                "count",
                "total ms",
                "%tick",
                "us each/tick"
            )
        );

        for (var row : rows) {
            var cell = row.getValue();
            var name = row.getKey();

            // Oct 7: a type with no registered name reports "<null>" - point at one so it can be found in the world.
            if (name == null || name.contains("null")) {
                var sample = BLibPerfProfiler.blockEntitySamplePosition(name);
                name = (name == null ? "<null>" : name)
                    + (sample == null
                        ? ""
                        : " at " + net.minecraft.core.BlockPos.getX(sample) + " " + net.minecraft.core.BlockPos.getY(sample)
                            + " " + net.minecraft.core.BlockPos.getZ(sample));
            }

            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-48s %7d %10.3f %6.1f%% %12.2f%n",
                    name,
                    cell[2],
                    nanosToMillis(cell[0]) / ticks,
                    percent(cell[0], serverTickNanos),
                    cell[1] > 0L ? cell[0] / 1000.0 / cell[1] : 0.0
                )
            );
        }
    }

    /** The reasons in CacheDecision order (index = ordinal), as shown in the PATH CACHES table. */
    private static final String[] CACHE_DECISION_LABELS = {
        "reused from the previous search",
        "rebuilt: first search / nothing kept",
        "rebuilt: blibPathCacheReuse is off",
        "rebuilt: older than 30 s",
        "rebuilt: area or caches too large",
        "rebuilt: a block changed nearby",
        "rebuilt: different search settings",
        "kept, after patching nearby changes"
    };

    /**
     * Oct 6 - how often a pathfinder reused its block lookups from its previous search (blibPathCacheReuse) and how
     * often the failed-search cap shrank a search (blibPathFailedSearchCap). Oct 7: every rebuild now carries its
     * reason, so a low reuse rate explains itself.
     */
    private static void appendPathCaches(StringBuilder text, long ticks) {
        var counts = BLibPerfProfiler.pathCacheSnapshot();
        var decisions = BLibPerfProfiler.PATH_CACHE_DECISIONS;
        var capped = counts[decisions];
        var searches = 0L;

        for (var i = 0; i < decisions; i++) {
            searches += counts[i];
        }

        if (searches + capped == 0L) {
            return;
        }

        var seconds = Math.max(1.0, ticks / 20.0);
        text.append("\n== PATH CACHES (server-thread searches) ==\n");

        for (var i = 0; i < decisions && i < CACHE_DECISION_LABELS.length; i++) {
            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-40s %6d  (%5.1f%%, %.1f/s)%n",
                    CACHE_DECISION_LABELS[i],
                    counts[i],
                    searches > 0L ? 100.0 * counts[i] / searches : 0.0,
                    counts[i] / seconds
                )
            );
        }

        text.append(
            BLibPerfProfiler.formatLocale(
                "%-40s %6d  (%.1f/s)%n",
                "searches shrunk by the failed-search cap",
                capped,
                capped / seconds
            )
        );
    }

    /**
     * Oct 6 - where server-thread path searches spend their time, by phase. Phases nest (total_search contains the
     * per-node phases), so each line is shown against total_search rather than summed.
     */
    private static void appendPathPhases(StringBuilder text, long ticks) {
        var snapshot = BLibPerfProfiler.pathPhaseSnapshot();
        var nanos = snapshot[0];
        var calls = snapshot[1];
        var total = 0L;

        for (var phase = 0; phase < nanos.length; phase++) {
            if ("total_search".equals(BLibPerfProfiler.pathPhaseName(phase))) {
                total = nanos[phase];
            }
        }

        var any = false;
        for (var value : nanos) {
            any |= value > 0L;
        }

        if (!any) {
            return;
        }

        text.append("\n== PATH SEARCHES ON THE SERVER THREAD, BY PHASE (ms per server tick; % of total_search) ==\n");
        text.append(
            BLibPerfProfiler.formatLocale("%-32s %10s %10s %8s %10s%n", "phase", "calls", "ms/tick", "%", "us/call")
        );

        for (var phase = 0; phase < nanos.length; phase++) {
            if (nanos[phase] <= 0L) {
                continue;
            }

            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-32s %10d %10.3f %7.1f%% %10.1f%n",
                    BLibPerfProfiler.pathPhaseName(phase),
                    calls[phase],
                    nanosToMillis(nanos[phase]) / Math.max(1L, ticks),
                    total > 0L ? 100.0 * nanos[phase] / total : 0.0,
                    nanos[phase] / 1000.0 / Math.max(1L, calls[phase])
                )
            );
        }
    }

    /** Profiler v3: lines in the "inside the tick" table. */
    private static final int INSIDE_ROWS = Integer.MAX_VALUE;

    private static final String[] LABEL_KIND_NAMES = { "goal", "action", "code", "sensor" };

    /**
     * Profiler v3 - the named parts of each mob type's tick, heaviest first across every type: vanilla goals (part of
     * the goals column), BLib GOAP actions (part of AI; each type also gets a "(planning + sensors)" line for the AI
     * time that is not inside any action), and named laps in mod code (part of other). Per mob per tick, so lines from
     * different types compare directly; the total column is what the whole type costs the server each tick.
     */
    private static void appendInsideTheTick(StringBuilder text, List<Row> rows, long ticks) {
        record Line(
            String type,
            String kind,
            String label,
            long nanos,
            long calls,
            long entityTicks
        ) {}

        var lines = new ArrayList<Line>();

        for (var row : rows) {
            var actionNanos = 0L;

            for (var kind = 0; kind < BLibPerfProfiler.LABEL_KINDS; kind++) {
                for (var entry : row.labels[kind].entrySet()) {
                    var cell = entry.getValue();
                    lines.add(new Line(row.name, LABEL_KIND_NAMES[kind], entry.getKey(), cell[0], cell[1], row.entityTicks));

                    if (kind == BLibPerfProfiler.LABEL_ACTION || kind == BLibPerfProfiler.LABEL_SENSOR) {
                        actionNanos += cell[0];
                    }
                }
            }

            // What "other" holds besides the named code laps: vanilla's own entity tick outside the timed columns, and
            // any
            // mod code nobody has put a lap in yet. Its size says how much is still unexplained.
            var measured = row.aiNanos;

            for (var nanos : row.sectionNanos) {
                measured += nanos;
            }

            var codeNanos = 0L;

            for (var cell : row.labels[BLibPerfProfiler.LABEL_CODE].values()) {
                codeNanos += cell[0];
            }

            // Only for mobs that have labelled code or a GOAP brain (ours); a vanilla mob's "other" is all vanilla
            // anyway.
            if (!row.labels[BLibPerfProfiler.LABEL_CODE].isEmpty() || row.aiNanos > 0) {
                lines.add(
                    new Line(
                        row.name,
                        "code",
                        "(other, not labelled)",
                        Math.max(0L, row.tickNanos - measured - codeNanos),
                        0L,
                        row.entityTicks
                    )
                );
            }

            // Oct 6 - one total per GOAP mob type for all its sensors, so a type whose sensors are each too small to
            // reach the table still shows what sensing costs it in all (and 0 here means none were timed).
            if (row.aiNanos > 0) {
                var sensorNanos = 0L;
                var sensorCalls = 0L;

                for (var cell : row.labels[BLibPerfProfiler.LABEL_SENSOR].values()) {
                    sensorNanos += cell[0];
                    sensorCalls += cell[1];
                }

                lines.add(new Line(row.name, "sensor", "(all sensors)", sensorNanos, sensorCalls, row.entityTicks));
            }

            if (row.aiNanos > 0) {
                lines.add(
                    new Line(
                        row.name,
                        "action",
                        "(planning, outside sensors/actions)",
                        Math.max(0L, row.aiNanos - actionNanos),
                        0L,
                        row.entityTicks
                    )
                );
            }
        }

        if (lines.isEmpty()) {
            return;
        }

        lines.sort(Comparator.comparingLong(Line::nanos).reversed());

        text.append("\n== INSIDE THE TICK - every named part, heaviest first (us per mob per tick; total = ms per server tick) ==\n");
        text.append(
            BLibPerfProfiler.formatLocale(
                "%-28s %-6s %-40s %8s %8s %8s%n",
                "mob type",
                "kind",
                "name",
                "calls/t",
                "us/mob",
                "total"
            )
        );

        var shown = 0;

        for (var line : lines) {
            if (shown++ >= INSIDE_ROWS) {
                break;
            }

            var mobTicks = Math.max(1L, line.entityTicks());

            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-28s %-6s %-40s %8.2f %8.1f %8.3f%n",
                    truncate(line.type(), 28),
                    line.kind(),
                    truncate(line.label(), 40),
                    line.calls() / (double) mobTicks,
                    perEntityTickMicros(line.nanos(), line.entityTicks()),
                    nanosToMillis(line.nanos()) / Math.max(1L, ticks)
                )
            );
        }
    }

    /** Rows in the per-type breakdown table. */
    private static final int BREAKDOWN_ROWS = Integer.MAX_VALUE;

    /**
     * Oct 5 - where each mob's tick goes, in microseconds per entity tick. AI = its GOAP agent (planning, sensing and
     * the actions it runs, including the path searches they start); goals = sensing and the vanilla target and goal
     * selectors (a mod's own Goals run here); nav = following a path; step = Mob.customServerAiStep; ctrl = move, look
     * and jump controls; move = travel (physics and collision); other = everything else in the entity's tick - its own
     * tick code and vanilla's base tick.
     */
    private static void appendBreakdown(StringBuilder text, String title, List<Row> rows, int limit) {
        text.append("\n== WHERE THE TIME GOES - ").append(title).append(" (us per mob per tick) ==\n");
        text.append(
            BLibPerfProfiler.formatLocale(
                "%-36s %5s %8s %7s %7s %7s %7s %7s %7s %7s %7s%n",
                "name",
                "mobs",
                "total",
                "AI",
                "goals",
                "nav",
                "step",
                "ctrl",
                "move",
                "base",
                "other"
            )
        );

        var shown = 0;

        for (var row : rows) {
            if (shown++ >= limit) {
                break;
            }

            var measured = row.aiNanos;

            for (var nanos : row.sectionNanos) {
                measured += nanos;
            }

            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-36s %5d %8.1f %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f%n",
                    truncate(row.name, 36),
                    row.mobCount,
                    perEntityTickMicros(row.tickNanos, row.entityTicks),
                    perEntityTickMicros(row.aiNanos, row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_GOALS], row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_NAV], row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_MOB_STEP], row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_CONTROLS], row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_MOVE], row.entityTicks),
                    perEntityTickMicros(row.sectionNanos[BLibPerfProfiler.SECTION_BASE], row.entityTicks),
                    perEntityTickMicros(Math.max(0L, row.tickNanos - measured), row.entityTicks)
                )
            );
        }
    }

    private static int totalErrors(List<MobStats> mobs) {
        var total = 0;

        for (var stats : mobs) {
            total += (int) stats.errors;
        }

        return total;
    }

    private static void appendTable(StringBuilder text, List<Row> rows, int ticks, long serverTickNanos, int limit) {
        text.append(
            BLibPerfProfiler.formatLocale(
                "%-34s %5s %9s %8s %6s %8s %7s %7s %8s %5s %6s %7s %8s %5s%n",
                "name",
                "mobs",
                "per mob",
                "total ms",
                "%tick",
                "AI ms",
                "paths/s",
                "path ms",
                "nodes/s",
                "fail%",
                "part%",
                "scans/s",
                "scanned/s",
                "errs"
            )
        );

        var seconds = ticks / 20.0;

        for (var i = 0; i < Math.min(limit, rows.size()); i++) {
            var row = rows.get(i);
            var searches = row.pathSearches + row.vanillaPathSearches;

            text.append(
                BLibPerfProfiler.formatLocale(
                    "%-34s %5d %7.1fus %8.3f %5.1f%% %8.3f %7.1f %7.3f %8s %4.0f%% %5.0f%% %7.1f %9.0f %5d%n",
                    truncate(row.name, 34),
                    row.mobCount,
                    perEntityTickMicros(row.tickNanos, row.entityTicks),
                    nanosToMillis(row.tickNanos) / ticks,
                    percent(row.tickNanos, serverTickNanos),
                    nanosToMillis(row.aiNanos) / ticks,
                    searches / seconds,
                    nanosToMillis(row.pathNanos) / ticks,
                    row.pathSearches == 0 ? "-" : BLibPerfProfiler.formatLocale("%.0f", row.pathNodes / seconds),
                    searches == 0 ? 0.0 : 100.0 * row.pathFailures / searches,
                    searches == 0 ? 0.0 : 100.0 * row.pathPartials / searches,
                    row.scans / seconds,
                    row.scannedEntities / seconds,
                    row.errors
                )
            );
        }
    }

    private static String summaryLine(Row row, int ticks, long serverTickNanos) {
        return BLibPerfProfiler.formatLocale(
            "%s x%d: %.2f ms (%.1f%%), AI %.2f, paths %.1f/s",
            row.name,
            row.mobCount,
            nanosToMillis(row.tickNanos) / ticks,
            percent(row.tickNanos, serverTickNanos),
            nanosToMillis(row.aiNanos) / ticks,
            (row.pathSearches + row.vanillaPathSearches) / (ticks / 20.0)
        );
    }

    private static String describeMob(int rank, MobStats stats, int ticks) {
        return BLibPerfProfiler.formatLocale(
            "%d. %s #%d in %s at %d %d %d: %.0f µs/tick (AI %.0f), paths %d (%d failed), scans %d, errors %d",
            rank,
            stats.typeId,
            stats.entityId,
            stats.dimension,
            stats.x,
            stats.y,
            stats.z,
            perEntityTickMicros(stats.tickNanos, stats.ticks),
            perEntityTickMicros(stats.aiNanos, stats.ticks),
            stats.totalPathSearches(),
            stats.pathFailures,
            stats.scans,
            stats.errors
        );
    }

    private static Component teleportLine(int rank, MobStats stats, int ticks) {
        var command = "/execute in %s run tp @s %d %d %d".formatted(stats.dimension, stats.x, stats.y, stats.z);
        MutableComponent line = Component.literal(
            BLibPerfProfiler.formatLocale(
                " %d. %s at %d %d %d - %.0f µs/tick (AI %.0f)",
                rank,
                stats.typeId,
                stats.x,
                stats.y,
                stats.z,
                perEntityTickMicros(stats.tickNanos, stats.ticks),
                perEntityTickMicros(stats.aiNanos, stats.ticks)
            )
        );

        return line.withStyle(
            style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))
        );
    }

    private interface KeyFunction {

        String key(MobStats stats);
    }

    private static List<Row> aggregate(List<MobStats> mobs, KeyFunction keyFunction) {
        Map<String, Row> rows = new LinkedHashMap<>();

        for (var stats : mobs) {
            var row = rows.computeIfAbsent(keyFunction.key(stats), Row::new);
            row.mobCount++;
            row.entityTicks += stats.ticks;
            row.tickNanos += stats.tickNanos;
            row.aiNanos += stats.aiNanos;
            row.pathSearches += stats.pathSearches;
            row.vanillaPathSearches += stats.vanillaPathSearches;
            row.pathNanos += stats.pathNanos;
            row.pathNodes += stats.pathNodes;
            row.pathFailures += stats.pathFailures;
            row.pathPartials += stats.pathPartials;
            row.scans += stats.scans;
            row.scannedEntities += stats.scannedEntities;
            row.errors += stats.errors;

            for (var i = 0; i < BLibPerfProfiler.SECTION_COUNT; i++) {
                row.sectionNanos[i] += stats.sectionNanos[i];
            }

            for (var kind = 0; kind < BLibPerfProfiler.LABEL_KINDS; kind++) {
                for (var entry : stats.labels[kind].entrySet()) {
                    var cell = row.labels[kind].computeIfAbsent(entry.getKey(), $ -> new long[2]);
                    cell[0] += entry.getValue()[0];
                    cell[1] += entry.getValue()[1];
                }
            }
        }

        var sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparingLong((Row row) -> row.tickNanos).reversed());

        return sorted;
    }

    private static final class Row {

        final String name;

        int mobCount;

        long entityTicks;

        long tickNanos;

        long aiNanos;

        long pathSearches;

        long vanillaPathSearches;

        long pathNanos;

        long pathNodes;

        long pathFailures;

        long pathPartials;

        long scans;

        long scannedEntities;

        long errors;

        final long[] sectionNanos = new long[BLibPerfProfiler.SECTION_COUNT];

        @SuppressWarnings("unchecked")
        final Map<String, long[]>[] labels = new Map[] {
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>(),
            new HashMap<String, long[]>() };

        Row(String name) {
            this.name = name;
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double perEntityTickMicros(long nanos, long entityTicks) {
        return entityTicks == 0 ? 0.0 : nanos / 1_000.0 / entityTicks;
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0.0 : 100.0 * part / whole;
    }

    private static String truncate(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width - 1) + "~";
    }
}
