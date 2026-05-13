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

    private static final double BETA = 1.0; // safety factor (CP * beta <= relative_deadline)
    private final double tightness;         // 1.2 tight, 3.0 loose

    public NegotiationModule(double tightness) {
        this.tightness = tightness;
    }

    /**
     * Evaluates the workflow, sets deadline in the record, returns true if accepted.
     */
    public boolean negotiate(WorkflowRecord wfr) {
        double cp = computeCriticalPath(wfr.getTaskList());
        wfr.setCriticalPathLength(cp);
        double deadline = wfr.getArrivalTime() + cp * tightness;
        wfr.setDeadline(deadline);
        // Accept when the deadline slack satisfies the safety factor
        boolean feasible = (cp * BETA <= cp * tightness);
        wfr.setAccepted(feasible);
        return feasible;
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
}
