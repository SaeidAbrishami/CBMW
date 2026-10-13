package org.workflowsim.cbmw;

/** Applies the one-second tolerance only when classifying completed workflows. */
public final class DeadlineOutcome {
    public static final double MISS_THRESHOLD_SECONDS = 1.0;

    private DeadlineOutcome() {}

    public static boolean isMiss(double completion, double deadline) {
        return Double.isFinite(completion) && completion < Double.MAX_VALUE
                && Double.isFinite(deadline)
                && completion - deadline >= MISS_THRESHOLD_SECONDS;
    }

    public static boolean isMet(double completion, double deadline) {
        return Double.isFinite(completion) && completion < Double.MAX_VALUE
                && Double.isFinite(deadline)
                && completion - deadline < MISS_THRESHOLD_SECONDS;
    }

    /** Return the full lateness for a reportable miss; otherwise return zero. */
    public static double missSeconds(double completion, double deadline) {
        return isMiss(completion, deadline) ? completion - deadline : 0.0;
    }
}
