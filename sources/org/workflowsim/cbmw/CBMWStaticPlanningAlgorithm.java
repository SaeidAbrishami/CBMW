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
 * assigned to a dummy on-demand resource with execution at LST. Workflow
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
            // Backward greedy placement can commit a successor to a reserved
            // slot too early for its parent, even when a different placement
            // meets the workflow deadline. Retry from a clean booking state.
            try {
                computePaperTiming(tasks, wfr.getDeadline());
                planEarliestFeasible(tasks);
                validatePlannedPrecedence(tasks);
                refreshRecoveredSubdeadlines(tasks);
                CBMWLogger.log("PLAN-RECOVERED", String.format(
                        "wf=%d originalFailure=%s", wfr.getWorkflowId(),
                        e.getMessage()));
            } catch (Exception retryFailure) {
                releaseWorkflowBookings(tasks);
                retryFailure.addSuppressed(e);
                throw retryFailure;
            }
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
            double lst = floorTick(lft - dur);

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
        double lst = floorTick(lft - dur);

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

            int load = pool.getBookingCount(vm.getId());
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
            CBMWLogger.logf("PLAN-ASSIGN-RESERVED",
                    "wf=%d task=%d est=%.4f lst=%.4f lft=%.4f"
                            + " -> vm=%d slot=[%.4f, %.4f]",
                    wfr.getWorkflowId(), taskId, est, lst, lft,
                    bestVm, bestSlot, bestSlot + dur);
            return;
        }

        // SST denotes execution start on both resource types. The order is
        // placed at the preceding tick, one OPD before this execution slot.
        double sst = lst;
        double spt = floorTick(sst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
        if (sst + 1e-9 < ceilTick(est)
                || spt + 1e-9 < ceilTick(wfr.getArrivalTime())) {
            throw new IllegalStateException("No feasible on-demand request for task "
                    + taskId + " (execution=" + sst + ", request=" + spt + ")");
        }

        task.setVmId(ON_DEMAND_SENTINEL);
        wfr.setAssignedVm(taskId, ON_DEMAND_SENTINEL);
        wfr.setScheduledStart(taskId, sst);
        wfr.setPlannedProvisionOrder(taskId, spt);
        wfr.setPlannedContainerReady(taskId,
                spt + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);

        CBMWLogger.logf("PLAN-ASSIGN-ONDEMAND",
                "wf=%d task=%d est=%.4f lft=%.4f sst=%.4f"
                        + " request=%.4f dur=%.4f",
                wfr.getWorkflowId(), taskId, est, lft, sst, spt, dur);
    }

    /**
     * Recovery plan: provision each task no earlier than the next periodic
     * tick after arrival, and use a reserved slot only if it can finish no
     * later than that task's earliest on-demand alternative. Thus a reserved
     * decision cannot make any dependency path slower than the on-demand plan.
     */
    private void planEarliestFeasible(List<Task> tasks) {
        Map<Integer, Double> finishes = new HashMap<>();
        double order = ceilTick(Math.max(wfr.getArrivalTime(), CloudSim.clock()));
        double ready = order + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
        for (Task task : tasks) {
            placeEarliest(task, finishes, order, ready);
        }
    }

    private double placeEarliest(Task task, Map<Integer, Double> finishes,
                                 double order, double ready) {
        int id = task.getCloudletId();
        if (finishes.containsKey(id)) return finishes.get(id);

        double dependencyReady = wfr.getArrivalTime();
        for (Task parent : task.getParentList()) {
            dependencyReady = Math.max(dependencyReady,
                    placeEarliest(parent, finishes, order, ready));
        }
        double dur = duration(task);
        double onDemandStart = ceilTick(Math.max(dependencyReady, ready));
        int bestVm = ON_DEMAND_SENTINEL;
        double bestStart = onDemandStart;
        int cores = wfr.getTaskCores(id);
        int ramMb = wfr.getTaskRamMb(id);
        // Examine only ticks that could improve on the on-demand start.
        // Scanning the whole future booking profile here is unbounded under
        // contention, while the useful window is at most one OPD long.
        double earliestReserved = ceilTick(Math.max(dependencyReady,
                CloudSim.clock()));
        for (double slot = earliestReserved;
                slot <= onDemandStart + 1e-9 && bestVm == ON_DEMAND_SENTINEL;
                slot += HybridVmPool.SCHEDULING_PERIOD) {
            for (CondorVM vm : pool.getReservedVms()) {
                if (pool.hasBookedCapacity(vm.getId(), slot,
                        slot + dur, cores, ramMb)) {
                    bestVm = vm.getId();
                    bestStart = slot;
                    break;
                }
            }
        }
        double finish = bestStart + dur;
        if (finish > wfr.getDeadline() + 1e-9) {
            throw new IllegalStateException(String.format(
                    "No periodic plan by deadline for workflow %d task %d"
                            + " (finish=%.4f deadline=%.4f)",
                    wfr.getWorkflowId(), id, finish, wfr.getDeadline()));
        }
        task.setVmId(bestVm);
        wfr.setAssignedVm(id, bestVm);
        wfr.setScheduledStart(id, bestStart);
        if (bestVm == ON_DEMAND_SENTINEL) {
            wfr.setPlannedProvisionOrder(id, order);
            wfr.setPlannedContainerReady(id, ready);
        } else {
            pool.bookSlot(bestVm, id, bestStart, finish, cores, ramMb);
        }
        finishes.put(id, finish);
        return finish;
    }

    private void refreshRecoveredSubdeadlines(List<Task> tasks) {
        for (Task task : tasks) {
            int id = task.getCloudletId();
            double lft = wfr.getLFT(id);
            for (Task child : task.getChildList()) {
                lft = Math.min(lft,
                        wfr.getScheduledStart(child.getCloudletId()));
            }
            // The initial sweep is resource-independent and can have less
            // space than a feasible tick-aligned execution. Preserve the
            // actual conservative finish as this task's lower bound.
            lft = Math.max(lft, wfr.getScheduledStart(id) + duration(task));
            double lst = floorTick(lft - duration(task));
            wfr.setLFT(id, lft);
            wfr.setLST(id, lst);
            task.setLatestStartTime(lst);
        }
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

    /** SST is the execution start for either type of resource. */
    private double plannedExecutionStart(int taskId) {
        return wfr.getScheduledStart(taskId);
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
        double earliest = ceilTick(Math.max(est, CloudSim.clock()));
        // Search the event profile backward, then verify each tick-aligned
        // candidate over its exact conservative execution interval.
        double upper = lft;
        while (upper + 1e-9 >= earliest + dur) {
            double raw = pool.findLatestFeasibleSlot(
                    vmId, earliest, upper, dur, cores, ramMb);
            if (raw < 0.0) return -1.0;
            double candidate = floorTick(raw);
            if (candidate + 1e-9 >= earliest
                    && pool.hasBookedCapacity(vmId, candidate,
                            candidate + dur, cores, ramMb)) {
                return candidate;
            }
            upper = candidate + dur - 1e-6;
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
            lft = Math.min(lft, floorTick(
                    computeLFT(child, memo, deadline) - duration(child)));
        }

        memo.put(taskId, lft);
        return lft;
    }

    private double duration(Task task) {
        return wfr.getEstimatedExecTime(task);
    }

    static double floorTick(double time) {
        double period = HybridVmPool.SCHEDULING_PERIOD;
        return period * Math.floor((time + 1e-9) / period);
    }

    static double ceilTick(double time) {
        double period = HybridVmPool.SCHEDULING_PERIOD;
        return period * Math.ceil((time - 1e-9) / period);
    }
}
