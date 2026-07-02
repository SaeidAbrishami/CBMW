package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Completely static greedy baseline from new_experiments_text.txt.
 *
 * Each valid workflow is fully planned when it arrives. Tasks are considered
 * by descending upward rank and assigned to their earliest deadline-feasible
 * reserved slot. A dedicated on-demand container is selected only when no
 * reserved slot exists in that window. Runtime execution keeps every planned
 * resource assignment fixed; actual runtimes may only delay the plan.
 */
public class StaticGreedyBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;
    private final StaticGreedySchedulingAlgorithm dispatcher;

    public StaticGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        this.dispatcher = new StaticGreedySchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
    }

    /** StaticGreedy has no CBMW admission gate: every valid arrival is planned. */
    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        double cp = negotiation.computeCriticalPath(wfr);
        wfr.setCriticalPathLength(cp);
        wfr.setDeadlineFeasible(
                cp <= wfr.getDeadline() - wfr.getArrivalTime() + EPS);
        wfr.setAccepted(true);
        return true;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        double arrival = wfr.getArrivalTime();
        Map<Integer, Double> upwardRank = negotiation.computeRemainingCPs(wfr);

        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator
                .comparingDouble((Task task) -> upwardRank.getOrDefault(
                        task.getCloudletId(), 0.0))
                .reversed()
                .thenComparingInt(Task::getCloudletId));

        Map<Integer, Double> lftMemo = new HashMap<>();
        for (Task task : tasks) computeLFT(task, wfr, lftMemo);

        Map<Integer, Double> plannedEnd = new HashMap<>();
        for (Task task : sorted) {
            int taskId = task.getCloudletId();
            double duration = wfr.getEstimatedExecTime(task);
            int cores = wfr.getTaskCores(taskId);
            int ramMb = wfr.getTaskRamMb(taskId);

            double est = arrival;
            for (Task parent : task.getParentList()) {
                est = Math.max(est,
                        plannedEnd.getOrDefault(parent.getCloudletId(), arrival));
            }
            double lft = wfr.getLFT(taskId);
            wfr.setEST(taskId, est);
            wfr.setEFT(taskId, est + duration);
            wfr.setLST(taskId, lft - duration);
            task.setLatestStartTime(lft - duration);

            int bestVm = CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL;
            double bestStart = Double.MAX_VALUE;
            for (CondorVM vm : vmPool.getReservedVms()) {
                double start = vmPool.findEarliestFeasibleSlot(
                        vm.getId(), est, lft, duration, cores, ramMb);
                if (start == Double.MAX_VALUE) continue;
                if (start < bestStart - EPS
                        || (Math.abs(start - bestStart) <= EPS
                            && (bestVm < 0 || vm.getId() < bestVm))) {
                    bestStart = start;
                    bestVm = vm.getId();
                }
            }

            task.setWorkflowId(wfr.getWorkflowId());
            if (bestVm != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                task.setVmId(bestVm);
                wfr.setAssignedVm(taskId, bestVm);
                wfr.setScheduledStart(taskId, bestStart);
                vmPool.bookSlot(bestVm, taskId, bestStart,
                        bestStart + duration, cores, ramMb);
                plannedEnd.put(taskId, bestStart + duration);
                CBMWLogger.logf("SG-PLAN",
                        "wf=%d task=%d -> reserved vm=%d slot=[%.2f,%.2f]",
                        wfr.getWorkflowId(), taskId, bestVm,
                        bestStart, bestStart + duration);
            } else {
                double orderTime = Math.max(arrival,
                        est - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
                double readyTime = projectedOnDemandReadyTime(orderTime);
                double plannedStart = Math.max(est, readyTime);

                task.setVmId(CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                wfr.setAssignedVm(taskId,
                        CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                wfr.setScheduledStart(taskId, plannedStart);
                wfr.setPlannedProvisionOrder(taskId, orderTime);
                wfr.setPlannedContainerReady(taskId, readyTime);
                plannedEnd.put(taskId, plannedStart + duration);

                schedule(getId(), Math.max(0.0, orderTime - CloudSim.clock()),
                        WorkflowSimTags.STATIC_GREEDY_ON_DEMAND_ORDER, taskId);
                CBMWLogger.logf("SG-PLAN",
                        "wf=%d task=%d -> on-demand order=%.2f"
                                + " ready=%.2f start=%.2f est=%.2f",
                        wfr.getWorkflowId(), taskId, orderTime,
                        readyTime, plannedStart, est);
            }
        }
        return true;
    }

    private double computeLFT(Task task, WorkflowRecord wfr,
                              Map<Integer, Double> memo) {
        int taskId = task.getCloudletId();
        Double cached = memo.get(taskId);
        if (cached != null) return cached;

        double lft = wfr.getDeadline();
        for (Task child : task.getChildList()) {
            lft = Math.min(lft,
                    computeLFT(child, wfr, memo)
                            - wfr.getEstimatedExecTime(child));
        }
        memo.put(taskId, lft);
        wfr.setLFT(taskId, lft);
        return lft;
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.STATIC_GREEDY_ON_DEMAND_ORDER) {
            int taskId = (Integer) ev.getData();
            orderLogicalOnDemandContainer(taskId);
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        super.processEvent(ev);
    }

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

        double nextWake = dispatcher.getNextWakeTime();
        if (Double.isFinite(nextWake) && nextWake > CloudSim.clock() + EPS) {
            schedule(getId(), nextWake - CloudSim.clock(),
                    WorkflowSimTags.CLOUDLET_UPDATE);
        }
    }

    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
    }
}
