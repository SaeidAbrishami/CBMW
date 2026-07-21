package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import org.workflowsim.Task;
import org.workflowsim.cbmw.WorkflowRecord;

/** Dependency-free invariant tests for CEWB's environment and timing helpers. */
public final class CEWBValidationTest {

    private CEWBValidationTest() {}

    public static void main(String[] args) {
        configureDeterministicMarket();
        testCapacityMatchedMarket();
        testTimingPolicy();
        testCriticalityPolicy();
        CEWBReferencePolicyValidationTest.runAll();
        testPricingPolicy();
        testInvalidRequests();
        System.out.println("CEWBValidationTest: PASS");
    }

    private static void testCriticalityPolicy() {
        Task entry = new Task(1, 10_000L);
        Task exit = new Task(2, 10_000L);
        entry.getChildList().add(exit);
        exit.getParentList().add(entry);
        WorkflowRecord workflow = new WorkflowRecord(0, "synthetic.xml", 0.0);
        workflow.setTaskList(Arrays.asList(entry, exit));
        workflow.setDeadline(40.0);
        workflow.setEstimatedExecTime(1, 10.0);
        workflow.setEstimatedExecTime(2, 10.0);

        CEWBCriticalityPolicy policy = new CEWBCriticalityPolicy();
        policy.preprocess(workflow, workflow.getTaskList());
        checkClose(20.0, workflow.getLFT(1), "entry proportional subdeadline");
        checkClose(40.0, workflow.getLFT(2), "exit subdeadline");

        workflow.setLFT(1, 5.0);
        workflow.setLFT(2, 40.0);
        Map<Integer, CEWBTaskDecision> decisions = policy.classify(
                workflow.getTaskList(), workflow, 0.0);
        check(decisions.get(1).getResourceClass()
                        == CEWBCriticalityPolicy.ON_DEMAND,
                "negative-slack task must use on-demand");
        check(decisions.get(2).getResourceClass()
                        == CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT,
                "relaxed task must use low-reliability spot");
    }

    private static void testPricingPolicy() {
        System.setProperty("cbmw.cewb.pricing.policy", "CONSTANT_PROFIT");
        System.setProperty("cbmw.cewb.pricing.profit.margin", "0.10");
        Task task = new Task(11, 10_000L);
        WorkflowRecord workflow = new WorkflowRecord(1, "pricing.xml", 0.0);
        workflow.setTaskList(Arrays.asList(task));
        workflow.setEstimatedExecTime(11, 10.0);
        CEWBPricingPolicy policy = new CEWBPricingPolicy();
        policy.quoteBeforeExecution(workflow);
        check(workflow.getOfferedPrice() > 0.0,
                "pre-execution CEWB quote must be positive");
        workflow.addOnDemandCost(0.02);
        workflow.addSpotCost(0.01);
        policy.settleAfterExecution(workflow);
        checkClose(0.033, workflow.getBrokerRevenue(),
                "constant-profit revenue");
        checkClose(0.003, workflow.getBrokerProfit(),
                "constant-profit settlement");
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
        for (int i = 0; i < 960; i++) {
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
        check(market.getActiveInstances() == 560,
                "idle shared spot instances must remain provisioned");
        check(market.getActiveCores() == 960,
                "idle shared spot cores must remain provisioned");

        boolean doubleReleaseRejected = false;
        try {
            market.release(offers.get(0));
        } catch (IllegalStateException expected) {
            doubleReleaseRejected = true;
        }
        check(doubleReleaseRejected, "double release must fail fast");
        market.terminateAll();
        check(market.getActiveInstances() == 0,
                "terminateAll must release physical instances");
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
