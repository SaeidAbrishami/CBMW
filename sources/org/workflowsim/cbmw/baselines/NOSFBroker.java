package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.workflowsim.utils.Parameters;

/** Paper-faithful NOSF Algorithms 1-3 in the project's common market. */
public class NOSFBroker extends AbstractWorkflowBroker {

    private static final double EPS = 1e-9;

    /** One source of truth: NOSF uses the same provisioning delay as CBMW. */
    static final double PROVISIONING_DELAY =
            HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
    static final double BILLING_QUANTUM =
            NOSFConfiguration.billingQuantumSeconds();

    private final NOSFWorkflowPlanner workflowPlanner = new NOSFWorkflowPlanner();
    private final NOSFResourceSelector selector =
            new NOSFResourceSelector(PROVISIONING_DELAY, BILLING_QUANTUM);
    private final List<NOSFVmType> vmTypes = NOSFVmType.configuredTypes();
    private final List<NOSFVmState> vmStates = new ArrayList<>();
    private final Map<Integer, NOSFVmState> stateByVmId = new HashMap<>();
    private final Set<Integer> allocatedJobs = new HashSet<>();

    public NOSFBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        CBMWLogger.logf("NOSF-CONFIG",
                "profile=%s types=%d provisioningDelay=%.1f"
                        + " billingQuantum=%.1f sigmaRatio=%.4f priority=%s"
                        + " transferMode=%s bandwidthMbps=%.1f",
                NOSFConfiguration.profileName(), vmTypes.size(),
                PROVISIONING_DELAY, BILLING_QUANTUM,
                NOSFRuntimeModel.STDDEV_RATIO,
                workflowPlanner.getPriorityPolicy().name(),
                workflowPlanner.getTransferModel().getMode().name(),
                workflowPlanner.getTransferModel().getBandwidthMbps());
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return NOSFRuntimeModel.weight(meanExecutionTime);
    }

    /** NOSF has no CBMW admission negotiation. */
    @Override
    protected boolean negotiateWorkflow(WorkflowRecord workflow) {
        workflow.setCriticalPathLength(negotiation.computeCriticalPath(workflow));
        workflow.setAccepted(true);
        return true;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord workflow, List<Task> tasks) {
        workflowPlanner.preprocess(workflow, tasks, vmTypes);
        for (Task task : tasks) {
            workflow.setPlannedVmType(task.getCloudletId(), "On-Demand");
        }
        CBMWLogger.logf("NOSF-PREPROCESS",
                "wf=%d tasks=%d deadline=%.2f estimator=MU_PLUS_SIGMA"
                        + " sigmaRatio=%.4f priority=%s provisioningDelay=%.1f"
                        + " billingQuantum=%.1f transferMode=%s",
                workflow.getWorkflowId(), tasks.size(), workflow.getDeadline(),
                NOSFRuntimeModel.STDDEV_RATIO,
                workflowPlanner.getPriorityPolicy().name(), PROVISIONING_DELAY,
                BILLING_QUANTUM,
                workflowPlanner.getTransferModel().getMode().name());
        return true;
    }

    @Override
    protected boolean usesPeriodicScheduling() { return false; }

    @Override
    protected boolean managesOwnOnDemandLifecycle() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent event) {
        double now = CloudSim.clock();
        launchAndStartReadyVms(now);

        List<Cloudlet> brokerReadyList = (List<Cloudlet>) getCloudletList();
        recordReadyQueue(brokerReadyList);
        List<Cloudlet> readyJobs = new ArrayList<>(brokerReadyList);
        readyJobs.sort(Comparator
                .comparingDouble((Cloudlet cloudlet) ->
                        getPriority((Job) cloudlet))
                .thenComparingInt(cloudlet -> workflowIdForJob((Job) cloudlet))
                .thenComparingInt(Cloudlet::getCloudletId));

        for (Cloudlet cloudlet : readyJobs) {
            Job job = (Job) cloudlet;
            if (allocatedJobs.contains(job.getCloudletId())) continue;
            WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
            if (workflow == null) continue;
            int taskId = primaryTaskId(job);

            Map<Integer, Double> dataReadyByVm = new HashMap<>();
            for (NOSFVmState state : vmStates) {
                if (!state.released) {
                    dataReadyByVm.put(state.vm.getId(),
                            workflowPlanner.dataReadyTime(workflow, taskId,
                                    state.vm.getId(), now));
                }
            }
            double newVmDataReady = workflowPlanner.dataReadyTime(
                    workflow, taskId, Integer.MIN_VALUE, now);
            NOSFResourceSelector.Choice choice = selector.choose(now,
                    workflow.getEstimatedExecTime(taskId),
                    workflow.getTaskCores(taskId),
                    workflow.getTaskRamMb(taskId),
                    workflowPlanner.subDeadline(workflow, taskId),
                    vmStates, vmTypes, dataReadyByVm, newVmDataReady);
            if (choice == null) {
                throw new IllegalStateException("No NOSF VM type can run task "
                        + taskId + "; configure nosf.vm.type.* capacity");
            }

            NOSFVmState state = choice.vm != null ? choice.vm
                    : createVm(choice.newType, taskId, now);
            assign(job, workflow, state, choice);
            allocatedJobs.add(job.getCloudletId());
            brokerReadyList.remove(cloudlet);
            getCloudletSubmittedList().add(cloudlet);
            cloudletsSubmitted++;
            startWaitingIfReady(state, now);
        }

        launchAndStartReadyVms(now);
        releaseExpiredVms(now);
    }

    private NOSFVmState createVm(NOSFVmType type, int taskId, double now) {
        CondorVM vm = vmPool.provisionOnDemandVm(getId(), taskId, type.cores,
                type.ramMb, type.mipsPerCore, type.pricePerSecond);
        double ready = now + PROVISIONING_DELAY;
        NOSFVmState state = new NOSFVmState(vm, type, now, ready);
        vmStates.add(state);
        stateByVmId.put(vm.getId(), state);
        accounting.markOnDemandOrdered(vm.getId(), type.cores, type.ramMb,
                now, ready);
        accounting.markTaskProvisioningOrdered(taskId, now, ready);
        if (PROVISIONING_DELAY > 0.0) {
            schedule(getId(), PROVISIONING_DELAY,
                    WorkflowSimTags.CLOUDLET_UPDATE);
        }
        return state;
    }

    private void assign(Job job, WorkflowRecord workflow, NOSFVmState state,
                        NOSFResourceSelector.Choice choice) {
        if (!state.canAcceptWaitingTask()) {
            throw new IllegalStateException("NOSF VM " + state.vm.getId()
                    + " already has a waiting task");
        }
        int taskId = primaryTaskId(job);
        job.setVmId(state.vm.getId());
        if (!job.getTaskList().isEmpty()) {
            job.getTaskList().get(0).setVmId(state.vm.getId());
        }
        state.waiting = job;
        state.waitingDataReadyTime = choice.dataReadyTime;
        state.plannedAvailableTime = choice.finish;
        state.releaseAt = Double.POSITIVE_INFINITY;
        workflow.setAssignedVm(taskId, state.vm.getId());
        workflow.setScheduledStart(taskId, choice.start);
        if (!choice.feasible) accounting.markDeadlineRisk(taskId);
        CBMWLogger.logf("NOSF-ALLOCATE",
                "wf=%d task=%d priority=%.2f subDeadline=%.2f start=%.2f"
                        + " finish=%.2f feasible=%s selectionCost=$%.6f"
                        + " incrementalRentalCost=$%.6f idle=%.2f vm=%d reused=%s",
                workflow.getWorkflowId(), taskId,
                workflowPlanner.priority(workflow, taskId),
                workflowPlanner.subDeadline(workflow, taskId), choice.start,
                choice.finish, choice.feasible ? "yes" : "no",
                choice.selectionCost, choice.incrementalRentalCost,
                choice.idleTime, state.vm.getId(),
                choice.vm != null ? "yes" : "no");
    }

    private void launchAndStartReadyVms(double now) {
        for (NOSFVmState state : vmStates) {
            if (state.released) continue;
            if (!state.launched && now + EPS >= state.readyTime) {
                state.launched = true;
                state.vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
                vmPool.activateOnDemandContainer(state.vm.getId());
                accounting.markOnDemandLaunched(state.vm.getId(), now);
                accounting.snapshotUtilization(vmPool);
            }
            startWaitingIfReady(state, now);
        }
    }

    private void startWaitingIfReady(NOSFVmState state, double now) {
        if (!state.launched || state.running != null || state.waiting == null) return;
        WorkflowRecord workflow = activeWorkflows.get(
                workflowIdForJob(state.waiting));
        if (workflow == null) return;
        double readyTime = Math.max(state.readyTime,
                state.waitingDataReadyTime);
        if (now + EPS < readyTime) {
            schedule(getId(), readyTime - now,
                    WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        startNext(state, now);
    }

    private void startNext(NOSFVmState state, double now) {
        Job job = state.waiting;
        state.waiting = null;
        state.waitingDataReadyTime = Double.NaN;
        int taskId = primaryTaskId(job);
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        if (workflow == null) return;
        double queueDelay = Parameters.getOverheadParams().getQueueDelay() != null
                ? Parameters.getOverheadParams().getQueueDelay(job) : 0.0;
        double actualRuntime = HybridVmPool.executionTimeSeconds(
                job.getCloudletLength(), workflow.getTaskCores(taskId),
                state.type.mipsPerCore);
        try {
            job.setResourceParameter(getId(), state.type.pricePerSecond);
            job.setSubmissionTime(now);
            job.setExecStartTime(now + queueDelay);
            job.setCloudletStatus(Cloudlet.INEXEC);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not start NOSF task " + taskId, exception);
        }
        state.running = job;
        state.releaseAt = Double.POSITIVE_INFINITY;
        state.plannedAvailableTime = now + queueDelay
                + state.type.runtime(workflow.getEstimatedExecTime(taskId));
        state.vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
        vmPool.taskStarted(state.vm.getId(), taskId);
        accounting.markTaskConfiguration(taskId, state.type.name,
                state.type.pricePerSecond);
        accounting.markTaskSubmitted(job, "On-Demand");
        accounting.snapshotUtilization(vmPool);
        schedule(getId(), queueDelay + actualRuntime,
                WorkflowSimTags.ON_DEMAND_TASK_COMPLETE, job);
    }

    @Override
    protected void onTaskReturned(Cloudlet cloudlet, boolean onDemand) {
        NOSFVmState state = stateByVmId.get(cloudlet.getVmId());
        if (state == null) return;
        state.running = null;
        state.vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
        state.busyTime += cloudlet.getActualCPUTime();
        state.completedTasks++;
        double now = CloudSim.clock();

        double actualCost = state.billedCost(now, BILLING_QUANTUM);
        double delta = Math.max(0.0, actualCost - state.chargedCost);
        state.chargedCost = actualCost;
        WorkflowRecord workflow = activeWorkflows.get(
                workflowIdForJob((Job) cloudlet));
        if (workflow != null) workflow.addOnDemandCost(delta);

        state.plannedAvailableTime = now;
        if (state.waiting != null) {
            startWaitingIfReady(state, now);
        } else {
            scheduleRelease(state, now);
        }
        CBMWLogger.logf("TASK-COMPLETE",
                "task=%d wf=%d vm=%d(NOSF reusable) actualCPU=%.4fs"
                        + " incrementalActualCost=$%.6f releaseAt=%.2f",
                cloudlet.getCloudletId(), workflowIdForJob((Job) cloudlet),
                cloudlet.getVmId(), cloudlet.getActualCPUTime(), delta,
                state.releaseAt);
    }

    private void scheduleRelease(NOSFVmState state, double now) {
        state.releaseAt = state.currentBillingBoundary(now, BILLING_QUANTUM);
        schedule(getId(), Math.max(0.0, state.releaseAt - now),
                WorkflowSimTags.CLOUDLET_UPDATE);
    }

    private void releaseExpiredVms(double now) {
        for (Iterator<NOSFVmState> iterator = vmStates.iterator();
             iterator.hasNext();) {
            NOSFVmState state = iterator.next();
            if (state.released || state.running != null || state.waiting != null
                    || now + EPS < state.releaseAt) continue;
            state.released = true;
            accounting.markOnDemandDestroyed(state.vm.getId(), state.releaseAt);
            vmPool.terminateOnDemandVm(state.vm.getId());
            accounting.snapshotUtilization(vmPool);
            stateByVmId.remove(state.vm.getId());
            iterator.remove();
            CBMWLogger.logf("NOSF-RELEASE",
                    "vm=%d order=%.2f release=%.2f billed=$%.6f",
                    state.vm.getId(), state.orderTime, state.releaseAt,
                    state.chargedCost);
        }
    }

    /** Finalize reusable VM lifecycles if WorkflowSim stops before a billing boundary. */
    @Override
    public void shutdownEntity() {
        double now = CloudSim.clock();
        for (NOSFVmState state : new ArrayList<>(vmStates)) {
            if (state.released) continue;
            if (state.running != null || state.waiting != null) {
                throw new IllegalStateException(
                        "NOSF simulation ended with work on VM " + state.vm.getId());
            }
            double releaseAt = state.launched
                    ? state.currentBillingBoundary(now, BILLING_QUANTUM)
                    : now;
            state.released = true;
            accounting.markOnDemandDestroyed(state.vm.getId(), releaseAt);
            vmPool.terminateOnDemandVm(state.vm.getId());
            stateByVmId.remove(state.vm.getId());
        }
        vmStates.clear();
        accounting.snapshotUtilization(vmPool);
        super.shutdownEntity();
    }

    @Override
    protected void onWorkflowTaskComplete(WorkflowRecord workflow, int taskId,
                                          double finishTime) {
        double previousEft = workflow.getEFT(taskId);
        workflowPlanner.feedback(workflow, taskId, finishTime);
        CBMWLogger.log("NOSF-FEEDBACK", String.format(Locale.US,
                "wf=%d task=%d predictedFinish=%.2f actualFinish=%.2f deviation=%.2f",
                workflow.getWorkflowId(), taskId, previousEft, finishTime,
                finishTime - previousEft));
        if (workflow.isComplete()) workflowPlanner.forget(workflow.getWorkflowId());
    }

    private double getPriority(Job job) {
        WorkflowRecord workflow = activeWorkflows.get(workflowIdForJob(job));
        return workflow != null
                ? workflowPlanner.priority(workflow, primaryTaskId(job))
                : Double.MAX_VALUE;
    }

    private static double readNonNegative(String property, double defaultValue) {
        double value = Double.parseDouble(System.getProperty(
                property, Double.toString(defaultValue)));
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    property + " must be finite and non-negative");
        }
        return value;
    }

    private static double readPositive(String property, double defaultValue) {
        double value = readNonNegative(property, defaultValue);
        if (value <= 0.0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }
}
