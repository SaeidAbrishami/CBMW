package org.workflowsim.cbmw.baselines;

/** Pure timing helpers for CEWB's existing spot/on-demand switching policy. */
final class CEWBTimingPolicy {

    private CEWBTimingPolicy() {}

    static double safeStart(double arrival, double latestStart,
                            double onDemandProvisioningDelay) {
        requireFinite("arrival", arrival);
        requireFinite("latestStart", latestStart);
        requireFiniteNonNegative("onDemandProvisioningDelay",
                onDemandProvisioningDelay);
        return Math.max(arrival, latestStart - onDemandProvisioningDelay);
    }

    static double predictedOnDemandFinish(double orderTime,
                                           double provisioningDelay,
                                           double estimatedRuntime) {
        requireFinite("orderTime", orderTime);
        requireFiniteNonNegative("provisioningDelay", provisioningDelay);
        requireFiniteNonNegative("estimatedRuntime", estimatedRuntime);
        return orderTime + provisioningDelay + estimatedRuntime;
    }

    static boolean predictedFallbackMiss(double orderTime,
                                         double provisioningDelay,
                                         double estimatedRuntime,
                                         double subDeadline) {
        requireFinite("subDeadline", subDeadline);
        return predictedOnDemandFinish(orderTime, provisioningDelay,
                estimatedRuntime) > subDeadline + 1e-9;
    }

    static double exactWakeDelay(double now, double safeStart) {
        requireFinite("now", now);
        requireFinite("safeStart", safeStart);
        return Math.max(0.0, safeStart - now);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        requireFinite(name, value);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
