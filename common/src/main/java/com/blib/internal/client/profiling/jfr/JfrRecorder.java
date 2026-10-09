package com.blib.internal.client.profiling.jfr;

import java.nio.file.Path;

/**
 * Optional Java Flight Recorder capture that runs alongside a profiling session, giving method sampling and allocation
 * samples for the same window. The JFR classes live in {@code JfrSession}, which is only loaded once the
 * {@code jdk.jfr} module is known to be present: trimmed runtimes, including some launcher-bundled ones, leave it out.
 */
@SuppressWarnings("unused")
public final class JfrRecorder {

    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private JfrRecorder() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isRecording() {
        return AVAILABLE && JfrSession.isRecording();
    }

    /** Starts a recording with the JDK's {@code profile} settings. */
    public static void start() throws Exception {
        if (!AVAILABLE) {
            throw new IllegalStateException("This Java runtime has no jdk.jfr module");
        }

        JfrSession.start();
    }

    /** Stops the recording and writes it to {@code file}. */
    public static void stop(Path file) throws Exception {
        if (AVAILABLE) {
            JfrSession.stop(file);
        }
    }
}
