package com.blib.internal.client.profiling.profile;

import com.blib.api.client.profiling.v1.AzProfileStage;

/** One aggregated line of a profiling report. All times are in nanoseconds. */
public record ReportRow(
    AzProfileStage stage,
    String subject,
    long calls,
    long totalNanos,
    long selfNanos,
    long p50Nanos,
    long p99Nanos,
    long maxNanos
) {

    public double meanNanos() {
        return calls == 0 ? 0 : (double) totalNanos / calls;
    }
}
