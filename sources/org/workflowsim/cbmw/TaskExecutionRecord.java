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
    private final double executionTime;
    private final double subDeadlineTime;
    private final double workflowAlpha;
    private final List<Integer> parentIds;

    private int vmId = -1;
    private double readyTime = Double.NaN;
    private double submitTime = Double.NaN;
    private double startTime = Double.NaN;
    private double finishTime = Double.NaN;
    private String vmType = "";
    private String status = "";

    public TaskExecutionRecord(int taskId, String taskName, int workflowId,
                               String workflowPath, double executionTime,
                               double subDeadlineTime, double workflowAlpha,
                               List<Integer> parentIds) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.workflowId = workflowId;
        this.workflowPath = workflowPath;
        this.executionTime = executionTime;
        this.subDeadlineTime = subDeadlineTime;
        this.workflowAlpha = workflowAlpha;
        this.parentIds = new ArrayList<>(parentIds);
    }

    public int getTaskId() { return taskId; }
    public String getTaskName() { return taskName; }
    public int getWorkflowId() { return workflowId; }
    public String getWorkflowPath() { return workflowPath; }
    public int getVmId() { return vmId; }
    public double getReadyTime() { return readyTime; }
    public double getSubmitTime() { return submitTime; }
    public double getExecutionTime() { return executionTime; }
    public double getStartTime() { return startTime; }
    public double getFinishTime() { return finishTime; }
    public double getSubDeadlineTime() { return subDeadlineTime; }
    public double getWorkflowAlpha() { return workflowAlpha; }
    public String getVmType() { return vmType; }
    public String getStatus() { return status; }
    public List<Integer> getParentIds() { return Collections.unmodifiableList(parentIds); }

    public void markReady(double time) {
        if (Double.isNaN(readyTime)) readyTime = time;
    }

    public void markSubmitted(double time, int vmId, String vmType) {
        this.submitTime = time;
        this.vmId = vmId;
        this.vmType = vmType;
    }

    public void markFinished(double startTime, double finishTime, String status) {
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.status = status;
    }
}
