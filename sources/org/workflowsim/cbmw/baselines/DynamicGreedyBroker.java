package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Reserved-first, event-driven greedy baseline. A task becomes eligible for
 * placement only after all predecessors finish. If reserved capacity remains
 * unavailable at LST - OPD, order a dedicated container while continuing to
 * check reserved capacity until the task actually starts.
 */
public class DynamicGreedyBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;

    /** Ready tasks that have not yet started (including tasks being provisioned). */
    private final Map<Integer, Cloudlet> waitingReady = new HashMap<>();
    private final Map<Integer, Double> latestStarts = new HashMap<>();
    private final TreeSet<Integer> waitingByLatestStart = new TreeSet<>(Comparator
            .comparingDouble(this::latestStart)
            .thenComparingInt(Integer::intValue));
    /** An on-demand request never precedes readiness and is issued only once. */
    private final Map<Integer, Integer> orderedOnDemand = new HashMap<>();
    private final Map<Integer, Integer> taskByContainer = new HashMap<>();
    private final Map<Integer, Cloudlet> newlyReadyJobs = new HashMap<>();
    private final Set<Integer> newlyReadyTaskIds = new HashSet<>();
    private final Set<Integer> containersReady = new HashSet<>();
    private final Set<Integer> thresholdWakeups = new HashSet<>();
    private final Set<Integer> dueThresholds = new HashSet<>();
    private boolean reservedCapacityReleased;

    public DynamicGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        setCloudletList(new IndexedCloudletQueue());
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    @Override
    protected boolean billProvisioningDelay() {
        return true;
    }

    /** The baseline accepts every valid arrival and records actual misses. */
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
    protected boolean usesPeriodicScheduling() {
        return false;
    }

    /** Calculate continuous-time deadline bounds, without assigning resources. */
    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            task.setWorkflowId(wfr.getWorkflowId());
            double lst = wfr.getDeadline()
                    - remainingCPs.getOrDefault(taskId, 0.0);
            wfr.setLST(taskId, lst);
            wfr.setLFT(taskId, lst + wfr.getEstimatedExecTime(taskId));
            task.setLatestStartTime(lst);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletSubmit(SimEvent ev) {
        List<? extends Cloudlet> jobs = (List<? extends Cloudlet>) ev.getData();
        for (Cloudlet cloudlet : jobs) {
            int taskId = primaryTaskId((Job) cloudlet);
            newlyReadyJobs.put(taskId, cloudlet);
            newlyReadyTaskIds.add(taskId);
        }
        recordReadyQueue((List<Cloudlet>) jobs);
        super.processCloudletSubmit(ev);
    }

    @Override
    protected void onLogicalContainerReady(int vmId) {
        Integer taskId = taskByContainer.get(vmId);
        if (taskId != null) containersReady.add(taskId);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.DYNAMIC_GREEDY_SST_REACHED) {
            int taskId = (Integer) ev.getData();
            thresholdWakeups.remove(taskId);
            if (waitingReady.containsKey(taskId)
                    && !orderedOnDemand.containsKey(taskId)) {
                dueThresholds.add(taskId);
                sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
            return;
        }
        super.processEvent(ev);
    }

    @Override
    protected void onTaskReturned(Cloudlet cloudlet, boolean onDemand) {
        int taskId = primaryTaskId((Job) cloudlet);
        waitingByLatestStart.remove(taskId);
        waitingReady.remove(taskId);
        latestStarts.remove(taskId);
        Integer vmId = orderedOnDemand.remove(taskId);
        if (vmId != null) taskByContainer.remove(vmId);
        newlyReadyJobs.remove(taskId);
        newlyReadyTaskIds.remove(taskId);
        containersReady.remove(taskId);
        thresholdWakeups.remove(taskId);
        dueThresholds.remove(taskId);
        if (!onDemand) reservedCapacityReleased = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        Set<Integer> candidates = new HashSet<>(newlyReadyTaskIds);
        newlyReadyTaskIds.clear();
        candidates.addAll(dueThresholds);
        dueThresholds.clear();

        waitingReady.putAll(newlyReadyJobs);
        for (int taskId : newlyReadyJobs.keySet()) {
            latestStarts.put(taskId, latestStart(taskId));
        }
        waitingByLatestStart.addAll(newlyReadyJobs.keySet());
        newlyReadyJobs.clear();
        candidates.addAll(containersReady);
        containersReady.clear();

        if (reservedCapacityReleased) {
            // This set stays ordered as tasks arrive. A completion can check
            // waiting tasks without re-sorting the entire queue each time.
            reservedCapacityReleased = false;
            for (Iterator<Integer> it = waitingByLatestStart.iterator();
                    it.hasNext();) {
                int taskId = it.next();
                Cloudlet cloudlet = waitingReady.get(taskId);
                if (cloudlet != null) considerReadyTask(cloudlet);
                if (!waitingReady.containsKey(taskId)) {
                    it.remove();
                    latestStarts.remove(taskId);
                }
            }
            return;
        }
        List<Integer> orderedCandidates = new ArrayList<>(candidates);
        orderedCandidates.sort(Comparator
                .comparingDouble(this::latestStart)
                .thenComparingInt(Integer::intValue));
        for (int taskId : orderedCandidates) {
            Cloudlet cloudlet = waitingReady.get(taskId);
            if (cloudlet != null) {
                considerReadyTask(cloudlet);
                if (!waitingReady.containsKey(taskId)) {
                    waitingByLatestStart.remove(taskId);
                    latestStarts.remove(taskId);
                }
            }
        }
    }

    private void considerReadyTask(Cloudlet cloudlet) {
        Job job = (Job) cloudlet;
        int taskId = primaryTaskId(job);
        double now = CloudSim.clock();
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        if (workflow == null) return;

        CondorVM reserved = vmPool.getAnyIdleReservedVm(taskId);
        if (reserved != null) {
            // The request has already started billing, even if it has not
            // finished provisioning. Retain that cost when switching.
            Integer cancelledVm = orderedOnDemand.remove(taskId);
            if (cancelledVm != null) {
                taskByContainer.remove(cancelledVm);
                cancelLogicalOnDemandContainer(taskId);
            }
            cloudlet.setVmId(reserved.getId());
            workflow.setAssignedVm(taskId, reserved.getId());
            workflow.setScheduledStart(taskId, now);
            dispatchScheduledJobs(java.util.Collections.singletonList(cloudlet));
            waitingReady.remove(taskId);
            thresholdWakeups.remove(taskId);
            CBMWLogger.logf("DG-DISPATCH",
                    "wf=%d task=%d -> reserved vm=%d at %.4f",
                    workflow.getWorkflowId(), taskId, reserved.getId(), now);
            return;
        }

        if (orderedOnDemand.containsKey(taskId)) {
            if (isLogicalContainerReady(taskId)) {
                int vmId = orderedOnDemand.get(taskId);
                cloudlet.setVmId(vmId);
                workflow.setAssignedVm(taskId,
                        CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                workflow.setScheduledStart(taskId, now);
                dispatchScheduledJobs(java.util.Collections.singletonList(cloudlet));
                waitingReady.remove(taskId);
                orderedOnDemand.remove(taskId);
                taskByContainer.remove(vmId);
                thresholdWakeups.remove(taskId);
                CBMWLogger.logf("DG-DISPATCH",
                        "wf=%d task=%d -> on-demand vm=%d at %.4f",
                        workflow.getWorkflowId(), taskId, vmId, now);
            }
            return;
        }

        double orderThreshold = workflow.getLST(taskId)
                - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
        if (now + EPS >= orderThreshold) {
            CondorVM container = orderLogicalOnDemandContainer(taskId);
            orderedOnDemand.put(taskId, container.getId());
            taskByContainer.put(container.getId(), taskId);
            workflow.setAssignedVm(taskId,
                    CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
            workflow.setPlannedProvisionOrder(taskId, now);
            workflow.setPlannedContainerReady(taskId,
                    projectedOnDemandReadyTime(now));
            if (projectedOnDemandReadyTime(now) > workflow.getLST(taskId) + EPS) {
                accounting.markDeadlineRisk(taskId);
            }
            CBMWLogger.logf("DG-ORDER",
                    "wf=%d task=%d requested at %.4f ready at %.4f LST=%.4f",
                    workflow.getWorkflowId(), taskId, now,
                    projectedOnDemandReadyTime(now), workflow.getLST(taskId));
        } else if (thresholdWakeups.add(taskId)) {
            schedule(getId(), orderThreshold - now,
                    WorkflowSimTags.DYNAMIC_GREEDY_SST_REACHED, taskId);
        }
    }

    private double latestStart(int taskId) {
        Double cached = latestStarts.get(taskId);
        if (cached != null) return cached;
        Cloudlet cloudlet = waitingReady.get(taskId);
        WorkflowRecord workflow = cloudlet == null ? null
                : activeWorkflows.get(workflowIdForJob((Job) cloudlet));
        return workflow == null ? Double.POSITIVE_INFINITY
                : workflow.getLST(taskId);
    }
}
