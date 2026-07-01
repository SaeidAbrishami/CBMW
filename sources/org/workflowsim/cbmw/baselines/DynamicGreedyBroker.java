package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Dynamic Greedy baseline (document §1.2).
 *
 * Planning: at workflow arrival, compute LST and SST (= LST - OPD) for every
 * task from the remaining critical path. No VM pre-assignment.
 *
 * Runtime — event-based, three cases on every CLOUDLET_UPDATE:
 *   (a/b) Task ready or reserved VM freed → dispatch immediately to any idle
 *         reserved VM (preferred, already paid for).
 *   (c)   Task reaches its SST with no idle reserved VM available → provision
 *         an on-demand container for it.
 *
 * Tasks in the delayed-ready queue are kept sorted by SST ascending so the
 * most time-critical tasks are served first.
 */
public class DynamicGreedyBroker extends AbstractWorkflowBroker {

    public DynamicGreedyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    // -----------------------------------------------------------------------
    // Planning — compute LST and SST = LST - OPD for every task
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
        double deadline = wfr.getDeadline();
        double arrival  = wfr.getArrivalTime();

        for (Task task : tasks) {
            task.setWorkflowId(wfr.getWorkflowId());
            double remCP = remainingCPs.getOrDefault(task.getCloudletId(), 0.0);
            double lst   = deadline - remCP;
            double sst   = Math.max(arrival,
                    lst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            wfr.setLST(task.getCloudletId(), lst);
            wfr.setScheduledStart(task.getCloudletId(), sst);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Runtime — event-driven dispatch (events a/b/c from the document)
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        double now = CloudSim.clock();
        recordReadyQueue((List<Cloudlet>) getCloudletList());

        // Delayed ready queue: all pending tasks sorted by SST ascending.
        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator.comparingDouble(cl -> getSst((Job) cl)));

        List<Cloudlet> toSchedule = new ArrayList<>();

        for (Cloudlet cl : readyJobs) {
            Job job    = (Job) cl;
            double sst = getSst(job);

            // Events (a) and (b): idle reserved VM available → dispatch immediately.
            CondorVM reserved = vmPool.getAnyIdleReservedVm(
                    primaryTaskId(job));
            if (reserved != null) {
                cl.setVmId(reserved.getId());
                toSchedule.add(cl);
                CBMWLogger.log("DG-DISPATCH",
                        String.format("wf=%d task=%d sst=%.1f -> reserved vm=%d",
                                workflowIdForJob(job), primaryTaskId(job),
                                sst, reserved.getId()));

            } else if (now >= sst) {
                // Event (c): SST reached, no reserved VM — provision on-demand.
                CondorVM vm = provisioner.getOrProvision(job);
                cl.setVmId(vm.getId());
                toSchedule.add(cl);
                CBMWLogger.log("DG-DISPATCH",
                        String.format("wf=%d task=%d sst=%.1f now=%.1f -> on-demand vm=%d",
                                workflowIdForJob(job), primaryTaskId(job),
                                sst, now, vm.getId()));
            }
            // else: stays in delayed-ready queue until next CLOUDLET_UPDATE.
        }

        dispatchScheduledJobs(toSchedule);
    }

    private double getSst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return (wfr != null) ? wfr.getScheduledStart(primaryTaskId(job)) : 0.0;
    }
}
