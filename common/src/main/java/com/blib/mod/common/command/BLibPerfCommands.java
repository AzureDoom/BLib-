package com.blib.mod.common.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import com.blib.internal.common.perf.BLibPerfProfiler;

/**
 * {@code /blib perf start [seconds]}, {@code /blib perf stop}, {@code /blib perf status}.
 * <p>
 * Oct 5 - the measuring tool for the performance programme. Op level 2 comes from the {@code /blib} root. A session
 * runs for a fixed window (60 seconds unless told otherwise, 5 to 600), then prints a summary to whoever started it and
 * writes the full tables to {@code logs/blib-perf/}. Nothing is recorded while no session runs.
 * </p>
 */
@ApiStatus.Internal
public final class BLibPerfCommands {

    private static final int DEFAULT_SECONDS = 60;

    private BLibPerfCommands() {
        throw new UnsupportedOperationException();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perf")
            .then(
                Commands.literal("start")
                    .executes(context -> start(context.getSource(), DEFAULT_SECONDS))
                    .then(
                        Commands.argument("seconds", IntegerArgumentType.integer(5, 600))
                            .executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))
                    )
            )
            .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
            .then(Commands.literal("status").executes(context -> status(context.getSource())));
    }

    private static int start(CommandSourceStack source, int seconds) {
        var replaced = !BLibPerfProfiler.start(source.getServer(), source, seconds);

        source.sendSuccess(
            () -> Component.literal(
                "BLib perf: profiling for %d s%s. Use /blib perf stop to end early.".formatted(
                    seconds,
                    replaced ? " (the previous session was discarded)" : ""
                )
            ).withStyle(ChatFormatting.GOLD),
            true
        );

        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!BLibPerfProfiler.isActive()) {
            source.sendFailure(Component.literal("BLib perf: no session is running."));
            return 0;
        }

        BLibPerfProfiler.stop();

        return 1;
    }

    private static int status(CommandSourceStack source) {
        var status = BLibPerfProfiler.status();
        source.sendSuccess(() -> Component.literal("BLib perf: " + (status == null ? "idle" : status)), false);

        return 1;
    }
}
