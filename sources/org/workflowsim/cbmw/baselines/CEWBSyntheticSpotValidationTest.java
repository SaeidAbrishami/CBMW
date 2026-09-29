package org.workflowsim.cbmw.baselines;

import java.util.Map;

/** Verify synthetic Spot pricing and interruption at the physical VM level. */
public final class CEWBSyntheticSpotValidationTest {
    public static void main(String[] args) {
        System.setProperty("cbmw.cewb.spot.mode", "SYNTHETIC");
        System.setProperty("cbmw.cewb.spot.startup.sec", "60");
        System.setProperty("cbmw.cewb.spot.synthetic.capacity", "1");
        try {
            System.setProperty("cbmw.cewb.spot.synthetic.reclamation.hourly", "0");
            CEWBSpotMarket noReclamation = new CEWBSpotMarket(7L);
            CEWBSpotMarket.Offer stable = noReclamation.acquire(1, 512,
                    30, 30_000, 0, 500, 1.536 / 3600,
                    CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT);
            if (stable == null || stable.willBeInterrupted()
                    || stable.getPricePerSecond() * 3600 < 1.536 * .30
                    || stable.getPricePerSecond() * 3600 > 1.536 * .70
                    || !noReclamation.isPhysicalMode()) {
                throw new AssertionError("Expected a discounted, interruption-free physical VM");
            }
            CEWBSpotMarket.Offer reused = noReclamation.acquire(1, 512, 30,
                    30_000, 0, 500, 1.536 / 3600, 3);
            if (reused == null || reused.getInstanceId() != stable.getInstanceId()) {
                throw new AssertionError("Low-priority task should reuse the single Spot VM");
            }
            noReclamation.recordUsage(stable, 1, 0, 90);
            noReclamation.release(stable);
            noReclamation.recordUsage(reused, 2, 0, 90);
            noReclamation.release(reused);
            Map<Integer, Double> settled = noReclamation.settlePhysicalCosts(90);
            near(settled.get(1) + settled.get(2),
                    60 * stable.getPricePerSecond());
            near(CEWBSpotSyntheticPricing.billInstance(60, 90,
                    stable.getPricePerSecond(), false),
                    60 * stable.getPricePerSecond());
            near(CEWBSpotSyntheticPricing.billInstance(60, 90,
                    stable.getPricePerSecond(), true), 0);
            near(CEWBSpotSyntheticPricing.billInstance(60, 3700,
                    stable.getPricePerSecond(), true),
                    3640 * stable.getPricePerSecond());

            System.setProperty("cbmw.cewb.spot.synthetic.capacity", "30");
            System.setProperty("cbmw.cewb.spot.synthetic.discount.min", "0.5");
            System.setProperty("cbmw.cewb.spot.synthetic.discount.max", "0.5");
            CEWBSpotMarket classPrices = new CEWBSpotMarket(7L);
            CEWBSpotMarket.Offer middle = classPrices.acquire(1, 512,
                    30, 30_000, 0, 500, 1.536 / 3600, 3);
            if (middle == null || !middle.getTypeName().equals("standard")) {
                throw new AssertionError("50% discount must exceed economy maximum, but fit standard");
            }
            CEWBSpotMarket.Offer critical = classPrices.acquire(1, 512,
                    30, 30_000, 0, 500, 1.536 / 3600, 1);
            if (critical == null || !critical.getTypeName().equals("performance")) {
                throw new AssertionError("Class 1 must use the high-maximum-price Spot pool");
            }
            System.clearProperty("cbmw.cewb.spot.synthetic.discount.min");
            System.clearProperty("cbmw.cewb.spot.synthetic.discount.max");

            System.setProperty("cbmw.cewb.spot.synthetic.reclamation.hourly", "0.40");
            CEWBSpotMarket highRisk = new CEWBSpotMarket(7L);
            CEWBSpotMarket.Offer interrupted = highRisk.acquire(1, 512,
                    20000, 20_000_000, 0, 30000, 1.536 / 3600, 1);
            if (interrupted == null || !interrupted.willBeInterrupted()) {
                throw new AssertionError("Long Spot task should be reclaimed under high risk");
            }
            near(interrupted.getSuccessProbability(),
                    Math.pow(0.6, 20000 / 3600.0));
            highRisk.revoke(interrupted);
            highRisk.recordUsage(interrupted, 2, 0,
                    interrupted.getEventDelaySeconds());
            Map<Integer, Double> costs = highRisk.settlePhysicalCosts(
                    interrupted.getEventDelaySeconds());
            if (costs.get(2) < 0) throw new AssertionError("Negative Spot charge");
            System.out.println("CEWB synthetic Spot validation passed");
        } finally {
            System.clearProperty("cbmw.cewb.spot.mode");
            System.clearProperty("cbmw.cewb.spot.startup.sec");
            System.clearProperty("cbmw.cewb.spot.synthetic.capacity");
            System.clearProperty("cbmw.cewb.spot.synthetic.reclamation.hourly");
            System.clearProperty("cbmw.cewb.spot.synthetic.discount.min");
            System.clearProperty("cbmw.cewb.spot.synthetic.discount.max");
        }
    }

    private static void near(double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
