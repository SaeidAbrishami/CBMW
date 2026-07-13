package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * Module 3 – Dynamic Scheduling (Algorithm 3 from the paper).
 *
 * Two loops per tick:
 *   1. First loop  (sstji ≤ CT): dispatch each task on its allocated resource;
 *      if reserved VM is busy call CheckReserved for another; if none, the
 *      broker applies its current-cycle reserved-task replacement policy.
 *   2. Second loop (sstji > CT): advance future tasks to idle reserved VMs;
 *      break on the first task that CheckReserved cannot serve.
 *
 * ReadyTasks are kept sorted by sstji ascending (paper §4.3).
 */
public class CBMWDynamicSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private HybridVmPool pool;
    private Map<Integer, WorkflowRecord> activeWorkflows;
    private ProvisioningModule provisioner;
    private Set<Integer> waitingForPlannedReservedVm = Collections.emptySet();

    public CBMWDynamicSchedulingAlgorithm() {}

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner) {
        this(pool, activeWorkflows, provisioner, Collections.emptySet());
    }

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner,
                                           Set<Integer> waitingForPlannedReservedVm) {
        this.pool            = pool;
        this.activeWorkflows = activeWorkflows;
        this.provisioner     = provisioner;
        this.waitingForPlannedReservedVm = waitingForPlannedReservedVm;
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
    public void run() throws Exception {
        if (pool == null) return;

        double now = CloudSim.clock();
        Map<Integer, Integer> pendingCores = new HashMap<>();
        Map<Integer, Integer> pendingRamMb = new HashMap<>();

        // Paper §4.3: sorted by sstji ascending.
        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator.comparingDouble(cl -> getScheduledStartForJob((Job) cl)));

        List<Cloudlet> toSchedule = new ArrayList<>();
        List<Cloudlet> futureJobs = new ArrayList<>();

        if (!readyJobs.isEmpty()) {
            CBMWLogger.log("SCHED-TICK",
                    String.format("readyJobs=%d now=%.1f", readyJobs.size(), now));
        }

        // ---- First loop (Algorithm 3, lines 3–15) ----------------------------
        // Dispatch every task whose sstji has been reached.
        for (Cloudlet cl : readyJobs) {
            Job job    = (Job) cl;
            int wfId   = getWorkflowId(job);
            int taskId = getPrimaryTaskId(job);
            WorkflowRecord wfr = (activeWorkflows != null) ? activeWorkflows.get(wfId) : null;
            double sst = (wfr != null) ? wfr.getScheduledStart(taskId) : 0.0;

            if (sst > now) {
                futureJobs.add(cl);  // handled in second loop
                continue;
            }

            if (wfr == null) {
                CBMWLogger.log("DISPATCH-SKIP",
                        String.format("wf=%d task=%d missing workflow record",
                                wfId, taskId));
                continue;
            }

            int plannedVm = wfr.getAssignedVm(taskId);
            double planningDuration = wfr.getEstimatedExecTime(taskId);
            if (!Double.isFinite(planningDuration) || planningDuration <= 0.0) {
                CBMWLogger.log("DISPATCH-SKIP",
                        String.format("wf=%d task=%d invalid planning duration=%.4f",
                                wfId, taskId, planningDuration));
                continue;
            }

            // Algorithm 3 commits to o0 once the provisioner is invoked.
            CondorVM committedOnDemand = provisioner.getProvisionedVm(taskId);
            if (committedOnDemand != null) {
                assign(job, committedOnDemand);
                toSchedule.add(job);
                CBMWLogger.log("DISPATCH",
                        String.format("wf=%d task=%d -> committed on-demand vm=%d",
                                wfId, taskId, committedOnDemand.getId()));
                continue;
            }

            if (plannedVm == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                // arij = o0: Provisioner(tji, o0) always succeeds — provision on-demand directly.
                CondorVM vm = provisioner.getOrProvision(job);
                assign(job, vm);
                toSchedule.add(job);
                CBMWLogger.log("DISPATCH",
                        String.format("wf=%d task=%d sst=%.1f -> on-demand vm=%d",
                                wfId, taskId, sst, vm.getId()));

            } else {
                // arij = reserved VM. Try Provisioner(tji, arij).
                CondorVM planned = pool.getVmById(plannedVm);
                if (planned != null && hasRuntimeCapacity(
                        plannedVm, taskId, pendingCores, pendingRamMb)) {
                    // Provisioner returns true — dispatch to planned reserved VM.
                    if (waitingForPlannedReservedVm.contains(taskId)) {
                        waitingForPlannedReservedVm.remove(taskId);
                    }
                    pool.rebookSlot(taskId, plannedVm,
                            now, now + planningDuration);
                    assign(job, planned);
                    toSchedule.add(job);
                    reservePending(plannedVm, taskId,
                            pendingCores, pendingRamMb);
                    CBMWLogger.log("DISPATCH",
                            String.format("wf=%d task=%d -> vm=%d (planned reserved)",
                                    wfId, taskId, plannedVm));
                } else {
                    // Provisioner returns false — CheckReserved(tji, CT): find another idle reserved VM.
                    if (waitingForPlannedReservedVm.contains(taskId)) {
                        CBMWLogger.log("DISPATCH-WAIT-PLANNED",
                                String.format("wf=%d task=%d planned=vm%d BUSY waiting-for-planned-capacity",
                                        wfId, taskId, plannedVm));
                        continue;
                    }
                    CondorVM other = findReserved(
                            now, now + planningDuration, taskId,
                            pendingCores, pendingRamMb);
                    if (other != null) {
                        pool.rebookSlot(taskId, other.getId(),
                                now, now + planningDuration);
                        assign(job, other);
                        toSchedule.add(job);
                        reservePending(other.getId(), taskId,
                                pendingCores, pendingRamMb);
                        CBMWLogger.log("DISPATCH",
                                String.format("wf=%d task=%d planned=vm%d BUSY -> reserved vm=%d (CheckReserved)",
                                        wfId, taskId, plannedVm, other.getId()));
                    } else {
                        // The broker's current-cycle replacement policy will
                        // consider only reserved tasks that are still running
                        // before their SST, selecting the eligible task with
                        // the greatest safe post-preemption slack. If none
                        // exists, the broker keeps this task queued for its
                        // planned reserved VM.
                        CBMWLogger.log("DISPATCH-STUCK",
                                String.format("wf=%d task=%d planned=vm%d BUSY awaiting-prerun-replacement-or-wait",
                                        wfId, taskId, plannedVm));
                    }
                }
            }
        }

        // ---- Second loop (Algorithm 3, lines 16–27) --------------------------
        // Advance future tasks early onto idle reserved VMs.
        // Break on the first task CheckReserved cannot serve.
        for (Cloudlet cl : futureJobs) {
            Job job    = (Job) cl;
            int wfId   = getWorkflowId(job);
            int taskId = getPrimaryTaskId(job);
            WorkflowRecord wfr = (activeWorkflows != null)
                    ? activeWorkflows.get(wfId) : null;
            double planningDuration = wfr != null
                    ? wfr.getEstimatedExecTime(taskId) : Double.NaN;
            double sst = getScheduledStartForJob(job);

            if (!Double.isFinite(planningDuration) || planningDuration <= 0.0) {
                CBMWLogger.log("DISPATCH-SKIP",
                        String.format("wf=%d task=%d missing planning duration",
                                wfId, taskId));
                continue;
            }

            // Do not advance tasks whose on-demand container is already ordered.
            if (provisioner.getProvisionedVm(taskId) != null) {
                continue;
            }

            CondorVM res = findReserved(
                    now, now + planningDuration, taskId,
                    pendingCores, pendingRamMb);
            if (res != null) {
                pool.rebookSlot(taskId, res.getId(),
                        now, now + planningDuration);
                assign(job, res);
                toSchedule.add(job);
                reservePending(res.getId(), taskId,
                        pendingCores, pendingRamMb);
                CBMWLogger.log("DISPATCH-ADVANCE",
                        String.format("wf=%d task=%d sst=%.1f now=%.1f -> advanced to vm=%d",
                                wfId, taskId, sst, now, res.getId()));
            } else {
                CBMWLogger.log("DISPATCH-STUCK",
                        String.format("wf=%d task=%d sst=%.1f now=%.1f no-reserved-available break",
                                wfId, taskId, sst, now));
                break;  // stop — subsequent tasks (later sst) also cannot advance
            }
        }

        getScheduledList().addAll(toSchedule);
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
