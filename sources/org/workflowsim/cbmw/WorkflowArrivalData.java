package org.workflowsim.cbmw;

/** Carries workflow arrival event data through the CloudSim event bus. */
public class WorkflowArrivalData {
    private final String daxPath;
    private final double arrivalTime;
    private final double userDeadline;

    public WorkflowArrivalData(String daxPath, double arrivalTime, double userDeadline) {
        this.daxPath = daxPath;
        this.arrivalTime = arrivalTime;
        this.userDeadline = userDeadline;
    }

    public String getDaxPath()     { return daxPath; }
    public double getArrivalTime() { return arrivalTime; }
    public double getUserDeadline() { return userDeadline; }
}
