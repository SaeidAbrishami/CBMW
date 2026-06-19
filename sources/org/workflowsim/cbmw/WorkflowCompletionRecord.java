package org.workflowsim.cbmw;

/** Per-workflow completion accounting used by detailed result exports. */
public class WorkflowCompletionRecord {
    private final int workflowId;
    private final String workflowPath;
    private final String status;
    private final double startTime;
    private final double finishTime;
    private final double criticalPathTime;
    private final double deadline;
    private final boolean metDeadline;
    private final double alpha;

    public WorkflowCompletionRecord(int workflowId, String workflowPath, String status,
                                    double startTime, double finishTime,
                                    double criticalPathTime, double deadline,
                                    boolean metDeadline, double alpha) {
        this.workflowId = workflowId;
        this.workflowPath = workflowPath;
        this.status = status;
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.criticalPathTime = criticalPathTime;
        this.deadline = deadline;
        this.metDeadline = metDeadline;
        this.alpha = alpha;
    }

    public int getWorkflowId() { return workflowId; }
    public String getWorkflowPath() { return workflowPath; }
    public String getStatus() { return status; }
    public double getStartTime() { return startTime; }
    public double getFinishTime() { return finishTime; }
    public double getCriticalPathTime() { return criticalPathTime; }
    public double getDeadline() { return deadline; }
    public boolean isMetDeadline() { return metDeadline; }
    public double getAlpha() { return alpha; }
}
