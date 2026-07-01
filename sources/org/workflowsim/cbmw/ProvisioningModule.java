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

    public ProvisioningModule(HybridVmPool pool, int userId) {
        this.pool   = pool;
        this.userId = userId;
    }

    /**
     * Returns a dedicated on-demand VM/container for this job.
     * New VMs must be registered with the datacenter before use (caller's
     * responsibility via dispatchScheduledJobs).
     */
    public CondorVM getOrProvision(Job job) {
        int jobId = job.getCloudletId();
        if (jobToVm.containsKey(jobId)) {
            return jobToVm.get(jobId);
        }
        CondorVM vm = pool.provisionOnDemandVm(userId);
        vm.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_BUSY);
        jobToVm.put(jobId, vm);
        return vm;
    }

    /**
     * Called when a job completes. Paper on-demand containers are dedicated,
     * so the associated container is always returned for immediate release.
     */
    public CondorVM jobCompleted(int jobId) {
        CondorVM vm = jobToVm.remove(jobId);
        if (vm != null) {
            vm.setState(org.workflowsim.WorkflowSimTags.VM_STATUS_IDLE);
        }
        return vm;
    }

    public boolean isOnDemandVm(int vmId) {
        return !pool.isReserved(vmId);
    }
}
