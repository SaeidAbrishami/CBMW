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
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * StaticGreedy baseline.
 *
 * Planning: at workflow arrival, assigns tasks to reserved VMs in round-robin
 * order. No deadline-awareness, no slot booking — just rotate through the
 * reserved pool task by task.
 *
 * Dispatch: sends each ready task to its pre-assigned reserved VM if idle,
 * otherwise falls back to any other idle reserved VM, then on-demand.
 *
 * Accepts/rejects workflows using the same negotiation check as CBMW so
 * the comparison is fair.
 */
public class StaticGreedyBroker extends AbstractWorkflowBroker {

    public StaticGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    // -----------------------------------------------------------------------
    // Planning — round-robin reserved VM assignment
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        List<CondorVM> reserved = vmPool.getReservedVms();
        int i = 0;
        for (Task task : tasks) {
            CondorVM vm = reserved.get(i % reserved.size());
            task.setVmId(vm.getId());
            task.setWorkflowId(wfr.getWorkflowId());
            wfr.setAssignedVm(task.getCloudletId(), vm.getId());
            i++;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Dispatch — assigned VM first, then any idle reserved, then on-demand
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        List<Cloudlet> toSchedule = new ArrayList<>();

        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));

            CondorVM vm = resolveVm(job, wfr);
            if (vm != null && vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                cl.setVmId(vm.getId());
                toSchedule.add(cl);
            }
        }

        dispatchScheduledJobs(toSchedule);
    }

    private CondorVM resolveVm(Job job, WorkflowRecord wfr) {
        // 1. Statically assigned reserved VM
        if (wfr != null) {
            int plannedId = wfr.getAssignedVm(primaryTaskId(job));
            if (plannedId != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                CondorVM planned = vmPool.getVmById(plannedId);
                if (planned != null && planned.getState() == WorkflowSimTags.VM_STATUS_IDLE)
                    return planned;
            }
        }
        // 2. Any other idle reserved VM
        CondorVM any = vmPool.getAnyIdleReservedVm();
        if (any != null) return any;
        // 3. Idle on-demand VM already running, or provision a new one
        CondorVM idleOD = vmPool.getAnyIdleOnDemandVm();
        return idleOD != null ? idleOD : provisioner.getOrProvision(job);
    }
}
