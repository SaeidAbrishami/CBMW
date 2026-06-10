package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 *      if reserved VM is busy call CheckReserved for another; if none, on-demand.
 *   2. Second loop (sstji > CT): advance future tasks to idle reserved VMs;
 *      break on the first task that CheckReserved cannot serve.
 *
 * ReadyTasks are kept sorted by sstji ascending (paper §4.3).
 */
public class CBMWDynamicSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private HybridVmPool pool;
    private Map<Integer, WorkflowRecord> activeWorkflows;
    private ProvisioningModule provisioner;

    public CBMWDynamicSchedulingAlgorithm() {}

    public CBMWDynamicSchedulingAlgorithm(HybridVmPool pool,
                                           Map<Integer, WorkflowRecord> activeWorkflows,
                                           ProvisioningModule provisioner) {
        this.pool            = pool;
        this.activeWorkflows = activeWorkflows;
        this.provisioner     = provisioner;
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
                // No workflow record — best-effort dispatch to any idle reserved VM.
                CondorVM vm = pool.getAnyIdleReservedVm();
                if (vm != null) {
                    assign(job, vm);
                    toSchedule.add(job);
                    CBMWLogger.log("DISPATCH",
                            String.format("wf=? task=%d -> vm=%d (no wfr)", taskId, vm.getId()));
                }
                continue;
            }

            int plannedVm = wfr.getAssignedVm(taskId);

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
                if (planned != null && planned.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    // Provisioner returns true — dispatch to planned reserved VM.
                    assign(job, planned);
                    toSchedule.add(job);
                    CBMWLogger.log("DISPATCH",
                            String.format("wf=%d task=%d -> vm=%d (planned reserved)",
                                    wfId, taskId, plannedVm));
                } else {
                    // Provisioner returns false — CheckReserved(tji, CT): find another idle reserved VM.
                    double execTime = job.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
                    CondorVM other = pool.getIdleReservedVmForAdvance(now, now + execTime);
                    if (other != null) {
                        assign(job, other);
                        toSchedule.add(job);
                        CBMWLogger.log("DISPATCH",
                                String.format("wf=%d task=%d planned=vm%d BUSY -> reserved vm=%d (CheckReserved)",
                                        wfId, taskId, plannedVm, other.getId()));
                    } else {
                        // CheckReserved = ∅: arij ← o0, fall back to on-demand.
                        CondorVM vm = provisioner.getOrProvision(job);
                        assign(job, vm);
                        toSchedule.add(job);
                        CBMWLogger.log("DISPATCH",
                                String.format("wf=%d task=%d planned=vm%d BUSY no-reserved -> on-demand vm=%d",
                                        wfId, taskId, plannedVm, vm.getId()));
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
            double execTime = job.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
            double sst = getScheduledStartForJob(job);

            CondorVM res = pool.getIdleReservedVmForAdvance(now, now + execTime);
            if (res != null) {
                assign(job, res);
                toSchedule.add(job);
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

    private void assign(Job job, CondorVM vm) {
        vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
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
