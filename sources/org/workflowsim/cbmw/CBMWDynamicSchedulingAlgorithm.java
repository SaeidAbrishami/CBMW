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
 * Module 3 - Dynamic Scheduling.
 * Dispatches ready jobs to their planned reserved VM or to an on-demand VM.
 * Called periodically from CBMWBroker.
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

        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator.comparingDouble(cl -> getDeadlineForJob((Job) cl)));

        List<Cloudlet> toSchedule = new ArrayList<>();

        if (!readyJobs.isEmpty()) {
            CBMWLogger.log("SCHED-TICK",
                    String.format("readyJobs=%d", readyJobs.size()));
        }

        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int wfId   = getWorkflowId(job);
            int taskId = getPrimaryTaskId(job);
            WorkflowRecord wfr = (activeWorkflows != null) ? activeWorkflows.get(wfId) : null;

            if (wfr == null) {
                CondorVM vm = pool.getAnyIdleReservedVm();
                if (vm != null) {
                    assign(job, vm);
                    toSchedule.add(job);
                    CBMWLogger.log("DISPATCH",
                            String.format("wf=? task=%d -> vm=%d (reserved, no wfr)", taskId, vm.getId()));
                }
                continue;
            }

            int plannedVm = wfr.getAssignedVm(taskId);

            if (plannedVm == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                CondorVM vm = provisioner.getOrProvision(job);
                assign(job, vm);
                toSchedule.add(job);
                CBMWLogger.log("DISPATCH",
                        String.format("wf=%d task=%d planned=ON-DEMAND -> vm=%d (on-demand)",
                                wfId, taskId, vm.getId()));
            } else {
                CondorVM planned = pool.getVmById(plannedVm);
                if (planned != null && planned.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    assign(job, planned);
                    toSchedule.add(job);
                    CBMWLogger.log("DISPATCH",
                            String.format("wf=%d task=%d -> vm=%d (planned reserved, was idle)",
                                    wfId, taskId, plannedVm));
                } else {
                    double execTime = job.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
                    CondorVM earlier = pool.getIdleReservedVmForAdvance(now, now + execTime);
                    if (earlier != null && canAdvance(job, wfr, now)) {
                        assign(job, earlier);
                        toSchedule.add(job);
                        CBMWLogger.log("DISPATCH",
                                String.format("wf=%d task=%d planned=vm%d BUSY -> advanced to vm=%d",
                                        wfId, taskId, plannedVm, earlier.getId()));
                    } else if (earlier == null || now >= wfr.getLST(taskId)) {
                        // No idle reserved VM available at all, or past LST — fall back to on-demand.
                        CondorVM vm = provisioner.getOrProvision(job);
                        assign(job, vm);
                        toSchedule.add(job);
                        CBMWLogger.log("DISPATCH",
                                String.format("wf=%d task=%d planned=vm%d %s -> on-demand vm=%d",
                                        wfId, taskId, plannedVm,
                                        earlier == null ? "no-idle-reserved" : "past-LST",
                                        vm.getId()));
                    } else {
                        // Idle reserved VM exists but advancing would push a child past its LST.
                        // Wait for the planned VM or until LST is reached.
                        CBMWLogger.log("DISPATCH-STUCK",
                                String.format("wf=%d task=%d planned=vm%d BUSY"
                                        + " canAdvance=false lst=%.1f now=%.1f",
                                        wfId, taskId, plannedVm,
                                        wfr.getLST(taskId), now));
                    }
                }
            }
        }

        getScheduledList().addAll(toSchedule);
    }

    private void assign(Job job, CondorVM vm) {
        vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
        job.setVmId(vm.getId());
    }

    private boolean canAdvance(Job job, WorkflowRecord wfr, double now) {
        double execTime = job.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
        double endTime  = now + execTime;
        if (endTime > wfr.getDeadline()) return false;
        // remainingCP[child] = deadline - LST[child]; advancing is safe if
        // endTime + remainingCP[child] <= deadline, i.e. endTime <= LST[child].
        for (Task child : getChildTasks(job)) {
            if (endTime > wfr.getLST(child.getCloudletId())) return false;
        }
        return true;
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
