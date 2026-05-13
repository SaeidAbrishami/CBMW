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

        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int wfId = getWorkflowId(job);
            WorkflowRecord wfr = (activeWorkflows != null) ? activeWorkflows.get(wfId) : null;

            if (wfr == null) {
                CondorVM vm = pool.getAnyIdleReservedVm();
                if (vm != null) { assign(job, vm); toSchedule.add(job); }
                continue;
            }

            int taskId    = getPrimaryTaskId(job);
            int plannedVm = wfr.getAssignedVm(taskId);

            if (plannedVm == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                CondorVM vm = provisioner.getOrProvision(job);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    assign(job, vm);
                    toSchedule.add(job);
                }
            } else {
                CondorVM planned = pool.getVmById(plannedVm);
                if (planned != null && planned.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    assign(job, planned);
                    toSchedule.add(job);
                } else {
                    CondorVM earlier = pool.getAnyIdleReservedVm();
                    if (earlier != null && canAdvance(job, wfr, now)) {
                        assign(job, earlier);
                        toSchedule.add(job);
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
