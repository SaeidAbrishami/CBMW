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

        // Fixed cost for reserved instances (all time, regardless of utilisation)
        double hours = Math.ceil(simulationDurationSeconds() / 3600.0);
        double reservedCost = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_HOURLY_COST * hours;

        Log.printLine("\n========== RESULTS: " + scenario + " ==========");
        Log.printLine(String.format("Workflows total/accepted : %d / %d", total, accepted));
        Log.printLine(String.format("Deadline met rate        : %.3f (%d / %d)", deadlineRate, met, accepted));
        Log.printLine(String.format("On-demand cost ($)       : %.4f", odCost));
        Log.printLine(String.format("Reserved fixed cost ($)  : %.2f", reservedCost));
        Log.printLine(String.format("Total cost ($)           : %.4f", odCost + reservedCost));
        Log.printLine(String.format("Makespan (sim s)         : %.2f", makespan));
    }

    /** CSV header for batch output. */
    public static String csvHeader() {
        return "scenario,algorithm,lambda,tightness,run,total,accepted,deadlineRate,onDemandCost,reservedCost,makespan";
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

        return String.format("%s_%s_lam%.0f_t%.1f,%s,%.1f,%.1f,%d,%d,%d,%.4f,%.4f,%.2f,%.2f",
                algorithm, tightness > 2 ? "loose" : "tight", lambda, tightness,
                algorithm, lambda, tightness, run, total, accepted,
                deadlineRate, odCost, reservedCost, makespan);
    }

    private double simulationDurationSeconds() {
        return org.workflowsim.utils.Parameters.getSimDuration();
    }
}
