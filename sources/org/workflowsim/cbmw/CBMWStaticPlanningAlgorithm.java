package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
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

        // Step 3: sort by LST descending (latest-deadline tasks assigned first)
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingDouble(
                (Task t) -> wfr.getLST(t.getCloudletId())).reversed());

        CBMWLogger.log("PLAN-START",
                String.format("wf=%d tasks=%d deadline=%.4f",
                        wfr.getWorkflowId(), tasks.size(), deadline));

        // Step 4: backward sweep assignment — bookings are read/written to the
        // shared pool registry so cross-workflow conflicts are visible.
        for (Task task : sorted) {
            double lst     = wfr.getLST(task.getCloudletId());
            double dur     = task.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
            int    bestVm  = ON_DEMAND_SENTINEL;
            double bestSlot = -1.0;

            for (CondorVM vm : pool.getReservedVms()) {
                double slot = findLatestFeasibleSlot(vm.getId(), lst, dur, deadline);
                if (slot >= 0 && slot > bestSlot) {
                    bestSlot = slot;
                    bestVm   = vm.getId();
                }
            }

            if (bestVm != ON_DEMAND_SENTINEL) {
                task.setVmId(bestVm);
                wfr.setAssignedVm(task.getCloudletId(), bestVm);
                pool.bookSlot(bestVm, task.getCloudletId(), bestSlot, bestSlot + dur);
                CBMWLogger.log("PLAN-ASSIGN-RESERVED",
                        String.format("wf=%d task=%d -> vm=%d slot=[%.4f, %.4f]",
                                wfr.getWorkflowId(), task.getCloudletId(),
                                bestVm, bestSlot, bestSlot + dur));
            } else {
                task.setVmId(ON_DEMAND_SENTINEL);
                wfr.setAssignedVm(task.getCloudletId(), ON_DEMAND_SENTINEL);
                CBMWLogger.log("PLAN-ASSIGN-ONDEMAND",
                        String.format("wf=%d task=%d lst=%.4f dur=%.4f"
                                + " (no reserved slot fits before deadline=%.4f)",
                                wfr.getWorkflowId(), task.getCloudletId(),
                                lst, dur, deadline));
            }
        }

        CBMWLogger.log("PLAN-DONE",
                String.format("wf=%d tasks=%d deadline=%.4f",
                        wfr.getWorkflowId(), tasks.size(), wfr.getDeadline()));
    }

    /**
     * Finds the latest start time on vmId such that:
     *   - start + dur <= deadline  (task finishes before its workflow deadline)
     *   - start <= lst - dur       (respects latest-start-time constraint)
     *   - [start, start+dur] does not overlap any booking in the pool
     * Returns -1 if no such slot exists.
     */
    private double findLatestFeasibleSlot(int vmId, double lst, double dur, double deadline) {
        // Cap planning horizon to arrival + 1.5×CP so loose-deadline workflows don't
        // block reserved capacity far into the future.
        double horizon = wfr.getArrivalTime() + wfr.getCriticalPathLength() * 1.5;
        double candidate = Math.min(lst - dur, Math.min(deadline - dur, horizon - dur));
        if (candidate < 0) return -1.0;

        List<double[]> booked = pool.getBookings(vmId);
        List<double[]> sorted = new ArrayList<>(booked);
        Collections.sort(sorted, Comparator.comparingDouble(s -> s[0]));

        double now = CloudSim.clock();
        while (candidate >= 0) {
            boolean conflict = false;
            for (double[] interval : sorted) {
                // Skip bookings that are already past — task already ran and released its slot
                if (interval[1] <= now) continue;
                if (candidate < interval[1] && candidate + dur > interval[0]) {
                    conflict = true;
                    candidate = interval[0] - dur - 1e-9;
                    break;
                }
            }
            if (!conflict) return candidate;
        }
        return -1.0;
    }
}
