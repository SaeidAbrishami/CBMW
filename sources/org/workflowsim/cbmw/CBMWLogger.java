package org.workflowsim.cbmw;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Set;
import org.cloudbus.cloudsim.core.CloudSim;

/**
 * Flush-on-every-write logger for CBMW workflow and task state changes.
 * Output goes to cbmw_detail.log in the working directory.
 *
 * Only events in LOGGED_TAGS are written; all other tags are silently dropped.
 *
 * Format per line:
 *   [t=<sim-time>][<TAG>] <message>
 *
 * Call CBMWLogger.init() before startSimulation() and CBMWLogger.close() after.
 */
public class CBMWLogger {

    /** Only these tags produce output. Everything else is silently dropped. */
    private static final Set<String> LOGGED_TAGS = Set.of(
            "NEGOTIATE",      // workflow accepted or rejected (with reason)
            "DISPATCH",       // task assigned to a VM (ready -> running)
            "TASK-COMPLETE",  // task finished (running -> done)
            "WF-COMPLETE",    // workflow done, deadline met or missed
            "NOSF-PREPROCESS",
            "NOSF-ALLOCATE",
            "NOSF-FEEDBACK"
    );

    private static PrintWriter writer;
    private static String currentLogFile = "cbmw_detail.log";

    /** Returns the path of the currently open log file. */
    public static String getLogFile() { return currentLogFile; }

    /** Opens (or truncates) the given log file. Must be called once per scenario run. */
    public static void init(String logFile) {
        currentLogFile = logFile;
        try {
            if (writer != null) { writer.flush(); writer.close(); }
            writer = new PrintWriter(new FileWriter(logFile, false));
            writeLine("INIT", "=== CBMW detail log opened ===");
        } catch (Exception e) {
            System.err.println("CBMWLogger: cannot open " + logFile + ": " + e.getMessage());
        }
    }

    /** Disables detail logging for fast runs while preserving the current path. */
    public static void disable(String logFile) {
        currentLogFile = logFile;
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    /**
     * Writes one log line if tag is in LOGGED_TAGS.
     * Safe to call at any time; silently does nothing if init() was not called.
     */
    public static void log(String tag, String message) {
        if (writer == null || !LOGGED_TAGS.contains(tag)) return;
        writeLine(tag, message);
    }

    /** Formats only when this tag is actively being written. */
    public static void logf(String tag, String format, Object... args) {
        if (writer == null || !LOGGED_TAGS.contains(tag)) return;
        writeLine(tag, String.format(Locale.US, format, args));
    }

    /** Flushes and closes the log file. */
    public static void close() {
        if (writer == null) return;
        writeLine("CLOSE", "=== CBMW detail log closed ===");
        writer.flush();
        writer.close();
        writer = null;
    }

    private static void writeLine(String tag, String message) {
        double t;
        try { t = CloudSim.clock(); } catch (Exception e) { t = -1.0; }
        writer.printf("[t=%12.4f][%-22s] %s%n", t, tag, message);
    }
}
