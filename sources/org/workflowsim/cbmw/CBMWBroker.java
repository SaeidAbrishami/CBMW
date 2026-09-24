package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;

/**
 * CBMW broker: four-module algorithm (Negotiate → Static Plan → Dynamic
 * Dispatch → Provision).
 *
 * Inherits all CloudSim wiring, DAX parsing, and result tracking from
 * AbstractWorkflowBroker. This class owns only the CBMW-specific logic:
 *   - planWorkflow: deadline-aware backward sweep-line static planner
 *   - processCloudletUpdate: LST/deadline-aware dynamic dispatcher
 *   - onTaskComplete: releases the booking slot on reserved VMs
 */
public class CBMWBroker extends AbstractWorkflowBroker {

    private static final double PREEMPTION_SAFETY_SECONDS =
            readNonNegativeDouble("cbmw.preemption.safety.sec", 0.0);
    private static final double SST_WAKE_EPSILON = 1e-9;

    private final CBMWDynamicSchedulingAlgorithm dynamicScheduler;
    private final Map<Integer, ReservedPreemption> pendingPreemptions =
            new HashMap<>();
    private final Map<Integer, Double> remainingPlanningDurations =
            new HashMap<>();
    private final Set<Job> runningReservedJobs = new LinkedHashSet<>();
    private final Set<Integer> waitingForPlannedReservedVm =
            new HashSet<>();
    /** Static on-demand order events cancelled by a successful early advance. */
    private final Set<Integer> cancelledOnDemandOrders = new HashSet<>();
    private final java.util.PriorityQueue<PlannedOrder> plannedOrders =
            new java.util.PriorityQueue<>(Comparator.comparingDouble(o -> o.time));

    private static final class PlannedOrder {
        final int taskId;
        final double time;
        PlannedOrder(int taskId, double time) {
            this.taskId = taskId;
            this.time = time;
        }
    }
    /** Earliest capacity-safe retry time for a delayed reserved task. */
    private final Map<Integer, Double> reservedRetryTimes = new HashMap<>();
    private final ReservedSstWakeController reservedSstWakeController =
            new ReservedSstWakeController();
    private boolean immediateReservedCapacityWake;

    static final class ReservedSstWake {
        private final long generation;
        private final double targetTime;

        private ReservedSstWake(long generation, double targetTime) {
            this.generation = generation;
            this.targetTime = targetTime;
        }

        long getGeneration() { return generation; }
        double getTargetTime() { return targetTime; }
    }

    /** Keeps exactly one logical SST wake active while stale events drain. */
    static final class ReservedSstWakeController {
        private long generation;
        private double targetTime = Double.POSITIVE_INFINITY;

        ReservedSstWake arm(double requestedTime) {
            if (!Double.isFinite(requestedTime)) {
                clear();
                return null;
            }
            if (Double.isFinite(targetTime)
                    && Math.abs(targetTime - requestedTime)
                            <= SST_WAKE_EPSILON) {
                return null;
            }
            generation++;
            targetTime = requestedTime;
            return new ReservedSstWake(generation, targetTime);
        }

        boolean consume(ReservedSstWake wake) {
            if (wake == null || wake.generation != generation
                    || !Double.isFinite(targetTime)
                    || Math.abs(wake.targetTime - targetTime)
                            > SST_WAKE_EPSILON) {
                return false;
            }
            targetTime = Double.POSITIVE_INFINITY;
            return true;
        }

        void clear() {
            if (Double.isFinite(targetTime)) {
                generation++;
                targetTime = Double.POSITIVE_INFINITY;
            }
        }

        double getTargetTime() { return targetTime; }
    }

    private static final class ReservedPreemption {
        private final Job victim;
        private final int replacementTaskId;
        private final int vmId;

        private ReservedPreemption(Job victim, int replacementTaskId, int vmId) {
            this.victim = victim;
            this.replacementTaskId = replacementTaskId;
            this.vmId = vmId;
        }
    }

    public CBMWBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        negotiation.setBeta(PaperRuntimeModel.NEGOTIATION_BETA);
        negotiation.setGamma(PaperRuntimeModel.NEGOTIATION_GAMMA);
        this.dynamicScheduler = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner,
                waitingForPlannedReservedVm, cancelledOnDemandOrders,
                reservedRetryTimes, false);
        this.dynamicScheduler.setContainerLifecycle(
                this::cancelLogicalOnDemandContainer,
                this::isLogicalContainerReady);
        CBMWLogger.logf("CONFIG",
                "periodicDispatch=true interval=%.1f opd=%.1f",
                HybridVmPool.SCHEDULING_PERIOD,
                HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    // -----------------------------------------------------------------------
    // Module 2 — Static Planning (backward sweep-line)
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(wfr, vmPool, negotiation);
        planner.setTaskList(tasks);
        planner.setVmList(vmPool.getAllVms());
        long planningStart = CBMWPerformanceMetrics.isEnabled()
                ? System.nanoTime() : 0L;
        try {
            planner.run();
        } catch (Exception e) {
            Log.printLine(getName() + ": static planner error: " + e.getMessage());
            CBMWLogger.logf("PLAN-FAILED", "wf=%d arrival=%.4f deadline=%.4f reason=%s",
                    wfr.getWorkflowId(), wfr.getArrivalTime(),
                    wfr.getDeadline(), e.getMessage());
            wfr.setAccepted(false);
            return false;
        } finally {
            if (CBMWPerformanceMetrics.isEnabled()) {
                CBMWPerformanceMetrics.recordPlanningTime(
                        System.nanoTime() - planningStart);
            }
        }
        return true;
    }

    private void schedulePlannedOnDemandOrders(WorkflowRecord wfr, List<Task> tasks) {
        double now = CloudSim.clock();
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            if (wfr.getAssignedVm(taskId)
                    != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                continue;
            }
            double orderTime = CBMWStaticPlanningAlgorithm.floorTick(
                    wfr.getScheduledStart(taskId)
                    - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            plannedOrders.add(new PlannedOrder(taskId, orderTime));
            schedule(getId(), Math.max(0.0, orderTime - now),
                    WorkflowSimTags.CBMW_ON_DEMAND_ORDER, taskId);
            CBMWLogger.logf("CBMW-ONDEMAND-ORDER-SCHEDULED",
                    "wf=%d task=%d orderAt=%.4f now=%.4f",
                    wfr.getWorkflowId(), taskId, orderTime, now);
        }
    }

    @Override
    protected void finalizeWorkflowNegotiation(WorkflowRecord wfr) {
        negotiation.quoteExecutionPrice(wfr);
        schedulePlannedOnDemandOrders(wfr, wfr.getTaskList());
    }

    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        // The static planner is the admission test. Critical path is retained
        // for reporting, but a separate CP test must not reject a request.
        wfr.setCriticalPathLength(negotiation.computeCriticalPath(wfr));
        wfr.setDeadlineFeasible(true);
        wfr.setAccepted(true);
        return true;
    }

    // -----------------------------------------------------------------------
    // Module 3 — Dynamic Dispatch
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletSubmit(SimEvent ev) {
        List<Cloudlet> additions = (List<Cloudlet>) ev.getData();
        CBMWPerformanceMetrics.recordReadyQueueScan(additions.size());
        recordReadyQueue(additions);

        // The existing queue is already SST-ordered. Sort only the newly-ready
        // batch, then merge it stably so equal-SST jobs retain the same order
        // produced by ArrayList.sort() over the old queue plus appended jobs.
        CBMWPerformanceMetrics.recordPreemptionSort(additions.size());
        stableMergeSorted(getCloudletList(), additions,
                Comparator.comparingDouble(this::scheduledStart));
        sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        try {
            immediateReservedCapacityWake = false;
            List<Cloudlet> ready = (List<Cloudlet>) getCloudletList();

            dynamicScheduler.setCloudletList(ready);
            dynamicScheduler.setVmList(getVmsCreatedList());
            dynamicScheduler.getScheduledList().clear();

            long dispatchStart = CBMWPerformanceMetrics.isEnabled()
                    ? System.nanoTime() : 0L;
            try {
                dynamicScheduler.run();
                dispatchScheduledJobs(dynamicScheduler.getScheduledList());
                launchDueContainers();
            } catch (Exception e) {
                throw new IllegalStateException("CBMW dynamic scheduler failed at "
                        + CloudSim.clock(), e);
            } finally {
                if (CBMWPerformanceMetrics.isEnabled()) {
                    CBMWPerformanceMetrics.recordDispatchTime(
                            System.nanoTime() - dispatchStart);
                }
            }

        } finally {
            scheduleNextReservedSstWake();
        }
    }

    private void launchDueContainers() {
        double now = CloudSim.clock();
        while (!plannedOrders.isEmpty()
                && plannedOrders.peek().time <= now + 1e-9) {
            PlannedOrder order = plannedOrders.remove();
            if (cancelledOnDemandOrders.remove(order.taskId)) continue;
            orderLogicalOnDemandContainer(order.taskId);
        }
    }

    /**
     * Retained as an inert compatibility hook for earlier wake-up tests.
     */
    @SuppressWarnings("unchecked")
    private void scheduleNextReservedSstWake() {
        // The base broker schedules the next five-second tick when an event
        // arrives between ticks. No immediate SST dispatch is permitted.
    }

    /**
     * When a due, reserved-planned task has no reserved capacity, replace only
     * a task that is still executing before its planned SST. Among candidates
     * that can individually release enough CPU and RAM, choose the task with
     * the greatest safe post-preemption deadline slack. If no such pre-running
     * task exists, leave the task queued until its planned reserved VM
     * releases enough runtime capacity.
     */
    @SuppressWarnings("unchecked")
    private boolean requestReservedPreemption() {
        List<Cloudlet> ready = (List<Cloudlet>) getCloudletList();

        double now = CloudSim.clock();
        for (Cloudlet cloudlet : ready) {
            if (scheduledStart(cloudlet) > now) break;
            Job waiting = (Job) cloudlet;
            if (requestReservedPreemption(waiting)) return true;
        }
        return false;
    }

    private boolean requestReservedPreemption(Job waiting) {
        double now = CloudSim.clock();
        int waitingTaskId = primaryTaskId(waiting);
        WorkflowRecord waitingWorkflow = activeWorkflows.get(
                workflowIdForJob(waiting));
        if (waitingWorkflow == null
                || waitingWorkflow.getScheduledStart(waitingTaskId) > now
                || waitingWorkflow.getAssignedVm(waitingTaskId)
                        == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                || provisioner.getProvisionedVm(waitingTaskId) != null
                || waitingForPlannedReservedVm.contains(waitingTaskId)) {
            return false;
        }

        if (hasFeasibleReservedPlacement(
                waiting, waitingWorkflow, waitingTaskId, now)) {
            return false;
        }

        double expectedInterruption = remainingPlanningDurations.getOrDefault(
                waitingTaskId,
                waitingWorkflow.getEstimatedExecTime(waitingTaskId));
        Job victim = selectVictim(waitingTaskId, now, expectedInterruption);
        if (victim == null) {
            waitingForPlannedReservedVm.add(waitingTaskId);
            CBMWLogger.logf("WAIT-PLANNED-RESERVED-NO-SAFE-VICTIM",
                    "waitingWf=%d waitingTask=%d plannedVm=%d now=%.4f"
                            + " expectedInterruption=%.4f safetyMargin=%.4f",
                    waitingWorkflow.getWorkflowId(), waitingTaskId,
                    waitingWorkflow.getAssignedVm(waitingTaskId), now,
                    expectedInterruption,
                    PREEMPTION_SAFETY_SECONDS);
            return false;
        }

        int victimVmId = victim.getVmId();
        Integer datacenterId = getVmsToDatacentersMap().get(victimVmId);
        if (datacenterId == null) return false;

        waitingWorkflow.setAssignedVm(waitingTaskId, victimVmId);
        pendingPreemptions.put(victim.getCloudletId(),
                new ReservedPreemption(victim, waitingTaskId, victimVmId));
        int[] request = {victim.getCloudletId(), getId(), victimVmId};
        sendNow(datacenterId, CloudSimTags.CLOUDLET_CANCEL, request);
        double victimSst = scheduledStart(victim);
        double victimSlack = taskDeadlineSlack(victim, now);
        double postPreemptionSlack = calculatePostPreemptionSlack(
                taskSubDeadline(victim), now,
                estimatedRemainingPlanningDuration(victim, now),
                expectedInterruption);
        CBMWLogger.logf("RESERVED-PREEMPT-REQUEST",
                "waitingWf=%d waitingTask=%d victimWf=%d victimTask=%d"
                        + " vm=%d now=%.4f victimSst=%.4f victimSlack=%.4f"
                        + " expectedInterruption=%.4f postPreemptionSlack=%.4f"
                        + " safetyMargin=%.4f",
                waitingWorkflow.getWorkflowId(), waitingTaskId,
                workflowIdForJob(victim), primaryTaskId(victim),
                victimVmId, now, victimSst, victimSlack,
                expectedInterruption, postPreemptionSlack,
                PREEMPTION_SAFETY_SECONDS);
        return true;
    }

    private boolean hasFeasibleReservedPlacement(Job waiting,
                                                  WorkflowRecord workflow,
                                                  int taskId, double now) {
        int attemptedVmId = waiting.getVmId() >= 0
                ? waiting.getVmId() : workflow.getAssignedVm(taskId);
        if (vmPool.isReserved(attemptedVmId)
                && vmPool.hasRuntimeCapacity(attemptedVmId, taskId)) {
            return true;
        }

        double duration = workflow.getEstimatedExecTime(taskId);
        if (!Double.isFinite(duration) || duration <= 0.0) return false;
        int cores = vmPool.getTaskCores(taskId);
        int ramMb = vmPool.getTaskRamMb(taskId);
        for (CondorVM vm : vmPool.getReservedVms()) {
            if (vm.getId() == attemptedVmId) continue;
            if (vmPool.hasRuntimeCapacity(vm.getId(), taskId)
                    && vmPool.hasBookedCapacity(vm.getId(), now,
                            now + duration, cores, ramMb)) {
                return true;
            }
        }
        return false;
    }

    private Job selectVictim(int waitingTaskId, double now,
                             double expectedInterruption) {
        if (!Double.isFinite(expectedInterruption)
                || expectedInterruption < 0.0) {
            return null;
        }
        int requiredCores = vmPool.getTaskCores(waitingTaskId);
        int requiredRam = vmPool.getTaskRamMb(waitingTaskId);
        assert runningReservedIndexMatchesSubmittedList();

        Job best = null;
        for (Job job : runningReservedJobs) {
            if (!isCurrentlyPreRunning(now, scheduledStart(job))) continue;
            if (!getVmsToDatacentersMap().containsKey(job.getVmId())) continue;
            if (vmPool.getTaskCores(primaryTaskId(job)) < requiredCores) continue;
            if (vmPool.getTaskRamMb(primaryTaskId(job)) < requiredRam) continue;
            if (!capacityAfterRemoving(job, waitingTaskId)) continue;
            if (!isDeadlineSafeVictim(job, now, expectedInterruption)) continue;
            if (best == null || compareVictimPriority(
                    job, best, now, expectedInterruption) > 0) {
                best = job;
            }
        }
        return best;
    }

    private boolean isDeadlineSafeVictim(Job job, double now,
                                         double expectedInterruption) {
        double postPreemptionSlack = calculatePostPreemptionSlack(
                taskSubDeadline(job), now,
                estimatedRemainingPlanningDuration(job, now),
                expectedInterruption);
        return hasSafePostPreemptionSlack(
                postPreemptionSlack, PREEMPTION_SAFETY_SECONDS);
    }

    private int compareVictimPriority(Job left, Job right, double now,
                                      double expectedInterruption) {
        return compareVictimPriorityValues(
                postPreemptionSlack(left, now, expectedInterruption),
                taskSubDeadline(left), primaryTaskId(left),
                postPreemptionSlack(right, now, expectedInterruption),
                taskSubDeadline(right), primaryTaskId(right));
    }

    private double postPreemptionSlack(Job job, double now,
                                       double expectedInterruption) {
        return calculatePostPreemptionSlack(taskSubDeadline(job), now,
                estimatedRemainingPlanningDuration(job, now),
                expectedInterruption);
    }

    private double taskDeadlineSlack(Job job, double now) {
        return calculateTaskDeadlineSlack(taskSubDeadline(job), now,
                estimatedRemainingPlanningDuration(job, now));
    }

    static boolean isCurrentlyPreRunning(double now, double scheduledStart) {
        return Double.isFinite(now) && Double.isFinite(scheduledStart)
                && scheduledStart > now;
    }

    static double calculateTaskDeadlineSlack(double subDeadline, double now,
                                             double remainingDuration) {
        return subDeadline - now - Math.max(0.0, remainingDuration);
    }

    static double calculatePostPreemptionSlack(
            double subDeadline, double now, double remainingDuration,
            double expectedInterruption) {
        return calculateTaskDeadlineSlack(subDeadline, now, remainingDuration)
                - Math.max(0.0, expectedInterruption);
    }

    static boolean hasSafePostPreemptionSlack(
            double postPreemptionSlack, double safetyMargin) {
        return Double.isFinite(postPreemptionSlack)
                && Double.isFinite(safetyMargin)
                && safetyMargin >= 0.0
                && postPreemptionSlack >= safetyMargin;
    }

    static int compareVictimPriorityValues(
            double leftSlack, double leftSubDeadline, int leftTaskId,
            double rightSlack, double rightSubDeadline, int rightTaskId) {
        int bySlack = Double.compare(leftSlack, rightSlack);
        if (bySlack != 0) return bySlack;

        int bySubDeadline = Double.compare(leftSubDeadline, rightSubDeadline);
        if (bySubDeadline != 0) return bySubDeadline;

        // A smaller task ID wins the final tie while still using Stream.max.
        return Integer.compare(rightTaskId, leftTaskId);
    }

    private double taskSubDeadline(Job job) {
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null
                ? workflow.getLFT(primaryTaskId(job))
                : Double.NEGATIVE_INFINITY;
    }

    private double estimatedRemainingPlanningDuration(Job job, double now) {
        int taskId = primaryTaskId(job);
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        if (workflow == null) return Double.POSITIVE_INFINITY;

        double remainingAtLatestStart = remainingPlanningDurations.getOrDefault(
                taskId, workflow.getEstimatedExecTime(taskId));
        double execStart = job.getExecStartTime();
        double elapsedThisAttempt = Double.isFinite(execStart)
                && execStart >= 0.0 && execStart <= now
                ? now - execStart : 0.0;
        return Math.max(0.0, remainingAtLatestStart - elapsedThisAttempt);
    }

    private boolean capacityAfterRemoving(Job victim, int waitingTaskId) {
        CondorVM vm = vmPool.getVmById(victim.getVmId());
        if (vm == null) return false;
        int victimTaskId = primaryTaskId(victim);
        int coresAfter = vmPool.getRunningCores(vm.getId())
                - vmPool.getTaskCores(victimTaskId)
                + vmPool.getTaskCores(waitingTaskId);
        int ramAfter = vmPool.getRunningRamMb(vm.getId())
                - vmPool.getTaskRamMb(victimTaskId)
                + vmPool.getTaskRamMb(waitingTaskId);
        return coresAfter <= vm.getNumberOfPes() && ramAfter <= vm.getRam();
    }

    private double scheduledStart(Cloudlet cloudlet) {
        Job job = (Job) cloudlet;
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null
                ? workflow.getScheduledStart(primaryTaskId(job)) : 0.0;
    }

    /** Stable merge equivalent to sorting {@code sorted + additions}. */
    static <T> void stableMergeSorted(List<T> sorted, List<T> additions,
                                      Comparator<? super T> comparator) {
        if (additions.isEmpty()) return;

        List<T> incoming = new ArrayList<>(additions);
        incoming.sort(comparator);
        List<T> merged = new ArrayList<>(sorted.size() + incoming.size());
        int existingIndex = 0;
        int incomingIndex = 0;
        while (existingIndex < sorted.size() && incomingIndex < incoming.size()) {
            T existing = sorted.get(existingIndex);
            T added = incoming.get(incomingIndex);
            if (comparator.compare(existing, added) <= 0) {
                merged.add(existing);
                existingIndex++;
            } else {
                merged.add(added);
                incomingIndex++;
            }
        }
        while (existingIndex < sorted.size()) {
            merged.add(sorted.get(existingIndex++));
        }
        while (incomingIndex < incoming.size()) {
            merged.add(incoming.get(incomingIndex++));
        }
        sorted.clear();
        sorted.addAll(merged);
    }

    /** Inserts after existing equal-key entries, matching stable append+sort. */
    static <T> void stableInsertSorted(List<T> sorted, T addition,
                                       Comparator<? super T> comparator) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (comparator.compare(sorted.get(middle), addition) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        sorted.add(low, addition);
    }

    private static double readNonNegativeDouble(String property,
                                                double defaultValue) {
        double value = Double.parseDouble(System.getProperty(
                property, Double.toString(defaultValue)));
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(property + " must be >= 0");
        }
        return value;
    }

    // -----------------------------------------------------------------------
    // Module 4 — Release booking slot on reserved-VM completion
    // -----------------------------------------------------------------------

    @Override
    protected boolean usesPeriodicScheduling() {
        return true;
    }

    @Override
    protected boolean billProvisioningDelay() { return true; }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.CBMW_RESERVED_SST_WAKE) {
            return;
        }
        if (ev.getTag() == CloudSimTags.CLOUDLET_CANCEL) {
            processReservedPreemptionAck((Cloudlet) ev.getData());
            return;
        }
        if (ev.getTag() == WorkflowSimTags.CBMW_ON_DEMAND_ORDER) {
            // Wake the periodic scheduler; requests are issued after its
            // ready-task passes, whether or not the task is ready.
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        super.processEvent(ev);
    }

    private void processReservedPreemptionAck(Cloudlet canceled) {
        if (canceled == null) {
            // A completion may have won the same-time race. Clear all pending
            // requests and let the normal dispatcher re-evaluate capacity.
            pendingPreemptions.clear();
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }

        ReservedPreemption preemption = pendingPreemptions.remove(
                canceled.getCloudletId());
        if (preemption == null) {
            return;
        }

        Job canceledJob = (Job) canceled;
        int victimTaskId = primaryTaskId(canceledJob);
        remainingPlanningDurations.put(victimTaskId,
                estimatedRemainingPlanningDuration(
                        canceledJob, CloudSim.clock()));
        long remainingLength = Math.max(1L,
                canceled.getCloudletLength() - canceled.getCloudletFinishedSoFar());
        canceled.setCloudletLength(remainingLength);

        getCloudletSubmittedList().remove(canceled);
        cloudletsSubmitted = Math.max(0, cloudletsSubmitted - 1);
        runningReservedJobs.remove(canceledJob);
        vmPool.taskFinished(preemption.vmId, victimTaskId);
        vmPool.releaseSlot(victimTaskId);
        accounting.markReservedTaskPreempted(canceled);
        stableInsertSorted(getCloudletList(), canceled,
                Comparator.comparingDouble(this::scheduledStart));

        CBMWLogger.logf("RESERVED-PREEMPTED",
                "victimWf=%d victimTask=%d vm=%d remainingMI=%d replacementTask=%d",
                workflowIdForJob(preemption.victim), victimTaskId,
                preemption.vmId, remainingLength, preemption.replacementTaskId);
        sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
        assert runningReservedIndexMatchesSubmittedList();
    }

    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
        if (!waitingForPlannedReservedVm.isEmpty()) {
            immediateReservedCapacityWake = true;
        }
    }

    @Override
    protected void onTaskReturned(Cloudlet cl, boolean onDemand) {
        runningReservedJobs.remove(cl);
        int taskId = primaryTaskId((Job) cl);
        remainingPlanningDurations.remove(taskId);
        waitingForPlannedReservedVm.remove(taskId);
        reservedRetryTimes.remove(taskId);
        assert runningReservedIndexMatchesSubmittedList();
    }

    @Override
    protected void onTaskSubmitted(Cloudlet cl, boolean onDemand) {
        if (!onDemand && cl instanceof Job && vmPool.isReserved(cl.getVmId())) {
            runningReservedJobs.add((Job) cl);
        }
    }

    @Override
    protected void onSubmissionBatchComplete() {
        assert runningReservedIndexMatchesSubmittedList();
    }

    private boolean runningReservedIndexMatchesSubmittedList() {
        Set<Job> expected = new LinkedHashSet<>();
        for (Cloudlet cloudlet : getCloudletSubmittedList()) {
            if (cloudlet instanceof Job && vmPool.isReserved(cloudlet.getVmId())) {
                expected.add((Job) cloudlet);
            }
        }
        return runningReservedJobs.equals(expected);
    }
}
