package com.blib.internal.client.profiling.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Writes a session as a CSV (for diffing runs before and after a change) and an aligned text table (for reading). Rows
 * are sorted by self time, the time a stage spent outside its child stages.
 */
public final class ReportWriter {

    private static final Comparator<ReportRow> BY_SELF = Comparator.comparingLong(ReportRow::selfNanos).reversed();

    private ReportWriter() {}

    public static List<ReportRow> sortedRows(ProfilerSession session) {
        var rows = session.rows();
        rows.sort(BY_SELF);
        return rows;
    }

    public static void write(ProfilerSession session, List<ReportRow> rows, Path csv, Path txt) throws IOException {
        Files.createDirectories(csv.getParent());
        double wallSeconds = session.wallNanos() / 1e9;
        Files.writeString(csv, csv(rows, wallSeconds));
        Files.writeString(txt, text(session, rows, wallSeconds));
    }

    /** Self time as a percentage of the session's wall time, i.e. of the render thread. */
    public static double threadPercent(ReportRow row, ProfilerSession session) {
        return 100.0 * row.selfNanos() / Math.max(1, session.wallNanos());
    }

    private static String csv(List<ReportRow> rows, double wallSeconds) {
        var out = new StringBuilder(
            "stage,subject,calls,calls_per_sec,total_ms,self_ms,self_ms_per_sec,mean_us,p50_us,p99_us,max_us\n"
        );

        for (var row : rows) {
            out.append(row.stage())
                .append(',')
                .append(csvField(row.subject()))
                .append(',')
                .append(row.calls())
                .append(',')
                .append(fmt(row.calls() / wallSeconds))
                .append(',')
                .append(fmt(row.totalNanos() / 1e6))
                .append(',')
                .append(fmt(row.selfNanos() / 1e6))
                .append(',')
                .append(fmt(row.selfNanos() / 1e6 / wallSeconds))
                .append(',')
                .append(fmt(row.meanNanos() / 1e3))
                .append(',')
                .append(fmt(row.p50Nanos() / 1e3))
                .append(',')
                .append(fmt(row.p99Nanos() / 1e3))
                .append(',')
                .append(fmt(row.maxNanos() / 1e3))
                .append('\n');
        }

        return out.toString();
    }

    private static String text(ProfilerSession session, List<ReportRow> rows, double wallSeconds) {
        var out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "AzureLib profile: %.1f s, %d entries%n", wallSeconds, rows.size()));

        if (session.unbalanced() > 0 || session.overflowed() > 0) {
            out.append(
                String.format(
                    Locale.ROOT,
                    "Dropped: %d unbalanced stage calls, %d over max depth%n",
                    session.unbalanced(),
                    session.overflowed()
                )
            );
        }

        out.append("Sorted by self time. % thread = self time / wall time.\n\n");
        out.append(
            String.format(
                Locale.ROOT,
                "%-18s %8s %9s %10s %10s %10s %10s %s%n",
                "stage",
                "% thread",
                "calls/s",
                "mean us",
                "p50 us",
                "p99 us",
                "max us",
                "subject"
            )
        );

        for (var row : rows) {
            out.append(
                String.format(
                    Locale.ROOT,
                    "%-18s %7.2f%% %9.1f %10.1f %10.1f %10.1f %10.1f %s%n",
                    row.stage(),
                    threadPercent(row, session),
                    row.calls() / wallSeconds,
                    row.meanNanos() / 1e3,
                    row.p50Nanos() / 1e3,
                    row.p99Nanos() / 1e3,
                    row.maxNanos() / 1e3,
                    row.subject()
                )
            );
        }

        return out.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
