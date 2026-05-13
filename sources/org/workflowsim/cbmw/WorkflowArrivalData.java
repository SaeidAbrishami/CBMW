package org.workflowsim.cbmw;

/** Carries workflow arrival event data through the CloudSim event bus. */
public class WorkflowArrivalData {
    private final String daxPath;
    private final double arrivalTime;

    public WorkflowArrivalData(String daxPath, double arrivalTime) {
        this.daxPath = daxPath;
        this.arrivalTime = arrivalTime;
    }

    public String getDaxPath() { return daxPath; }
    public double getArrivalTime() { return arrivalTime; }
}
