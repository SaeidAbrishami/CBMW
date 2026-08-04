package org.workflowsim.cbmw.baselines;

import org.workflowsim.cbmw.PaperRuntimeModel;

/** Paper NOSF execution-time approximation for a normally distributed task. */
final class NOSFRuntimeModel {

    /** Shared with CBMW so algorithm comparisons use the same uncertainty. */
    static final double STDDEV_RATIO = PaperRuntimeModel.STDDEV_RATIO;

    private NOSFRuntimeModel() {}

    /** The normal-distribution specialization of Eq. (1): w(lambda) = mu + sigma. */
    static double weight(double meanExecutionTime) {
        if (meanExecutionTime <= 0.0) return meanExecutionTime;
        return meanExecutionTime * (1.0 + STDDEV_RATIO);
    }

    static double sigma(double meanExecutionTime) {
        return Math.max(0.0, meanExecutionTime) * STDDEV_RATIO;
    }
}
