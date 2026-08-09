package org.workflowsim.cbmw;

/** Point-in-time resource utilization accounting. */
public class UtilizationSnapshot {
    private final double time;
    private final int totalReservedCores;
    private final int totalOnDemandCores;
    private final int totalSpotCores;
    private final int totalReservedRamMb;
    private final int totalOnDemandRamMb;
    private final int totalSpotRamMb;
    private final int runningReservedCores;
    private final int runningOnDemandCores;
    private final int runningSpotCores;
    private final int runningReservedRamMb;
    private final int runningOnDemandRamMb;
    private final int runningSpotRamMb;
    private final int runningCores;
    private final int runningRamMb;
    private final double reservedUtilization;
    private final double overallUtilization;
    private final double reservedRamUtilization;
    private final double overallRamUtilization;

    public UtilizationSnapshot(double time, int totalReservedCores, int totalOnDemandCores,
                               int totalReservedRamMb, int totalOnDemandRamMb,
                               int runningReservedCores, int runningOnDemandCores,
                               int runningReservedRamMb, int runningOnDemandRamMb) {
        this(time, totalReservedCores, totalOnDemandCores, 0,
                totalReservedRamMb, totalOnDemandRamMb, 0,
                runningReservedCores, runningOnDemandCores, 0,
                runningReservedRamMb, runningOnDemandRamMb, 0);
    }

    public UtilizationSnapshot(double time,
                               int totalReservedCores, int totalOnDemandCores,
                               int totalSpotCores,
                               int totalReservedRamMb, int totalOnDemandRamMb,
                               int totalSpotRamMb,
                               int runningReservedCores, int runningOnDemandCores,
                               int runningSpotCores,
                               int runningReservedRamMb, int runningOnDemandRamMb,
                               int runningSpotRamMb) {
        requireFiniteNonNegative("snapshot time", time);
        requireValidUsage("reserved cores", runningReservedCores,
                totalReservedCores);
        requireValidUsage("on-demand cores", runningOnDemandCores,
                totalOnDemandCores);
        requireValidUsage("spot cores", runningSpotCores, totalSpotCores);
        requireValidUsage("reserved RAM", runningReservedRamMb,
                totalReservedRamMb);
        requireValidUsage("on-demand RAM", runningOnDemandRamMb,
                totalOnDemandRamMb);
        requireValidUsage("spot RAM", runningSpotRamMb, totalSpotRamMb);
        this.time = time;
        this.totalReservedCores = totalReservedCores;
        this.totalOnDemandCores = totalOnDemandCores;
        this.totalSpotCores = totalSpotCores;
        this.totalReservedRamMb = totalReservedRamMb;
        this.totalOnDemandRamMb = totalOnDemandRamMb;
        this.totalSpotRamMb = totalSpotRamMb;
        this.runningReservedCores = runningReservedCores;
        this.runningOnDemandCores = runningOnDemandCores;
        this.runningSpotCores = runningSpotCores;
        this.runningReservedRamMb = runningReservedRamMb;
        this.runningOnDemandRamMb = runningOnDemandRamMb;
        this.runningSpotRamMb = runningSpotRamMb;
        this.runningCores = runningReservedCores + runningOnDemandCores
                + runningSpotCores;
        this.runningRamMb = runningReservedRamMb + runningOnDemandRamMb
                + runningSpotRamMb;
        this.reservedUtilization = totalReservedCores > 0
                ? (double) runningReservedCores / totalReservedCores : 0.0;
        int totalCores = totalReservedCores + totalOnDemandCores + totalSpotCores;
        this.overallUtilization = totalCores > 0 ? (double) runningCores / totalCores : 0.0;
        this.reservedRamUtilization = totalReservedRamMb > 0
                ? (double) runningReservedRamMb / totalReservedRamMb : 0.0;
        int totalRam = totalReservedRamMb + totalOnDemandRamMb + totalSpotRamMb;
        this.overallRamUtilization = totalRam > 0
                ? (double) runningRamMb / totalRam : 0.0;
    }

    public double getTime() { return time; }
    public int getTotalReservedCores() { return totalReservedCores; }
    public int getTotalOnDemandCores() { return totalOnDemandCores; }
    public int getTotalSpotCores() { return totalSpotCores; }
    public int getTotalCores() {
        return totalReservedCores + totalOnDemandCores + totalSpotCores;
    }
    public int getTotalReservedRamMb() { return totalReservedRamMb; }
    public int getTotalOnDemandRamMb() { return totalOnDemandRamMb; }
    public int getTotalSpotRamMb() { return totalSpotRamMb; }
    public int getTotalRamMb() {
        return totalReservedRamMb + totalOnDemandRamMb + totalSpotRamMb;
    }
    public int getRunningReservedCores() { return runningReservedCores; }
    public int getRunningOnDemandCores() { return runningOnDemandCores; }
    public int getRunningSpotCores() { return runningSpotCores; }
    public int getRunningReservedRamMb() { return runningReservedRamMb; }
    public int getRunningOnDemandRamMb() { return runningOnDemandRamMb; }
    public int getRunningSpotRamMb() { return runningSpotRamMb; }
    public int getRunningCores() { return runningCores; }
    public int getRunningRamMb() { return runningRamMb; }
    public double getReservedUtilization() { return reservedUtilization; }
    public double getOverallUtilization() { return overallUtilization; }
    public double getReservedRamUtilization() { return reservedRamUtilization; }
    public double getOverallRamUtilization() { return overallRamUtilization; }

    private static void requireFiniteNonNegative(String label, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(label
                    + " must be finite and non-negative");
        }
    }

    private static void requireValidUsage(String label, int running, int total) {
        if (total < 0 || running < 0 || running > total) {
            throw new IllegalArgumentException(label + " must satisfy 0 <= running <= total"
                    + " (running=" + running + ", total=" + total + ")");
        }
    }
}
