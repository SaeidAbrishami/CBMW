package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;
import org.workflowsim.utils.Parameters;

/**
 * CEWB baseline with an explicit logical spot market. Tasks use the cheapest
 * deadline-feasible spot class and fall back to on-demand at their safe start
 * time or after repeated spot interruptions.
 */
public class CEWBBroker extends AbstractWorkflowBroker {

    private static final int MAX_SPOT_ATTEMPTS = Integer.getInteger(
            "cbmw.cewb.spot.max.attempts", 3);

    private static final class SpotAttempt {
        private final Job job;
        private final CEWBSpotMarket.Offer offer;

        SpotAttempt(Job job, CEWBSpotMarket.Offer offer) {
            this.job = job;
            this.offer = offer;
        }
    }

    private final CEWBSpotMarket spotMarket = new CEWBSpotMarket(
            Long.getLong("cbmw.cewb.seed", 42L));
    private final Map<Integer, SpotAttempt> activeSpotAttempts = new HashMap<>();
    private final Map<Integer, Integer> spotAttemptCounts = new HashMap<>();
    private final Set<Integer> forceOnDemand = new HashSet<>();
    private final Set<Integer> onDemandLogged = new HashSet<>();

    public CEWBBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.CEWB_SPOT_TASK_COMPLETE) {
            completeSpotAttempt((SpotAttempt) ev.getData());
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CEWB_SPOT_TASK_INTERRUPTED) {
            interruptSpotAttempt((SpotAttempt) ev.getData());
            return;
        }
        super.processEvent(ev);
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
        double deadline = wfr.getDeadline();
        double arrival = wfr.getArrivalTime();

        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            task.setWorkflowId(wfr.getWorkflowId());
            double duration = wfr.getEstimatedExecTime(taskId);
            double remCP = remainingCPs.getOrDefault(taskId, duration);
            double lst = deadline - remCP;
            double lft = lst + duration;
            double sst = Math.max(arrival,
                    lst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            wfr.setLST(taskId, lst);
            wfr.setLFT(taskId, lft);
            wfr.setScheduledStart(taskId, sst);
            wfr.setAssignedVm(taskId, -1);
            task.setVmId(-1);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());
        List<Cloudlet> readyJobs = new ArrayList<>(
                (List<Cloudlet>) getCloudletList());
        readyJobs.sort(Comparator
                .comparingDouble((Cloudlet cl) -> getSst((Job) cl))
                .thenComparingDouble(cl -> getLft((Job) cl))
                .thenComparingInt(Cloudlet::getCloudletId));

        List<Cloudlet> onDemandJobs = new ArrayList<>();
        double now = CloudSim.clock();
        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int jobId = job.getCloudletId();
            int taskId = primaryTaskId(job);
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
            if (wfr == null) continue;

            if (forceOnDemand.contains(jobId) || now >= getSst(job)) {
                CondorVM vm = provisioner.getOrProvision(job);
                cl.setVmId(vm.getId());
                onDemandJobs.add(cl);
                if (onDemandLogged.add(jobId)) {
                    CBMWLogger.logf("CEWB-ON-DEMAND",
                            "wf=%d task=%d attempts=%d sst=%.2f now=%.2f vm=%d",
                            wfr.getWorkflowId(), taskId,
                            spotAttemptCounts.getOrDefault(jobId, 0),
                            getSst(job), now, vm.getId());
                }
                continue;
            }

            CEWBSpotMarket.Offer offer = spotMarket.acquire(
                    wfr.getTaskCores(taskId), wfr.getTaskRamMb(taskId),
                    wfr.getEstimatedExecTime(taskId), cl.getCloudletLength(),
                    now, getLft(job), vmPool.getOnDemandPricePerSecond(taskId));
            if (offer != null) dispatchSpot(job, offer);
        }
        dispatchScheduledJobs(onDemandJobs);
    }

    private void dispatchSpot(Job job, CEWBSpotMarket.Offer offer) {
        int taskId = primaryTaskId(job);
        int wfId = workflowIdForJob(job);
        job.setVmId(offer.getInstanceId());
        try {
            job.setResourceParameter(getId(), offer.getPricePerSecond());
            job.setSubmissionTime(CloudSim.clock());
            job.setExecStartTime(CloudSim.clock() + offer.getStartupSeconds());
            job.setCloudletStatus(Cloudlet.INEXEC);
        } catch (Exception e) {
            spotMarket.release(offer);
            throw new IllegalStateException("Could not start CEWB spot task "
                    + job.getCloudletId(), e);
        }

        getCloudletList().remove(job);
        getCloudletSubmittedList().add(job);
        cloudletsSubmitted++;
        accounting.markTaskProvisioningOrdered(job, CloudSim.clock(),
                CloudSim.clock() + offer.getStartupSeconds());
        accounting.markTaskSubmitted(job, "Spot");

        SpotAttempt attempt = new SpotAttempt(job, offer);
        activeSpotAttempts.put(job.getCloudletId(), attempt);
        spotAttemptCounts.merge(job.getCloudletId(), 1, Integer::sum);
        int tag = offer.willBeInterrupted()
                ? WorkflowSimTags.CEWB_SPOT_TASK_INTERRUPTED
                : WorkflowSimTags.CEWB_SPOT_TASK_COMPLETE;
        schedule(getId(), offer.getEventDelaySeconds(), tag, attempt);

        CBMWLogger.logf("CEWB-SPOT-START",
                "wf=%d task=%d instance=%d class=%s price=$%.8f/s"
                        + " successProb=%.4f predictedEvent=%s",
                wfId, taskId, offer.getInstanceId(), offer.getTypeName(),
                offer.getPricePerSecond(), offer.getSuccessProbability(),
                offer.willBeInterrupted() ? "interrupt" : "complete");
    }

    private void completeSpotAttempt(SpotAttempt attempt) {
        Job job = attempt.job;
        SpotAttempt active = activeSpotAttempts.get(job.getCloudletId());
        if (active != attempt) return;
        activeSpotAttempts.remove(job.getCloudletId());
        spotMarket.release(attempt.offer);

        double cost = attempt.offer.getAttemptCost();
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        if (wfr != null) wfr.addSpotCost(cost);
        job.setExecParam(attempt.offer.getEventDelaySeconds(),
                attempt.offer.getExecutionSeconds());
        try {
            job.setCloudletStatus(Cloudlet.SUCCESS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not complete CEWB spot task "
                    + job.getCloudletId(), e);
        }

        getCloudletReceivedList().add(job);
        getCloudletSubmittedList().remove(job);
        cloudletsSubmitted--;
        accounting.markTaskFinished(job, "Spot");
        updateWorkflowCompletion(job);

        CBMWLogger.logf("CEWB-SPOT-COMPLETE",
                "wf=%d task=%d instance=%d class=%s cost=$%.6f attempts=%d",
                workflowIdForJob(job), primaryTaskId(job),
                attempt.offer.getInstanceId(), attempt.offer.getTypeName(), cost,
                spotAttemptCounts.getOrDefault(job.getCloudletId(), 1));

        double delay = Parameters.getOverheadParams().getPostDelay() != null
                ? Parameters.getOverheadParams().getPostDelay(job) : 0.0;
        schedule(workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, job);
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    private void interruptSpotAttempt(SpotAttempt attempt) {
        Job job = attempt.job;
        SpotAttempt active = activeSpotAttempts.get(job.getCloudletId());
        if (active != attempt) return;
        activeSpotAttempts.remove(job.getCloudletId());
        spotMarket.release(attempt.offer);

        double cost = attempt.offer.getAttemptCost();
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        if (wfr != null) wfr.addSpotCost(cost);
        getCloudletSubmittedList().remove(job);
        cloudletsSubmitted--;
        accounting.markTaskInterrupted(job);

        int attempts = spotAttemptCounts.getOrDefault(job.getCloudletId(), 1);
        if (attempts >= Math.max(1, MAX_SPOT_ATTEMPTS)) {
            forceOnDemand.add(job.getCloudletId());
        }
        try {
            job.setCloudletStatus(Cloudlet.CREATED);
        } catch (Exception e) {
            throw new IllegalStateException("Could not retry interrupted CEWB task "
                    + job.getCloudletId(), e);
        }
        job.setVmId(-1);
        getCloudletList().add(job);

        CBMWLogger.logf("CEWB-SPOT-INTERRUPT",
                "wf=%d task=%d instance=%d class=%s cost=$%.6f"
                        + " attempts=%d fallback=%s",
                workflowIdForJob(job), primaryTaskId(job),
                attempt.offer.getInstanceId(), attempt.offer.getTypeName(), cost,
                attempts, forceOnDemand.contains(job.getCloudletId()) ? "yes" : "no");
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    private double getSst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getScheduledStart(primaryTaskId(job)) : 0.0;
    }

    private double getLft(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getLFT(primaryTaskId(job)) : Double.MAX_VALUE;
    }
}
