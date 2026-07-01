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

    public void registerWorkflowTasks(WorkflowRecord wfr, List<Task> tasks, double alpha) {
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            double execTime = task.getCloudletLength() / HybridVmPool.RESERVED_MIPS;
            double subDeadline = wfr.getLFT(taskId);
            if (!Double.isFinite(subDeadline)) subDeadline = wfr.getDeadline();
            List<Integer> parentIds = new ArrayList<>();
            for (Task parent : task.getParentList()) parentIds.add(parent.getCloudletId());
            taskRecords.put(taskId, new TaskExecutionRecord(
                    taskId, task.getType(), wfr.getWorkflowId(), wfr.getDaxPath(),
                    execTime, subDeadline, alpha, parentIds));
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
            record.markSubmitted(CloudSim.clock(), cl.getVmId(),
                    onDemand ? "On-Demand" : "Reserved");
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
                pool.getActiveOnDemandCount() * HybridVmPool.ON_DEMAND_CORES,
                pool.getReservedVms().size() * HybridVmPool.RESERVED_RAM_MB,
                pool.getActiveOnDemandCount() * HybridVmPool.ON_DEMAND_RAM_MB,
                runningReservedTasks.size() * HybridVmPool.TASK_CORES,
                runningOnDemandTasks.size() * HybridVmPool.TASK_CORES,
                runningReservedTasks.size() * HybridVmPool.TASK_RAM_MB,
                runningOnDemandTasks.size() * HybridVmPool.TASK_RAM_MB));
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
}
