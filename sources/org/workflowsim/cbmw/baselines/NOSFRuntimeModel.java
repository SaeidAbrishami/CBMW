package org.workflowsim.cbmw.baselines;

import org.workflowsim.cbmw.PaperRuntimeModel;
import java.util.Locale;

/** Runtime estimates for the CBMW-aligned NOSF and paper-policy sensitivity. */
final class NOSFRuntimeModel {

    enum Estimator { CBMW_CONSERVATIVE, MU_PLUS_SIGMA }

    static final Estimator ESTIMATOR = Estimator.valueOf(System.getProperty(
            "nosf.runtime.estimator", "CBMW_CONSERVATIVE")
            .trim().toUpperCase(Locale.ROOT));

    /** Shared with CBMW so algorithm comparisons use the same uncertainty. */
    static final double STDDEV_RATIO = PaperRuntimeModel.STDDEV_RATIO;

    private NOSFRuntimeModel() {}

    /** Use the shared CBMW planning duration by default. */
    static double weight(double meanExecutionTime) {
        return weight(meanExecutionTime, ESTIMATOR);
    }

    static double weight(double meanExecutionTime, Estimator estimator) {
        if (meanExecutionTime <= 0.0) return meanExecutionTime;
        if (estimator == Estimator.MU_PLUS_SIGMA) {
            return meanExecutionTime + sigma(meanExecutionTime);
        }
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    static double sigma(double meanExecutionTime) {
        return Math.max(0.0, meanExecutionTime) * STDDEV_RATIO;
    }
}
