package com.blib.internal.client.profiling.jfr;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

/** Holds the JFR types. Only touch this class through {@link JfrRecorder}. */
final class JfrSession {

    private static Recording recording;

    private JfrSession() {}

    static boolean isRecording() {
        return recording != null;
    }

    static void start() throws IOException, ParseException {
        if (recording != null) {
            return;
        }

        var configuration = Configuration.getConfiguration("profile");
        recording = new Recording(configuration);
        recording.setName("AzureLib Profiler");
        recording.start();
    }

    static void stop(Path file) throws IOException {
        if (recording == null) {
            return;
        }

        try {
            recording.stop();
            Files.createDirectories(file.getParent());
            recording.dump(file);
        } finally {
            recording.close();
            recording = null;
        }
    }
}
