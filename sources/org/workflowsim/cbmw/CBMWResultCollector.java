package org.workflowsim.cbmw;

import java.util.List;
import org.cloudbus.cloudsim.Log;

/** Collects and prints per-scenario statistics. */
public class CBMWResultCollector {

    private final List<WorkflowRecord> allWorkflows;
    private final double reservedCostMultiplier;

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows) {
        this(allWorkflows, 1.0);
    }

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows,
                               double reservedCostMultiplier) {
        this.allWorkflows = allWorkflows;
        this.reservedCostMultiplier = reservedCostMultiplier;
    }

    public void printReport(String scenario) {
        long total = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long met = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        double odCost = totalOnDemandCost();
        double spotCost = totalSpotCost();
        double makespan = makespan();
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double reservedCost = reservedCost();
        double reservedUtil = reservedUtilization();

        Log.printLine("\n========== RESULTS: " + scenario + " ==========");
        Log.printLine(String.format("Workflows total/accepted : %d / %d", total, accepted));
        Log.printLine(String.format("Deadline met rate        : %.3f (%d / %d)",
                deadlineRate, met, accepted));
        Log.printLine(String.format("Reserved VM utilization  : %.1f%%",
                reservedUtil * 100));
        Log.printLine(String.format("On-demand cost ($)       : %.4f", odCost));
        Log.printLine(String.format("Spot cost ($)            : %.4f", spotCost));
        Log.printLine(String.format("Reserved fixed cost ($)  : %.2f", reservedCost));
        Log.printLine(String.format("Total cost ($)           : %.4f",
                odCost + spotCost + reservedCost));
        Log.printLine(String.format("Makespan (sim s)         : %.2f", makespan));
    }

    public static String csvHeader() {
        return "scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,"
                + "total,accepted,deadlineRate,onDemandCost,spotCost,reservedCost,"
                + "totalCost,makespan,reservedUtil,onDemandUsageRatio,spotUsageRatio";
    }

    public String toCsvRow(String scenario, String load, String deadlineClass,
                           String algorithm, double arrivalScale,
                           double tightness, int run, double onDemandUsageRatio,
                           double spotUsageRatio) {
        return toCsvRow(toScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, onDemandUsageRatio, spotUsageRatio));
    }

    public ScenarioMetrics toScenarioMetrics(String scenario, String load,
                                             String deadlineClass,
                                             String algorithm,
                                             double arrivalScale,
                                             double tightness,
                                             int run,
                                             double onDemandUsageRatio,
                                             double spotUsageRatio) {
        long total = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long met = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double odCost = totalOnDemandCost();
        double spotCost = totalSpotCost();
        double reservedCost = reservedCost();
        double totalCost = odCost + spotCost + reservedCost;

        return new ScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, total, accepted, deadlineRate,
                odCost, spotCost, reservedCost, totalCost, makespan(),
                reservedUtilization(), onDemandUsageRatio, spotUsageRatio);
    }

    public static String toCsvRow(ScenarioMetrics metrics) {
        return String.format("%s,%s,%s,%s,%.4f,%.1f,%d,%d,%d,%.4f,%.4f,%.4f,"
                        + "%.2f,%.4f,%.2f,%.4f,%.4f,%.4f",
                metrics.scenario, metrics.load, metrics.deadlineClass,
                metrics.algorithm, metrics.arrivalScale, metrics.tightness,
                metrics.run, metrics.total, metrics.accepted,
                metrics.deadlineRate, metrics.onDemandCost, metrics.spotCost,
                metrics.reservedCost, metrics.totalCost, metrics.makespan,
                metrics.reservedUtil, metrics.onDemandUsageRatio,
                metrics.spotUsageRatio);
    }

    private double totalOnDemandCost() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalOnDemandCost).sum();
    }

    private double totalSpotCost() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalSpotCost).sum();
    }

    private double reservedCost() {
        double hours = Math.ceil(simulationDurationSeconds() / 3600.0);
        return HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_HOURLY_COST
                * hours * reservedCostMultiplier;
    }

    private double makespan() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0.0);
    }

    private double reservedUtilization() {
        double totalCpu = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalReservedCpuTime).sum();
        double capacity = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES
                * makespan();
        return capacity > 0 ? totalCpu / capacity : 0.0;
    }

    private double simulationDurationSeconds() {
        return org.workflowsim.utils.Parameters.getSimDuration();
    }

    /** Raw per-run metrics that can be aggregated after the experiment. */
    public static class ScenarioMetrics {
        public final String scenario;
        public final String load;
        public final String deadlineClass;
        public final String algorithm;
        public final double arrivalScale;
        public final double tightness;
        public final int run;
        public final long total;
        public final long accepted;
        public final double deadlineRate;
        public final double onDemandCost;
        public final double spotCost;
        public final double reservedCost;
        public final double totalCost;
        public final double makespan;
        public final double reservedUtil;
        public final double onDemandUsageRatio;
        public final double spotUsageRatio;

        public ScenarioMetrics(String scenario, String load, String deadlineClass,
                               String algorithm, double arrivalScale,
                               double tightness, int run, long total,
                               long accepted, double deadlineRate,
                               double onDemandCost, double spotCost,
                               double reservedCost, double totalCost,
                               double makespan, double reservedUtil,
                               double onDemandUsageRatio,
                               double spotUsageRatio) {
            this.scenario = scenario;
            this.load = load;
            this.deadlineClass = deadlineClass;
            this.algorithm = algorithm;
            this.arrivalScale = arrivalScale;
            this.tightness = tightness;
            this.run = run;
            this.total = total;
            this.accepted = accepted;
            this.deadlineRate = deadlineRate;
            this.onDemandCost = onDemandCost;
            this.spotCost = spotCost;
            this.reservedCost = reservedCost;
            this.totalCost = totalCost;
            this.makespan = makespan;
            this.reservedUtil = reservedUtil;
            this.onDemandUsageRatio = onDemandUsageRatio;
            this.spotUsageRatio = spotUsageRatio;
        }
    }
}
