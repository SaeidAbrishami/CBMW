package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * NOSF paper baseline approximation: no reserved planning, on-demand only.
 *
 * It computes a latest safe on-demand order time per task, then dispatches
 * ready tasks to dedicated on-demand containers in FCFS/SST order.
 */
public class NOSFBroker extends AbstractWorkflowBroker {

    private static final int MAX_ON_DEMAND_VMS = Integer.getInteger(
            "cbmw.nosf.max.ondemand.vms",
            Math.max(1, (HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES)
                    / HybridVmPool.ON_DEMAND_CORES));

    public NOSFBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(tasks);
        double deadline = wfr.getDeadline();
        double arrival = wfr.getArrivalTime();

        for (Task task : tasks) {
            task.setWorkflowId(wfr.getWorkflowId());
            double remCP = remainingCPs.getOrDefault(task.getCloudletId(), 0.0);
            double lst = deadline - remCP;
            double sst = Math.max(arrival,
                    lst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            wfr.setLST(task.getCloudletId(), lst);
            wfr.setScheduledStart(task.getCloudletId(), sst);
            wfr.setAssignedVm(task.getCloudletId(), -1);
            task.setVmId(-1);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());

        List<Cloudlet> toSchedule = new ArrayList<>();
        double now = CloudSim.clock();

        for (Cloudlet cl : (List<Cloudlet>) getCloudletList()) {
            Job job = (Job) cl;
            double sst = getSst(job);
            if (now < sst) continue;

            CondorVM vm = provisioner.getOrProvisionShared(job, MAX_ON_DEMAND_VMS);
            if (vm == null) break;
            cl.setVmId(vm.getId());
            toSchedule.add(cl);
            CBMWLogger.log("NOSF-DISPATCH",
                    String.format("wf=%d task=%d sst=%.1f now=%.1f -> on-demand vm=%d",
                            workflowIdForJob(job), primaryTaskId(job), sst, now, vm.getId()));
        }

        dispatchScheduledJobs(toSchedule);
    }

    @Override
    protected boolean terminateOnDemandWhenIdle() {
        return false;
    }

    private double getSst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return (wfr != null) ? wfr.getScheduledStart(primaryTaskId(job)) : 0.0;
    }
}
