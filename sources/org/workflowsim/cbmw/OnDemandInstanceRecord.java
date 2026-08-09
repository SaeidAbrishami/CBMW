package org.workflowsim.cbmw;

/** Lifecycle accounting for an on-demand VM/container. */
public class OnDemandInstanceRecord {
    private final int vmId;
    private int cores;
    private int ramMb;
    private double orderTime = Double.NaN;
    private double readyTime = Double.NaN;
    private double launchTime = Double.NaN;
    private double destroyTime = Double.NaN;

    public OnDemandInstanceRecord(int vmId) {
        this.vmId = vmId;
    }

    public OnDemandInstanceRecord(int vmId, int cores, int ramMb) {
        this.vmId = vmId;
        setCapacity(cores, ramMb);
    }

    public int getVmId() { return vmId; }
    public int getCores() { return cores; }
    public int getRamMb() { return ramMb; }
    public double getOrderTime() { return orderTime; }
    public double getReadyTime() { return readyTime; }
    public double getLaunchTime() { return launchTime; }
    public double getDestroyTime() { return destroyTime; }

    public void setCapacity(int cores, int ramMb) {
        if (cores < 0 || ramMb < 0) {
            throw new IllegalArgumentException(
                    "On-demand capacity cannot be negative");
        }
        if ((this.cores != 0 || this.ramMb != 0)
                && (this.cores != cores || this.ramMb != ramMb)) {
            throw new IllegalStateException("On-demand VM " + vmId
                    + " capacity changed from " + this.cores + "/" + this.ramMb
                    + " to " + cores + "/" + ramMb);
        }
        this.cores = cores;
        this.ramMb = ramMb;
    }

    public void markOrdered(double orderTime, double readyTime) {
        requireFiniteNonNegative("order time", orderTime);
        requireFiniteNonNegative("ready time", readyTime);
        if (readyTime < orderTime) {
            throw new IllegalArgumentException(
                    "On-demand ready time precedes order time for VM " + vmId);
        }
        if (Double.isNaN(this.orderTime)) this.orderTime = orderTime;
        this.readyTime = readyTime;
    }

    public void markLaunched(double launchTime) {
        requireFiniteNonNegative("launch time", launchTime);
        if (Double.isFinite(orderTime) && launchTime < orderTime) {
            throw new IllegalArgumentException(
                    "On-demand launch time precedes order time for VM " + vmId);
        }
        if (Double.isNaN(this.launchTime)) this.launchTime = launchTime;
    }

    public void markDestroyed(double destroyTime) {
        requireFiniteNonNegative("destroy time", destroyTime);
        if (Double.isFinite(launchTime) && destroyTime < launchTime) {
            throw new IllegalArgumentException(
                    "On-demand destroy time precedes launch time for VM " + vmId);
        }
        this.destroyTime = destroyTime;
    }

    public double getUptime() {
        if (Double.isNaN(launchTime) || Double.isNaN(destroyTime)) return Double.NaN;
        return Math.max(0.0, destroyTime - launchTime);
    }

    /** Uptime clipped to one scenario measurement window. */
    public double getUptime(double measurementStart, double measurementEnd) {
        validateWindow(measurementStart, measurementEnd);
        if (!Double.isFinite(launchTime)) return 0.0;
        double effectiveDestroy = Double.isFinite(destroyTime)
                ? destroyTime : measurementEnd;
        double start = Math.max(measurementStart, launchTime);
        double end = Math.min(measurementEnd, effectiveDestroy);
        return Math.max(0.0, end - start);
    }

    public double getCapacityCoreSeconds(double measurementStart,
                                         double measurementEnd) {
        return cores * getUptime(measurementStart, measurementEnd);
    }

    public double getCapacityRamMbSeconds(double measurementStart,
                                          double measurementEnd) {
        return ramMb * getUptime(measurementStart, measurementEnd);
    }

    private void validateWindow(double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) {
            throw new IllegalArgumentException(
                    "Measurement window must be finite and ordered");
        }
    }

    private void requireFiniteNonNegative(String label, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(label
                    + " must be finite and non-negative for VM " + vmId);
        }
    }
}
