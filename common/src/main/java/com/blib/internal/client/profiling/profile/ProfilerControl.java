package com.blib.internal.client.profiling.profile;

import com.blib.api.client.profiling.v1.AzProfiler;
import com.blib.internal.client.profiling.jfr.JfrRecorder;
import com.blib.mod.BLib;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Owns the active session. The listener is only installed while a session runs, so AzureLib's hooks cost nothing
 * otherwise. All methods are meant to be called from the client thread.
 */
public final class ProfilerControl {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static ProfilerSession session;

    private ProfilerControl() {}

    public static boolean isRunning() {
        return session != null;
    }

    public static ProfilerSession session() {
        return session;
    }

    /** Starts a session on the calling thread. Returns {@code false} if one is already running. */
    public static boolean start() {
        if (session != null) {
            return false;
        }

        // Render-thread session regardless of which thread runs the command (see ProfilerSession#isOwnerThread).
        session = new ProfilerSession(null);
        var previous = AzProfiler.setListener(session);

        if (previous != null) {
            BLib.LOGGER.warn("Replaced an existing AzureLib profiler listener: {}", previous);
        }

        return true;
    }

    /** Discards the collected data and keeps profiling. */
    public static void reset() {
        if (session == null) {
            return;
        }

        session.stop();
        session = new ProfilerSession(null);
        AzProfiler.setListener(session);
    }

    /**
     * Stops the session and writes its reports, plus the JFR recording if one is running.
     *
     * @param label optional name added to the file names, for telling runs apart
     */
    public static Result stop(String label) throws Exception {
        if (session == null) {
            return null;
        }

        var finished = session;
        finished.stop();

        if (AzProfiler.getListener() == finished) {
            AzProfiler.setListener(null);
        }

        session = null;

        var dir = outputDirectory();
        var base = STAMP.format(LocalDateTime.now()) + (label == null || label.isBlank() ? "" : "_" + sanitize(label));
        var rows = ReportWriter.sortedRows(finished);
        var txt = dir.resolve(base + ".txt");
        ReportWriter.write(finished, rows, dir.resolve(base + ".csv"), txt);

        Path jfr = null;

        if (JfrRecorder.isRecording()) {
            jfr = dir.resolve(base + ".jfr");
            JfrRecorder.stop(jfr);
        }

        return new Result(finished, rows, txt, jfr);
    }

    public static Path outputDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(BLib.MOD_ID);
    }

    private static String sanitize(String label) {
        return label.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    public record Result(
        ProfilerSession session,
        List<ReportRow> rows,
        Path report,
        Path jfr
    ) {}
}
