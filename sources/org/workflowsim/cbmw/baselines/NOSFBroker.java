package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.PaperRuntimeModel;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Paper-informed NOSF reconstruction for the experiment's on-demand container
 * model. It implements preprocessing, EST-priority allocation, task
 * sub-deadlines, uncertainty-aware durations, and completion feedback.
 */
public class NOSFBroker extends AbstractWorkflowBroker {

    private final NOSFWorkflowPlanner workflowPlanner = new NOSFWorkflowPlanner();
    private final Set<Integer> allocatedJobs = new HashSet<>();

    public NOSFBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    /** NOSF schedules every submitted workflow instead of using CBMW admission. */
    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        wfr.setCriticalPathLength(negotiation.computeCriticalPath(wfr));
        wfr.setAccepted(true);
        return true;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        workflowPlanner.preprocess(wfr, tasks);
        CBMWLogger.logf("NOSF-PREPROCESS",
                "wf=%d tasks=%d deadline=%.2f alpha=%.3f",
                wfr.getWorkflowId(), tasks.size(), wfr.getDeadline(),
                PaperRuntimeModel.QUANTILE);
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());

        List<Cloudlet> readyJobs = new ArrayList<>(
                (List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator
                .comparingDouble((Cloudlet cl) -> getEst((Job) cl))
                .thenComparingDouble(cl -> getSubDeadline((Job) cl))
                .thenComparingInt(Cloudlet::getCloudletId));

        List<Cloudlet> toSchedule = new ArrayList<>();
        double now = CloudSim.clock();
        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int wfId = workflowIdForJob(job);
            int taskId = primaryTaskId(job);
            WorkflowRecord wfr = activeWorkflows.get(wfId);
            if (wfr == null) continue;

            CondorVM vm = provisioner.getOrProvision(job);
            cl.setVmId(vm.getId());
            toSchedule.add(cl);

            if (allocatedJobs.add(job.getCloudletId())) {
                double duration = wfr.getEstimatedExecTime(taskId);
                double predictedStart = now
                        + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
                double predictedFinish = predictedStart + duration;
                double subDeadline = wfr.getLFT(taskId);
                double incrementalCost = Math.max(
                        HybridVmPool.ON_DEMAND_MIN_BILLING_SECONDS, duration)
                        * vmPool.getOnDemandPricePerSecond(taskId);
                CBMWLogger.logf("NOSF-ALLOCATE",
                        "wf=%d task=%d est=%.2f subDeadline=%.2f"
                                + " predictedFinish=%.2f feasible=%s"
                                + " incrementalCost=$%.6f vm=%d",
                        wfId, taskId, wfr.getEST(taskId), subDeadline,
                        predictedFinish,
                        predictedFinish <= subDeadline ? "yes" : "no",
                        incrementalCost, vm.getId());
            }
        }

        dispatchScheduledJobs(toSchedule);
    }

    @Override
    protected void onWorkflowTaskComplete(WorkflowRecord wfr, int taskId,
                                          double finishTime) {
        double previousEft = wfr.getEFT(taskId);
        workflowPlanner.feedback(wfr, taskId, finishTime);
        CBMWLogger.log("NOSF-FEEDBACK", String.format(Locale.US,
                "wf=%d task=%d predictedFinish=%.2f actualFinish=%.2f"
                        + " deviation=%.2f",
                wfr.getWorkflowId(), taskId, previousEft, finishTime,
                finishTime - previousEft));
    }

    private double getEst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getEST(primaryTaskId(job)) : 0.0;
    }

    private double getSubDeadline(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getLFT(primaryTaskId(job))
                : Double.MAX_VALUE;
    }
}
