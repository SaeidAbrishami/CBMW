package org.workflowsim.cbmw.baselines;

import java.util.Locale;

/** Central configuration for common-market and paper-aligned NOSF runs. */
public final class NOSFConfiguration {

    public enum Profile { COMMON_MARKET, PAPER_ALIGNED }

    /** NOSF paper and common-market comparisons both use hourly VM billing. */
    private static final double COMMON_BILLING_QUANTUM_SECONDS = 3600.0;
    private static final double PAPER_BILLING_QUANTUM_SECONDS = 3600.0;

    private NOSFConfiguration() {}

    public static Profile profile() {
        String configured = System.getProperty(
                "nosf.profile", Profile.COMMON_MARKET.name())
                .trim().toUpperCase(Locale.US).replace('-', '_');
        if ("PAPER".equals(configured) || "PAPER_REFERENCE".equals(configured)) {
            configured = Profile.PAPER_ALIGNED.name();
        }
        return Profile.valueOf(configured);
    }

    public static String profileName() {
        return profile().name();
    }

    public static boolean isPaperAligned() {
        return profile() == Profile.PAPER_ALIGNED;
    }

    public static int defaultRepetitions() {
        return isPaperAligned() ? 30 : 1;
    }

    static double billingQuantumSeconds() {
        double defaultValue = isPaperAligned()
                ? PAPER_BILLING_QUANTUM_SECONDS
                : COMMON_BILLING_QUANTUM_SECONDS;
        double value = Double.parseDouble(System.getProperty(
                "nosf.billing.quantum.sec", Double.toString(defaultValue)));
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    "nosf.billing.quantum.sec must be finite and positive");
        }
        return value;
    }

    static String defaultTransferMode() {
        return isPaperAligned()
                ? NOSFTransferModel.Mode.PAPER_NETWORK.name()
                : NOSFTransferModel.Mode.COMMON_SHARED_STORAGE.name();
    }
}
