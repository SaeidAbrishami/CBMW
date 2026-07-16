package org.workflowsim.cbmw;

import org.apache.commons.math3.distribution.NormalDistribution;

/** Paper runtime-uncertainty parameters used by CBMW negotiation and planning. */
public final class PaperRuntimeModel {

    public static final double QUANTILE = readDouble(
            "cbmw.runtime.quantile", 0.99);
    public static final double STDDEV_RATIO = readDouble(
            "cbmw.runtime.stddev.ratio", 0.10);
    public static final double NEGOTIATION_BETA = readDouble(
            "cbmw.negotiation.beta", 1.0);
    public static final double NEGOTIATION_GAMMA = readDouble(
            "cbmw.negotiation.gamma", 1.0);

    private static final NormalDistribution STANDARD_NORMAL =
            new NormalDistribution(0.0, 1.0);
    private static final double QUANTILE_Z;

    static {
        if (!Double.isFinite(QUANTILE) || QUANTILE < 0.90 || QUANTILE >= 1.0) {
            throw new IllegalArgumentException(
                    "cbmw.runtime.quantile must be in [0.90, 1.0)");
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
        QUANTILE_Z = STANDARD_NORMAL.inverseCumulativeProbability(QUANTILE);
    }

    private PaperRuntimeModel() {}

    /** Returns cet = mu + z(alpha) * sigma, where sigma = ratio * mu. */
    public static double conservativeEstimate(double meanExecutionTime) {
        if (meanExecutionTime <= 0.0) return meanExecutionTime;
        double sigma = STDDEV_RATIO * meanExecutionTime;
        return meanExecutionTime + QUANTILE_Z * sigma;
    }

    public static double getQuantileZ() {
        return QUANTILE_Z;
    }

    private static double readDouble(String property, double defaultValue) {
        return Double.parseDouble(System.getProperty(
                property, Double.toString(defaultValue)));
    }
}
