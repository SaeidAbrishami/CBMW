package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free invariant tests for CEWB's environment and timing helpers. */
public final class CEWBValidationTest {

    private CEWBValidationTest() {}

    public static void main(String[] args) {
        configureDeterministicMarket();
        testCapacityMatchedMarket();
        testTimingPolicy();
        testInvalidRequests();
        System.out.println("CEWBValidationTest: PASS");
    }

    private static void configureDeterministicMarket() {
        System.setProperty("cbmw.cewb.spot.total.cores", "960");
        System.setProperty("cbmw.cewb.spot.price.factor.min", "1.0");
        System.setProperty("cbmw.cewb.spot.price.factor.max", "1.0");
        System.setProperty("cbmw.cewb.spot.min.success.prob", "0.0");
        System.setProperty("cbmw.cewb.spot.mtbi.sec", "1000000000");
    }

    private static void testCapacityMatchedMarket() {
        CEWBSpotMarket market = new CEWBSpotMarket(42L);
        check(market.getConfiguredTotalCores() == 960,
                "default capacity must match 960 reserved cores");
        check(market.getConfiguredTotalInstances() == 560,
                "320/160/80 class capacities must provide 560 instances");

        List<CEWBSpotMarket.Offer> offers = new ArrayList<>();
        for (int i = 0; i < 560; i++) {
            CEWBSpotMarket.Offer offer = market.acquire(
                    1, 1, 10.0, 10_000L, 0.0, 1_000.0, 0.000340);
            check(offer != null, "offer " + i + " unexpectedly unavailable");
            offers.add(offer);
        }
        check(market.getActiveInstances() == 560,
                "active instance count must equal configured capacity");
        check(market.getActiveCores() == 960,
                "active core count must equal matched capacity");
        check(market.getPeakActiveCores() == 960,
                "peak core telemetry must record saturation");
        check(market.acquire(1, 1, 10.0, 10_000L,
                0.0, 1_000.0, 0.000340) == null,
                "market must reject an allocation beyond capacity");
        check(market.getSaturatedCalls() == 1,
                "saturation telemetry must count the rejected request");

        for (CEWBSpotMarket.Offer offer : offers) market.release(offer);
        check(market.getActiveInstances() == 0,
                "all spot instances must be released");
        check(market.getActiveCores() == 0,
                "all spot cores must be released");

        boolean doubleReleaseRejected = false;
        try {
            market.release(offers.get(0));
        } catch (IllegalStateException expected) {
            doubleReleaseRejected = true;
        }
        check(doubleReleaseRejected, "double release must fail fast");
    }

    private static void testTimingPolicy() {
        checkClose(80.0, CEWBTimingPolicy.safeStart(0.0, 200.0, 120.0),
                "ordinary safe start");
        checkClose(100.0, CEWBTimingPolicy.safeStart(100.0, 150.0, 120.0),
                "safe start clamped to arrival");
        checkClose(230.0, CEWBTimingPolicy.predictedOnDemandFinish(
                80.0, 120.0, 30.0), "predicted fallback finish");
        check(!CEWBTimingPolicy.predictedFallbackMiss(
                80.0, 120.0, 30.0, 230.0),
                "finish exactly at subdeadline must be feasible");
        check(CEWBTimingPolicy.predictedFallbackMiss(
                81.0, 120.0, 30.0, 230.0),
                "late order must be diagnosed as a predicted miss");
        checkClose(2.25, CEWBTimingPolicy.exactWakeDelay(10.25, 12.50),
                "wake delay must preserve the exact safe-start time");
        checkClose(0.0, CEWBTimingPolicy.exactWakeDelay(13.0, 12.50),
                "past safe-start wake must be immediate");
    }

    private static void testInvalidRequests() {
        CEWBSpotMarket market = new CEWBSpotMarket(7L);
        boolean invalidRequestRejected = false;
        try {
            market.acquire(0, 1, 10.0, 10_000L,
                    0.0, 1_000.0, 0.000340);
        } catch (IllegalArgumentException expected) {
            invalidRequestRejected = true;
        }
        check(invalidRequestRejected, "invalid core request must fail fast");

        check(market.acquire(1, 1, 10.0, 10_000L,
                0.0, 5.0, 0.000340) == null,
                "deadline-infeasible spot request must return no offer");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkClose(double expected, double actual,
                                   String message) {
        if (Math.abs(expected - actual) > 1e-9) {
            throw new AssertionError(message + ": expected=" + expected
                    + " actual=" + actual);
        }
    }
}
