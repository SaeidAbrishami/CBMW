package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * CEWB paper baseline approximation: opportunistic low-cost capacity first,
 * on-demand fallback for critical tasks or when no low-cost slot is available.
 *
 * The current simulator has no native spot market, so reserved-capacity slots
 * are used as the low-cost/revocable class for comparative behavior.
 */
public class CEWBBroker extends AbstractWorkflowBroker {

    private static final double REVOCATION_PROB = Double.parseDouble(
            System.getProperty("cbmw.cewb.revocation.prob", "0.05"));
    private final Random rng = new Random(Long.getLong("cbmw.cewb.seed", 42L));

    public CEWBBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
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

        List<Cloudlet> readyJobs = new ArrayList<>((List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator.<Cloudlet>comparingDouble(cl -> getSst((Job) cl))
                .thenComparingInt(cl -> cl.getCloudletId()));

        List<Cloudlet> toSchedule = new ArrayList<>();
        double now = CloudSim.clock();

        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            double sst = getSst(job);
            CondorVM lowCost = findLowCostVm(primaryTaskId(job));

            if (lowCost != null && !isRevoked()) {
                cl.setVmId(lowCost.getId());
                toSchedule.add(cl);
                CBMWLogger.log("CEWB-DISPATCH",
                        String.format("wf=%d task=%d -> low-cost vm=%d",
                                workflowIdForJob(job), primaryTaskId(job), lowCost.getId()));
            } else if (now >= sst) {
                CondorVM vm = provisioner.getOrProvision(job);
                cl.setVmId(vm.getId());
                toSchedule.add(cl);
                CBMWLogger.log("CEWB-DISPATCH",
                        String.format("wf=%d task=%d sst=%.1f now=%.1f -> on-demand vm=%d",
                                workflowIdForJob(job), primaryTaskId(job), sst, now, vm.getId()));
            }
        }

        dispatchScheduledJobs(toSchedule);
    }

    private CondorVM findLowCostVm(int taskId) {
        return vmPool.getAnyIdleReservedVm(taskId);
    }

    private boolean isRevoked() {
        return rng.nextDouble() < REVOCATION_PROB;
    }

    private double getSst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return (wfr != null) ? wfr.getScheduledStart(primaryTaskId(job)) : 0.0;
    }
}
