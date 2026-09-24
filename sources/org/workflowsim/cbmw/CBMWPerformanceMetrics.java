package org.workflowsim.cbmw;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

/**
 * Opt-in performance counters for deterministic baseline runs.
 *
 * The counters are disabled by default and do not participate in scheduling
 * decisions or the normal result schema. Enable them with
 * {@code -Dcbmw.perf.metrics=true} and select the CSV destination with
 * {@code -Dcbmw.perf.metrics.file=<path>}.
 */
public final class CBMWPerformanceMetrics {

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("cbmw.perf.metrics", "false"));
    private static final String OUTPUT_FILE = System.getProperty(
            "cbmw.perf.metrics.file", "Output/performance_metrics.csv");

    private static String scenario;
    private static String algorithm;
    private static int workflowCount;
    private static int run;
    private static long wallStartNanos;
    private static long cpuStartNanos;

    private static long cloudletUpdateEvents;
    private static long blockedBeforeVmAck;
    private static long periodicDeferrals;
    private static long periodicWakeSchedules;
    private static long schedulingPasses;
    private static long readyQueueScanCalls;
    private static long readyQueueScanItems;
    private static long preemptionSortCalls;
    private static long preemptionSortItems;
    private static long dynamicSortCalls;
    private static long dynamicSortItems;
    private static long maxReadyQueue;
    private static long planningNanos;
    private static long dispatchNanos;
    private static long planningAttempts;

    private CBMWPerformanceMetrics() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void beginScenario(String scenarioName, String algorithmName,
                                     int workflows, int runNumber) {
        if (!ENABLED) return;
        scenario = scenarioName;
        algorithm = algorithmName;
        workflowCount = workflows;
        run = runNumber;
        cloudletUpdateEvents = 0L;
        blockedBeforeVmAck = 0L;
        periodicDeferrals = 0L;
        periodicWakeSchedules = 0L;
        schedulingPasses = 0L;
        readyQueueScanCalls = 0L;
        readyQueueScanItems = 0L;
        preemptionSortCalls = 0L;
        preemptionSortItems = 0L;
        dynamicSortCalls = 0L;
        dynamicSortItems = 0L;
        maxReadyQueue = 0L;
        planningNanos = 0L;
        dispatchNanos = 0L;
        planningAttempts = 0L;
        resetHeapPeaks();
        cpuStartNanos = processCpuTimeNanos();
        wallStartNanos = System.nanoTime();
    }

    public static void recordCloudletUpdateEvent() {
        if (ENABLED) cloudletUpdateEvents++;
    }

    public static void recordBlockedBeforeVmAck() {
        if (ENABLED) blockedBeforeVmAck++;
    }

    public static void recordPeriodicDeferral() {
        if (ENABLED) periodicDeferrals++;
    }

    public static void recordPeriodicWakeScheduled() {
        if (ENABLED) periodicWakeSchedules++;
    }

    public static void recordSchedulingPass(int readyQueueSize) {
        if (!ENABLED) return;
        schedulingPasses++;
        updateMaxReadyQueue(readyQueueSize);
    }

    public static void recordReadyQueueScan(int readyQueueSize) {
        if (!ENABLED) return;
        readyQueueScanCalls++;
        readyQueueScanItems += readyQueueSize;
        updateMaxReadyQueue(readyQueueSize);
    }

    public static void recordPreemptionSort(int readyQueueSize) {
        if (!ENABLED) return;
        preemptionSortCalls++;
        preemptionSortItems += readyQueueSize;
        updateMaxReadyQueue(readyQueueSize);
    }

    public static void recordDynamicSort(int readyQueueSize) {
        if (!ENABLED) return;
        dynamicSortCalls++;
        dynamicSortItems += readyQueueSize;
        updateMaxReadyQueue(readyQueueSize);
    }

    public static void recordPlanningTime(long elapsedNanos) {
        if (ENABLED) {
            planningNanos += Math.max(0L, elapsedNanos);
            planningAttempts++;
        }
    }

    public static void recordDispatchTime(long elapsedNanos) {
        if (ENABLED) dispatchNanos += Math.max(0L, elapsedNanos);
    }

    public static void finishScenario() {
        if (!ENABLED) return;
        long wallNanos = System.nanoTime() - wallStartNanos;
        long cpuEndNanos = processCpuTimeNanos();
        long cpuNanos = cpuStartNanos >= 0L && cpuEndNanos >= cpuStartNanos
                ? cpuEndNanos - cpuStartNanos : -1L;
        long peakHeapBytes = peakHeapBytes();
        appendCsv(wallNanos, cpuNanos, peakHeapBytes);
    }

    private static void updateMaxReadyQueue(int size) {
        if (size > maxReadyQueue) maxReadyQueue = size;
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        }
    }

    private static long peakHeapBytes() {
        long total = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                total += Math.max(0L, pool.getPeakUsage().getUsed());
            }
        }
        return total;
    }

    private static long processCpuTimeNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean)
                    .getProcessCpuTime();
        }
        return -1L;
    }

    private static void appendCsv(long wallNanos, long cpuNanos,
                                  long peakHeapBytes) {
        File output = new File(OUTPUT_FILE);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException(
                    "Could not create performance metrics directory: " + parent);
        }
        boolean writeHeader = !output.exists() || output.length() == 0L;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output, true))) {
            if (writeHeader) {
                writer.write("scenario,algorithm,workflowCount,run,wallSeconds,"
                        + "processCpuSeconds,peakHeapBytes,cloudletUpdateEvents,"
                        + "blockedBeforeVmAck,periodicDeferrals,periodicWakeSchedules,"
                        + "schedulingPasses,readyQueueScanCalls,readyQueueScanItems,"
                        + "preemptionSortCalls,preemptionSortItems,dynamicSortCalls,"
                        + "dynamicSortItems,maxReadyQueue,planningAttempts,"
                        + "planningSeconds,dispatchSeconds,meanPlanningSeconds,"
                        + "meanDispatchSeconds");
                writer.newLine();
            }
            writer.write(csv(scenario));
            writer.write(',');
            writer.write(csv(algorithm));
            writer.write(',');
            writer.write(Integer.toString(workflowCount));
            writer.write(',');
            writer.write(Integer.toString(run));
            writer.write(',');
            writer.write(formatSeconds(wallNanos));
            writer.write(',');
            writer.write(cpuNanos >= 0L ? formatSeconds(cpuNanos) : "");
            writer.write(',');
            writer.write(Long.toString(peakHeapBytes));
            writer.write(',');
            writer.write(Long.toString(cloudletUpdateEvents));
            writer.write(',');
            writer.write(Long.toString(blockedBeforeVmAck));
            writer.write(',');
            writer.write(Long.toString(periodicDeferrals));
            writer.write(',');
            writer.write(Long.toString(periodicWakeSchedules));
            writer.write(',');
            writer.write(Long.toString(schedulingPasses));
            writer.write(',');
            writer.write(Long.toString(readyQueueScanCalls));
            writer.write(',');
            writer.write(Long.toString(readyQueueScanItems));
            writer.write(',');
            writer.write(Long.toString(preemptionSortCalls));
            writer.write(',');
            writer.write(Long.toString(preemptionSortItems));
            writer.write(',');
            writer.write(Long.toString(dynamicSortCalls));
            writer.write(',');
            writer.write(Long.toString(dynamicSortItems));
            writer.write(',');
            writer.write(Long.toString(maxReadyQueue));
            writer.write(',');
            writer.write(Long.toString(planningAttempts));
            writer.write(',');
            writer.write(formatSeconds(planningNanos));
            writer.write(',');
            writer.write(formatSeconds(dispatchNanos));
            writer.write(',');
            writer.write(formatSeconds(planningAttempts == 0 ? 0
                    : planningNanos / planningAttempts));
            writer.write(',');
            writer.write(formatSeconds(schedulingPasses == 0 ? 0
                    : dispatchNanos / schedulingPasses));
            writer.newLine();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not write performance metrics: " + output, e);
        }
    }

    private static String formatSeconds(long nanos) {
        return String.format(java.util.Locale.US, "%.6f", nanos / 1_000_000_000.0);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
