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
     * Returns the on-demand VM for this job, provisioning one if needed.
     * The caller must register the new VM with the datacenter before use.
     */
    public CondorVM getOrProvision(Job job) {
        int jobId = job.getCloudletId();
        if (jobToVm.containsKey(jobId)) {
            return jobToVm.get(jobId);
        }
        CondorVM vm = pool.provisionOnDemandVm(userId);
        jobToVm.put(jobId, vm);
        vmJobCount.merge(vm.getId(), 1, Integer::sum);
        return vm;
    }

    /** Returns true if the VM was newly provisioned (not previously known). */
    public boolean isNewVm(int vmId) {
        return vmJobCount.containsKey(vmId) && vmJobCount.get(vmId) == 1;
    }

    /** Called when a job on an on-demand VM completes. */
    public void jobCompleted(int jobId) {
        CondorVM vm = jobToVm.remove(jobId);
        if (vm != null) {
            int count = vmJobCount.merge(vm.getId(), -1, Integer::sum);
            if (count <= 0) {
                vmJobCount.remove(vm.getId());
                pool.terminateOnDemandVm(vm.getId());
            }
        }
    }

    public boolean isOnDemandVm(int vmId) {
        return !pool.isReserved(vmId);
    }
}
