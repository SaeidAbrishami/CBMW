package org.workflowsim.cbmw;

/** Lifecycle accounting for an on-demand VM/container. */
public class OnDemandInstanceRecord {
    private final int vmId;
    private double orderTime = Double.NaN;
    private double readyTime = Double.NaN;
    private double launchTime = Double.NaN;
    private double destroyTime = Double.NaN;

    public OnDemandInstanceRecord(int vmId) {
        this.vmId = vmId;
    }

    public int getVmId() { return vmId; }
    public double getOrderTime() { return orderTime; }
    public double getReadyTime() { return readyTime; }
    public double getLaunchTime() { return launchTime; }
    public double getDestroyTime() { return destroyTime; }

    public void markOrdered(double orderTime, double readyTime) {
        if (Double.isNaN(this.orderTime)) this.orderTime = orderTime;
        this.readyTime = readyTime;
    }

    public void markLaunched(double launchTime) {
        if (Double.isNaN(this.launchTime)) this.launchTime = launchTime;
    }

    public void markDestroyed(double destroyTime) {
        this.destroyTime = destroyTime;
    }

    public double getUptime() {
        if (Double.isNaN(launchTime) || Double.isNaN(destroyTime)) return Double.NaN;
        return Math.max(0.0, destroyTime - launchTime);
    }
}
