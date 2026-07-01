package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-task execution accounting used by detailed result exports. */
public class TaskExecutionRecord {
    private final int taskId;
    private final String taskName;
    private final int workflowId;
    private final String workflowPath;
    private final String workflowDisposition;
    private final double nominalRuntime;
    private final double runtimeStddev;
    private final double conservativeRuntime;
    private final double planningRuntime;
    private final double actualRuntime;
    private final double earliestStartTime;
    private final double earliestFinishTime;
    private final double latestStartTime;
    private final double latestFinishTime;
    private final double scheduledStartTime;
    private final double subDeadlineTime;
    private final double deadlineTightness;
    private final Integer plannedVmId;
    private final String plannedVmType;
    private final List<Integer> parentIds;

    private int vmId = -1;
    private double readyTime = Double.NaN;
    private double submitTime = Double.NaN;
    private double provisioningOrderTime = Double.NaN;
    private double containerReadyTime = Double.NaN;
    private double startTime = Double.NaN;
    private double finishTime = Double.NaN;
    private String vmType = "";
    private String status = "";
    private String schedulingReason = "";

    public TaskExecutionRecord(int taskId, String taskName, int workflowId,
                               String workflowPath, String workflowDisposition,
                               double nominalRuntime, double runtimeStddev,
                               double conservativeRuntime, double planningRuntime,
                               double actualRuntime, double earliestStartTime,
                               double earliestFinishTime, double latestStartTime,
                               double latestFinishTime, double scheduledStartTime,
                               double subDeadlineTime, double deadlineTightness,
                               Integer plannedVmId, String plannedVmType,
                               List<Integer> parentIds) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.workflowId = workflowId;
        this.workflowPath = workflowPath;
        this.workflowDisposition = workflowDisposition;
        this.nominalRuntime = nominalRuntime;
        this.runtimeStddev = runtimeStddev;
        this.conservativeRuntime = conservativeRuntime;
        this.planningRuntime = planningRuntime;
        this.actualRuntime = actualRuntime;
        this.earliestStartTime = earliestStartTime;
        this.earliestFinishTime = earliestFinishTime;
        this.latestStartTime = latestStartTime;
        this.latestFinishTime = latestFinishTime;
        this.scheduledStartTime = scheduledStartTime;
        this.subDeadlineTime = subDeadlineTime;
        this.deadlineTightness = deadlineTightness;
        this.plannedVmId = plannedVmId;
        this.plannedVmType = plannedVmType;
        this.parentIds = new ArrayList<>(parentIds);
        if (!"ACCEPTED".equals(workflowDisposition)) {
            this.status = "NOT_EXECUTED";
            this.schedulingReason = workflowDisposition;
        }
    }

    public int getTaskId() { return taskId; }
    public String getTaskName() { return taskName; }
    public int getWorkflowId() { return workflowId; }
    public String getWorkflowPath() { return workflowPath; }
    public String getWorkflowDisposition() { return workflowDisposition; }
    public double getNominalRuntime() { return nominalRuntime; }
    public double getRuntimeStddev() { return runtimeStddev; }
    public double getConservativeRuntime() { return conservativeRuntime; }
    public double getPlanningRuntime() { return planningRuntime; }
    public double getActualRuntime() { return actualRuntime; }
    public double getEarliestStartTime() { return earliestStartTime; }
    public double getEarliestFinishTime() { return earliestFinishTime; }
    public double getLatestStartTime() { return latestStartTime; }
    public double getLatestFinishTime() { return latestFinishTime; }
    public double getScheduledStartTime() { return scheduledStartTime; }
    public int getVmId() { return vmId; }
    public double getReadyTime() { return readyTime; }
    public double getSubmitTime() { return submitTime; }
    public double getExecutionTime() { return actualRuntime; }
    public double getProvisioningOrderTime() { return provisioningOrderTime; }
    public double getContainerReadyTime() { return containerReadyTime; }
    public double getStartTime() { return startTime; }
    public double getFinishTime() { return finishTime; }
    public double getSubDeadlineTime() { return subDeadlineTime; }
    public double getDeadlineTightness() { return deadlineTightness; }
    public Integer getPlannedVmId() { return plannedVmId; }
    public String getPlannedVmType() { return plannedVmType; }
    public String getVmType() { return vmType; }
    public String getStatus() { return status; }
    public String getSchedulingReason() { return schedulingReason; }
    public List<Integer> getParentIds() { return Collections.unmodifiableList(parentIds); }

    public double getWaitingTime() {
        return finiteDifference(startTime, readyTime);
    }

    public double getQueueDelay() {
        return finiteDifference(startTime, submitTime);
    }

    public double getProvisioningDelay() {
        return finiteDifference(containerReadyTime, provisioningOrderTime);
    }

    public double getStartDeviation() {
        return finiteDifference(startTime, scheduledStartTime);
    }

    public double getSubDeadlineDeviation() {
        return finiteDifference(finishTime, subDeadlineTime);
    }

    public boolean isRescheduled() {
        if (plannedVmId == null || vmType.isEmpty()) return false;
        if ("On-Demand".equals(plannedVmType)) return !"On-Demand".equals(vmType);
        return plannedVmId != vmId;
    }

    public void markReady(double time) {
        if (Double.isNaN(readyTime)) readyTime = time;
    }

    public void markProvisioningOrdered(double orderTime, double readyTime) {
        this.provisioningOrderTime = orderTime;
        this.containerReadyTime = readyTime;
    }

    public void markSubmitted(double time, int vmId, String vmType,
                              String schedulingReason) {
        this.submitTime = time;
        this.vmId = vmId;
        this.vmType = vmType;
        this.schedulingReason = schedulingReason;
    }

    public void markFinished(double startTime, double finishTime, String status) {
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.status = status;
    }

    private static double finiteDifference(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                ? left - right : Double.NaN;
    }
}
