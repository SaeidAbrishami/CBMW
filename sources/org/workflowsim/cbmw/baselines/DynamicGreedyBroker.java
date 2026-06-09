package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * DynamicGreedy baseline.
 *
 * Planning: none. Tasks receive their workflow ID but no VM pre-assignment.
 *
 * Dispatch: at runtime, each ready task goes to the first available VM —
 * reserved VMs are preferred (already paid for), then any idle on-demand VM,
 * then a freshly provisioned on-demand VM. No deadline awareness.
 *
 * Accepts/rejects workflows using the same negotiation check as CBMW so
 * the comparison is fair.
 */
public class DynamicGreedyBroker extends AbstractWorkflowBroker {

    public DynamicGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    // -----------------------------------------------------------------------
    // Planning — none, just stamp the workflow ID on tasks
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        for (Task task : tasks) {
            task.setWorkflowId(wfr.getWorkflowId());
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Dispatch — first available VM, reserved before on-demand
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        List<Cloudlet> toSchedule = new ArrayList<>();

        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;

            CondorVM vm = vmPool.getAnyIdleReservedVm();
            if (vm == null) vm = vmPool.getAnyIdleOnDemandVm();
            if (vm == null) vm = provisioner.getOrProvision(job);

            if (vm != null && vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                cl.setVmId(vm.getId());
                toSchedule.add(cl);
            }
        }

        dispatchScheduledJobs(toSchedule);
    }
}
