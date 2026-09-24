package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * Periodic dispatcher: due ready tasks first, then all remaining ready tasks
 * for an earlier reserved start. The broker launches due containers after
 * these two passes. SST denotes planned execution start on both resource types.
 */
public class CBMWDynamicSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private HybridVmPool pool;
    private Map<Integer, WorkflowRecord> activeWorkflows;
    private ProvisioningModule provisioner;
    private Set<Integer> waitingForPlannedReservedVm = Collections.emptySet();
    private Map<Integer, Double> reservedRetryTimes = Collections.emptyMap();
    private boolean safeReservedRebookingEnabled;
    /**
     * Static o0 orders superseded when Algorithm 3 advances a future task to
     * reserved capacity. The broker consumes the marker at the scheduled
     * order event, preventing an unused logical container from being created.
     */
    private Set<Integer> cancelledOnDemandOrders = new HashSet<>();
    private java.util.function.Consumer<Integer> cancelContainer = id -> {};
    private java.util.function.IntPredicate containerReady = id -> true;

    public void setContainerLifecycle(java.util.function.Consumer<Integer> cancel,
                                      java.util.function.IntPredicate ready) {
        this.cancelContainer = cancel;
        this.containerReady = ready;
    }

    public CBMWDynamicSchedulingAlgorithm() {}

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner) {
        this(pool, activeWorkflows, provisioner, Collections.emptySet(),
                new HashSet<Integer>(), new HashMap<Integer, Double>(), false);
    }

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner,
                                           Set<Integer> waitingForPlannedReservedVm) {
        this(pool, activeWorkflows, provisioner, waitingForPlannedReservedVm,
                new HashSet<Integer>(), new HashMap<Integer, Double>(), false);
    }

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner,
                                           Set<Integer> waitingForPlannedReservedVm,
                                           Set<Integer> cancelledOnDemandOrders) {
        this(pool, activeWorkflows, provisioner, waitingForPlannedReservedVm,
                cancelledOnDemandOrders, new HashMap<Integer, Double>(), false);
    }

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner,
                                           Set<Integer> waitingForPlannedReservedVm,
                                           Set<Integer> cancelledOnDemandOrders,
                                           Map<Integer, Double> reservedRetryTimes,
                                           boolean safeReservedRebookingEnabled) {
        this.pool            = pool;
        this.activeWorkflows = activeWorkflows;
        this.provisioner     = provisioner;
        this.waitingForPlannedReservedVm = waitingForPlannedReservedVm;
        this.cancelledOnDemandOrders = cancelledOnDemandOrders;
        this.reservedRetryTimes = reservedRetryTimes;
        this.safeReservedRebookingEnabled = safeReservedRebookingEnabled;
    }

    public void init(HybridVmPool pool,
                     Map<Integer, WorkflowRecord> activeWorkflows,
                     ProvisioningModule provisioner) {
        this.pool            = pool;
        this.activeWorkflows = activeWorkflows;
        this.provisioner     = provisioner;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
        if (pool == null) return;
        double now = CloudSim.clock();
        List<Cloudlet> readyJobs = (List<Cloudlet>) getCloudletList();
        Map<Integer, Integer> pendingCores = new HashMap<>();
        Map<Integer, Integer> pendingRamMb = new HashMap<>();
        List<Cloudlet> selected = new ArrayList<>();

        // The broker keeps ready tasks in ascending planned execution start.
        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int taskId = getPrimaryTaskId(job);
            WorkflowRecord wfr = activeWorkflows.get(getWorkflowId(job));
            if (wfr == null) continue;
            if (wfr.getScheduledStart(taskId) > now + 1e-9) break;
            int plannedVm = wfr.getAssignedVm(taskId);
            double duration = wfr.getEstimatedExecTime(taskId);
            if (plannedVm != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                // A previously started task can still be running when this
                // booked slot becomes due. Retain the ready task and retry
                // when capacity is released on a later periodic dispatch.
                if (!hasRuntimeCapacity(plannedVm, taskId,
                        pendingCores, pendingRamMb)) {
                    if (waitingForPlannedReservedVm.add(taskId)) {
                        CBMWLogger.logf("RESERVED-WAIT",
                                "wf=%d task=%d vm=%d now=%.4f",
                                wfr.getWorkflowId(), taskId, plannedVm, now);
                    }
                    continue;
                }
                waitingForPlannedReservedVm.remove(taskId);
                select(job, plannedVm, selected, pendingCores, pendingRamMb);
            } else {
                CondorVM reserved = findReserved(now, now + duration,
                        taskId, pendingCores, pendingRamMb);
                if (reserved != null) {
                    cancelOnDemand(taskId);
                    pool.bookSlot(reserved.getId(), taskId, now,
                            now + duration);
                    select(job, reserved.getId(), selected,
                            pendingCores, pendingRamMb);
                } else {
                    CondorVM container = provisioner.getProvisionedVm(taskId);
                    if (container != null && containerReady.test(taskId)) {
                        job.setVmId(container.getId());
                        selected.add(job);
                    }
                }
            }
        }

        // Failure for one task does not preclude another task with different
        // resource requirements from starting on reserved capacity.
        for (Cloudlet cl : readyJobs) {
            if (selected.contains(cl)) continue;
            Job job = (Job) cl;
            int taskId = getPrimaryTaskId(job);
            WorkflowRecord wfr = activeWorkflows.get(getWorkflowId(job));
            if (wfr == null || wfr.getScheduledStart(taskId) <= now + 1e-9) {
                continue;
            }
            double duration = wfr.getEstimatedExecTime(taskId);
            CondorVM reserved = null;
            for (CondorVM vm : pool.getReservedVms()) {
                if (hasRuntimeCapacity(vm.getId(), taskId,
                        pendingCores, pendingRamMb)
                        && pool.canMoveBookingNow(taskId, vm.getId(), now,
                                now + duration)) {
                    reserved = vm;
                    break;
                }
            }
            if (reserved == null) continue;
            if (!pool.moveBookingNow(taskId, reserved.getId(), now,
                    now + duration)) {
                throw new IllegalStateException(
                        "Reserved booking changed during dispatch");
            }
            if (wfr.getAssignedVm(taskId)
                    == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                cancelOnDemand(taskId);
            }
            select(job, reserved.getId(), selected,
                    pendingCores, pendingRamMb);
        }
        getScheduledList().addAll(selected);
    }

    private void cancelOnDemand(int taskId) {
        cancelledOnDemandOrders.add(taskId);
        cancelContainer.accept(taskId);
    }

    private void select(Job job, int vmId, List<Cloudlet> selected,
                        Map<Integer, Integer> pendingCores,
                        Map<Integer, Integer> pendingRamMb) {
        int id = getPrimaryTaskId(job);
        job.setVmId(vmId);
        selected.add(job);
        reservePending(vmId, id, pendingCores, pendingRamMb);
    }

    private boolean hasRuntimeCapacity(int vmId, int taskId,
                                       Map<Integer, Integer> pendingCores,
                                       Map<Integer, Integer> pendingRamMb) {
        CondorVM vm = pool.getVmById(vmId);
        if (vm == null) return false;
        int cores = pool.getRunningCores(vmId)
                + pendingCores.getOrDefault(vmId, 0)
                + pool.getTaskCores(taskId);
        int ramMb = pool.getRunningRamMb(vmId)
                + pendingRamMb.getOrDefault(vmId, 0)
                + pool.getTaskRamMb(taskId);
        return cores <= vm.getNumberOfPes() && ramMb <= vm.getRam();
    }

    private CondorVM findReserved(double now, double end, int taskId,
                                  Map<Integer, Integer> pendingCores,
                                  Map<Integer, Integer> pendingRamMb) {
        int cores = pool.getTaskCores(taskId);
        int ramMb = pool.getTaskRamMb(taskId);
        for (CondorVM vm : pool.getReservedVms()) {
            if (!hasRuntimeCapacity(vm.getId(), taskId,
                    pendingCores, pendingRamMb)) continue;
            if (pool.hasBookedCapacity(vm.getId(), now, end, cores, ramMb)) {
                return vm;
            }
        }
        return null;
    }

    private void reservePending(int vmId, int taskId,
                                Map<Integer, Integer> pendingCores,
                                Map<Integer, Integer> pendingRamMb) {
        pendingCores.merge(vmId, pool.getTaskCores(taskId), Integer::sum);
        pendingRamMb.merge(vmId, pool.getTaskRamMb(taskId), Integer::sum);
    }

    private void assign(Job job, CondorVM vm) {
        job.setVmId(vm.getId());
    }

    private double getScheduledStartForJob(Job job) {
        int wfId   = getWorkflowId(job);
        int taskId = getPrimaryTaskId(job);
        WorkflowRecord wfr = (activeWorkflows != null) ? activeWorkflows.get(wfId) : null;
        return (wfr != null) ? wfr.getScheduledStart(taskId) : 0.0;
    }

    private double getDeadlineForJob(Job job) {
        WorkflowRecord wfr = (activeWorkflows != null)
                ? activeWorkflows.get(getWorkflowId(job)) : null;
        return wfr != null ? wfr.getDeadline() : Double.MAX_VALUE;
    }

    private int getWorkflowId(Job job) {
        return !job.getTaskList().isEmpty()
                ? job.getTaskList().get(0).getWorkflowId() : -1;
    }

    private int getPrimaryTaskId(Job job) {
        return !job.getTaskList().isEmpty()
                ? job.getTaskList().get(0).getCloudletId() : job.getCloudletId();
    }

    private List<Task> getChildTasks(Job job) {
        List<Task> children = new ArrayList<>();
        for (Task t : job.getTaskList()) children.addAll(t.getChildList());
        return children;
    }
}
