package org.workflowsim.cbmw;

import java.util.Arrays;

/** Validates the workbook-requested fields exposed by each raw scenario row. */
public final class RequestedReportFieldsValidationTest {

    private static final double EPS = 1e-4;

    private RequestedReportFieldsValidationTest() {}

    public static void main(String[] args) {
        WorkflowRecord success = workflow(1, 0.0, 10.0, 5.0, true, 1.0, 0.5);
        WorkflowRecord miss = workflow(2, 0.0, 10.0, 12.0, false, 2.0, 0.5);

        CBMWAccounting accounting = new CBMWAccounting();
        accounting.markOnDemandOrdered(100, 2, 4096, 0.0, 1.0);
        accounting.markOnDemandLaunched(100, 1.0);
        accounting.markOnDemandDestroyed(100, 61.0);
        accounting.recordUtilizationSnapshot(new UtilizationSnapshot(
                0.0, 2 * HybridVmPool.RESERVED_CORES, 2,
                2 * HybridVmPool.RESERVED_RAM_MB, 4096,
                4, 2, 2048, 1024));
        accounting.recordUtilizationSnapshot(new UtilizationSnapshot(
                12.0, 2 * HybridVmPool.RESERVED_CORES, 0,
                2 * HybridVmPool.RESERVED_RAM_MB, 0,
                0, 0, 0, 0));

        CBMWResultCollector collector = new CBMWResultCollector(
                Arrays.asList(success, miss), accounting, 2);
        CBMWResultCollector.ScenarioMetrics metrics = collector.toScenarioMetrics(
                "full500_arrival15_alpha1.2", "arrival15", "alpha1.2",
                "CBMW", 1.0, 1.2, 0, 123L, "COMMON_MARKET", 0.5, 0.1);

        double expectedReservedCost = 2 * (12.0 / 3600.0)
                * HybridVmPool.RESERVED_HOURLY_COST;
        assert metrics.metDeadline == 1;
        assertClose(metrics.overallSuccessRate, 0.5);
        assertClose(metrics.simulationDuration, 12.0);
        assertClose(metrics.onDemandCost, 3.0);
        assertClose(metrics.spotCost, 1.0);
        assertClose(metrics.reservedCost, expectedReservedCost);
        assertClose(metrics.totalCost, 4.0 + expectedReservedCost);
        assert Double.isNaN(metrics.marginalCost)
                : "Marginal cost must remain blank until edge200 is available";

        assert metrics.reservedInstanceCount == 2;
        assert metrics.reservedCoresPerInstance == HybridVmPool.RESERVED_CORES;
        assert metrics.reservedRamMbPerInstance == HybridVmPool.RESERVED_RAM_MB;
        assert metrics.reservedTotalCores == 2 * HybridVmPool.RESERVED_CORES;
        assert metrics.reservedTotalRamMb == 2L * HybridVmPool.RESERVED_RAM_MB;
        assertClose(metrics.reservedCoreSeconds,
                metrics.reservedTotalCores * 12.0);
        assertClose(metrics.reservedRamMbSeconds,
                metrics.reservedTotalRamMb * 12.0);

        assert metrics.provisionedOnDemandVms == 1;
        assertClose(metrics.onDemandAverageUptime, 60.0);
        assert metrics.onDemandTotalCores == 2;
        assert metrics.onDemandTotalRamMb == 4096;
        assertClose(metrics.onDemandCoreSeconds, 120.0);
        assertClose(metrics.onDemandRamMbSeconds, 245760.0);
        assertClose(metrics.meanUtilizedCores, 6.0);
        assertClose(metrics.maxUtilizedCores, 6.0);
        assertClose(metrics.meanUtilizedRamMb, 3072.0);
        assertClose(metrics.maxUtilizedRamMb, 3072.0);

        String[] header = CBMWResultCollector.csvHeader().split(",", -1);
        String[] row = CBMWResultCollector.toCsvRow(metrics).split(",", -1);
        assert header.length == row.length
                : "CSV header has " + header.length + " columns but row has "
                + row.length;

        System.out.println("RequestedReportFieldsValidationTest: PASS");
    }

    private static WorkflowRecord workflow(int id, double arrival,
                                           double deadline, double completion,
                                           boolean deadlineMet,
                                           double onDemandCost,
                                           double spotCost) {
        WorkflowRecord workflow = new WorkflowRecord(id, "workflow.xml", arrival);
        workflow.setAccepted(true);
        workflow.setDeadline(deadline);
        workflow.setCompletionTime(completion);
        workflow.setDeadlineMet(deadlineMet);
        workflow.addOnDemandCost(onDemandCost);
        workflow.addSpotCost(spotCost);
        return workflow;
    }

    private static void assertClose(double actual, double expected) {
        assert Math.abs(actual - expected) <= EPS
                : "Expected " + expected + " but got " + actual;
    }
}
