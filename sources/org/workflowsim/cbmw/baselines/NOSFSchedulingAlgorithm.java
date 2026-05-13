package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * NOSF baseline scheduling algorithm.
 * No Static, First-come: dispatches each ready job to the first idle VM
 * (on-demand or reserved) in FCFS order.
 */
public class NOSFSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    @SuppressWarnings("unchecked")
    public void run() throws Exception {
        List<Cloudlet> toSchedule = new ArrayList<>();
        List<Cloudlet> cloudlets = (List<Cloudlet>) getCloudletList();

        for (Cloudlet cl : cloudlets) {
            CondorVM idleVm = findIdleVm();
            if (idleVm == null) break;
            idleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cl.setVmId(idleVm.getId());
            toSchedule.add(cl);
        }

        getScheduledList().addAll(toSchedule);
    }

    @SuppressWarnings("unchecked")
    private CondorVM findIdleVm() {
        List<Vm> vms = (List<Vm>) getVmList();
        for (Vm vm : vms) {
            CondorVM cvm = (CondorVM) vm;
            if (cvm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return cvm;
        }
        return null;
    }
}
