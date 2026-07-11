package org.workflowsim.cbmw;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;

/**
 * CBMW broker: four-module algorithm (Negotiate → Static Plan → Dynamic
 * Dispatch → Provision).
 *
 * Inherits all CloudSim wiring, DAX parsing, and result tracking from
 * AbstractWorkflowBroker. This class owns only the CBMW-specific logic:
 *   - planWorkflow: deadline-aware backward sweep-line static planner
 *   - processCloudletUpdate: LST/deadline-aware dynamic dispatcher
 *   - onTaskComplete: releases the booking slot on reserved VMs
 */
public class CBMWBroker extends AbstractWorkflowBroker {

    private final CBMWDynamicSchedulingAlgorithm dynamicScheduler;
    private final Map<Integer, ReservedPreemption> pendingPreemptions =
            new HashMap<>();
    private final Set<Integer> preemptedTaskIds = new HashSet<>();

    private static final class ReservedPreemption {
        private final Job victim;
        private final int replacementTaskId;
        private final int vmId;

        private ReservedPreemption(Job victim, int replacementTaskId, int vmId) {
            this.victim = victim;
            this.replacementTaskId = replacementTaskId;
            this.vmId = vmId;
        }
    }

    public CBMWBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        negotiation.setBeta(PaperRuntimeModel.NEGOTIATION_BETA);
        negotiation.setGamma(PaperRuntimeModel.NEGOTIATION_GAMMA);
        this.dynamicScheduler = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    // -----------------------------------------------------------------------
    // Module 2 — Static Planning (backward sweep-line)
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(wfr, vmPool, negotiation);
        planner.setTaskList(tasks);
        planner.setVmList(vmPool.getAllVms());
        try {
            planner.run();
        } catch (Exception e) {
            Log.printLine(getName() + ": static planner error: " + e.getMessage());
            wfr.setAccepted(false);
            return false;
        }
        schedulePlannedOnDemandOrders(wfr, tasks);
        return true;
    }

    private void schedulePlannedOnDemandOrders(WorkflowRecord wfr, List<Task> tasks) {
        double now = CloudSim.clock();
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            if (wfr.getAssignedVm(taskId)
                    != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                continue;
            }
            double orderTime = wfr.getScheduledStart(taskId);
            schedule(getId(), Math.max(0.0, orderTime - now),
                    WorkflowSimTags.CBMW_ON_DEMAND_ORDER, taskId);
            CBMWLogger.logf("CBMW-ONDEMAND-ORDER-SCHEDULED",
                    "wf=%d task=%d orderAt=%.4f now=%.4f",
                    wfr.getWorkflowId(), taskId, orderTime, now);
        }
    }

    @Override
    protected void finalizeWorkflowNegotiation(WorkflowRecord wfr) {
        negotiation.quoteExecutionPrice(wfr);
    }

    // -----------------------------------------------------------------------
    // Module 3 — Dynamic Dispatch
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());

        // A replacement cannot be submitted until the datacenter confirms
        // that the selected victim has stopped and released its PEs.
        if (!pendingPreemptions.isEmpty() || requestReservedPreemption()) {
            return;
        }

        dynamicScheduler.setCloudletList(getCloudletList());
        dynamicScheduler.setVmList(getVmsCreatedList());
        dynamicScheduler.getScheduledList().clear();

        try {
            dynamicScheduler.run();
        } catch (Exception e) {
            Log.printLine("CBMW dynamic scheduler error: " + e.getMessage());
        }

        dispatchScheduledJobs(dynamicScheduler.getScheduledList());

        // Runtime usage now includes every successful submission from this
        // pass. If a later due task was retained because those submissions
        // consumed its capacity, start replacement now at the same timestamp.
        requestReservedPreemption();
    }

    /**
     * When a due, reserved-planned task has no reserved capacity, replace the
     * running reserved task whose workflow has the greatest remaining time to
     * its deadline. A victim must release enough CPU and RAM by itself.
     * Previously preempted tasks remain eligible for normal dispatch but do
     * not initiate another preemption, preventing immediate swap oscillation.
     */
    @SuppressWarnings("unchecked")
    private boolean requestReservedPreemption() {
        List<Cloudlet> ready = (List<Cloudlet>) getCloudletList();
        ready.sort(Comparator.comparingDouble(this::scheduledStart));

        for (Cloudlet cloudlet : ready) {
            Job waiting = (Job) cloudlet;
            if (requestReservedPreemption(waiting)) return true;
        }
        return false;
    }

    private boolean requestReservedPreemption(Job waiting) {
        double now = CloudSim.clock();
        int waitingTaskId = primaryTaskId(waiting);
        WorkflowRecord waitingWorkflow = activeWorkflows.get(
                workflowIdForJob(waiting));
        if (waitingWorkflow == null
                || preemptedTaskIds.contains(waitingTaskId)
                || waitingWorkflow.getScheduledStart(waitingTaskId) > now
                || waitingWorkflow.getAssignedVm(waitingTaskId)
                        == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
            return false;
        }

        int assignedVmId = waiting.getVmId() >= 0
                ? waiting.getVmId() : waitingWorkflow.getAssignedVm(waitingTaskId);
        if (vmPool.hasRuntimeCapacity(assignedVmId, waitingTaskId)) {
            return false;
        }

        Job victim = selectVictim(waitingTaskId);
        if (victim == null) return false;

        int victimVmId = victim.getVmId();
        Integer datacenterId = getVmsToDatacentersMap().get(victimVmId);
        if (datacenterId == null) return false;

        waitingWorkflow.setAssignedVm(waitingTaskId, victimVmId);
        pendingPreemptions.put(victim.getCloudletId(),
                new ReservedPreemption(victim, waitingTaskId, victimVmId));
        int[] request = {victim.getCloudletId(), getId(), victimVmId};
        sendNow(datacenterId, CloudSimTags.CLOUDLET_CANCEL, request);
        CBMWLogger.logf("RESERVED-PREEMPT-REQUEST",
                "waitingWf=%d waitingTask=%d victimWf=%d victimTask=%d vm=%d now=%.4f",
                waitingWorkflow.getWorkflowId(), waitingTaskId,
                workflowIdForJob(victim), primaryTaskId(victim),
                victimVmId, now);
        return true;
    }

    private Job selectVictim(int waitingTaskId) {
        int requiredCores = vmPool.getTaskCores(waitingTaskId);
        int requiredRam = vmPool.getTaskRamMb(waitingTaskId);

        return getCloudletSubmittedList().stream()
                .filter(cl -> cl instanceof Job)
                .map(cl -> (Job) cl)
                .filter(job -> vmPool.isReserved(job.getVmId()))
                .filter(job -> !preemptedTaskIds.contains(primaryTaskId(job)))
                .filter(job -> vmPool.getTaskCores(primaryTaskId(job)) >= requiredCores)
                .filter(job -> vmPool.getTaskRamMb(primaryTaskId(job)) >= requiredRam)
                .filter(job -> capacityAfterRemoving(job, waitingTaskId))
                .max(Comparator.comparingDouble(this::deadline))
                .orElse(null);
    }

    private boolean capacityAfterRemoving(Job victim, int waitingTaskId) {
        CondorVM vm = vmPool.getVmById(victim.getVmId());
        if (vm == null) return false;
        int victimTaskId = primaryTaskId(victim);
        int coresAfter = vmPool.getRunningCores(vm.getId())
                - vmPool.getTaskCores(victimTaskId)
                + vmPool.getTaskCores(waitingTaskId);
        int ramAfter = vmPool.getRunningRamMb(vm.getId())
                - vmPool.getTaskRamMb(victimTaskId)
                + vmPool.getTaskRamMb(waitingTaskId);
        return coresAfter <= vm.getNumberOfPes() && ramAfter <= vm.getRam();
    }

    private double deadline(Job job) {
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null ? workflow.getDeadline() : Double.NEGATIVE_INFINITY;
    }

    private double scheduledStart(Cloudlet cloudlet) {
        Job job = (Job) cloudlet;
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null
                ? workflow.getScheduledStart(primaryTaskId(job)) : 0.0;
    }

    // -----------------------------------------------------------------------
    // Module 4 — Release booking slot on reserved-VM completion
    // -----------------------------------------------------------------------

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == CloudSimTags.CLOUDLET_CANCEL) {
            processReservedPreemptionAck((Cloudlet) ev.getData());
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CBMW_ON_DEMAND_ORDER) {
            int taskId = (Integer) ev.getData();
            orderLogicalOnDemandContainer(taskId);
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        super.processEvent(ev);
    }

    private void processReservedPreemptionAck(Cloudlet canceled) {
        if (canceled == null) {
            // A completion may have won the same-time race. Clear all pending
            // requests and let the normal dispatcher re-evaluate capacity.
            pendingPreemptions.clear();
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }

        ReservedPreemption preemption = pendingPreemptions.remove(
                canceled.getCloudletId());
        if (preemption == null) {
            return;
        }

        int victimTaskId = primaryTaskId(preemption.victim);
        long remainingLength = Math.max(1L,
                canceled.getCloudletLength() - canceled.getCloudletFinishedSoFar());
        canceled.setCloudletLength(remainingLength);

        getCloudletSubmittedList().remove(canceled);
        cloudletsSubmitted = Math.max(0, cloudletsSubmitted - 1);
        vmPool.taskFinished(preemption.vmId, victimTaskId);
        vmPool.releaseSlot(victimTaskId);
        accounting.markReservedTaskPreempted(canceled);
        preemptedTaskIds.add(victimTaskId);
        getCloudletList().add(canceled);

        CBMWLogger.logf("RESERVED-PREEMPTED",
                "victimWf=%d victimTask=%d vm=%d remainingMI=%d replacementTask=%d",
                workflowIdForJob(preemption.victim), victimTaskId,
                preemption.vmId, remainingLength, preemption.replacementTaskId);
        sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
    }

    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
    }
}
