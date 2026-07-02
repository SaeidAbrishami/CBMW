package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/** Event-driven dynamic greedy baseline from new_experiments_text.txt. */
public class DynamicGreedyBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;

    /** Ready tasks waiting for reserved capacity, keyed by task ID. */
    private final Map<Integer, Cloudlet> delayedReady = new HashMap<>();
    /** Task ID to its irrevocably rented dedicated on-demand container. */
    private final Map<Integer, Integer> lockedOnDemand = new HashMap<>();
    private final Set<Integer> newlyReadyTaskIds = new HashSet<>();
    private final Set<Integer> scheduledSstWakeups = new HashSet<>();
    private int completionEventsPending;

    public DynamicGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    /** DynamicGreedy schedules every valid workflow without CBMW admission. */
    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        double cp = negotiation.computeCriticalPath(wfr);
        wfr.setCriticalPathLength(cp);
        wfr.setDeadlineFeasible(
                cp <= wfr.getDeadline() - wfr.getArrivalTime() + EPS);
        wfr.setAccepted(true);
        return true;
    }

    /** This baseline reacts directly to events instead of periodic ticks. */
    @Override
    protected boolean usesPeriodicScheduling() {
        return false;
    }

    /** Computes each task's OPD-adjusted latest on-demand order threshold. */
    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
        double deadline = wfr.getDeadline();
        double arrival = wfr.getArrivalTime();

        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            task.setWorkflowId(wfr.getWorkflowId());
            double remainingCP = remainingCPs.getOrDefault(taskId, 0.0);
            double lst = deadline - remainingCP;
            double sst = Math.max(arrival,
                    lst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            wfr.setLST(taskId, lst);
            wfr.setLFT(taskId, lst + wfr.getEstimatedExecTime(taskId));
            wfr.setScheduledStart(taskId, sst);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletSubmit(SimEvent ev) {
        List<? extends Cloudlet> jobs = (List<? extends Cloudlet>) ev.getData();
        for (Cloudlet cloudlet : jobs) {
            newlyReadyTaskIds.add(primaryTaskId((Job) cloudlet));
        }
        super.processCloudletSubmit(ev);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.DYNAMIC_GREEDY_SST_REACHED) {
            processSstReached((Integer) ev.getData());
            return;
        }
        super.processEvent(ev);
    }

    @Override
    protected void onTaskReturned(Cloudlet cloudlet, boolean onDemand) {
        int taskId = primaryTaskId((Job) cloudlet);
        delayedReady.remove(taskId);
        lockedOnDemand.remove(taskId);
        newlyReadyTaskIds.remove(taskId);
        scheduledSstWakeups.remove(taskId);
        completionEventsPending++;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());

        // A rented container owns its task permanently. Container-ready events
        // return here and dispatch it without reconsidering reserved resources.
        dispatchReadyLockedOnDemandTasks();

        // Event (b): after one task completion, start only the first delayed
        // task (ascending SST) whose reserved requirements can be satisfied.
        int completions = completionEventsPending;
        completionEventsPending = 0;
        for (int i = 0; i < completions; i++) {
            dispatchFirstExecutableDelayedTask();
        }

        // Event (a): each newly ready task immediately tries reserved capacity.
        List<Integer> newlyReady = new ArrayList<>(newlyReadyTaskIds);
        newlyReadyTaskIds.clear();
        Collections.sort(newlyReady);
        for (Integer taskId : newlyReady) {
            Cloudlet cloudlet = findReadyTask(taskId);
            if (cloudlet != null
                    && !delayedReady.containsKey(taskId)
                    && !lockedOnDemand.containsKey(taskId)) {
                processNewlyReadyTask(cloudlet);
            }
        }

        // Defensive classification for jobs delivered without the usual
        // CLOUDLET_SUBMIT callback.
        List<Cloudlet> unclassified = new ArrayList<>((List<Cloudlet>) getCloudletList());
        unclassified.sort(Comparator.comparingInt(Cloudlet::getCloudletId));
        for (Cloudlet cloudlet : unclassified) {
            int taskId = primaryTaskId((Job) cloudlet);
            if (!delayedReady.containsKey(taskId)
                    && !lockedOnDemand.containsKey(taskId)) {
                processNewlyReadyTask(cloudlet);
            }
        }
    }

    private void processNewlyReadyTask(Cloudlet cloudlet) {
        Job job = (Job) cloudlet;
        int taskId = primaryTaskId(job);
        CondorVM reserved = vmPool.getAnyIdleReservedVm(taskId);
        if (reserved != null) {
            cloudlet.setVmId(reserved.getId());
            dispatchOne(cloudlet);
            CBMWLogger.logf("DG-DISPATCH",
                    "event=READY wf=%d task=%d -> reserved vm=%d",
                    workflowIdForJob(job), taskId, reserved.getId());
            return;
        }

        double sst = getSst(job);
        if (CloudSim.clock() + EPS >= sst) {
            rentOnDemand(cloudlet, "READY_AFTER_SST");
            return;
        }

        delayedReady.put(taskId, cloudlet);
        if (scheduledSstWakeups.add(taskId)) {
            schedule(getId(), sst - CloudSim.clock(),
                    WorkflowSimTags.DYNAMIC_GREEDY_SST_REACHED, taskId);
        }
        CBMWLogger.logf("DG-DELAY",
                "wf=%d task=%d delayed until sst=%.2f",
                workflowIdForJob(job), taskId, sst);
    }

    private void dispatchFirstExecutableDelayedTask() {
        List<Cloudlet> delayed = new ArrayList<>(delayedReady.values());
        delayed.sort(Comparator
                .comparingDouble((Cloudlet cl) -> getSst((Job) cl))
                .thenComparingInt(Cloudlet::getCloudletId));

        for (Cloudlet cloudlet : delayed) {
            int taskId = primaryTaskId((Job) cloudlet);
            if (findReadyTask(taskId) == null) {
                delayedReady.remove(taskId);
                continue;
            }
            CondorVM reserved = vmPool.getAnyIdleReservedVm(taskId);
            if (reserved == null) continue;

            delayedReady.remove(taskId);
            scheduledSstWakeups.remove(taskId);
            cloudlet.setVmId(reserved.getId());
            dispatchOne(cloudlet);
            CBMWLogger.logf("DG-DISPATCH",
                    "event=COMPLETE wf=%d task=%d -> reserved vm=%d",
                    workflowIdForJob((Job) cloudlet), taskId, reserved.getId());
            return;
        }
    }

    private void processSstReached(int taskId) {
        scheduledSstWakeups.remove(taskId);
        Cloudlet cloudlet = findReadyTask(taskId);
        if (cloudlet == null || lockedOnDemand.containsKey(taskId)) return;
        delayedReady.remove(taskId);
        rentOnDemand(cloudlet, "SST_REACHED");
    }

    private void rentOnDemand(Cloudlet cloudlet, String reason) {
        Job job = (Job) cloudlet;
        int taskId = primaryTaskId(job);
        CondorVM container = orderLogicalOnDemandContainer(taskId);
        lockedOnDemand.put(taskId, container.getId());
        delayedReady.remove(taskId);
        scheduledSstWakeups.remove(taskId);
        cloudlet.setVmId(container.getId());
        if (vmPool.isOnDemandContainerActive(container.getId())) {
            dispatchOne(cloudlet);
        }
        CBMWLogger.logf("DG-DISPATCH",
                "event=%s wf=%d task=%d -> on-demand vm=%d",
                reason, workflowIdForJob(job), taskId, container.getId());
    }

    private void dispatchReadyLockedOnDemandTasks() {
        List<Cloudlet> ready = new ArrayList<>();
        for (Cloudlet cloudlet : getCloudletList()) {
            int taskId = primaryTaskId((Job) cloudlet);
            Integer vmId = lockedOnDemand.get(taskId);
            if (vmId != null && vmPool.isOnDemandContainerActive(vmId)) {
                cloudlet.setVmId(vmId);
                ready.add(cloudlet);
            }
        }
        for (Cloudlet cloudlet : ready) dispatchOne(cloudlet);
    }

    private void dispatchOne(Cloudlet cloudlet) {
        dispatchScheduledJobs(Collections.singletonList(cloudlet));
    }

    private Cloudlet findReadyTask(int taskId) {
        for (Cloudlet cloudlet : getCloudletList()) {
            if (primaryTaskId((Job) cloudlet) == taskId) return cloudlet;
        }
        return null;
    }

    private double getSst(Job job) {
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null
                ? workflow.getScheduledStart(primaryTaskId(job)) : 0.0;
    }
}
