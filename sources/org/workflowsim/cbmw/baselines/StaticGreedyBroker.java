package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWDynamicSchedulingAlgorithm;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Static Greedy baseline (document §1.1).
 *
 * Planning: tasks sorted by upward rank (= remaining CP, highest first).
 * Each task is placed at the earliest feasible start time on a reserved VM
 * (earliest-finish HEFT style). Falls back to on-demand if on-demand would
 * finish earlier than the best reserved slot.
 *
 * Runtime: reuses CBMWDynamicSchedulingAlgorithm (Algorithm 3) — the same
 * OPD-aware dispatch used by CBMW but driven by earliest-start slots instead
 * of latest-start slots.
 */
public class StaticGreedyBroker extends AbstractWorkflowBroker {

    private final CBMWDynamicSchedulingAlgorithm dispatcher;

    public StaticGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        this.dispatcher = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
    }

    // -----------------------------------------------------------------------
    // Planning — upward-rank ordering, earliest-feasible-slot assignment
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        double arrival = wfr.getArrivalTime();

        // Upward rank = remaining CP from each task to the exit (HEFT definition
        // with zero communication costs on homogeneous VMs).
        Map<Integer, Double> urank = negotiation.computeRemainingCPs(tasks);

        // Process in descending upward-rank order (most critical tasks first).
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingDouble(
                (Task t) -> urank.getOrDefault(t.getCloudletId(), 0.0)).reversed());

        // Planned completion times (for children's EST calculation).
        Map<Integer, Double> plannedEnd = new HashMap<>();

        for (Task task : sorted) {
            int    taskId = task.getCloudletId();
            double dur    = task.getCloudletLength() / HybridVmPool.RESERVED_MIPS;

            // EST = max over all parents of their planned completion time.
            double est = arrival;
            for (Task parent : task.getParentList()) {
                est = Math.max(est,
                        plannedEnd.getOrDefault(parent.getCloudletId(), arrival));
            }

            // Find the reserved VM with the earliest finish time (HEFT).
            int    bestVm     = CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL;
            double bestStart  = 0.0;
            double bestFinish = Double.MAX_VALUE;

            for (CondorVM vm : vmPool.getReservedVms()) {
                double slot   = findEarliestSlot(vm.getId(), est, dur);
                double finish = slot + dur;
                if (finish < bestFinish) {
                    bestFinish = finish;
                    bestStart  = slot;
                    bestVm     = vm.getId();
                }
            }

            // On-demand EFT: provisioning triggers at max(arrival, est-OPD),
            // VM ready OPD seconds later, task starts at max(est, arrival+OPD).
            double sst       = Math.max(arrival,
                    est - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            double odStart   = Math.max(est,
                    sst + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            double odFinish  = odStart + dur;

            if (bestVm != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                    && bestFinish <= odFinish) {
                // Reserved slot finishes no later than on-demand → use reserved.
                task.setVmId(bestVm);
                task.setWorkflowId(wfr.getWorkflowId());
                wfr.setAssignedVm(taskId, bestVm);
                wfr.setScheduledStart(taskId, bestStart);
                wfr.setLST(taskId, bestStart);
                vmPool.bookSlot(bestVm, taskId, bestStart, bestStart + dur);
                plannedEnd.put(taskId, bestStart + dur);
                CBMWLogger.log("SG-PLAN",
                        String.format("wf=%d task=%d -> vm=%d slot=[%.2f,%.2f]",
                                wfr.getWorkflowId(), taskId, bestVm,
                                bestStart, bestStart + dur));
            } else {
                // On-demand is faster — provision OPD seconds before EST.
                task.setVmId(CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                task.setWorkflowId(wfr.getWorkflowId());
                wfr.setAssignedVm(taskId, CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                wfr.setScheduledStart(taskId, sst);
                wfr.setLST(taskId, sst);
                plannedEnd.put(taskId, odFinish);
                CBMWLogger.log("SG-PLAN",
                        String.format("wf=%d task=%d -> ON-DEMAND sst=%.2f est=%.2f",
                                wfr.getWorkflowId(), taskId, sst, est));
            }
        }
        return true;
    }

    /**
     * Earliest slot on vmId starting at or after {@code est} where a task of
     * duration {@code dur} fits without overlapping any existing booking.
     * O(bookings) per call.
     */
    private double findEarliestSlot(int vmId, double est, double dur) {
        List<double[]> booked = vmPool.getBookings(vmId);
        List<double[]> sorted = new ArrayList<>(booked);
        sorted.sort(Comparator.comparingDouble(s -> s[0]));

        double candidate = est;
        for (double[] interval : sorted) {
            if (interval[1] <= candidate) continue;     // booking already passed
            if (interval[0] >= candidate + dur) break;  // task fits before this booking
            candidate = interval[1];                     // push past the conflict
        }
        return candidate;
    }

    // -----------------------------------------------------------------------
    // Runtime — Algorithm 3 (identical to CBMW, driven by earliest-start SSTs)
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());
        dispatcher.setCloudletList(getCloudletList());
        dispatcher.setVmList(getVmsCreatedList());
        dispatcher.getScheduledList().clear();
        try {
            dispatcher.run();
        } catch (Exception e) {
            Log.printLine("StaticGreedy dispatcher error: " + e.getMessage());
        }
        dispatchScheduledJobs(dispatcher.getScheduledList());
    }

    /** Release the reserved-VM booking when the task completes. */
    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
    }
}
