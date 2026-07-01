package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.Job;
import org.workflowsim.Task;

/** Central in-memory accounting store for detailed CBMW experiment outputs. */
public class CBMWAccounting {
    private final Map<Integer, TaskExecutionRecord> taskRecords = new HashMap<>();
    private final List<WorkflowCompletionRecord> workflowRecords = new ArrayList<>();
    private final Map<Integer, OnDemandInstanceRecord> onDemandRecords = new HashMap<>();
    private final List<UtilizationSnapshot> utilizationSnapshots = new ArrayList<>();
    private final Set<Integer> runningReservedTasks = new HashSet<>();
    private final Set<Integer> runningOnDemandTasks = new HashSet<>();

    public void registerWorkflowTasks(WorkflowRecord wfr, List<Task> tasks,
                                      double deadlineTightness,
                                      boolean actualRuntimeAvailable,
                                      String workflowDisposition) {
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            double nominalRuntime = wfr.getNominalExecTime(taskId);
            double runtimeStddev = PaperRuntimeModel.STDDEV_RATIO * nominalRuntime;
            double conservativeRuntime =
                    PaperRuntimeModel.conservativeEstimate(nominalRuntime);
            double planningRuntime = wfr.getEstimatedExecTime(taskId);
            double actualRuntime = actualRuntimeAvailable
                    ? task.getCloudletLength() / HybridVmPool.RESERVED_MIPS
                    : Double.NaN;
            double est = wfr.hasEST(taskId) ? wfr.getEST(taskId) : Double.NaN;
            double eft = wfr.hasEFT(taskId) ? wfr.getEFT(taskId) : Double.NaN;
            double lst = wfr.hasLST(taskId) ? wfr.getLST(taskId) : Double.NaN;
            double lft = wfr.hasLFT(taskId) ? wfr.getLFT(taskId) : Double.NaN;
            double scheduledStart = wfr.hasScheduledStart(taskId)
                    ? wfr.getScheduledStart(taskId) : Double.NaN;
            double subDeadline = Double.isFinite(lft) ? lft : wfr.getDeadline();
            Integer plannedVmId = wfr.hasAssignedVm(taskId)
                    ? wfr.getAssignedVm(taskId) : null;
            String plannedVmType = plannedVmId == null ? "Unassigned"
                    : plannedVmId < 0 ? "On-Demand" : "Reserved";
            List<Integer> parentIds = new ArrayList<>();
            for (Task parent : task.getParentList()) parentIds.add(parent.getCloudletId());
            taskRecords.put(taskId, new TaskExecutionRecord(
                    taskId, task.getType(), wfr.getWorkflowId(), wfr.getDaxPath(),
                    workflowDisposition, wfr.getTaskCores(taskId),
                    wfr.getTaskRamMb(taskId), wfr.getTaskResourceSource(taskId),
                    nominalRuntime, runtimeStddev,
                    conservativeRuntime, planningRuntime, actualRuntime,
                    est, eft, lst, lft, scheduledStart, subDeadline,
                    deadlineTightness, plannedVmId, plannedVmType, parentIds));
        }
    }

    public void markReadyQueue(List<Cloudlet> readyJobs) {
        double now = CloudSim.clock();
        for (Cloudlet cl : readyJobs) {
            TaskExecutionRecord record = taskRecords.get(primaryTaskId(cl));
            if (record != null) record.markReady(now);
        }
    }

    public void markTaskSubmitted(Cloudlet cl, boolean onDemand) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) {
            record.markReady(CloudSim.clock());
            String actualVmType = onDemand ? "On-Demand" : "Reserved";
            record.markSubmitted(CloudSim.clock(), cl.getVmId(), actualVmType,
                    schedulingReason(record, cl.getVmId(), actualVmType));
        }
        if (onDemand) runningOnDemandTasks.add(taskId);
        else runningReservedTasks.add(taskId);
    }

    public void markTaskFinished(Cloudlet cl, boolean onDemand) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) {
            double finish = cl.getFinishTime() > 0 ? cl.getFinishTime() : CloudSim.clock();
            double start = cl.getExecStartTime() > 0 ? cl.getExecStartTime()
                    : Math.max(0.0, finish - cl.getActualCPUTime());
            String status = cl.getCloudletStatus() == Cloudlet.SUCCESS ? "SUCCESS" : "FAILED";
            record.markFinished(start, finish, status);
        }
        if (onDemand) runningOnDemandTasks.remove(taskId);
        else runningReservedTasks.remove(taskId);
    }

    public void markTaskProvisioningOrdered(Cloudlet cl, double orderTime,
                                            double readyTime) {
        TaskExecutionRecord record = taskRecords.get(primaryTaskId(cl));
        if (record != null) record.markProvisioningOrdered(orderTime, readyTime);
    }

    public void markWorkflowComplete(WorkflowRecord wfr, double alpha) {
        workflowRecords.add(new WorkflowCompletionRecord(
                wfr.getWorkflowId(), wfr.getDaxPath(), "COMPLETED",
                wfr.getArrivalTime(), wfr.getCompletionTime(),
                wfr.getCriticalPathLength(), wfr.getDeadline(),
                wfr.isDeadlineMet(), alpha));
    }

    public void markOnDemandOrdered(int vmId, double orderTime, double readyTime) {
        onDemandRecords.computeIfAbsent(vmId, OnDemandInstanceRecord::new)
                .markOrdered(orderTime, readyTime);
    }

    public void markOnDemandLaunched(int vmId, double launchTime) {
        onDemandRecords.computeIfAbsent(vmId, OnDemandInstanceRecord::new)
                .markLaunched(launchTime);
    }

    public void markOnDemandDestroyed(int vmId, double destroyTime) {
        onDemandRecords.computeIfAbsent(vmId, OnDemandInstanceRecord::new)
                .markDestroyed(destroyTime);
    }

    public double billableDestroyTime(int vmId, double actualDestroyTime) {
        OnDemandInstanceRecord record = onDemandRecords.get(vmId);
        if (record == null || !Double.isFinite(record.getLaunchTime())) {
            return actualDestroyTime;
        }
        return Math.max(actualDestroyTime,
                record.getLaunchTime() + HybridVmPool.ON_DEMAND_MIN_BILLING_SECONDS);
    }

    public double getOnDemandUptime(int vmId) {
        OnDemandInstanceRecord record = onDemandRecords.get(vmId);
        return record != null ? record.getUptime() : Double.NaN;
    }

    public void snapshotUtilization(HybridVmPool pool) {
        utilizationSnapshots.add(new UtilizationSnapshot(
                CloudSim.clock(),
                pool.getReservedVms().size() * HybridVmPool.RESERVED_CORES,
                pool.getActiveOnDemandCores(),
                pool.getReservedVms().size() * HybridVmPool.RESERVED_RAM_MB,
                pool.getActiveOnDemandRamMb(),
                pool.getTotalRunningCores(false),
                pool.getTotalRunningCores(true),
                pool.getTotalRunningRamMb(false),
                pool.getTotalRunningRamMb(true)));
    }

    public List<TaskExecutionRecord> getTaskRecords() {
        return Collections.unmodifiableList(new ArrayList<>(taskRecords.values()));
    }

    public List<WorkflowCompletionRecord> getWorkflowRecords() {
        return Collections.unmodifiableList(workflowRecords);
    }

    public List<OnDemandInstanceRecord> getOnDemandRecords() {
        return Collections.unmodifiableList(new ArrayList<>(onDemandRecords.values()));
    }

    public List<UtilizationSnapshot> getUtilizationSnapshots() {
        return Collections.unmodifiableList(utilizationSnapshots);
    }

    public double getOnDemandUsageRatio() {
        double onDemandTime = 0.0;
        double totalTime = 0.0;
        for (TaskExecutionRecord record : taskRecords.values()) {
            if (!Double.isFinite(record.getFinishTime())) continue;
            double execTime = record.getExecutionTime();
            if (!Double.isFinite(execTime)) continue;
            totalTime += execTime;
            if ("On-Demand".equals(record.getVmType())) {
                onDemandTime += execTime;
            }
        }
        return totalTime > 0.0 ? onDemandTime / totalTime : 0.0;
    }

    private int primaryTaskId(Cloudlet cl) {
        if (cl instanceof Job) {
            Job job = (Job) cl;
            if (!job.getTaskList().isEmpty()) {
                return job.getTaskList().get(0).getCloudletId();
            }
        }
        return cl.getCloudletId();
    }

    private String schedulingReason(TaskExecutionRecord record, int actualVmId,
                                    String actualVmType) {
        Integer plannedVmId = record.getPlannedVmId();
        if (plannedVmId == null) return "DYNAMIC_ASSIGNMENT";
        String plannedVmType = record.getPlannedVmType();
        if ("On-Demand".equals(plannedVmType)) {
            return "On-Demand".equals(actualVmType)
                    ? "PLANNED_ON_DEMAND" : "ADVANCED_TO_IDLE_RESERVED";
        }
        if ("On-Demand".equals(actualVmType)) {
            return "FALLBACK_ON_DEMAND_NO_RESERVED_CAPACITY";
        }
        return plannedVmId == actualVmId
                ? "PLANNED_RESERVED" : "RESCHEDULED_RESERVED_CAPACITY";
    }
}
