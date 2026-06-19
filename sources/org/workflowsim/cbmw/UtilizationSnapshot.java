package org.workflowsim.cbmw;

/** Point-in-time resource utilization accounting. */
public class UtilizationSnapshot {
    private final double time;
    private final int totalReservedCores;
    private final int totalOnDemandCores;
    private final int totalReservedRamMb;
    private final int totalOnDemandRamMb;
    private final int runningReservedCores;
    private final int runningOnDemandCores;
    private final int runningReservedRamMb;
    private final int runningOnDemandRamMb;
    private final int runningCores;
    private final double reservedUtilization;
    private final double overallUtilization;
    private final double reservedRamUtilization;
    private final double overallRamUtilization;

    public UtilizationSnapshot(double time, int totalReservedCores, int totalOnDemandCores,
                               int totalReservedRamMb, int totalOnDemandRamMb,
                               int runningReservedCores, int runningOnDemandCores,
                               int runningReservedRamMb, int runningOnDemandRamMb) {
        this.time = time;
        this.totalReservedCores = totalReservedCores;
        this.totalOnDemandCores = totalOnDemandCores;
        this.totalReservedRamMb = totalReservedRamMb;
        this.totalOnDemandRamMb = totalOnDemandRamMb;
        this.runningReservedCores = runningReservedCores;
        this.runningOnDemandCores = runningOnDemandCores;
        this.runningReservedRamMb = runningReservedRamMb;
        this.runningOnDemandRamMb = runningOnDemandRamMb;
        this.runningCores = runningReservedCores + runningOnDemandCores;
        this.reservedUtilization = totalReservedCores > 0
                ? (double) runningReservedCores / totalReservedCores : 0.0;
        int totalCores = totalReservedCores + totalOnDemandCores;
        this.overallUtilization = totalCores > 0 ? (double) runningCores / totalCores : 0.0;
        this.reservedRamUtilization = totalReservedRamMb > 0
                ? (double) runningReservedRamMb / totalReservedRamMb : 0.0;
        int totalRam = totalReservedRamMb + totalOnDemandRamMb;
        this.overallRamUtilization = totalRam > 0
                ? (double) (runningReservedRamMb + runningOnDemandRamMb) / totalRam : 0.0;
    }

    public double getTime() { return time; }
    public int getTotalReservedCores() { return totalReservedCores; }
    public int getTotalOnDemandCores() { return totalOnDemandCores; }
    public int getTotalReservedRamMb() { return totalReservedRamMb; }
    public int getTotalOnDemandRamMb() { return totalOnDemandRamMb; }
    public int getRunningReservedCores() { return runningReservedCores; }
    public int getRunningOnDemandCores() { return runningOnDemandCores; }
    public int getRunningReservedRamMb() { return runningReservedRamMb; }
    public int getRunningOnDemandRamMb() { return runningOnDemandRamMb; }
    public int getRunningCores() { return runningCores; }
    public double getReservedUtilization() { return reservedUtilization; }
    public double getOverallUtilization() { return overallUtilization; }
    public double getReservedRamUtilization() { return reservedRamUtilization; }
    public double getOverallRamUtilization() { return overallRamUtilization; }
}
