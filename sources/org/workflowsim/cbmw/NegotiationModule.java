package org.workflowsim.cbmw;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;

/**
 * Module 1 — Negotiation.
 * Computes the Critical Path, assigns a deadline, and decides whether to accept
 * the incoming workflow.
 */
public class NegotiationModule {

    private static final double DEFAULT_BETA = 1.1;
    private final double tightness;         // 1.2 tight, 3.0 loose
    private double beta = DEFAULT_BETA;
    private double gamma = PaperRuntimeModel.NEGOTIATION_GAMMA;

    public NegotiationModule(double tightness) {
        this.tightness = tightness;
        setGamma(gamma);
    }

    public void setBeta(double beta) {
        if (beta < 1.0 || !Double.isFinite(beta)) {
            throw new IllegalArgumentException("beta must be finite and >= 1");
        }
        this.beta = beta;
    }

    public void setGamma(double gamma) {
        if (gamma < 1.0 || !Double.isFinite(gamma)) {
            throw new IllegalArgumentException("gamma must be finite and >= 1");
        }
        this.gamma = gamma;
    }

    public double getGamma() { return gamma; }

    /**
     * Checks whether the workflow can feasibly meet its user-given deadline.
     * Deadline must already be set on wfr before calling this.
     * Returns true if accepted.
     */
    public boolean negotiate(WorkflowRecord wfr) {
        double cp   = computeCriticalPath(wfr);
        wfr.setCriticalPathLength(cp);

        double slack    = wfr.getDeadline() - wfr.getArrivalTime();
        double required = cp * beta;
        boolean feasible = (required <= slack);
        wfr.setDeadlineFeasible(feasible);
        wfr.setAccepted(feasible);

        CBMWLogger.log("NEGOTIATE",
                String.format("wf=%d tasks=%d arrivalTime=%.4f deadline=%.4f"
                        + " cp=%.4f beta=%.3f BETA*cp=%.4f slack=%.4f -> %s",
                        wfr.getWorkflowId(), wfr.getTaskList().size(),
                        wfr.getArrivalTime(), wfr.getDeadline(),
                        cp, beta, required, slack,
                        feasible ? "ACCEPTED" : "REJECTED (slack < BETA*cp)"));
        return feasible;
    }

    /**
     * Computes the paper's post-planning price quote. With no simulated user,
     * every quote is accepted automatically.
     */
    public double quoteExecutionPrice(WorkflowRecord wfr) {
        double reservedCost = 0.0;
        double onDemandCost = 0.0;
        double reservedPricePerSecond =
                HybridVmPool.RESERVED_HOURLY_COST / 3600.0;

        for (Task task : wfr.getTaskList()) {
            int taskId = task.getCloudletId();
            double duration = wfr.getEstimatedExecTime(taskId);
            int vmId = wfr.getAssignedVm(taskId);
            if (vmId >= 0) {
                reservedCost += duration * reservedPricePerSecond;
            } else {
                double billableDuration = Math.max(
                        HybridVmPool.ON_DEMAND_MIN_BILLING_SECONDS, duration);
                onDemandCost += billableDuration
                        * HybridVmPool.onDemandPricePerSecond(
                                wfr.getTaskCores(taskId), wfr.getTaskRamMb(taskId));
            }
        }

        double rawCost = reservedCost + onDemandCost;
        double offeredPrice = rawCost * gamma;
        wfr.setEstimatedReservedCost(reservedCost);
        wfr.setEstimatedOnDemandCost(onDemandCost);
        wfr.setEstimatedRawCost(rawCost);
        wfr.setPriceMarkupGamma(gamma);
        wfr.setOfferedPrice(offeredPrice);
        wfr.setPriceAccepted(true);

        CBMWLogger.logf("PRICE-QUOTE",
                "wf=%d reservedEstimate=$%.6f onDemandEstimate=$%.6f"
                        + " rawCost=$%.6f gamma=%.3f offeredPrice=$%.6f"
                        + " userDecision=AUTO_ACCEPTED",
                wfr.getWorkflowId(), reservedCost, onDemandCost, rawCost,
                gamma, offeredPrice);
        return offeredPrice;
    }

    /**
     * Returns the length of the critical path (in simulated seconds) using the
     * nominal MIPS of a reserved VM.
     */
    public double computeCriticalPath(List<Task> tasks) {
        Map<Integer, Double> memo = new HashMap<>();
        double cp = 0.0;
        for (Task t : tasks) {
            double rank = remainingCP(t, memo);
            if (rank > cp) cp = rank;
        }
        return cp;
    }

    /** Returns the critical path using paper estimated execution times (cetji). */
    public double computeCriticalPath(WorkflowRecord wfr) {
        Map<Integer, Double> memo = new HashMap<>();
        double cp = 0.0;
        for (Task t : wfr.getTaskList()) {
            double rank = remainingCP(t, wfr, memo);
            if (rank > cp) cp = rank;
        }
        return cp;
    }

    /**
     * Computes remaining CP from task t to the workflow exit.
     * remainingCP(t) = execTime(t) + max(remainingCP(child)) over children.
     */
    public Map<Integer, Double> computeRemainingCPs(List<Task> tasks) {
        Map<Integer, Double> memo = new HashMap<>();
        for (Task t : tasks) {
            remainingCP(t, memo);
        }
        return memo;
    }

    /** Remaining CPs based on paper estimated execution times (cetji). */
    public Map<Integer, Double> computeRemainingCPs(WorkflowRecord wfr) {
        Map<Integer, Double> memo = new HashMap<>();
        for (Task t : wfr.getTaskList()) {
            remainingCP(t, wfr, memo);
        }
        return memo;
    }

    private double remainingCP(Task t, Map<Integer, Double> memo) {
        if (memo.containsKey(t.getCloudletId())) {
            return memo.get(t.getCloudletId());
        }
        double execTime = t.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
        double maxChild = 0.0;
        for (Task child : t.getChildList()) {
            double childCP = remainingCP(child, memo);
            if (childCP > maxChild) maxChild = childCP;
        }
        double result = execTime + maxChild;
        memo.put(t.getCloudletId(), result);
        return result;
    }

    private double remainingCP(Task t, WorkflowRecord wfr, Map<Integer, Double> memo) {
        if (memo.containsKey(t.getCloudletId())) {
            return memo.get(t.getCloudletId());
        }
        double execTime = wfr.getEstimatedExecTime(t);
        double maxChild = 0.0;
        for (Task child : t.getChildList()) {
            double childCP = remainingCP(child, wfr, memo);
            if (childCP > maxChild) maxChild = childCP;
        }
        double result = execTime + maxChild;
        memo.put(t.getCloudletId(), result);
        return result;
    }
}
