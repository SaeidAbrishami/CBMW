package org.workflowsim.cbmw;

/** Point-in-time resource utilization accounting. */
public class UtilizationSnapshot {
    private final double time;
    private final int totalReservedCores;
    private final int totalOnDemandCores;
    private final int runningReservedTasks;
    private final int runningOnDemandTasks;
    private final int runningTasks;
    private final double reservedUtilization;
    private final double overallUtilization;

    public UtilizationSnapshot(double time, int totalReservedCores, int totalOnDemandCores,
                               int runningReservedTasks, int runningOnDemandTasks) {
        this.time = time;
        this.totalReservedCores = totalReservedCores;
        this.totalOnDemandCores = totalOnDemandCores;
        this.runningReservedTasks = runningReservedTasks;
        this.runningOnDemandTasks = runningOnDemandTasks;
        this.runningTasks = runningReservedTasks + runningOnDemandTasks;
        this.reservedUtilization = totalReservedCores > 0
                ? (double) runningReservedTasks / totalReservedCores : 0.0;
        int totalCores = totalReservedCores + totalOnDemandCores;
        this.overallUtilization = totalCores > 0 ? (double) runningTasks / totalCores : 0.0;
    }

    public double getTime() { return time; }
    public int getTotalReservedCores() { return totalReservedCores; }
    public int getTotalOnDemandCores() { return totalOnDemandCores; }
    public int getRunningReservedTasks() { return runningReservedTasks; }
    public int getRunningOnDemandTasks() { return runningOnDemandTasks; }
    public int getRunningTasks() { return runningTasks; }
    public double getReservedUtilization() { return reservedUtilization; }
    public double getOverallUtilization() { return overallUtilization; }
}
