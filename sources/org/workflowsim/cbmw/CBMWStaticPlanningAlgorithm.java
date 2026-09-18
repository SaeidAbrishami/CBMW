package org.workflowsim.cbmw;

import java.util.ArrayList;
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
 * assigned to the paper's dummy on-demand resource at LST - OPD. Workflow
 * deadlines already include the provisioning delay when they are loaded, so
 * planning never changes a workflow deadline based on its resource assignment.
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
        List<Task> sorted = new ArrayList<>(tasks);

        try {
            computePaperTiming(tasks, wfr.getDeadline());
            sorted.sort(Comparator.comparingDouble(
                    (Task t) -> wfr.getLFT(t.getCloudletId())).reversed());

            CBMWLogger.log("PLAN-START",
                    String.format("wf=%d tasks=%d deadline=%.4f",
                            wfr.getWorkflowId(), tasks.size(), wfr.getDeadline()));

            for (Task task : sorted) {
                planTask(task);
            }

            validatePlannedPrecedence(tasks);
        } catch (Exception e) {
            releaseWorkflowBookings(tasks);
            throw e;
        }

        CBMWLogger.log("PLAN-DONE",
                String.format("wf=%d tasks=%d deadline=%.4f",
                        wfr.getWorkflowId(), tasks.size(), wfr.getDeadline()));
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

    private void planTask(Task task) throws Exception {
        int taskId = task.getCloudletId();
        double est = wfr.getEST(taskId);
        double dur = duration(task);
        double lft = effectiveLatestFinish(task);
        double lst = lft - dur;

        // The paper timing sweep is resource-agnostic. Once a child has been
        // placed, tighten its parents against the child's selected execution
        // start so that resource placement cannot invert a dependency edge.
        wfr.setLFT(taskId, lft);
        wfr.setLST(taskId, lst);
        task.setLatestStartTime(lst);

        int bestVm = ON_DEMAND_SENTINEL;
        double bestSlot = -1.0;
        int bestLoad = Integer.MAX_VALUE;
        int taskCores = wfr.getTaskCores(taskId);
        int taskRamMb = wfr.getTaskRamMb(taskId);

        for (CondorVM vm : pool.getReservedVms()) {
            double slot = findLatestFeasibleSlot(vm.getId(), est, lft, dur,
                    taskCores, taskRamMb);
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
            pool.bookSlot(bestVm, taskId, bestSlot, bestSlot + dur,
                    taskCores, taskRamMb);
            CBMWLogger.log("PLAN-ASSIGN-RESERVED",
                    String.format("wf=%d task=%d est=%.4f lst=%.4f lft=%.4f"
                                    + " -> vm=%d slot=[%.4f, %.4f]",
                            wfr.getWorkflowId(), taskId, est, lst, lft,
                            bestVm, bestSlot, bestSlot + dur));
            return;
        }

        // Algorithm 1, lines 8-10: a failed reserved TaskPlanner result is
        // assigned to dummy on-demand resource o0 at LST - OPD.
        double sst = lst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;

        task.setVmId(ON_DEMAND_SENTINEL);
        wfr.setAssignedVm(taskId, ON_DEMAND_SENTINEL);
        wfr.setScheduledStart(taskId, sst);

        CBMWLogger.log("PLAN-ASSIGN-ONDEMAND",
                String.format("wf=%d task=%d est=%.4f lft=%.4f sst=%.4f"
                                + " rule=LST-OPD dur=%.4f",
                        wfr.getWorkflowId(), taskId, est, lft, sst, dur));
    }

    /**
     * Tightens the theoretical LFT against already-planned child starts.
     * Tasks are planned in descending LFT order, so every child is assigned
     * before its parents reach this method.
     */
    private double effectiveLatestFinish(Task task) {
        double effectiveLft = wfr.getLFT(task.getCloudletId());
        for (Task child : task.getChildList()) {
            int childId = child.getCloudletId();
            if (!wfr.hasScheduledStart(childId)
                    || !wfr.hasAssignedVm(childId)) {
                throw new IllegalStateException(
                        "Child must be planned before parent: " + childId);
            }
            effectiveLft = Math.min(
                    effectiveLft, plannedExecutionStart(childId));
        }
        return effectiveLft;
    }

    /** SST is execution start for reserved tasks and order time for o0. */
    private double plannedExecutionStart(int taskId) {
        double start = wfr.getScheduledStart(taskId);
        if (wfr.getAssignedVm(taskId) == ON_DEMAND_SENTINEL) {
            start += HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
        }
        return start;
    }

    /** Fails planning immediately if any selected resource slots invert an edge. */
    private void validatePlannedPrecedence(List<Task> tasks) {
        final double epsilon = 1e-9;
        for (Task parent : tasks) {
            int parentId = parent.getCloudletId();
            double parentFinish = plannedExecutionStart(parentId)
                    + duration(parent);
            for (Task child : parent.getChildList()) {
                int childId = child.getCloudletId();
                double childStart = plannedExecutionStart(childId);
                if (parentFinish > childStart + epsilon) {
                    throw new IllegalStateException(String.format(
                            "Invalid CBMW plan: parent %d finishes at %.4f"
                                    + " after child %d starts at %.4f",
                            parentId, parentFinish, childId, childStart));
                }
            }
        }
    }

    /** Frees reservations made before a later task makes planning fail. */
    private void releaseWorkflowBookings(List<Task> tasks) {
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            if (wfr.hasAssignedVm(taskId)
                    && wfr.getAssignedVm(taskId) != ON_DEMAND_SENTINEL) {
                pool.releaseSlot(taskId);
            }
        }
    }

    /**
     * Returns the latest start on vmId such that start >= EST, start+dur <= LFT,
     * and the slot does not exceed reserved VM core or RAM capacity.
     */
    private double findLatestFeasibleSlot(int vmId, double est, double lft,
                                          double dur, int cores, int ramMb) {
        double earliest = Math.max(est, CloudSim.clock());
        return pool.findLatestFeasibleSlot(
                vmId, earliest, lft, dur, cores, ramMb);
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
        return wfr.getEstimatedExecTime(task);
    }
}
