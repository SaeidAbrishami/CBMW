package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * CEWB baseline: spot-first, on-demand fallback.
 * Spot VMs have cost below SPOT_COST_THRESHOLD ($/sec).
 * Revocation is modelled probabilistically each dispatch round.
 */
public class CEWBSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private static final double SPOT_COST_THRESHOLD = 0.001;
    private static final double REVOCATION_PROB     = 0.05;
    private static final Random rng = new Random(42);

    @Override
    @SuppressWarnings("unchecked")
    public void run() throws Exception {
        simulateSpotRevocations();

        List<Cloudlet> toSchedule = new ArrayList<>();
        List<Cloudlet> cloudlets  = (List<Cloudlet>) getCloudletList();

        for (Cloudlet cl : cloudlets) {
            CondorVM vm = findIdleSpotVm();
            if (vm == null) vm = findIdleOnDemandVm();
            if (vm == null) break;

            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cl.setVmId(vm.getId());
            toSchedule.add(cl);
        }

        getScheduledList().addAll(toSchedule);
    }

    @SuppressWarnings("unchecked")
    private void simulateSpotRevocations() {
        List<Vm> vms = (List<Vm>) getVmList();
        for (Vm vm : vms) {
            CondorVM cvm = (CondorVM) vm;
            if (isSpotVm(cvm) && cvm.getState() == WorkflowSimTags.VM_STATUS_BUSY) {
                if (rng.nextDouble() < REVOCATION_PROB) {
                    cvm.setState(WorkflowSimTags.VM_STATUS_IDLE);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private CondorVM findIdleSpotVm() {
        List<Vm> vms = (List<Vm>) getVmList();
        for (Vm vm : vms) {
            CondorVM cvm = (CondorVM) vm;
            if (isSpotVm(cvm) && cvm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return cvm;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private CondorVM findIdleOnDemandVm() {
        List<Vm> vms = (List<Vm>) getVmList();
        for (Vm vm : vms) {
            CondorVM cvm = (CondorVM) vm;
            if (!isSpotVm(cvm) && cvm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return cvm;
        }
        return null;
    }

    private boolean isSpotVm(CondorVM vm) {
        return vm.getCost() > 0 && vm.getCost() < SPOT_COST_THRESHOLD;
    }
}
