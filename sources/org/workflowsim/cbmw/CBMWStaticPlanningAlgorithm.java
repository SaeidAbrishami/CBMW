package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.planning.BasePlanningAlgorithm;

/**
 * Module 2 — Static Planning (Backward Sweep-Line).
 *
 * Assigns each task to a reserved VM slot at the latest feasible start time
 * (LST = deadline - remainingCP[t]) to preserve capacity for later arrivals.
 * Tasks that cannot fit on any reserved VM are marked for on-demand dispatch
 * (vmId = -1).
 */
public class CBMWStaticPlanningAlgorithm extends BasePlanningAlgorithm {

    public static final int ON_DEMAND_SENTINEL = -1;

    private WorkflowRecord wfr;
    private HybridVmPool pool;
    private NegotiationModule negotiation;

    // Per-VM list of already-occupied time intervals [start, end]
    private final Map<Integer, List<double[]>> occupiedSlots = new HashMap<>();

    public CBMWStaticPlanningAlgorithm(WorkflowRecord wfr, HybridVmPool pool,
                                        NegotiationModule negotiation) {
        this.wfr = wfr;
        this.pool = pool;
        this.negotiation = negotiation;
    }

    @Override
    public void run() throws Exception {
        List<Task> tasks = getTaskList();
        double deadline = wfr.getDeadline();

        // Step 1: compute remaining CP for every task
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(tasks);

        // Step 2: compute LST for each task and store in the record + task
        for (Task t : tasks) {
            double lst = deadline - remainingCPs.getOrDefault(t.getCloudletId(), 0.0);
            wfr.setLST(t.getCloudletId(), lst);
            t.setLatestStartTime(lst);
            t.setWorkflowId(wfr.getWorkflowId());
        }

        // Initialise occupied-slot lists for reserved VMs
        for (CondorVM vm : pool.getReservedVms()) {
            occupiedSlots.put(vm.getId(), new ArrayList<>());
        }

        // Step 3: sort by LST descending (latest-deadline tasks assigned first)
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingDouble(
                (Task t) -> wfr.getLST(t.getCloudletId())).reversed());

        // Step 4: backward sweep assignment
        for (Task task : sorted) {
            double lst     = wfr.getLST(task.getCloudletId());
            double dur     = task.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
            int    bestVm  = ON_DEMAND_SENTINEL;
            double bestSlot = -1.0;

            for (CondorVM vm : pool.getReservedVms()) {
                double slot = findLatestFeasibleSlot(vm.getId(), lst, dur);
                if (slot >= 0 && slot > bestSlot) {
                    bestSlot = slot;
                    bestVm   = vm.getId();
                }
            }

            if (bestVm != ON_DEMAND_SENTINEL) {
                task.setVmId(bestVm);
                wfr.setAssignedVm(task.getCloudletId(), bestVm);
                occupiedSlots.get(bestVm).add(new double[]{bestSlot, bestSlot + dur});
            } else {
                task.setVmId(ON_DEMAND_SENTINEL);
                wfr.setAssignedVm(task.getCloudletId(), ON_DEMAND_SENTINEL);
                Log.printLine("CBMW planner: task " + task.getCloudletId()
                        + " assigned to on-demand (no reserved slot available)");
            }
        }
    }

    /**
     * Finds the latest start time <= (lst - dur) on the given VM that does not
     * overlap any already-occupied interval.  Returns -1 if no slot exists.
     */
    private double findLatestFeasibleSlot(int vmId, double lst, double dur) {
        double candidate = lst - dur;
        if (candidate < 0) return -1.0;

        List<double[]> slots = occupiedSlots.get(vmId);
        // Sort by start ascending so we can find gaps easily
        List<double[]> sorted = new ArrayList<>(slots);
        Collections.sort(sorted, Comparator.comparingDouble(s -> s[0]));

        while (candidate >= 0) {
            boolean conflict = false;
            for (double[] interval : sorted) {
                // overlaps if candidate < interval_end AND candidate+dur > interval_start
                if (candidate < interval[1] && candidate + dur > interval[0]) {
                    conflict = true;
                    // Move candidate to just before this interval
                    candidate = interval[0] - dur;
                    break;
                }
            }
            if (!conflict) return candidate;
        }
        return -1.0;
    }
}
