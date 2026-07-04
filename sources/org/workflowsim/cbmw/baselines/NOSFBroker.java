package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import org.workflowsim.cbmw.PaperRuntimeModel;
import org.workflowsim.cbmw.WorkflowRecord;
import org.workflowsim.utils.Parameters;

/**
 * Reference NOSF baseline: online EDF scheduling, reusable on-demand VMs,
 * minimum incremental billing cost, and completion feedback.
 */
public class NOSFBroker extends AbstractWorkflowBroker {

    static final double PROVISIONING_DELAY = readNonNegative(
            "nosf.provisioning.delay.sec", 0.0);
    static final double BILLING_QUANTUM = readPositive(
            "nosf.billing.quantum.sec", 60.0);

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
                "types=%d provisioningDelay=%.1f billingQuantum=%.1f",
                vmTypes.size(), PROVISIONING_DELAY, BILLING_QUANTUM);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    /** NOSF has no CBMW admission negotiation. */
    @Override
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        wfr.setCriticalPathLength(negotiation.computeCriticalPath(wfr));
        wfr.setAccepted(true);
        return true;
    }

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        workflowPlanner.preprocess(wfr, tasks);
        for (Task task : tasks) wfr.setPlannedVmType(task.getCloudletId(), "On-Demand");
        CBMWLogger.logf("NOSF-PREPROCESS",
                "wf=%d tasks=%d deadline=%.2f alpha=%.3f",
                wfr.getWorkflowId(), tasks.size(), wfr.getDeadline(),
                PaperRuntimeModel.QUANTILE);
        return true;
    }

    @Override
    protected boolean usesPeriodicScheduling() { return false; }

    @Override
    protected boolean managesOwnOnDemandLifecycle() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        double now = CloudSim.clock();
        launchAndStartReadyVms(now);

        List<Cloudlet> brokerReadyList = (List<Cloudlet>) getCloudletList();
        recordReadyQueue(brokerReadyList);
        List<Cloudlet> readyJobs = new ArrayList<>(brokerReadyList);
        readyJobs.sort(Comparator
                .comparingDouble((Cloudlet cl) -> getSubDeadline((Job) cl))
                .thenComparingDouble(cl -> getEst((Job) cl))
                .thenComparingInt(cl -> workflowIdForJob((Job) cl))
                .thenComparingInt(Cloudlet::getCloudletId));

        List<Cloudlet> assigned = new ArrayList<>();
        for (Cloudlet cl : readyJobs) {
            Job job = (Job) cl;
            if (allocatedJobs.contains(job.getCloudletId())) continue;
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
            if (wfr == null) continue;
            int taskId = primaryTaskId(job);
            double baseRuntime = wfr.getEstimatedExecTime(taskId);
            NOSFResourceSelector.Choice choice = selector.choose(now, baseRuntime,
                    wfr.getTaskCores(taskId), wfr.getTaskRamMb(taskId),
                    wfr.getLFT(taskId), vmStates, vmTypes);
            if (choice == null) {
                throw new IllegalStateException("No NOSF VM type can run task " + taskId
                        + "; configure nosf.vm.type.* capacity");
            }

            NOSFVmState state = choice.vm != null ? choice.vm
                    : createVm(choice.newType, taskId, now);
            assign(job, wfr, state, choice);
            assigned.add(cl);
            allocatedJobs.add(job.getCloudletId());
        }

        if (!assigned.isEmpty()) {
            brokerReadyList.removeAll(assigned);
            getCloudletSubmittedList().addAll(assigned);
            cloudletsSubmitted += assigned.size();
        }
        launchAndStartReadyVms(now);
    }

    private NOSFVmState createVm(NOSFVmType type, int taskId, double now) {
        CondorVM vm = vmPool.provisionOnDemandVm(getId(), taskId, type.cores,
                type.ramMb, type.mipsPerCore, type.pricePerSecond);
        double ready = now + PROVISIONING_DELAY;
        NOSFVmState state = new NOSFVmState(vm, type, now, ready);
        vmStates.add(state);
        stateByVmId.put(vm.getId(), state);
        accounting.markOnDemandOrdered(vm.getId(), now, ready);
        accounting.markTaskProvisioningOrdered(taskId, now, ready);
        if (PROVISIONING_DELAY > 0.0) {
            schedule(getId(), PROVISIONING_DELAY, WorkflowSimTags.CLOUDLET_UPDATE);
        }
        return state;
    }

    private void assign(Job job, WorkflowRecord wfr, NOSFVmState state,
                        NOSFResourceSelector.Choice choice) {
        int taskId = primaryTaskId(job);
        job.setVmId(state.vm.getId());
        if (!job.getTaskList().isEmpty()) job.getTaskList().get(0).setVmId(state.vm.getId());
        state.queue.add(job);
        state.plannedAvailableTime = choice.finish;
        state.plannedShutdownTime = Math.max(state.plannedShutdownTime, choice.finish);
        wfr.setAssignedVm(taskId, state.vm.getId());
        wfr.setScheduledStart(taskId, choice.start);
        if (!choice.feasible) accounting.markDeadlineRisk(taskId);
        CBMWLogger.logf("NOSF-ALLOCATE",
                "wf=%d task=%d subDeadline=%.2f start=%.2f finish=%.2f"
                        + " feasible=%s incrementalCost=$%.6f vm=%d reused=%s",
                wfr.getWorkflowId(), taskId, wfr.getLFT(taskId), choice.start,
                choice.finish, choice.feasible ? "yes" : "no",
                choice.incrementalCost, state.vm.getId(),
                choice.vm != null ? "yes" : "no");
    }

    private void launchAndStartReadyVms(double now) {
        for (NOSFVmState state : vmStates) {
            if (!state.launched && now + 1e-9 >= state.readyTime) {
                state.launched = true;
                state.vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
                vmPool.activateOnDemandContainer(state.vm.getId());
                accounting.markOnDemandLaunched(state.vm.getId(), now);
            }
            if (state.launched && state.running == null && !state.queue.isEmpty()) {
                startNext(state, now);
            }
        }
    }

    private void startNext(NOSFVmState state, double now) {
        Job job = state.queue.remove();
        int taskId = primaryTaskId(job);
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        if (wfr == null) return;
        double queueDelay = Parameters.getOverheadParams().getQueueDelay() != null
                ? Parameters.getOverheadParams().getQueueDelay(job) : 0.0;
        double actualRuntime = HybridVmPool.executionTimeSeconds(
                job.getCloudletLength(), wfr.getTaskCores(taskId),
                state.type.mipsPerCore);
        try {
            job.setResourceParameter(getId(), state.type.pricePerSecond);
            job.setSubmissionTime(now);
            job.setExecStartTime(now + queueDelay);
            job.setCloudletStatus(Cloudlet.INEXEC);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start NOSF task " + taskId, e);
        }
        state.running = job;
        state.vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
        vmPool.taskStarted(state.vm.getId(), taskId);
        accounting.markTaskSubmitted(job, "On-Demand");
        accounting.snapshotUtilization(vmPool);
        schedule(getId(), queueDelay + actualRuntime,
                WorkflowSimTags.ON_DEMAND_TASK_COMPLETE, job);
    }

    @Override
    protected void onTaskReturned(Cloudlet cl, boolean onDemand) {
        NOSFVmState state = stateByVmId.get(cl.getVmId());
        if (state == null) return;
        state.running = null;
        state.vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
        state.busyTime += cl.getActualCPUTime();
        state.completedTasks++;
        double now = CloudSim.clock();
        double actualCost = state.billedCost(now, BILLING_QUANTUM);
        double delta = Math.max(0.0, actualCost - state.chargedCost);
        state.chargedCost = actualCost;
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob((Job) cl));
        if (wfr != null) wfr.addOnDemandCost(delta);
        accounting.markOnDemandDestroyed(state.vm.getId(), now);
        rebaseQueuedPlan(state, now);
        CBMWLogger.logf("TASK-COMPLETE",
                "task=%d wf=%d vm=%d(NOSF reusable) actualCPU=%.4fs"
                        + " incrementalActualCost=$%.6f",
                cl.getCloudletId(), workflowIdForJob((Job) cl), cl.getVmId(),
                cl.getActualCPUTime(), delta);
    }

    private void rebaseQueuedPlan(NOSFVmState state, double now) {
        double available = now;
        for (Job queued : state.queue) {
            WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(queued));
            if (wfr == null) continue;
            int taskId = primaryTaskId(queued);
            wfr.setScheduledStart(taskId, available);
            available += state.type.runtime(wfr.getEstimatedExecTime(taskId));
        }
        state.plannedAvailableTime = available;
        state.plannedShutdownTime = Math.max(now, available);
    }

    @Override
    protected void onWorkflowTaskComplete(WorkflowRecord wfr, int taskId,
                                          double finishTime) {
        double previousEft = wfr.getEFT(taskId);
        workflowPlanner.feedback(wfr, taskId, finishTime);
        CBMWLogger.log("NOSF-FEEDBACK", String.format(Locale.US,
                "wf=%d task=%d predictedFinish=%.2f actualFinish=%.2f deviation=%.2f",
                wfr.getWorkflowId(), taskId, previousEft, finishTime,
                finishTime - previousEft));
    }

    private double getEst(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getEST(primaryTaskId(job)) : 0.0;
    }

    private double getSubDeadline(Job job) {
        WorkflowRecord wfr = activeWorkflows.get(workflowIdForJob(job));
        return wfr != null ? wfr.getLFT(primaryTaskId(job)) : Double.MAX_VALUE;
    }

    private static double readNonNegative(String property, double defaultValue) {
        double value = Double.parseDouble(System.getProperty(property,
                Double.toString(defaultValue)));
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(property + " must be finite and non-negative");
        }
        return value;
    }

    private static double readPositive(String property, double defaultValue) {
        double value = readNonNegative(property, defaultValue);
        if (value <= 0.0) throw new IllegalArgumentException(property + " must be positive");
        return value;
    }
}
