package org.keinus.logparser.application.pipeline;

/** Delays after consecutive failures; a successful delivery resets the count. */
final class OutputRetryPolicy {
    private OutputRetryPolicy() {}

    static long delayMillis(long failures) {
        long doublings = Math.min(10, Math.max(0, failures - 3));
        return Math.min(3_600_000L, 5_000L << doublings);
    }
}
