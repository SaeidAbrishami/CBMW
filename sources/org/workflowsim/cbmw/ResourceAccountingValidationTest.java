package org.workflowsim.cbmw;

/** Focused validation for time-integrated shared resource accounting. */
public final class ResourceAccountingValidationTest {

    private static final double EPS = 1e-9;

    private ResourceAccountingValidationTest() {}

    public static void main(String[] args) {
        CBMWAccounting accounting = new CBMWAccounting();
        accounting.markOnDemandOrdered(100, 4, 8192, 2.0, 3.0);
        accounting.markOnDemandLaunched(100, 3.0);
        accounting.markOnDemandDestroyed(100, 8.0);

        accounting.recordUtilizationSnapshot(snapshot(
                0.0, 8, 0, 16000, 0, 0, 0, 0, 0));
        accounting.recordUtilizationSnapshot(snapshot(
                2.0, 8, 0, 16000, 0, 4, 0, 4000, 0));
        // Two events at the same time: only the final state should govern the
        // following interval and instantaneous extrema.
        accounting.recordUtilizationSnapshot(snapshot(
                3.0, 8, 4, 16000, 8192, 4, 0, 4000, 0));
        accounting.recordUtilizationSnapshot(snapshot(
                3.0, 8, 4, 16000, 8192, 4, 2, 4000, 2048));
        accounting.recordUtilizationSnapshot(snapshot(
                6.0, 8, 4, 16000, 8192, 0, 4, 0, 8192));
        accounting.recordUtilizationSnapshot(snapshot(
                8.0, 8, 0, 16000, 0, 0, 0, 0, 0));
        accounting.recordUtilizationSnapshot(snapshot(
                10.0, 8, 0, 16000, 0, 0, 0, 0, 0));

        ResourceAccountingSummary summary = accounting.summarizeResources(0.0, 10.0);
        assert summary.getDuration() == 10.0;
        assert summary.getOnDemandInstanceCount() == 1;
        assertClose(summary.getAverageOnDemandUptime(), 5.0);
        assert summary.getTotalOnDemandCores() == 4;
        assert summary.getTotalOnDemandRamMb() == 8192;
        assertClose(summary.getOnDemandCapacityCoreSeconds(), 20.0);
        assertClose(summary.getOnDemandCapacityRamMbSeconds(), 40960.0);

        assertClose(summary.getReservedCapacityCores().getIntegral(), 80.0);
        assertClose(summary.getOnDemandCapacityCores().getIntegral(), 20.0);
        assertClose(summary.getReservedUsedCores().getIntegral(), 16.0);
        assertClose(summary.getOnDemandUsedCores().getIntegral(), 14.0);
        assertClose(summary.getUtilizedCores().getIntegral(), 30.0);
        assertClose(summary.getUtilizedCores().getMean(), 3.0);
        assertClose(summary.getUtilizedCores().getMin(), 0.0);
        assertClose(summary.getUtilizedCores().getMax(), 6.0);
        assertClose(summary.getReservedCoreUtilization().getMean(), 0.2);

        assertClose(summary.getReservedUsedRamMb().getIntegral(), 16000.0);
        assertClose(summary.getOnDemandUsedRamMb().getIntegral(), 22528.0);
        assertClose(summary.getUtilizedRamMb().getIntegral(), 38528.0);
        assertClose(summary.getUtilizedRamMb().getMax(), 8192.0);

        validateCapacityOverrides();
        validateLifecycleClipping();
        System.out.println("ResourceAccountingValidationTest: PASS");
    }

    private static void validateCapacityOverrides() {
        CBMWAccounting accounting = new CBMWAccounting();
        accounting.setOnDemandCapacitySeconds(123.0, 456.0);
        ResourceAccountingSummary summary = accounting.summarizeResources(0.0, 10.0);
        assertClose(summary.getOnDemandCapacityCoreSeconds(), 123.0);
        assertClose(summary.getOnDemandCapacityRamMbSeconds(), 456.0);
    }

    private static void validateLifecycleClipping() {
        CBMWAccounting accounting = new CBMWAccounting();
        accounting.markOnDemandOrdered(200, 2, 1024, 0.0, 1.0);
        accounting.markOnDemandLaunched(200, 1.0);
        accounting.markOnDemandDestroyed(200, 20.0);
        ResourceAccountingSummary summary = accounting.summarizeResources(5.0, 10.0);
        assert summary.getOnDemandInstanceCount() == 1;
        assertClose(summary.getAverageOnDemandUptime(), 19.0);
        assertClose(summary.getOnDemandCapacityCoreSeconds(), 38.0);
        assertClose(summary.getOnDemandCapacityRamMbSeconds(), 19456.0);
        OnDemandInstanceRecord record = accounting.getOnDemandRecords().get(0);
        assertClose(record.getUptime(5.0, 10.0), 5.0);
    }

    private static UtilizationSnapshot snapshot(
            double time, int reservedCores, int onDemandCores,
            int reservedRamMb, int onDemandRamMb,
            int runningReservedCores, int runningOnDemandCores,
            int runningReservedRamMb, int runningOnDemandRamMb) {
        return new UtilizationSnapshot(time, reservedCores, onDemandCores,
                reservedRamMb, onDemandRamMb,
                runningReservedCores, runningOnDemandCores,
                runningReservedRamMb, runningOnDemandRamMb);
    }

    private static void assertClose(double actual, double expected) {
        assert Math.abs(actual - expected) <= EPS
                : "Expected " + expected + " but got " + actual;
    }
}
