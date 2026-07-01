package org.workflowsim.cbmw;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.Task;

/** Holds per-workflow state across planning, scheduling, and result collection. */
public class WorkflowRecord {
    private final int workflowId;
    private final String daxPath;
    private final double arrivalTime;

    private double criticalPathLength;
    private double deadline;
    private boolean accepted;

    private List<Task> taskList;
    private final Map<Integer, Double> earliestStartTimes  = new HashMap<>();  // taskId -> estji
    private final Map<Integer, Double> earliestFinishTimes = new HashMap<>();  // taskId -> eftji
    private final Map<Integer, Double> latestStartTimes    = new HashMap<>();  // taskId -> lstji
    private final Map<Integer, Double> latestFinishTimes   = new HashMap<>();  // taskId -> lftji
    private final Map<Integer, Double> estimatedExecTimes  = new HashMap<>();  // taskId -> cetji
    private final Map<Integer, Double> scheduledStartTimes = new HashMap<>();  // taskId -> sstji
    private final Map<Integer, Integer> taskVmAssignment  = new HashMap<>();  // taskId -> vmId
    private final Set<Integer> completedTaskIds = new HashSet<>();

    private boolean deadlineMet;
    private double totalOnDemandCost;
    private double totalReservedCpuTime;
    private double completionTime = Double.MAX_VALUE;

    public WorkflowRecord(int workflowId, String daxPath, double arrivalTime) {
        this.workflowId = workflowId;
        this.daxPath = daxPath;
        this.arrivalTime = arrivalTime;
    }

    // --- core identity ---
    public int getWorkflowId() { return workflowId; }
    public String getDaxPath() { return daxPath; }
    public double getArrivalTime() { return arrivalTime; }

    // --- negotiation ---
    public double getCriticalPathLength() { return criticalPathLength; }
    public void setCriticalPathLength(double cp) { this.criticalPathLength = cp; }
    public double getDeadline() { return deadline; }
    public void setDeadline(double d) { this.deadline = d; }
    public boolean isAccepted() { return accepted; }
    public void setAccepted(boolean a) { this.accepted = a; }

    // --- tasks ---
    public List<Task> getTaskList() { return taskList; }
    public void setTaskList(List<Task> tasks) { this.taskList = tasks; }
    public int getTaskCount() { return taskList != null ? taskList.size() : 0; }
    public boolean markTaskCompleted(int taskId) { return completedTaskIds.add(taskId); }
    public int getCompletedTaskCount() { return completedTaskIds.size(); }
    public boolean isComplete() {
        return getTaskCount() > 0 && completedTaskIds.size() >= getTaskCount();
    }

    // --- paper estimated execution times used by negotiation/static planning ---
    public void setEstimatedExecTime(int taskId, double execTime) {
        estimatedExecTimes.put(taskId, execTime);
    }
    public double getEstimatedExecTime(Task task) {
        return estimatedExecTimes.getOrDefault(task.getCloudletId(),
                task.getCloudletLength() / HybridVmPool.RESERVED_MIPS);
    }
    public double getEstimatedExecTime(int taskId) {
        return estimatedExecTimes.getOrDefault(taskId, 0.0);
    }

    // --- static planner output ---
    public void setEST(int taskId, double est) { earliestStartTimes.put(taskId, est); }
    public double getEST(int taskId) {
        return earliestStartTimes.getOrDefault(taskId, arrivalTime);
    }
    public void setEFT(int taskId, double eft) { earliestFinishTimes.put(taskId, eft); }
    public double getEFT(int taskId) {
        return earliestFinishTimes.getOrDefault(taskId, arrivalTime);
    }
    public void setLST(int taskId, double lst) { latestStartTimes.put(taskId, lst); }
    public double getLST(int taskId) {
        return latestStartTimes.getOrDefault(taskId, Double.MAX_VALUE);
    }
    public void setLFT(int taskId, double lft) { latestFinishTimes.put(taskId, lft); }
    public double getLFT(int taskId) {
        return latestFinishTimes.getOrDefault(taskId, Double.MAX_VALUE);
    }
    /** Scheduled start time (sstji). */
    public void setScheduledStart(int taskId, double sst) { scheduledStartTimes.put(taskId, sst); }
    public double getScheduledStart(int taskId) {
        return scheduledStartTimes.getOrDefault(taskId, 0.0);
    }
    public void setAssignedVm(int taskId, int vmId) { taskVmAssignment.put(taskId, vmId); }
    public int getAssignedVm(int taskId) {
        return taskVmAssignment.getOrDefault(taskId, -1);
    }

    // --- results ---
    public boolean isDeadlineMet() { return deadlineMet; }
    public void setDeadlineMet(boolean met) { this.deadlineMet = met; }
    public double getTotalOnDemandCost() { return totalOnDemandCost; }
    public void addOnDemandCost(double cost) { this.totalOnDemandCost += cost; }
    public double getTotalReservedCpuTime() { return totalReservedCpuTime; }
    public void addReservedCpuTime(double t) { this.totalReservedCpuTime += t; }
    public double getCompletionTime() { return completionTime; }
    public void setCompletionTime(double t) { this.completionTime = t; }
}
