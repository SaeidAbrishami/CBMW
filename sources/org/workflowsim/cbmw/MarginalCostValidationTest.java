package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Validates full500 - edge200 marginal-cost pairing and blank semantics. */
public final class MarginalCostValidationTest {

    private static final double EPS = 1e-9;

    private MarginalCostValidationTest() {}

    public static void main(String[] args) {
        CBMWResultCollector.ScenarioMetrics full = metrics(
                "arrival15_alpha2_full500", 500, 5.0, 1.0);
        CBMWResultCollector.ScenarioMetrics edge = metrics(
                "arrival15_alpha2_edge200", 200, 1.5, 0.5);

        List<CBMWResultCollector.ScenarioMetrics> rows =
                new ArrayList<>(Arrays.asList(full));
        CBMWResultCollector.applyPairedMarginalCosts(rows);
        assert Double.isNaN(full.marginalCost)
                : "Full row must remain blank without its edge row";

        rows.add(edge);
        CBMWResultCollector.applyPairedMarginalCosts(rows);
        assertClose(full.marginalCost, full.totalCost - edge.totalCost);
        assert Double.isNaN(edge.marginalCost)
                : "The edge200 row must not receive a marginal cost";

        String[] header = CBMWResultCollector.csvHeader().split(",", -1);
        int marginalIndex = Arrays.asList(header).indexOf("marginalCost");
        assert marginalIndex >= 0;
        String[] fullCsv = CBMWResultCollector.toCsvRow(full).split(",", -1);
        String[] edgeCsv = CBMWResultCollector.toCsvRow(edge).split(",", -1);
        assert !fullCsv[marginalIndex].isEmpty();
        assert edgeCsv[marginalIndex].isEmpty();

        System.out.println("MarginalCostValidationTest: PASS");
    }

    private static CBMWResultCollector.ScenarioMetrics metrics(
            String scenario, int workflowCount,
            double onDemandCost, double spotCost) {
        List<WorkflowRecord> workflows = new ArrayList<>();
        for (int i = 0; i < workflowCount; i++) {
            WorkflowRecord workflow = new WorkflowRecord(i + 1,
                    "workflow_" + i + ".xml", 0.0);
            workflow.setAccepted(true);
            workflow.setDeadline(10.0);
            workflow.setCompletionTime(5.0);
            workflow.setDeadlineMet(true);
            if (i == 0) {
                workflow.addOnDemandCost(onDemandCost);
                workflow.addSpotCost(spotCost);
            }
            workflows.add(workflow);
        }
        CBMWResultCollector collector = new CBMWResultCollector(
                workflows, new CBMWAccounting(), 0);
        return collector.toScenarioMetrics(scenario, "arrival15", "alpha2",
                "CEWB", 1.0, 2.0, 0, 123L, "COMMON_MARKET", 1.0, 0.0);
    }

    private static void assertClose(double actual, double expected) {
        assert Math.abs(actual - expected) <= EPS
                : "Expected " + expected + " but got " + actual;
    }
}
