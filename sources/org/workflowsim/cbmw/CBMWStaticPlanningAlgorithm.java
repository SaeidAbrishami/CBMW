package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.BasePlanningAlgorithm;

/**
 * Module 2: Static Planning (paper Algorithms 1 and 2).
 *
 * Computes EST/EFT/LFT/LST for each task and assigns tasks to reserved VM slots
 * by searching backward from LFT. Tasks that cannot fit on reserved capacity are
 * assigned to on-demand only when the OPD-adjusted start can still meet LFT.
 */
public class CBMWStaticPlanningAlgorithm extends BasePlanningAlgorithm {

    public static final int ON_DEMAND_SENTINEL = -1;

    private final WorkflowRecord wfr;
    private final HybridVmPool pool;
    private final NegotiationModule negotiation;

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

        computePaperTiming(tasks, deadline);

        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingDouble(
                (Task t) -> wfr.getLFT(t.getCloudletId())).reversed());

        CBMWLogger.log("PLAN-START",
                String.format("wf=%d tasks=%d deadline=%.4f",
                        wfr.getWorkflowId(), tasks.size(), deadline));

        for (Task task : sorted) {
            planTask(task);
        }

        CBMWLogger.log("PLAN-DONE",
                String.format("wf=%d tasks=%d deadline=%.4f",
                        wfr.getWorkflowId(), tasks.size(), deadline));
    }

    private void computePaperTiming(List<Task> tasks, double deadline) {
        Map<Integer, Double> eftMemo = new HashMap<>();
        Map<Integer, Double> lftMemo = new HashMap<>();

        for (Task task : tasks) {
            double eft = computeEFT(task, eftMemo);
            double lft = computeLFT(task, lftMemo, deadline);
            double dur = duration(task);
            double est = eft - dur;
            double lst = lft - dur;

            wfr.setEST(task.getCloudletId(), est);
            wfr.setEFT(task.getCloudletId(), eft);
            wfr.setLFT(task.getCloudletId(), lft);
            wfr.setLST(task.getCloudletId(), lst);
            task.setLatestStartTime(lst);
            task.setWorkflowId(wfr.getWorkflowId());
        }
    }

    private void planTask(Task task) {
        int taskId = task.getCloudletId();
        double est = wfr.getEST(taskId);
        double lft = wfr.getLFT(taskId);
        double lst = wfr.getLST(taskId);
        double dur = duration(task);

        int bestVm = ON_DEMAND_SENTINEL;
        double bestSlot = -1.0;
        int bestLoad = Integer.MAX_VALUE;

        for (CondorVM vm : pool.getReservedVms()) {
            double slot = findLatestFeasibleSlot(vm.getId(), est, lft, dur);
            if (slot < 0.0) continue;

            int load = pool.getBookings(vm.getId()).size();
            if (slot > bestSlot + 1e-9
                    || (slot >= bestSlot - 1e-9 && load < bestLoad)) {
                bestSlot = slot;
                bestVm = vm.getId();
                bestLoad = load;
            }
        }

        if (bestVm != ON_DEMAND_SENTINEL) {
            task.setVmId(bestVm);
            wfr.setAssignedVm(taskId, bestVm);
            wfr.setScheduledStart(taskId, bestSlot);
            pool.bookSlot(bestVm, taskId, bestSlot, bestSlot + dur);
            CBMWLogger.log("PLAN-ASSIGN-RESERVED",
                    String.format("wf=%d task=%d est=%.4f lst=%.4f lft=%.4f"
                                    + " -> vm=%d slot=[%.4f, %.4f]",
                            wfr.getWorkflowId(), taskId, est, lst, lft,
                            bestVm, bestSlot, bestSlot + dur));
            return;
        }

        double sst = Math.max(wfr.getArrivalTime(),
                est - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
        double expectedStart = Math.max(est,
                sst + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
        double expectedFinish = expectedStart + dur;
        if (expectedFinish > lft + 1e-9) {
            throw new IllegalStateException(String.format(
                    "task %d cannot meet lft=%.4f on reserved or on-demand"
                            + " (est=%.4f expectedOdFinish=%.4f)",
                    taskId, lft, est, expectedFinish));
        }

        task.setVmId(ON_DEMAND_SENTINEL);
        wfr.setAssignedVm(taskId, ON_DEMAND_SENTINEL);
        wfr.setScheduledStart(taskId, sst);
        CBMWLogger.log("PLAN-ASSIGN-ONDEMAND",
                String.format("wf=%d task=%d est=%.4f lft=%.4f sst=%.4f"
                                + " odStart=%.4f dur=%.4f",
                        wfr.getWorkflowId(), taskId, est, lft, sst,
                        expectedStart, dur));
    }

    /**
     * Returns the latest start on vmId such that start >= EST, start+dur <= LFT,
     * and the slot does not exceed the reserved VM core capacity.
     */
    private double findLatestFeasibleSlot(int vmId, double est, double lft, double dur) {
        double earliest = Math.max(est, CloudSim.clock());
        double candidate = lft - dur;
        if (candidate < earliest) return -1.0;

        List<double[]> sorted = new ArrayList<>(pool.getBookings(vmId));
        Collections.sort(sorted, Comparator.comparingDouble(s -> s[0]));

        while (candidate >= earliest) {
            double end = candidate + dur;
            int overlaps = 0;
            double earliestOverlapStart = Double.POSITIVE_INFINITY;

            for (double[] interval : sorted) {
                if (interval[1] <= earliest) continue;
                if (candidate < interval[1] && end > interval[0]) {
                    overlaps++;
                    earliestOverlapStart = Math.min(earliestOverlapStart, interval[0]);
                    if (overlaps >= HybridVmPool.RESERVED_CORES) break;
                }
            }

            if (overlaps < HybridVmPool.RESERVED_CORES) return candidate;
            candidate = earliestOverlapStart - dur - 1e-9;
        }

        return -1.0;
    }

    private double computeEFT(Task task, Map<Integer, Double> memo) {
        int taskId = task.getCloudletId();
        if (memo.containsKey(taskId)) return memo.get(taskId);

        double est = wfr.getArrivalTime();
        for (Task parent : task.getParentList()) {
            est = Math.max(est, computeEFT(parent, memo));
        }

        double eft = est + duration(task);
        memo.put(taskId, eft);
        return eft;
    }

    private double computeLFT(Task task, Map<Integer, Double> memo, double deadline) {
        int taskId = task.getCloudletId();
        if (memo.containsKey(taskId)) return memo.get(taskId);

        double lft = deadline;
        for (Task child : task.getChildList()) {
            lft = Math.min(lft, computeLFT(child, memo, deadline) - duration(child));
        }

        memo.put(taskId, lft);
        return lft;
    }

    private double duration(Task task) {
        return task.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
    }
}
