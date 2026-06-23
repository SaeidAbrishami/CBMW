package org.workflowsim.cbmw;

import java.util.List;
import org.cloudbus.cloudsim.Log;

/** Collects and prints per-scenario statistics. */
public class CBMWResultCollector {

    private final List<WorkflowRecord> allWorkflows;

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows) {
        this.allWorkflows = allWorkflows;
    }

    public void printReport(String scenario) {
        long total    = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long met      = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        double odCost = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalOnDemandCost).sum();
        double makespan = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0);
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;

        double hours = Math.ceil(simulationDurationSeconds() / 3600.0);
        double reservedCost = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_HOURLY_COST * hours;
        double reservedUtil = reservedUtilization();

        Log.printLine("\n========== RESULTS: " + scenario + " ==========");
        Log.printLine(String.format("Workflows total/accepted : %d / %d", total, accepted));
        Log.printLine(String.format("Deadline met rate        : %.3f (%d / %d)", deadlineRate, met, accepted));
        Log.printLine(String.format("Reserved VM utilization  : %.1f%%", reservedUtil * 100));
        Log.printLine(String.format("On-demand cost ($)       : %.4f", odCost));
        Log.printLine(String.format("Reserved fixed cost ($)  : %.2f", reservedCost));
        Log.printLine(String.format("Total cost ($)           : %.4f", odCost + reservedCost));
        Log.printLine(String.format("Makespan (sim s)         : %.2f", makespan));
    }

    /** CSV header for batch output. */
    public static String csvHeader() {
        return "scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,total,accepted,deadlineRate,onDemandCost,reservedCost,totalCost,makespan,reservedUtil,onDemandUsageRatio";
    }

    public String toCsvRow(String scenario, String load, String deadlineClass,
                           String algorithm, double arrivalScale,
                           double tightness, int run, double onDemandUsageRatio) {
        return toCsvRow(toScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, onDemandUsageRatio));
    }

    public ScenarioMetrics toScenarioMetrics(String scenario, String load,
                                             String deadlineClass,
                                             String algorithm,
                                             double arrivalScale,
                                             double tightness,
                                             int run,
                                             double onDemandUsageRatio) {
        long total    = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long met      = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        double odCost = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalOnDemandCost).sum();
        double makespan = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0);
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double hours = Math.ceil(simulationDurationSeconds() / 3600.0);
        double reservedCost = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_HOURLY_COST * hours;
        double reservedUtil = reservedUtilization();
        double totalCost = odCost + reservedCost;

        return new ScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, total, accepted, deadlineRate,
                odCost, reservedCost, totalCost, makespan, reservedUtil,
                onDemandUsageRatio);
    }

    public static String toCsvRow(ScenarioMetrics metrics) {
        return String.format("%s,%s,%s,%s,%.4f,%.1f,%d,%d,%d,%.4f,%.4f,%.2f,%.4f,%.2f,%.4f,%.4f",
                metrics.scenario, metrics.load, metrics.deadlineClass,
                metrics.algorithm, metrics.arrivalScale, metrics.tightness,
                metrics.run, metrics.total, metrics.accepted,
                metrics.deadlineRate, metrics.onDemandCost,
                metrics.reservedCost, metrics.totalCost, metrics.makespan,
                metrics.reservedUtil, metrics.onDemandUsageRatio);
    }

    private double reservedUtilization() {
        double totalCpu = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalReservedCpuTime).sum();
        double makespan = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0);
        double capacity = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES * makespan;
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
        public final double reservedCost;
        public final double totalCost;
        public final double makespan;
        public final double reservedUtil;
        public final double onDemandUsageRatio;

        public ScenarioMetrics(String scenario, String load, String deadlineClass,
                               String algorithm, double arrivalScale,
                               double tightness, int run, long total,
                               long accepted, double deadlineRate,
                               double onDemandCost, double reservedCost,
                               double totalCost, double makespan,
                               double reservedUtil,
                               double onDemandUsageRatio) {
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
            this.reservedCost = reservedCost;
            this.totalCost = totalCost;
            this.makespan = makespan;
            this.reservedUtil = reservedUtil;
            this.onDemandUsageRatio = onDemandUsageRatio;
        }
    }
}
