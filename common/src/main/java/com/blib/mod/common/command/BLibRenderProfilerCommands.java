package com.blib.mod.common.command;

import com.blib.internal.client.profiling.jfr.JfrRecorder;
import com.blib.internal.client.profiling.profile.ProfilerControl;
import com.blib.internal.client.profiling.profile.ReportWriter;
import com.blib.mod.BLib;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The {@code /blib_render_profiler} client command, built over the loader's command source type:
 * <ul>
 * <li>{@code start} / {@code start jfr}: begin a session, optionally with a JFR recording alongside</li>
 * <li>{@code stop [label]}: end the session and write reports to {@code <game dir>/azurelibprofiler/}</li>
 * <li>{@code reset}: discard data collected so far and keep going</li>
 * <li>{@code status}: show elapsed time and entry count</li>
 * </ul>
 */
@ApiStatus.Internal
public final class BLibRenderProfilerCommands {

    private static final int SUMMARY_ROWS = 8;

    private BLibRenderProfilerCommands() {}

    public static <S> LiteralArgumentBuilder<S> build(BiConsumer<S, Component> feedback) {
        return LiteralArgumentBuilder.<S>literal("renderprofiler")
            .then(
                LiteralArgumentBuilder.<S>literal("start")
                    .executes(ctx -> start(ctx, feedback, false))
                    .then(LiteralArgumentBuilder.<S>literal("jfr").executes(ctx -> start(ctx, feedback, true)))
            )
            .then(
                LiteralArgumentBuilder.<S>literal("stop")
                    .executes(ctx -> stop(ctx, feedback, null))
                    .then(
                        RequiredArgumentBuilder.<S, String>argument("label", StringArgumentType.greedyString())
                            .executes(ctx -> stop(ctx, feedback, StringArgumentType.getString(ctx, "label")))
                    )
            )
            .then(LiteralArgumentBuilder.<S>literal("reset").executes(ctx -> reset(ctx, feedback)))
            .then(LiteralArgumentBuilder.<S>literal("status").executes(ctx -> status(ctx, feedback)));
    }

    private static <S> int start(CommandContext<S> ctx, BiConsumer<S, Component> feedback, boolean withJfr) {
        var source = ctx.getSource();

        if (!ProfilerControl.start()) {
            feedback.accept(source, Component.literal("BLib Render profiler is already running."));
            return 0;
        }

        if (withJfr) {
            try {
                JfrRecorder.start();
            } catch (Exception e) {
                BLib.LOGGER.warn("Could not start JFR recording", e);
                feedback.accept(source, Component.literal("JFR unavailable (" + e.getMessage() + "), timing only."));
            }
        }

        feedback.accept(
            source,
            Component.literal("BLib Render profiler started" + (JfrRecorder.isRecording() ? " with JFR." : "."))
        );
        return 1;
    }

    private static <S> int stop(CommandContext<S> ctx, BiConsumer<S, Component> feedback, String label) {
        var source = ctx.getSource();

        try {
            var result = ProfilerControl.stop(label);

            if (result == null) {
                feedback.accept(source, Component.literal("BLib Render profiler is not running."));
                return 0;
            }

            var session = result.session();
            feedback.accept(
                source,
                Component.literal(
                    String.format(Locale.ROOT, "Profiled %.1f s. Top stages by self time:", session.wallNanos() / 1e9)
                )
            );

            for (var row : result.rows().subList(0, Math.min(SUMMARY_ROWS, result.rows().size()))) {
                feedback.accept(
                    source,
                    Component.literal(
                        String.format(
                            Locale.ROOT,
                            "  %5.2f%%  %s  %s  (p99 %.0f us)",
                            ReportWriter.threadPercent(row, session),
                            row.stage(),
                            row.subject(),
                            row.p99Nanos() / 1e3
                        )
                    )
                );
            }

            feedback.accept(source, Component.literal("Report: " + result.report()));

            if (result.jfr() != null) {
                feedback.accept(source, Component.literal("JFR: " + result.jfr()));
            }

            return 1;
        } catch (Exception e) {
            BLib.LOGGER.error("Failed to write BLib Render profiler report", e);
            feedback.accept(source, Component.literal("Failed to write report: " + e.getMessage()));
            return 0;
        }
    }

    private static <S> int reset(CommandContext<S> ctx, BiConsumer<S, Component> feedback) {
        if (!ProfilerControl.isRunning()) {
            feedback.accept(ctx.getSource(), Component.literal("BLib Render profiler is not running."));
            return 0;
        }

        ProfilerControl.reset();
        feedback.accept(ctx.getSource(), Component.literal("BLib Render profiler data reset."));
        return 1;
    }

    private static <S> int status(CommandContext<S> ctx, BiConsumer<S, Component> feedback) {
        var session = ProfilerControl.session();

        if (session == null) {
            feedback.accept(ctx.getSource(), Component.literal("BLib Render profiler is not running."));
            return 0;
        }

        feedback.accept(
            ctx.getSource(),
            Component.literal(
                String.format(
                    Locale.ROOT,
                    "Running %.1f s, %d entries, %d unbalanced%s",
                    session.wallNanos() / 1e9,
                    session.entryCount(),
                    session.unbalanced(),
                    JfrRecorder.isRecording() ? ", JFR on" : ""
                )
            )
        );
        return 1;
    }
}
