package org.workflowsim.cbmw.baselines;

/** Paper NOSF execution-time approximation for a normally distributed task. */
final class NOSFRuntimeModel {

    static final double STDDEV_RATIO = readRatio();

    private NOSFRuntimeModel() {}

    /** The normal-distribution specialization of Eq. (1): w(lambda) = mu + sigma. */
    static double weight(double meanExecutionTime) {
        if (meanExecutionTime <= 0.0) return meanExecutionTime;
        return meanExecutionTime * (1.0 + STDDEV_RATIO);
    }

    static double sigma(double meanExecutionTime) {
        return Math.max(0.0, meanExecutionTime) * STDDEV_RATIO;
    }

    private static double readRatio() {
        String common = System.getProperty("cbmw.runtime.stddev.ratio", "0.05");
        double value = Double.parseDouble(System.getProperty(
                "nosf.runtime.stddev.ratio", common));
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    "nosf.runtime.stddev.ratio must be finite and non-negative");
        }
        return value;
    }
}
