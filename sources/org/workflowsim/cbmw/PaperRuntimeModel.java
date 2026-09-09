package org.workflowsim.cbmw;

/** Paper runtime-uncertainty parameters used by CBMW negotiation and planning. */
public final class PaperRuntimeModel {

    public static final double PLANNING_ALPHA = readDouble(
            "cbmw.runtime.planning.alpha", 0.20);
    public static final double STDDEV_RATIO = readDouble(
            "cbmw.runtime.stddev.ratio", 0.05);
    public static final double NEGOTIATION_BETA = readDouble(
            "cbmw.negotiation.beta", 1.0);
    public static final double NEGOTIATION_GAMMA = readDouble(
            "cbmw.negotiation.gamma", 1.0);

    static {
        if (!Double.isFinite(PLANNING_ALPHA) || PLANNING_ALPHA < 0.0) {
            throw new IllegalArgumentException(
                    "cbmw.runtime.planning.alpha must be finite and >= 0");
        }
        if (!Double.isFinite(STDDEV_RATIO) || STDDEV_RATIO < 0.0) {
            throw new IllegalArgumentException(
                    "cbmw.runtime.stddev.ratio must be >= 0");
        }
        if (!Double.isFinite(NEGOTIATION_BETA) || NEGOTIATION_BETA < 1.0) {
            throw new IllegalArgumentException(
                    "cbmw.negotiation.beta must be >= 1");
        }
        if (!Double.isFinite(NEGOTIATION_GAMMA) || NEGOTIATION_GAMMA < 1.0) {
            throw new IllegalArgumentException(
                    "cbmw.negotiation.gamma must be >= 1");
        }
    }

    private PaperRuntimeModel() {}

    /** Returns cet = mu + alpha * mu for CBMW negotiation and planning. */
    public static double conservativeEstimate(double meanExecutionTime) {
        if (meanExecutionTime <= 0.0) return meanExecutionTime;
        return meanExecutionTime + PLANNING_ALPHA * meanExecutionTime;
    }

    private static double readDouble(String property, double defaultValue) {
        return Double.parseDouble(System.getProperty(
                property, Double.toString(defaultValue)));
    }
}
