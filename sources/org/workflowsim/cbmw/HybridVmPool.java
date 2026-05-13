package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Manages the pool of reserved and on-demand CondorVMs.
 * Reserved VMs have ids 0..(NUM_RESERVED-1), on-demand start at NUM_RESERVED.
 */
public class HybridVmPool {

    public static final int    NUM_RESERVED        = 50;
    public static final double RESERVED_MIPS        = 1000.0;
    public static final double RESERVED_HOURLY_COST = 3.26;    // hpc7a.96xlarge $/hr
    public static final double ON_DEMAND_PER_SEC    = 0.000905; // Fargate $/sec

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private int nextOnDemandId = NUM_RESERVED;

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, 1,
                    4096, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
        }
    }

    public CondorVM getVmById(int id) {
        if (id < NUM_RESERVED) return reservedVms.get(id);
        for (CondorVM vm : onDemandVms) {
            if (vm.getId() == id) return vm;
        }
        return null;
    }

    public CondorVM getAnyIdleReservedVm() {
        for (CondorVM vm : reservedVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    public CondorVM provisionOnDemandVm(int userId) {
        int id = nextOnDemandId++;
        CondorVM vm = new CondorVM(id, userId, RESERVED_MIPS, 1,
                4096, 10000, 100000, "Xen",
                ON_DEMAND_PER_SEC, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        onDemandVms.add(vm);
        return vm;
    }

    public void terminateOnDemandVm(int vmId) {
        onDemandVms.removeIf(vm -> vm.getId() == vmId);
    }

    public boolean isReserved(int vmId) { return vmId < NUM_RESERVED; }

    public List<CondorVM> getReservedVms() { return reservedVms; }
    public List<CondorVM> getOnDemandVms()  { return onDemandVms; }

    public List<CondorVM> getAllVms() {
        List<CondorVM> all = new ArrayList<>(reservedVms);
        all.addAll(onDemandVms);
        return all;
    }
}
