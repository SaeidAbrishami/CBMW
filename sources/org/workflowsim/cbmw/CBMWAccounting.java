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
    private final Set<Integer> runningSpotTasks = new HashSet<>();
    private final Set<Integer> deadlineRiskTasks = new HashSet<>();
    private final Map<Integer, List<Integer>> childrenByParentTask = new HashMap<>();
    private double onDemandCapacityCoreSecondsOverride = Double.NaN;
    private double onDemandCapacityRamMbSecondsOverride = Double.NaN;

    public void registerWorkflowTasks(WorkflowRecord wfr, List<Task> tasks,
                                      double deadlineTightness,
                                      boolean actualRuntimeAvailable,
                                      String workflowDisposition) {
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            double nominalRuntime = wfr.getNominalExecTime(taskId);
            // The bundled TXT samples use a bounded uniform ±20% model;
            // its standard deviation is alpha*mu/sqrt(3). The optional
            // resampling path retains its configured normal ratio.
            double runtimeStddev = nominalRuntime
                    * (ExperimentRunContext.shouldResampleRuntimes()
                        ? PaperRuntimeModel.STDDEV_RATIO
                        : 0.20 / Math.sqrt(3.0));
            double conservativeRuntime =
                    PaperRuntimeModel.conservativeEstimate(nominalRuntime);
            double planningRuntime = wfr.getEstimatedExecTime(taskId);
            double actualRuntime = actualRuntimeAvailable
                    ? HybridVmPool.executionTimeSeconds(
                            task.getCloudletLength(), wfr.getTaskCores(taskId),
                            HybridVmPool.RESERVED_MIPS)
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
            String plannedVmType = wfr.hasPlannedVmType(taskId)
                    ? wfr.getPlannedVmType(taskId)
                    : plannedVmId == null ? "Unassigned"
                    : plannedVmId < 0 ? "On-Demand" : "Reserved";
            List<Integer> parentIds = new ArrayList<>();
            for (Task parent : task.getParentList()) {
                int parentId = parent.getCloudletId();
                parentIds.add(parentId);
                childrenByParentTask.computeIfAbsent(parentId,
                        ignored -> new ArrayList<>()).add(taskId);
            }
            TaskExecutionRecord record = new TaskExecutionRecord(
                    taskId, task.getType(), wfr.getWorkflowId(), wfr.getDaxPath(),
                    wfr.getArrivalTime(),
                    workflowDisposition, wfr.getTaskCores(taskId),
                    wfr.getTaskRamMb(taskId), wfr.getTaskResourceSource(taskId),
                    nominalRuntime, runtimeStddev,
                    conservativeRuntime, planningRuntime, actualRuntime,
                    est, eft, lst, lft, scheduledStart, subDeadline,
                    deadlineTightness, plannedVmId, plannedVmType, parentIds);
            if (parentIds.isEmpty()) record.markDependencyReady(wfr.getArrivalTime());
            taskRecords.put(taskId, record);
        }
    }

    public void markReadyQueue(List<Cloudlet> readyJobs) {
        for (Cloudlet cl : readyJobs) {
            markTaskReady(cl);
        }
    }

    public void markTaskReady(Cloudlet cloudlet) {
        TaskExecutionRecord record = taskRecords.get(primaryTaskId(cloudlet));
        if (record != null) record.markReady(CloudSim.clock());
    }

    public void markTaskSubmitted(Cloudlet cl, boolean onDemand) {
        markTaskSubmitted(cl, onDemand ? "On-Demand" : "Reserved");
    }

    public void markTaskSubmitted(Cloudlet cl, String vmType) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) {
            record.markReady(CloudSim.clock());
            record.markSubmitted(CloudSim.clock(), cl.getVmId(), vmType,
                    schedulingReason(record, cl.getVmId(), vmType));
        }
        if ("On-Demand".equals(vmType)) runningOnDemandTasks.add(taskId);
        else if ("Spot".equals(vmType)) runningSpotTasks.add(taskId);
        else runningReservedTasks.add(taskId);
    }

    public void markTaskPrice(int taskId, double pricePerSecond) {
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) record.setActualVmPricePerSecond(pricePerSecond);
    }

    public void markTaskConfiguration(int taskId, String vmName,
                                      double pricePerSecond) {
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) {
            record.setActualVmName(vmName);
            record.setActualVmPricePerSecond(pricePerSecond);
        }
    }

    public void markTaskFinished(Cloudlet cl, boolean onDemand) {
        markTaskFinished(cl, onDemand ? "On-Demand" : "Reserved");
    }

    public void markTaskFinished(Cloudlet cl, String vmType) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) {
            double finish = cl.getFinishTime() > 0 ? cl.getFinishTime() : CloudSim.clock();
            double start = cl.getExecStartTime() > 0 ? cl.getExecStartTime()
                    : Math.max(0.0, finish - cl.getActualCPUTime());
            String status = cl.getCloudletStatus() == Cloudlet.SUCCESS ? "SUCCESS" : "FAILED";
            record.markFinished(start, finish, status);
            for (int childId : childrenByParentTask.getOrDefault(taskId,
                    Collections.emptyList())) {
                TaskExecutionRecord child = taskRecords.get(childId);
                if (child != null) child.markDependencyReady(finish);
            }
        }
        if ("On-Demand".equals(vmType)) runningOnDemandTasks.remove(taskId);
        else if ("Spot".equals(vmType)) runningSpotTasks.remove(taskId);
        else runningReservedTasks.remove(taskId);
    }

    public void markTaskInterrupted(Cloudlet cl) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) record.markInterrupted();
        runningSpotTasks.remove(taskId);
    }

    /** Records a reserved-task preemption and returns it to ready state. */
    public void markReservedTaskPreempted(Cloudlet cl) {
        int taskId = primaryTaskId(cl);
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) record.markInterrupted();
        runningReservedTasks.remove(taskId);
    }

    public void markDeadlineRisk(int taskId) {
        deadlineRiskTasks.add(taskId);
    }

    public int getDeadlineRiskTaskCount() {
        return deadlineRiskTasks.size();
    }

    public int getProvisionedOnDemandVmCount() {
        return onDemandRecords.size();
    }

    /** Reference NOSF-style aggregate VM utilization: total busy / total active. */
    public double getOnDemandVmUtilization() {
        double busy = 0.0;
        for (TaskExecutionRecord record : taskRecords.values()) {
            if ("On-Demand".equals(record.getVmType())
                    && Double.isFinite(record.getExecutionTime())) {
                busy += record.getExecutionTime() * record.getTaskCores();
            }
        }
        if (Double.isFinite(onDemandCapacityCoreSecondsOverride)) {
            return onDemandCapacityCoreSecondsOverride > 0.0
                    ? busy / onDemandCapacityCoreSecondsOverride : 0.0;
        }
        double active = 0.0;
        for (OnDemandInstanceRecord record : onDemandRecords.values()) {
            double uptime = record.getUptime();
            if (Double.isFinite(uptime)) active += uptime * record.getCores();
        }
        if (active <= 0.0) {
            // Backward-compatible fallback for algorithm adapters that have not
            // yet supplied physical VM capacity.
            for (OnDemandInstanceRecord record : onDemandRecords.values()) {
                double uptime = record.getUptime();
                if (Double.isFinite(uptime)) active += uptime;
            }
        }
        return active > 0.0 ? busy / active : 0.0;
    }

    /** Supplies the physical capacity denominator for shared multi-core pools. */
    public void setOnDemandCapacityCoreSeconds(double capacityCoreSeconds) {
        if (!Double.isFinite(capacityCoreSeconds) || capacityCoreSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "On-demand capacity core-seconds must be finite and non-negative");
        }
        this.onDemandCapacityCoreSecondsOverride = capacityCoreSeconds;
    }

    public void setOnDemandCapacityRamMbSeconds(double capacityRamMbSeconds) {
        if (!Double.isFinite(capacityRamMbSeconds) || capacityRamMbSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "On-demand capacity RAM-seconds must be finite and non-negative");
        }
        this.onDemandCapacityRamMbSecondsOverride = capacityRamMbSeconds;
    }

    public void setOnDemandCapacitySeconds(double capacityCoreSeconds,
                                           double capacityRamMbSeconds) {
        setOnDemandCapacityCoreSeconds(capacityCoreSeconds);
        setOnDemandCapacityRamMbSeconds(capacityRamMbSeconds);
    }

    public void markTaskProvisioningOrdered(Cloudlet cl, double orderTime,
                                             double readyTime) {
        markTaskProvisioningOrdered(primaryTaskId(cl), orderTime, readyTime);
    }

    public void markTaskProvisioningOrdered(int taskId, double orderTime,
                                             double readyTime) {
        TaskExecutionRecord record = taskRecords.get(taskId);
        if (record != null) record.markProvisioningOrdered(orderTime, readyTime);
    }

    public void markWorkflowComplete(WorkflowRecord wfr, double alpha) {
        workflowRecords.add(new WorkflowCompletionRecord(
                wfr.getWorkflowId(), wfr.getDaxPath(), "COMPLETED",
                wfr.getArrivalTime(), wfr.getCompletionTime(),
                wfr.getCriticalPathLength(), wfr.getDeadline(),
                wfr.isDeadlineMet(), alpha, wfr.isDeadlineFeasible(),
                wfr.getEstimatedRawCost(), wfr.getPriceMarkupGamma(),
                wfr.getOfferedPrice(), wfr.getBrokerRevenue(),
                wfr.getBrokerProfit(), wfr.isPriceAccepted()));
    }

    public void markOnDemandOrdered(int vmId, double orderTime, double readyTime) {
        onDemandRecords.computeIfAbsent(vmId, OnDemandInstanceRecord::new)
                .markOrdered(orderTime, readyTime);
    }

    public void markOnDemandOrdered(int vmId, int cores, int ramMb,
                                    double orderTime, double readyTime) {
        OnDemandInstanceRecord record = onDemandRecords.computeIfAbsent(
                vmId, unused -> new OnDemandInstanceRecord(vmId, cores, ramMb));
        record.setCapacity(cores, ramMb);
        record.markOrdered(orderTime, readyTime);
    }

    public void registerOnDemandCapacity(int vmId, int cores, int ramMb) {
        OnDemandInstanceRecord record = onDemandRecords.computeIfAbsent(
                vmId, unused -> new OnDemandInstanceRecord(vmId, cores, ramMb));
        record.setCapacity(cores, ramMb);
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
        return record.getLaunchTime() + Math.max(
                HybridVmPool.ON_DEMAND_MIN_BILLING_SECONDS,
                Math.ceil(Math.max(0.0,
                        actualDestroyTime - record.getLaunchTime() - 1e-9)));
    }

    public double getOnDemandUptime(int vmId) {
        OnDemandInstanceRecord record = onDemandRecords.get(vmId);
        return record != null ? record.getUptime() : Double.NaN;
    }

    public void snapshotUtilization(HybridVmPool pool) {
        recordUtilizationSnapshot(new UtilizationSnapshot(
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

    /** Adds a generic resource state for shared or algorithm-specific pools. */
    public void recordUtilizationSnapshot(UtilizationSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Utilization snapshot is required");
        }
        if (!utilizationSnapshots.isEmpty()) {
            double previous = utilizationSnapshots
                    .get(utilizationSnapshots.size() - 1).getTime();
            if (snapshot.getTime() < previous) {
                throw new IllegalArgumentException(
                        "Utilization snapshots must be chronological");
            }
        }
        utilizationSnapshots.add(snapshot);
    }

    public ResourceAccountingSummary summarizeResources(double measurementStart,
                                                        double measurementEnd) {
        return ResourceAccountingSummary.from(utilizationSnapshots,
                new ArrayList<>(onDemandRecords.values()),
                measurementStart, measurementEnd,
                onDemandCapacityCoreSecondsOverride,
                onDemandCapacityRamMbSecondsOverride);
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

    /** Fraction of executed CPU work (allocated cores times actual seconds). */
    public double getCpuWorkShare(String resourceType) {
        double selected = 0.0;
        double total = 0.0;
        for (TaskExecutionRecord record : taskRecords.values()) {
            if (!Double.isFinite(record.getFinishTime())) continue;
            double work = record.getExecutionTime() * record.getTaskCores();
            if (!Double.isFinite(work) || work < 0.0) continue;
            total += work;
            if (resourceType.equals(record.getVmType())) selected += work;
        }
        return total > 0.0 ? selected / total : 0.0;
    }

    public double getSpotUsageRatio() {
        double spotTime = 0.0;
        double totalTime = 0.0;
        for (TaskExecutionRecord record : taskRecords.values()) {
            if (!Double.isFinite(record.getFinishTime())) continue;
            double execTime = record.getExecutionTime();
            if (!Double.isFinite(execTime)) continue;
            totalTime += execTime;
            if ("Spot".equals(record.getVmType())) spotTime += execTime;
        }
        return totalTime > 0.0 ? spotTime / totalTime : 0.0;
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
        String plannedVmType = record.getPlannedVmType();
        if (TaskExecutionRecord.SPOT_CANDIDATE.equals(plannedVmType)) {
            if ("Spot".equals(actualVmType)) return "CEWB_SPOT_SELECTION";
            if ("On-Demand".equals(actualVmType)) return "CEWB_ON_DEMAND_FALLBACK";
            return "CEWB_RESOURCE_CHANGE";
        }
        Integer plannedVmId = record.getPlannedVmId();
        if (plannedVmId == null) return "DYNAMIC_ASSIGNMENT";
        if ("Spot".equals(actualVmType)) return "CEWB_SPOT_SELECTION";
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
