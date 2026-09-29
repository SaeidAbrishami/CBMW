package org.workflowsim.cbmw.ablation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * Online ablation: deadline bounds only, without static resource assignments,
 * future bookings, or scheduled execution starts. Completion/ready and
 * container-ready events examine only their newly eligible jobs; ticks examine
 * the complete waiting set every five seconds.
 */
public final class CBMWNoPlanBroker extends AbstractWorkflowBroker {
    private static final double EPS = 1e-9;
    private final Map<Integer, Cloudlet> waiting = new HashMap<>();
    private final Map<Integer, Double> latest = new HashMap<>();
    private final TreeSet<Integer> waitingByLatest = new TreeSet<>(Comparator
            .comparingDouble((Integer id) -> latest.getOrDefault(id,
                    Double.POSITIVE_INFINITY)).thenComparingInt(Integer::intValue));
    private final Map<Integer, Integer> orderedContainers = new HashMap<>();
    private final Map<Integer, Integer> taskByContainer = new HashMap<>();
    private final Map<Integer, Cloudlet> newlyReady = new HashMap<>();
    private final Set<Integer> containersReady = new HashSet<>();
    private boolean tickPending;

    public CBMWNoPlanBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        setCloudletList(new IndexedCloudletQueue());
        negotiation.setBeta(PaperRuntimeModel.NEGOTIATION_BETA);
        negotiation.setGamma(PaperRuntimeModel.NEGOTIATION_GAMMA);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    @Override
    protected boolean billProvisioningDelay() { return true; }

    @Override
    protected boolean usesPeriodicScheduling() { return false; }

    @Override
    protected boolean negotiateWorkflow(WorkflowRecord workflow) {
        workflow.setCriticalPathLength(negotiation.computeCriticalPath(workflow));
        workflow.setDeadlineFeasible(true);
        workflow.setAccepted(true);
        return true;
    }

    /** Only precedence-based bounds; no VM, SST, or capacity reservation. */
    @Override
    protected boolean planWorkflow(WorkflowRecord workflow, List<Task> tasks) {
        Map<Integer, Double> finishes = new HashMap<>();
        Map<Integer, Double> latestFinishes = new HashMap<>();
        for (Task task : tasks) {
            int id = task.getCloudletId();
            double eft = earliestFinish(task, workflow, finishes);
            double lft = latestFinish(task, workflow, latestFinishes);
            double duration = workflow.getEstimatedExecTime(id);
            workflow.setEST(id, eft - duration);
            workflow.setEFT(id, eft);
            workflow.setLFT(id, lft);
            workflow.setLST(id, lft - duration);
            task.setLatestStartTime(lft - duration);
            task.setWorkflowId(workflow.getWorkflowId());
        }
        return true;
    }

    private double earliestFinish(Task task, WorkflowRecord workflow,
                                  Map<Integer, Double> memo) {
        int id = task.getCloudletId();
        Double previous = memo.get(id);
        if (previous != null) return previous;
        double start = workflow.getArrivalTime();
        for (Task parent : task.getParentList()) {
            start = Math.max(start, earliestFinish(parent, workflow, memo));
        }
        double finish = start + workflow.getEstimatedExecTime(id);
        memo.put(id, finish);
        return finish;
    }

    private double latestFinish(Task task, WorkflowRecord workflow,
                                Map<Integer, Double> memo) {
        int id = task.getCloudletId();
        Double previous = memo.get(id);
        if (previous != null) return previous;
        double finish = workflow.getDeadline();
        for (Task child : task.getChildList()) {
            int childId = child.getCloudletId();
            finish = Math.min(finish, latestFinish(child, workflow, memo)
                    - workflow.getEstimatedExecTime(childId));
        }
        memo.put(id, finish);
        return finish;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletSubmit(SimEvent ev) {
        List<Cloudlet> jobs = (List<Cloudlet>) ev.getData();
        recordReadyQueue(jobs);
        for (Cloudlet cloudlet : jobs) {
            newlyReady.put(primaryTaskId((Job) cloudlet), cloudlet);
        }
        super.processCloudletSubmit(ev);
    }

    @Override
    protected void onLogicalContainerReady(int vmId) {
        Integer taskId = taskByContainer.get(vmId);
        if (taskId != null) containersReady.add(taskId);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.CBMW_ABLATION_WAITING_TICK) {
            tickPending = false;
            consider(new ArrayList<>(waitingByLatest));
            armNextTick();
            return;
        }
        super.processEvent(ev);
    }

    @Override
    protected void processCloudletUpdate(SimEvent ev) {
        // Newly ready tasks are added to the shared queue by WorkflowScheduler.
        // Do not rescan tasks that were already waiting after every completion.
        if (!newlyReady.isEmpty()) {
            for (Map.Entry<Integer, Cloudlet> entry : newlyReady.entrySet()) {
                int id = entry.getKey();
                Cloudlet cloudlet = entry.getValue();
                if (waiting.containsKey(id)) continue;
                WorkflowRecord workflow = activeWorkflows.get(
                        workflowIdForJob((Job) cloudlet));
                if (workflow == null) continue;
                waiting.put(id, cloudlet);
                latest.put(id, workflow.getLST(id));
                waitingByLatest.add(id);
            }
        }
        Set<Integer> candidates = new HashSet<>(newlyReady.keySet());
        candidates.addAll(containersReady);
        newlyReady.clear();
        containersReady.clear();
        List<Integer> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingDouble(
                (Integer id) -> latest.getOrDefault(id, Double.POSITIVE_INFINITY))
                .thenComparingInt(Integer::intValue));
        consider(ordered);
        armNextTick();
    }

    private void consider(List<Integer> ids) {
        double now = CloudSim.clock();
        for (int id : ids) {
            Cloudlet cloudlet = waiting.get(id);
            if (cloudlet == null) continue;
            Job job = (Job) cloudlet;
            WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
            if (workflow == null) continue;
            CondorVM reserved = vmPool.getAnyIdleReservedVm(id);
            if (reserved != null) {
                if (orderedContainers.containsKey(id)) {
                    int vmId = orderedContainers.remove(id);
                    taskByContainer.remove(vmId);
                    cancelLogicalOnDemandContainer(id);
                }
                dispatch(cloudlet, workflow, reserved.getId());
                continue;
            }
            Integer containerId = orderedContainers.get(id);
            if (containerId != null) {
                if (isLogicalContainerReady(id)) {
                    orderedContainers.remove(id);
                    taskByContainer.remove(containerId);
                    dispatch(cloudlet, workflow, containerId);
                }
                continue;
            }
            double requestBy = workflow.getLST(id)
                    - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
            // An event between ticks cannot wait for a round past requestBy.
            double next = (Math.floor(now / HybridVmPool.SCHEDULING_PERIOD) + 1.0)
                    * HybridVmPool.SCHEDULING_PERIOD;
            if (now + EPS >= requestBy || next > requestBy + EPS) {
                CondorVM container = orderLogicalOnDemandContainer(id);
                orderedContainers.put(id, container.getId());
                taskByContainer.put(container.getId(), id);
                workflow.setAssignedVm(id, CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
                workflow.setPlannedProvisionOrder(id, now);
                workflow.setPlannedContainerReady(id,
                        projectedOnDemandReadyTime(now));
                if (now > requestBy + EPS) accounting.markDeadlineRisk(id);
                CBMWLogger.logf("ABLATION-NOPLAN-ORDER",
                        "task=%d at=%.4f latestRequest=%.4f", id, now, requestBy);
            }
        }
    }

    private void dispatch(Cloudlet cloudlet, WorkflowRecord workflow, int vmId) {
        int id = primaryTaskId((Job) cloudlet);
        cloudlet.setVmId(vmId);
        workflow.setAssignedVm(id, vmPool.isReserved(vmId)
                ? vmId : CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
        int queuedBefore = getCloudletList().size();
        dispatchScheduledJobs(java.util.Collections.singletonList(cloudlet));
        if (getCloudletList().size() < queuedBefore) {
            waitingByLatest.remove(id);
            waiting.remove(id);
            latest.remove(id);
        }
    }

    private void armNextTick() {
        if (tickPending || waiting.isEmpty()) return;
        double now = CloudSim.clock();
        double period = HybridVmPool.SCHEDULING_PERIOD;
        double next = (Math.floor(now / period) + 1.0) * period;
        tickPending = true;
        schedule(getId(), next - now, WorkflowSimTags.CBMW_ABLATION_WAITING_TICK);
    }
}
