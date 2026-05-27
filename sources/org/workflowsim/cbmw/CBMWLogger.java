package org.workflowsim.cbmw;

import java.io.FileWriter;
import java.io.PrintWriter;
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

    public static final String LOG_FILE = "cbmw_detail.log";

    /** Only these tags produce output. Everything else is silently dropped. */
    private static final Set<String> LOGGED_TAGS = Set.of(
            "NEGOTIATE",      // workflow accepted or rejected (with reason)
            "DISPATCH",       // task assigned to a VM (ready -> running)
            "TASK-COMPLETE",  // task finished (running -> done)
            "WF-COMPLETE"     // workflow done, deadline met or missed
    );

    private static PrintWriter writer;

    /** Opens (or truncates) the log file. Must be called once per scenario run. */
    public static void init() {
        try {
            if (writer != null) { writer.flush(); writer.close(); }
            writer = new PrintWriter(new FileWriter(LOG_FILE, false));
            writeLine("INIT", "=== CBMW detail log opened ===");
        } catch (Exception e) {
            System.err.println("CBMWLogger: cannot open " + LOG_FILE + ": " + e.getMessage());
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
        writer.flush();
    }
}
