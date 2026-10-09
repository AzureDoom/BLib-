package com.blib.internal.client.profiling.profile;

/**
 * Timing statistics for one (stage, root, subject) entry. Durations go into a log-linear histogram with 8 sub-buckets
 * per power of two, so percentiles are accurate to within about 12%. Recording never allocates.
 */
public final class StageStats {

    private static final int SUB_BITS = 3;

    private static final int SUB = 1 << SUB_BITS;

    private static final int BUCKETS = (64 - SUB_BITS) * SUB;

    private final long[] histogram = new long[BUCKETS];

    long count;

    long totalNanos;

    long selfNanos;

    long maxNanos;

    void record(long total, long self) {
        if (total < 0) {
            total = 0;
        }

        count++;
        totalNanos += total;
        selfNanos += Math.max(self, 0);

        if (total > maxNanos) {
            maxNanos = total;
        }

        histogram[bucket(total)]++;
    }

    /** Inclusive-time percentile in nanoseconds, {@code p} in [0, 1]. Returns the upper edge of the bucket. */
    long percentile(double p) {
        if (count == 0) {
            return 0;
        }

        long target = Math.max(1, (long) Math.ceil(p * count));
        long seen = 0;

        for (int b = 0; b < BUCKETS; b++) {
            seen += histogram[b];

            if (seen >= target) {
                return Math.min(upperBound(b), maxNanos);
            }
        }

        return maxNanos;
    }

    private static int bucket(long value) {
        if (value < SUB) {
            return (int) value;
        }

        int exponent = 63 - Long.numberOfLeadingZeros(value);
        int sub = (int) ((value >>> (exponent - SUB_BITS)) & (SUB - 1));
        return (exponent - SUB_BITS + 1) * SUB + sub;
    }

    private static long upperBound(int bucket) {
        if (bucket < SUB) {
            return bucket;
        }

        int exponent = bucket / SUB + SUB_BITS - 1;
        int sub = bucket % SUB;
        long width = 1L << (exponent - SUB_BITS);
        return ((long) (SUB + sub) << (exponent - SUB_BITS)) + width - 1;
    }
}
