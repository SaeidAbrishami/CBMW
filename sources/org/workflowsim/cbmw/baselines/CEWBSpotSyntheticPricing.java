package org.workflowsim.cbmw.baselines;

/** Synthetic discount fixed at VM launch; Linux EC2 Spot instance-second billing. */
final class CEWBSpotSyntheticPricing {
    private CEWBSpotSyntheticPricing() { }

    static double billInstance(double readyAt, double endedAt,
                               double pricePerSecond, boolean providerInterrupted) {
        if (!Double.isFinite(readyAt) || !Double.isFinite(endedAt)
                || endedAt < readyAt || !Double.isFinite(pricePerSecond)
                || pricePerSecond < 0) {
            throw new IllegalArgumentException("Invalid synthetic Spot rental interval");
        }
        double duration = endedAt - readyAt;
        if (providerInterrupted && duration < 3600.0) return 0.0;
        return Math.max(60.0, duration) * pricePerSecond;
    }
}
