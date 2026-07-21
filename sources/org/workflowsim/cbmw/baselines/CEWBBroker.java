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
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.TaskExecutionRecord;
import org.workflowsim.cbmw.WorkflowRecord;
import org.workflowsim.utils.Parameters;

/**
 * Paper-oriented CEWB baseline with PCP sub-deadlines, absolute-slack task
 * classes, reusable physical spot/on-demand pools, periodic capacity
 * adjustment, and checkpoint recovery after spot interruptions. The legacy
 * reconstructed safe-start policy remains selectable through
 * {@link CEWBPolicyMode}.
 */
public class CEWBBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;
    private static final int MAX_SPOT_ATTEMPTS = Integer.getInteger(
            "cbmw.cewb.spot.max.attempts", 3);
    private static final double SNAPSHOT_DELAY_SECONDS = Double.parseDouble(
            System.getProperty("cbmw.cewb.snapshot.delay.sec", "90.0"));
    private static final double ADMISSION_CP_MULTIPLIER = Double.parseDouble(
            System.getProperty("cbmw.cewb.admission.min.cp.multiplier", "1.0"));
    private final CEWBPolicyMode policyMode;
    private final boolean resumeReferenceProgress;
    private final CEWBTaskPolicy taskPolicy;
    private final CEWBPricingPolicy pricingPolicy;

    private static final class SpotAttempt {
        private final Job job;
        private final CEWBSpotMarket.Offer offer;
        private final double startTime;

        SpotAttempt(Job job, CEWBSpotMarket.Offer offer) {
            this.job = job;
            this.offer = offer;
            this.startTime = CloudSim.clock();
        }
    }

    private static final class OnDemandAttempt {
        private final Job job;
        private final CEWBOnDemandPool.Offer offer;
        private final double executionSeconds;

        OnDemandAttempt(Job job, CEWBOnDemandPool.Offer offer,
                        double executionSeconds) {
            this.job = job;
            this.offer = offer;
            this.executionSeconds = executionSeconds;
        }
    }

    private final CEWBSpotMarket spotMarket;
    private final CEWBOnDemandPool onDemandPool;
    private final Map<Integer, SpotAttempt> activeSpotAttempts = new HashMap<>();
    private final Map<Integer, OnDemandAttempt> activeOnDemandAttempts =
            new HashMap<>();
    private final Map<Integer, Integer> spotAttemptCounts = new HashMap<>();
    private final Map<Integer, Integer> maximumSpotClassByJob = new HashMap<>();
    private final Map<Integer, Double> remainingEstimatedRuntimeByJob = new HashMap<>();
    private final Set<Integer> forceOnDemand = new HashSet<>();
    private final Set<Integer> onDemandLogged = new HashSet<>();
    private final Set<Integer> waitingOnDemandCapacity = new HashSet<>();
    private final Set<Integer> scheduledSstWakeups = new HashSet<>();
    private long fallbackDispatches;
    private long predictedFallbackMisses;
    private long sstWakeupsScheduled;
    private long sstWakeupsFired;
    private long partialProgressRetries;
    private long zeroProgressFallbacks;
    private boolean provisioningTickDue;
    private boolean provisioningTickScheduled;
    private boolean onDemandCapacityChanged = true;
    private boolean onDemandPoolInitialized;
    private boolean onDemandCostsSettled;
    private boolean configurationLogged;
    private boolean summaryLogged;

    public CEWBBroker(String name, double tightness) throws Exception {
        this(name, tightness, CEWBPolicyMode.CURRENT);
    }

    public CEWBBroker(String name, double tightness,
                      boolean paperAligned) throws Exception {
        this(name, tightness, paperAligned ? CEWBPolicyMode.CURRENT
                : CEWBPolicyMode.RECONSTRUCTED);
    }

    public CEWBBroker(String name, double tightness,
                      CEWBPolicyMode policyMode) throws Exception {
        super(name, tightness);
        if (policyMode == null) {
            throw new IllegalArgumentException("CEWB policy mode is required");
        }
        this.policyMode = policyMode;
        this.resumeReferenceProgress = policyMode == CEWBPolicyMode.CURRENT
                ? Boolean.parseBoolean(System.getProperty(
                        "cbmw.cewb.resume.progress", "true"))
                : policyMode.usesReferenceRecovery()
                        && Boolean.parseBoolean(System.getProperty(
                                "cbmw.cewb.reference.resume.progress", "true"));
        this.spotMarket = new CEWBSpotMarket(
                Long.getLong("cbmw.cewb.seed", 42L),
                policyMode != CEWBPolicyMode.RECONSTRUCTED);
        this.onDemandPool = new CEWBOnDemandPool();
        this.taskPolicy = !policyMode.usesTaskPolicy() ? null
                : policyMode == CEWBPolicyMode.CURRENT
                        || policyMode.usesReferenceTaskPolicy()
                        ? new CEWBReferenceTaskPolicy()
                        : new CEWBCriticalityPolicy();
        this.pricingPolicy = policyMode.usesPaperPricing()
                ? new CEWBPricingPolicy() : null;
        if (!Double.isFinite(SNAPSHOT_DELAY_SECONDS)
                || SNAPSHOT_DELAY_SECONDS < 0.0) {
            throw new IllegalArgumentException(
                    "cbmw.cewb.snapshot.delay.sec must be finite and non-negative");
        }
        if (!Double.isFinite(ADMISSION_CP_MULTIPLIER)
                || ADMISSION_CP_MULTIPLIER < 1.0) {
            throw new IllegalArgumentException(
                    "cbmw.cewb.admission.min.cp.multiplier must be at least 1.0");
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.CEWB_PROVISIONING_TICK) {
            provisioningTickScheduled = false;
            provisioningTickDue = true;
            onDemandCapacityChanged = true;
            activateReadyOnDemandInstances();
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CEWB_ON_DEMAND_POOL_READY) {
            onDemandCapacityChanged = true;
            activateReadyOnDemandInstances();
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CEWB_ON_DEMAND_TASK_COMPLETE) {
            completeOnDemandAttempt((OnDemandAttempt) ev.getData());
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CEWB_SPOT_RETRY_READY) {
            Job job = (Job) ev.getData();
            getCloudletList().add(job);
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CEWB_SST_REACHED) {
            int taskId = (Integer) ev.getData();
            if (scheduledSstWakeups.remove(taskId)) {
                sstWakeupsFired++;
                sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
            return;
        }
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

    /** Emit final invariants only when WorkflowSim has drained every workflow. */
    @Override
    public void shutdownEntity() {
        emitDiagnostics();
        super.shutdownEntity();
    }

    /** CEWB is a just-in-time event-driven policy, not a polling policy. */
    @Override
    protected boolean usesPeriodicScheduling() {
        return false;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        ensureOnDemandPoolInitialized();
        if (policyMode.usesTaskPolicy()) {
            taskPolicy.preprocess(wfr, tasks);
            for (Task task : tasks) {
                int taskId = task.getCloudletId();
                task.setWorkflowId(wfr.getWorkflowId());
                wfr.setPlannedVmType(taskId, TaskExecutionRecord.SPOT_CANDIDATE);
                task.setVmId(-1);
            }
            return true;
        }
        Map<Integer, Double> remainingCPs = negotiation.computeRemainingCPs(wfr);
        double deadline = wfr.getDeadline();
        double arrival = wfr.getArrivalTime();

        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            task.setWorkflowId(wfr.getWorkflowId());
            double duration = wfr.getEstimatedExecTime(taskId);
            double remCP = remainingCPs.getOrDefault(taskId, duration);
            if (!Double.isFinite(duration) || duration <= 0.0
                    || !Double.isFinite(remCP) || remCP < duration - EPS) {
                throw new IllegalStateException("Invalid CEWB timing input for wf="
                        + wfr.getWorkflowId() + " task=" + taskId
                        + " duration=" + duration + " remainingCP=" + remCP);
            }
            double lst = deadline - remCP;
            double lft = lst + duration;
            double sst = CEWBTimingPolicy.safeStart(arrival, lst,
                    HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            wfr.setLST(taskId, lst);
            wfr.setLFT(taskId, lft);
            wfr.setScheduledStart(taskId, sst);
            // CEWB chooses a concrete spot offer only when the task becomes
            // ready.  This is a resource-class intent, not a fake on-demand
            // VM assignment.
            wfr.setPlannedVmType(taskId, TaskExecutionRecord.SPOT_CANDIDATE);
            task.setVmId(-1);
        }
        return true;
    }

    /** Do not rent the warm pool until at least one workflow passes admission. */
    private void ensureOnDemandPoolInitialized() {
        if (onDemandPoolInitialized) return;
        onDemandPoolInitialized = true;
        for (CEWBOnDemandPool.Instance instance
                : onDemandPool.initialize(CloudSim.clock())) {
            recordOnDemandInstance(instance);
        }
        onDemandCapacityChanged = true;
    }

    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        if (!policyMode.usesPaperPricing()) return super.negotiateWorkflow(wfr);
        double cp = negotiation.computeCriticalPath(wfr);
        boolean feasible = cp * ADMISSION_CP_MULTIPLIER
                <= wfr.getDeadline() - wfr.getArrivalTime() + EPS;
        wfr.setCriticalPathLength(cp);
        wfr.setDeadlineFeasible(feasible);
        wfr.setAccepted(feasible);
        return feasible;
    }

    @Override
    protected void finalizeWorkflowNegotiation(WorkflowRecord wfr) {
        if (policyMode.usesPaperPricing()) pricingPolicy.quoteBeforeExecution(wfr);
    }

    @Override
    protected void onWorkflowTaskComplete(WorkflowRecord wfr, int taskId,
                                          double finishTime) {
        // Shared physical-VM idle cost is known only when the scenario drains.
        // Pricing is settled in emitDiagnostics after that cost is allocated.
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        logConfigurationOnce();
        activateReadyOnDemandInstances();
        ensureProvisioningTicker();
        recordReadyQueue((List<Cloudlet>) getCloudletList());
        List<Cloudlet> readyJobs = new ArrayList<>(
                (List<Cloudlet>) getCloudletList());
        Map<Integer, CEWBTaskDecision> decisions = policyMode.usesTaskPolicy()
                ? classifyReadyJobs(readyJobs, CloudSim.clock()) : new HashMap<>();
        if (policyMode == CEWBPolicyMode.CURRENT) {
            readyJobs.sort(Comparator
                    .comparingDouble((Cloudlet cl) -> decisions
                            .get(cl.getCloudletId()).getSlack())
                    .thenComparing((Cloudlet cl) -> -taskPolicy
                            .getUpwardRank(primaryTaskId((Job) cl)))
                    .thenComparingDouble(cl -> activeWorkflows
                            .get(workflowIdForJob((Job) cl)).getDeadline())
                    .thenComparingInt(Cloudlet::getCloudletId));
        } else if (policyMode.usesReferenceTaskPolicy()) {
            readyJobs.sort(Comparator
                    .comparingDouble((Cloudlet cl) -> {
                        WorkflowRecord workflow = activeWorkflows.get(
                                workflowIdForJob((Job) cl));
                        return workflow != null ? workflow.getArrivalTime() : 0.0;
                    })
                    .thenComparingInt(cl -> workflowIdForJob((Job) cl))
                    .thenComparingInt(Cloudlet::getCloudletId));
        } else {
            readyJobs.sort(Comparator
                    .comparingDouble((Cloudlet cl) -> getSst((Job) cl))
                    .thenComparingDouble(cl -> getLft((Job) cl))
                    .thenComparingInt(Cloudlet::getCloudletId));
        }

        List<Cloudlet> onDemandJobs = new ArrayList<>();
        double now = CloudSim.clock();
        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            int jobId = job.getCloudletId();
            int taskId = primaryTaskId(job);
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
            if (wfr == null) continue;

            CEWBTaskDecision decision = decisions.get(jobId);
            if (decision != null) {
                CBMWLogger.logf("CEWB-CLASSIFY",
                        "mode=%s wf=%d task=%d slack=%.4f criticality=%.4f"
                                + " class=%d reason=%s remainingEstimate=%.4f",
                        policyMode, wfr.getWorkflowId(), taskId,
                        decision.getSlack(), decision.getCriticality(),
                        decision.getResourceClass(), decision.getReason(),
                        estimatedRuntime(job, wfr, taskId));
            }
            boolean criticalOnDemand = policyMode.usesTaskPolicy() && decision != null
                    && decision.getResourceClass() == CEWBCriticalityPolicy.ON_DEMAND;
            if (forceOnDemand.contains(jobId) || criticalOnDemand
                    || (!policyMode.usesTaskPolicy() && now >= getSst(job))) {
                scheduledSstWakeups.remove(jobId);
                onDemandJobs.add(cl);
                logOnDemandIntent(job, wfr, now);
                continue;
            }

            CEWBSpotMarket.Offer offer = spotMarket.acquire(
                    wfr.getTaskCores(taskId), wfr.getTaskRamMb(taskId),
                    estimatedRuntime(job, wfr, taskId), cl.getCloudletLength(),
                    now, getLft(job), vmPool.getOnDemandPricePerSecond(taskId),
                    policyMode.usesTaskPolicy() ? effectiveSpotClass(jobId, decision)
                            : CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT);
            if (offer != null) {
                scheduledSstWakeups.remove(jobId);
                waitingOnDemandCapacity.remove(jobId);
                dispatchSpot(job, offer);
            } else if (policyMode.usesTaskPolicy()) {
                forceOnDemand.add(jobId);
                onDemandJobs.add(cl);
                logOnDemandIntent(job, wfr, now);
            } else {
                scheduleSstWake(job, now);
            }
        }
        dispatchOnDemandJobs(onDemandJobs, now);
        provisioningTickDue = false;
        onDemandCapacityChanged = false;
    }

    private void dispatchOnDemandJobs(List<Cloudlet> jobs, double now) {
        if (provisioningTickDue) {
            int requiredCores = 0;
            int requiredRamMb = 0;
            for (Cloudlet cloudlet : jobs) {
                Job job = (Job) cloudlet;
                WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
                if (workflow == null) continue;
                int taskId = primaryTaskId(job);
                requiredCores += workflow.getTaskCores(taskId);
                requiredRamMb += workflow.getTaskRamMb(taskId);
            }
            for (CEWBOnDemandPool.Instance instance
                    : onDemandPool.provisionFor(requiredCores, requiredRamMb, now)) {
                recordOnDemandInstance(instance);
            }
        }

        boolean queuedDemand = false;
        for (Cloudlet cloudlet : jobs) {
            Job job = (Job) cloudlet;
            int jobId = job.getCloudletId();
            int workflowId = workflowIdForJob(job);
            WorkflowRecord workflow = activeWorkflows.get(workflowId);
            if (workflow == null) continue;
            int taskId = primaryTaskId(job);
            if (waitingOnDemandCapacity.contains(jobId)
                    && !onDemandCapacityChanged && !provisioningTickDue) {
                queuedDemand = true;
                continue;
            }
            CEWBOnDemandPool.Offer offer = onDemandPool.acquire(
                    workflowId, workflow.getTaskCores(taskId),
                    workflow.getTaskRamMb(taskId), now);
            if (offer == null) {
                waitingOnDemandCapacity.add(jobId);
                queuedDemand = true;
                continue;
            }
            waitingOnDemandCapacity.remove(jobId);
            dispatchOnDemand(job, offer);
        }

        if (provisioningTickDue) {
            for (CEWBOnDemandPool.Instance instance
                    : onDemandPool.maintainIdle(now, queuedDemand)) {
                accounting.markOnDemandDestroyed(instance.getId(),
                        instance.getDestroyAt());
                CBMWLogger.logf("CEWB-ON-DEMAND-TERMINATE",
                        "vm=%d destroyAt=%.2f", instance.getId(),
                        instance.getDestroyAt());
            }
        }
    }

    private void dispatchOnDemand(Job job, CEWBOnDemandPool.Offer offer) {
        double now = CloudSim.clock();
        int taskId = primaryTaskId(job);
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        if (workflow == null) {
            onDemandPool.release(offer, now);
            return;
        }
        int taskCores = workflow.getTaskCores(taskId);
        double executionSeconds = HybridVmPool.executionTimeSeconds(
                job.getCloudletLength(), taskCores, HybridVmPool.RESERVED_MIPS);
        double containerDelay = offer.getContainerDelaySeconds();
        job.setVmId(offer.getInstanceId());
        try {
            job.setResourceParameter(getId(), offer.getAllocatedPricePerSecond());
            job.setSubmissionTime(now);
            job.setExecStartTime(now + containerDelay);
            job.setCloudletStatus(Cloudlet.INEXEC);
        } catch (Exception e) {
            onDemandPool.release(offer, now);
            throw new IllegalStateException(
                    "Could not start CEWB on-demand container "
                            + job.getCloudletId(), e);
        }

        getCloudletList().remove(job);
        getCloudletSubmittedList().add(job);
        cloudletsSubmitted++;
        accounting.markTaskProvisioningOrdered(job, now, now + containerDelay);
        accounting.markTaskSubmitted(job, "On-Demand");
        OnDemandAttempt attempt = new OnDemandAttempt(job, offer,
                executionSeconds);
        activeOnDemandAttempts.put(job.getCloudletId(), attempt);
        schedule(getId(), containerDelay + executionSeconds,
                WorkflowSimTags.CEWB_ON_DEMAND_TASK_COMPLETE, attempt);

        CBMWLogger.logf("CEWB-ON-DEMAND-START",
                "wf=%d task=%d vm=%d cores=%d containerDelay=%.2f exec=%.2f",
                workflow.getWorkflowId(), taskId, offer.getInstanceId(),
                offer.getCores(), containerDelay, executionSeconds);
    }

    private void completeOnDemandAttempt(OnDemandAttempt attempt) {
        Job job = attempt.job;
        OnDemandAttempt active = activeOnDemandAttempts.get(job.getCloudletId());
        if (active != attempt) return;
        activeOnDemandAttempts.remove(job.getCloudletId());
        waitingOnDemandCapacity.remove(job.getCloudletId());
        remainingEstimatedRuntimeByJob.remove(job.getCloudletId());
        onDemandPool.release(attempt.offer, CloudSim.clock());
        onDemandCapacityChanged = true;
        job.setExecParam(attempt.offer.getContainerDelaySeconds()
                        + attempt.executionSeconds,
                attempt.executionSeconds);
        try {
            job.setCloudletStatus(Cloudlet.SUCCESS);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not complete CEWB on-demand container "
                            + job.getCloudletId(), e);
        }

        getCloudletReceivedList().add(job);
        getCloudletSubmittedList().remove(job);
        cloudletsSubmitted--;
        accounting.markTaskFinished(job, "On-Demand");
        updateWorkflowCompletion(job);

        double delay = Parameters.getOverheadParams().getPostDelay() != null
                ? Parameters.getOverheadParams().getPostDelay(job) : 0.0;
        schedule(workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, job);
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    private void logOnDemandIntent(Job job, WorkflowRecord workflow,
                                   double now) {
        int jobId = job.getCloudletId();
        if (!onDemandLogged.add(jobId)) return;
        int taskId = primaryTaskId(job);
        fallbackDispatches++;
        double predictedDelay = onDemandPool.predictedContainerDelay(
                workflow.getTaskCores(taskId), workflow.getTaskRamMb(taskId), now);
        boolean predictedMiss = CEWBTimingPolicy.predictedFallbackMiss(
                now, predictedDelay, estimatedRuntime(job, workflow, taskId),
                getLft(job));
        if (predictedMiss) predictedFallbackMisses++;
        CBMWLogger.logf("CEWB-ON-DEMAND",
                "wf=%d task=%d attempts=%d now=%.2f predictedDelay=%.2f"
                        + " forced=%s predictedMiss=%s",
                workflow.getWorkflowId(), taskId,
                spotAttemptCounts.getOrDefault(jobId, 0), now, predictedDelay,
                forceOnDemand.contains(jobId), predictedMiss);
    }

    private void ensureProvisioningTicker() {
        if (provisioningTickScheduled || activeWorkflows.isEmpty()) return;
        double interval = CEWBOnDemandPool.PROVISIONING_INTERVAL_SECONDS;
        double now = CloudSim.clock();
        double next = Math.ceil((now + EPS) / interval) * interval;
        if (next <= now + EPS) next = now + interval;
        provisioningTickScheduled = true;
        schedule(getId(), next - now, WorkflowSimTags.CEWB_PROVISIONING_TICK);
    }

    private void activateReadyOnDemandInstances() {
        List<CEWBOnDemandPool.Instance> activated =
                onDemandPool.activateReady(CloudSim.clock());
        if (!activated.isEmpty()) onDemandCapacityChanged = true;
        for (CEWBOnDemandPool.Instance instance : activated) {
            accounting.markOnDemandLaunched(instance.getId(), CloudSim.clock());
            CBMWLogger.logf("CEWB-ON-DEMAND-READY",
                    "vm=%d readyAt=%.2f", instance.getId(), CloudSim.clock());
        }
    }

    private void recordOnDemandInstance(CEWBOnDemandPool.Instance instance) {
        accounting.markOnDemandOrdered(instance.getId(), instance.getOrderedAt(),
                instance.getReadyAt());
        if (instance.isLaunched()) {
            accounting.markOnDemandLaunched(instance.getId(), instance.getReadyAt());
        } else {
            schedule(getId(), Math.max(0.0,
                            instance.getReadyAt() - CloudSim.clock()),
                    WorkflowSimTags.CEWB_ON_DEMAND_POOL_READY, instance.getId());
        }
        CBMWLogger.logf("CEWB-ON-DEMAND-PROVISION",
                "vm=%d orderedAt=%.2f readyAt=%.2f launched=%s",
                instance.getId(), instance.getOrderedAt(), instance.getReadyAt(),
                instance.isLaunched());
    }

    private Map<Integer, CEWBTaskDecision> classifyReadyJobs(
            List<Cloudlet> jobs, double now) {
        Map<Integer, List<Task>> byWorkflow = new HashMap<>();
        for (Cloudlet cloudlet : jobs) {
            Job job = (Job) cloudlet;
            if (job.getTaskList().isEmpty()) continue;
            byWorkflow.computeIfAbsent(workflowIdForJob(job), unused ->
                    new ArrayList<>()).add(job.getTaskList().get(0));
        }
        Map<Integer, CEWBTaskDecision> result = new HashMap<>();
        for (Map.Entry<Integer, List<Task>> entry : byWorkflow.entrySet()) {
            WorkflowRecord workflow = activeWorkflows.get(entry.getKey());
            if (workflow != null) {
                result.putAll(taskPolicy.classify(
                        entry.getValue(), workflow, now));
            }
        }
        if (resumeReferenceProgress
                && taskPolicy instanceof CEWBReferenceTaskPolicy) {
            CEWBReferenceTaskPolicy reference = (CEWBReferenceTaskPolicy) taskPolicy;
            for (Cloudlet cloudlet : jobs) {
                Double remaining = remainingEstimatedRuntimeByJob.get(
                        cloudlet.getCloudletId());
                if (remaining == null) continue;
                Job job = (Job) cloudlet;
                WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
                if (workflow != null) {
                    result.put(cloudlet.getCloudletId(), reference.classifyDuration(
                            getLft(job), now, remaining));
                }
            }
        }
        return result;
    }

    private int effectiveSpotClass(int jobId,
            CEWBTaskDecision decision) {
        int requested = decision != null ? decision.getResourceClass()
                : CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT;
        requested = Math.max(CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT,
                requested);
        return Math.min(requested, maximumSpotClassByJob.getOrDefault(jobId,
                CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT));
    }

    private void scheduleSstWake(Job job, double now) {
        int taskId = job.getCloudletId();
        double sst = getSst(job);
        if (sst <= now + EPS || !scheduledSstWakeups.add(taskId)) return;
        schedule(getId(), CEWBTimingPolicy.exactWakeDelay(now, sst),
                WorkflowSimTags.CEWB_SST_REACHED, taskId);
        sstWakeupsScheduled++;
        CBMWLogger.logf("CEWB-WAIT",
                "wf=%d task=%d no feasible spot offer; exact wake at sst=%.2f",
                workflowIdForJob(job), primaryTaskId(job), sst);
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
        remainingEstimatedRuntimeByJob.remove(job.getCloudletId());
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
        SpotAttempt active = activeSpotAttempts.get(attempt.job.getCloudletId());
        if (active != attempt) return;
        spotMarket.revoke(attempt.offer);
        List<SpotAttempt> revokedAttempts = new ArrayList<>();
        for (SpotAttempt candidate : activeSpotAttempts.values()) {
            if (candidate.offer.getInstanceId() == attempt.offer.getInstanceId()) {
                revokedAttempts.add(candidate);
            }
        }
        for (SpotAttempt revoked : revokedAttempts) {
            retryInterruptedAttempt(revoked);
        }
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    private void retryInterruptedAttempt(SpotAttempt attempt) {
        Job job = attempt.job;
        activeSpotAttempts.remove(job.getCloudletId());
        double cost = attempt.offer.getCostForElapsed(
                CloudSim.clock() - attempt.startTime);
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        if (wfr != null) wfr.addSpotCost(cost);
        getCloudletSubmittedList().remove(job);
        cloudletsSubmitted--;
        accounting.markTaskInterrupted(job);

        int attempts = spotAttemptCounts.getOrDefault(job.getCloudletId(), 1);
        boolean progressRetained = false;
        if (resumeReferenceProgress) {
            progressRetained = retainPartialProgress(job, attempt.offer, wfr);
            if (progressRetained) {
                partialProgressRetries++;
            } else {
                zeroProgressFallbacks++;
                forceOnDemand.add(job.getCloudletId());
            }
        } else if (policyMode.usesTaskPolicy()) {
            int attemptedClass = attempt.offer.getReliabilityClass();
            if (attemptedClass <= CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT) {
                forceOnDemand.add(job.getCloudletId());
            } else {
                maximumSpotClassByJob.put(job.getCloudletId(), attemptedClass - 1);
            }
        } else if (attempts >= Math.max(1, MAX_SPOT_ATTEMPTS)) {
            forceOnDemand.add(job.getCloudletId());
        }
        try {
            job.setCloudletStatus(Cloudlet.CREATED);
        } catch (Exception e) {
            throw new IllegalStateException("Could not retry interrupted CEWB task "
                    + job.getCloudletId(), e);
        }
        job.setVmId(-1);
        double recoveryDelay = progressRetained
                ? 2.0 * SNAPSHOT_DELAY_SECONDS : 0.0;
        if (recoveryDelay > 0.0) {
            schedule(getId(), recoveryDelay,
                    WorkflowSimTags.CEWB_SPOT_RETRY_READY, job);
        } else {
            getCloudletList().add(job);
        }

        CBMWLogger.logf("CEWB-SPOT-INTERRUPT",
                "wf=%d task=%d instance=%d class=%s cost=$%.6f"
                        + " attempts=%d fallback=%s progressRetained=%s"
                        + " recoveryDelay=%.2f remainingLength=%d",
                workflowIdForJob(job), primaryTaskId(job),
                attempt.offer.getInstanceId(), attempt.offer.getTypeName(), cost,
                attempts, forceOnDemand.contains(job.getCloudletId()) ? "yes" : "no",
                progressRetained, recoveryDelay, job.getCloudletLength());
    }

    private double getSst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getScheduledStart(primaryTaskId(job)) : 0.0;
    }

    private double estimatedRuntime(Job job, WorkflowRecord workflow,
                                    int taskId) {
        if (resumeReferenceProgress) {
            Double remaining = remainingEstimatedRuntimeByJob.get(
                    job.getCloudletId());
            if (remaining != null) return remaining;
        }
        return workflow.getEstimatedExecTime(taskId);
    }

    /** Retains the fraction of logical task work completed before revocation. */
    private boolean retainPartialProgress(Job job, CEWBSpotMarket.Offer offer,
                                          WorkflowRecord workflow) {
        if (workflow == null) return false;
        double execution = offer.getExecutionSeconds();
        double completed = offer.getAttemptRuntimeSeconds();
        if (!(execution > EPS) || !(completed > EPS)) return false;

        double completedFraction = Math.min(1.0, completed / execution);
        long currentLength = job.getCloudletLength();
        long remainingLength = (long) Math.ceil(
                currentLength * Math.max(0.0, 1.0 - completedFraction));
        if (remainingLength <= 0L) remainingLength = 1L;
        if (remainingLength >= currentLength) return false;

        int taskId = primaryTaskId(job);
        double currentEstimate = estimatedRuntime(job, workflow, taskId);
        double remainingEstimate = Math.max(EPS,
                currentEstimate * (remainingLength / (double) currentLength));
        job.setCloudletLength(remainingLength);
        remainingEstimatedRuntimeByJob.put(job.getCloudletId(), remainingEstimate);
        return true;
    }

    private double getLft(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getLFT(primaryTaskId(job)) : Double.MAX_VALUE;
    }

    private void logConfigurationOnce() {
        if (configurationLogged) return;
        configurationLogged = true;
        String summary = spotMarket.configurationSummary()
                + " " + onDemandPool.configurationSummary();
        summary += " mode=" + policyMode;
        if (taskPolicy != null) summary += " taskPolicy=" + taskPolicy.getName();
        summary += " resumeProgress=" + resumeReferenceProgress
                + " snapshotDelay=" + SNAPSHOT_DELAY_SECONDS
                + "s admissionCpMultiplier=" + ADMISSION_CP_MULTIPLIER;
        if (policyMode.usesPaperPricing()) summary += " pricing=" + pricingPolicy.getMode();
        System.out.println("[CEWB-CONFIG] " + summary);
        CBMWLogger.log("CEWB-CONFIG", summary);
    }

    private void emitDiagnostics() {
        if (summaryLogged) return;
        summaryLogged = true;
        settleOnDemandPool();
        String summary = spotMarket.diagnosticSummary()
                + " " + onDemandPool.diagnosticSummary()
                + " fallbacks=" + fallbackDispatches
                + " predictedFallbackMisses=" + predictedFallbackMisses
                + " wakesScheduled=" + sstWakeupsScheduled
                + " wakesFired=" + sstWakeupsFired
                + " partialProgressRetries=" + partialProgressRetries
                + " zeroProgressFallbacks=" + zeroProgressFallbacks;
        System.out.println("[CEWB-SUMMARY] " + summary);
        CBMWLogger.log("CEWB-SUMMARY", summary);
        spotMarket.terminateAll();
        assert spotMarket.getActiveInstances() == 0
                : "CEWB simulation ended with active spot instances: "
                        + spotMarket.getActiveInstances();
        assert spotMarket.getActiveCores() == 0
                : "CEWB simulation ended with active spot cores: "
                        + spotMarket.getActiveCores();
    }

    private void settleOnDemandPool() {
        if (onDemandCostsSettled) return;
        if (!activeOnDemandAttempts.isEmpty()) {
            throw new IllegalStateException(
                    "CEWB scenario ended with active on-demand containers: "
                            + activeOnDemandAttempts.size());
        }
        double now = CloudSim.clock();
        for (CEWBOnDemandPool.Instance instance : onDemandPool.terminateAll(now)) {
            accounting.markOnDemandDestroyed(instance.getId(),
                    instance.getDestroyAt());
        }
        Map<Integer, Double> costsByWorkflow = onDemandPool.settleCosts();
        Map<Integer, WorkflowRecord> workflowsById = new HashMap<>();
        for (WorkflowRecord workflow : allWorkflows) {
            workflowsById.put(workflow.getWorkflowId(), workflow);
        }
        for (Map.Entry<Integer, Double> entry : costsByWorkflow.entrySet()) {
            WorkflowRecord workflow = workflowsById.get(entry.getKey());
            if (workflow != null) workflow.addOnDemandCost(entry.getValue());
        }
        accounting.setOnDemandCapacityCoreSeconds(
                onDemandPool.getSettledCapacityCoreSeconds());
        if (policyMode.usesPaperPricing()) {
            for (WorkflowRecord workflow : allWorkflows) {
                if (workflow.isAccepted() && workflow.isComplete()) {
                    pricingPolicy.settleAfterExecution(workflow);
                }
            }
        }
        onDemandCostsSettled = true;
    }
}
