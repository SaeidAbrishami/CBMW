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
        return "scenario,algorithm,lambda,tightness,run,total,accepted,deadlineRate,onDemandCost,reservedCost,makespan,reservedUtil";
    }

    public String toCsvRow(String algorithm, double lambda, double tightness, int run) {
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

        return String.format("%s_%s_lam%.0f_t%.1f,%s,%.1f,%.1f,%d,%d,%d,%.4f,%.4f,%.2f,%.2f,%.4f",
                algorithm, tightness > 2 ? "loose" : "tight", lambda, tightness,
                algorithm, lambda, tightness, run, total, accepted,
                deadlineRate, odCost, reservedCost, makespan, reservedUtil);
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
}
