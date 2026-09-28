package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.IndexedCloudletQueue;
import org.workflowsim.cbmw.PaperRuntimeModel;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Completely static greedy baseline from new_experiments_text.txt.
 *
 * Each valid workflow is fully planned when it arrives. Tasks are considered
 * by descending upward rank and assigned to their earliest deadline-feasible
 * reserved slot. A dedicated on-demand container is selected only when no
 * reserved slot exists in that window. Runtime execution keeps each planned
 * resource and exact start fixed, even if predecessors finish early.
 */
public class StaticGreedyBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;
    private final Map<Integer, Cloudlet> readyTasks = new HashMap<>();
    private final TreeMap<Double, List<Cloudlet>> futureReady = new TreeMap<>();
    private final Comparator<Cloudlet> plannedOrder = Comparator
            .comparingDouble((Cloudlet cl) -> plannedStart((Job) cl))
            .thenComparingInt(Cloudlet::getCloudletId);
    private final TreeSet<Cloudlet> dueReserved = new TreeSet<>(plannedOrder);
    private final TreeSet<Cloudlet> dueOnDemand = new TreeSet<>(plannedOrder);
    private final Set<Cloudlet> newlyDue = new HashSet<>();
    private final Set<Integer> containersReady = new HashSet<>();
    private final Map<Integer, Integer> taskByContainer = new HashMap<>();
    private boolean reservedReleased;
    private long wakeGeneration;
    private double scheduledWakeTime = Double.POSITIVE_INFINITY;

    public StaticGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        setCloudletList(new IndexedCloudletQueue());
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

    /** StaticGreedy reacts to readiness, completion, provisioning, and plan wakes. */
    @Override
    protected boolean usesPeriodicScheduling() {
        return false;
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    @Override
    protected boolean billProvisioningDelay() {
        return true;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        double arrival = wfr.getArrivalTime();
        Map<Integer, Double> upwardRank = negotiation.computeRemainingCPs(wfr);

        Map<Integer, Double> lftMemo = new HashMap<>();
        for (Task task : tasks) computeLFT(task, wfr, lftMemo);

        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator
                .comparingDouble((Task task) -> upwardRank.getOrDefault(
                        task.getCloudletId(), 0.0))
                .reversed()
                .thenComparingDouble(task -> wfr.getLFT(task.getCloudletId())
                        - wfr.getEstimatedExecTime(task))
                .thenComparingInt(Task::getCloudletId));

        Map<Integer, Double> plannedEnd = new HashMap<>();
        for (Task task : sorted) {
            int taskId = task.getCloudletId();
            double duration = wfr.getEstimatedExecTime(task);
            int cores = wfr.getTaskCores(taskId);
            int ramMb = wfr.getTaskRamMb(taskId);

            double est = arrival;
            for (Task parent : task.getParentList()) {
                Double parentFinish = plannedEnd.get(parent.getCloudletId());
                if (parentFinish == null) {
                    throw new IllegalStateException("Upward-rank order placed child "
                            + taskId + " before parent " + parent.getCloudletId());
                }
                est = Math.max(est, parentFinish);
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
                // No VM can start earlier than EST; reserved VMs are in ID
                // order, so this is also the best possible tie break.
                if (bestStart <= est + EPS) break;
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
                double plannedStart = Math.max(est, arrival
                        + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
                double orderTime = plannedStart
                        - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
                double readyTime = projectedOnDemandReadyTime(orderTime);
                // Accept every workflow and record a task whose earliest
                // provisionable execution already exceeds its latest start.
                if (plannedStart > lft - duration + EPS) {
                    accounting.markDeadlineRisk(taskId);
                }

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
            CondorVM container = orderLogicalOnDemandContainer(taskId);
            taskByContainer.put(container.getId(), taskId);
            return;
        }
        if (ev.getTag() == WorkflowSimTags.STATIC_GREEDY_SCHEDULE_WAKE) {
            long generation = (Long) ev.getData();
            if (generation == wakeGeneration) {
                scheduledWakeTime = Double.POSITIVE_INFINITY;
                sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
            return;
        }
        super.processEvent(ev);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletSubmit(SimEvent ev) {
        List<Cloudlet> jobs = (List<Cloudlet>) ev.getData();
        recordReadyQueue(jobs);
        double now = CloudSim.clock();
        for (Cloudlet cl : jobs) {
            readyTasks.put(cl.getCloudletId(), cl);
            double start = plannedStart((Job) cl);
            if (start <= now + EPS) {
                addDue(cl);
                newlyDue.add(cl);
            } else {
                futureReady.computeIfAbsent(start,
                        ignored -> new ArrayList<>()).add(cl);
            }
        }
        super.processCloudletSubmit(ev);
    }

    @Override
    protected void onLogicalContainerReady(int vmId) {
        Integer taskId = taskByContainer.get(vmId);
        if (taskId != null) containersReady.add(taskId);
    }

    @Override
    protected void onTaskSubmitted(Cloudlet cl, boolean onDemand) {
        readyTasks.remove(cl.getCloudletId());
        dueReserved.remove(cl);
        dueOnDemand.remove(cl);
        if (onDemand) taskByContainer.remove(cl.getVmId());
    }

    @Override
    protected void processCloudletUpdate(SimEvent ev) {
        double now = CloudSim.clock();
        TreeSet<Cloudlet> candidates = new TreeSet<>(plannedOrder);
        candidates.addAll(newlyDue);
        newlyDue.clear();

        while (!futureReady.isEmpty()
                && futureReady.firstKey() <= now + EPS) {
            for (Cloudlet cl : futureReady.pollFirstEntry().getValue()) {
                addDue(cl);
                candidates.add(cl);
            }
        }
        if (reservedReleased) {
            candidates.addAll(dueReserved);
            reservedReleased = false;
        }
        for (int taskId : containersReady) {
            Cloudlet cl = readyTasks.get(taskId);
            if (cl != null && dueOnDemand.contains(cl)) candidates.add(cl);
        }
        containersReady.clear();

        List<Cloudlet> scheduled = new ArrayList<>();
        for (Cloudlet cl : candidates) {
            int taskId = cl.getCloudletId();
            Job job = (Job) cl;
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
            if (wfr == null || !readyTasks.containsKey(taskId)) continue;
            int plannedVm = wfr.getAssignedVm(taskId);
            if (plannedVm != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                if (vmPool.hasRuntimeCapacity(plannedVm, taskId)) {
                    job.setVmId(plannedVm);
                    scheduled.add(job);
                }
            } else {
                CondorVM container = provisioner.getProvisionedVm(taskId);
                if (container != null
                        && vmPool.isOnDemandContainerActive(container.getId())) {
                    job.setVmId(container.getId());
                    scheduled.add(job);
                }
            }
        }
        dispatchScheduledJobs(scheduled);
        scheduleNextWake(futureReady.isEmpty()
                ? Double.POSITIVE_INFINITY : futureReady.firstKey());
    }

    private void addDue(Cloudlet cl) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob((Job) cl));
        if (wfr == null) return;
        (wfr.getAssignedVm(cl.getCloudletId())
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                ? dueOnDemand : dueReserved).add(cl);
    }

    private double plannedStart(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr == null ? Double.POSITIVE_INFINITY
                : wfr.getScheduledStart(primaryTaskId(job));
    }

    /** Keeps at most one effective future plan wake; older events become stale. */
    private void scheduleNextWake(double nextWake) {
        double now = CloudSim.clock();
        if (!Double.isFinite(nextWake) || nextWake <= now + EPS) return;
        if (nextWake >= scheduledWakeTime - EPS) return;

        scheduledWakeTime = nextWake;
        long generation = ++wakeGeneration;
        schedule(getId(), nextWake - now,
                WorkflowSimTags.STATIC_GREEDY_SCHEDULE_WAKE, generation);
    }

    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
        reservedReleased = true;
    }
}
