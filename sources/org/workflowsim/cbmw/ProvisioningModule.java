package org.workflowsim.cbmw;

import java.util.HashMap;
import java.util.Map;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;

/**
 * Module 4 — On-demand VM provisioning.
 * Creates containers on demand and terminates them when their work is done.
 */
public class ProvisioningModule {

    private final HybridVmPool pool;
    private final int userId;

    // jobId -> on-demand VM provisioned for it
    private final Map<Integer, CondorVM> jobToVm = new HashMap<>();

    // vmId -> number of jobs still running on it
    private final Map<Integer, Integer> vmJobCount = new HashMap<>();

    public ProvisioningModule(HybridVmPool pool, int userId) {
        this.pool   = pool;
        this.userId = userId;
    }

    /**
     * Returns the on-demand VM for this job, reusing an idle one if available,
     * provisioning a new one only when none are free.
     * New VMs must be registered with the datacenter before use (caller's
     * responsibility via dispatchScheduledJobs).
     */
    public CondorVM getOrProvision(Job job) {
        int jobId = job.getCloudletId();
        if (jobToVm.containsKey(jobId)) {
            return jobToVm.get(jobId);
        }
        CondorVM vm = pool.getAnyIdleOnDemandVm();
        if (vm == null) {
            vm = pool.provisionOnDemandVm(userId);
        }
        vm.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        jobToVm.put(jobId, vm);
        vmJobCount.merge(vm.getId(), 1, Integer::sum);
        return vm;
    }

    /** Returns true if the VM was newly provisioned (not previously known). */
    public boolean isNewVm(int vmId) {
        return vmJobCount.containsKey(vmId) && vmJobCount.get(vmId) == 1;
    }

    /**
     * Called when a job on an on-demand VM completes. Marks the VM idle for
     * reuse and returns the VM when it has no remaining running jobs.
     */
    public CondorVM jobCompleted(int jobId) {
        CondorVM vm = jobToVm.remove(jobId);
        if (vm != null) {
            int count = vmJobCount.merge(vm.getId(), -1, Integer::sum);
            if (count <= 0) {
                vmJobCount.remove(vm.getId());
                vm.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
                return vm;
            }
        }
        return null;
    }

    public boolean isOnDemandVm(int vmId) {
        return !pool.isReserved(vmId);
    }
}
