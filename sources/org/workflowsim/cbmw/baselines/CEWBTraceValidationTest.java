package org.workflowsim.cbmw.baselines;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Checks training/replay separation, maximum-price crossings, and physical VM billing. */
public final class CEWBTraceValidationTest {
    public static void main(String[] args) throws Exception {
        Path july = Files.createTempFile("cewb-july-", ".tsv");
        Path august = Files.createTempFile("cewb-august-", ".tsv");
        try {
            Files.writeString(july,
                    "2026-07-01T00:00:00Z\t0.3\n"
                    + "2026-07-02T00:00:00Z\t0.5\n");
            Files.writeString(august,
                    "2026-08-01T00:00:00Z\t0.4\n"
                    + "2026-08-01T00:01:40Z\t0.7\n"
                    + "2026-08-01T00:03:20Z\t0.5\n");
            CEWBSpotPriceTrace trace = new CEWBSpotPriceTrace(
                    august.toString(), july.toString(), 0);
            near(trace.trainingMinimumPerSecond() * 3600, 0.3);
            near(trace.nextExceeding(0, 0.6 / 3600), 100);
            near(trace.priceAt(101) * 3600, 0.7);
            near(trace.cost(60, 120) * 3600, 40 * 0.4 + 20 * 0.7);
            near(trace.billInstance(60, 120, false) * 3600, 60 * 0.4);
            near(trace.billInstance(60, 3700, false) * 3600,
                    3600 * 0.4 + 40 * 0.5);
            near(trace.billInstance(60, 100, true), 0);

            System.setProperty("cbmw.cewb.spot.trace.july", july.toString());
            System.setProperty("cbmw.cewb.spot.trace.august", august.toString());
            System.setProperty("cbmw.cewb.spot.startup.sec", "60");
            CEWBSpotMarket market = new CEWBSpotMarket(42L);
            CEWBSpotMarket.Offer offer = market.acquire(
                    1, 512, 1000, 1_000_000, 0, 5000, 1.5 / 3600,
                    CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT);
            if (offer == null || !offer.willBeInterrupted()
                    || !offer.getTypeName().equals("economy")) {
                throw new AssertionError("Price crossing should revoke the low-maximum VM");
            }
            near(offer.getEventDelaySeconds(), 100);
            market.revoke(offer);
            market.recordUsage(offer, 7, 0, 100);
            Map<Integer, Double> costs = market.settlePhysicalCosts(100);
            near(costs.get(7), 0);

            // The August price never reaches the highest CEWB class maximum;
            // an independent capacity clock must still reclaim a long VM.
            System.setProperty("cbmw.cewb.spot.trace.reclamation.hourly", "0.10");
            CEWBSpotMarket capacityMarket = new CEWBSpotMarket(42L);
            CEWBSpotMarket.Offer capacityOffer = capacityMarket.acquire(
                    1, 512, 100000, 100_000_000L, 0, 200000, 1.5 / 3600,
                    CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT);
            if (capacityOffer == null || !capacityOffer.willBeInterrupted()) {
                throw new AssertionError("10% one-hour capacity risk must reclaim long trace VM");
            }
            near(capacityOffer.getSuccessProbability(),
                    Math.pow(0.9, 100000 / 3600.0));
            capacityMarket.revoke(capacityOffer);
            capacityMarket.recordUsage(capacityOffer, 9, 0,
                    capacityOffer.getEventDelaySeconds());
            Map<Integer, Double> capacityCosts = capacityMarket.settlePhysicalCosts(
                    capacityOffer.getEventDelaySeconds());
            if (capacityCosts.get(9) < 0
                    || !capacityMarket.diagnosticSummary().contains("capacityReclaimedVMs=1")
                    || !capacityMarket.diagnosticSummary().contains("priceInterruptedVMs=0")) {
                throw new AssertionError("Capacity event should be separate from price crossing");
            }
            System.out.println("CEWB July/August trace validation passed");
        } finally {
            System.clearProperty("cbmw.cewb.spot.trace.july");
            System.clearProperty("cbmw.cewb.spot.trace.august");
            System.clearProperty("cbmw.cewb.spot.startup.sec");
            System.clearProperty("cbmw.cewb.spot.trace.reclamation.hourly");
            Files.deleteIfExists(july);
            Files.deleteIfExists(august);
        }
    }

    private static void near(double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-7) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
