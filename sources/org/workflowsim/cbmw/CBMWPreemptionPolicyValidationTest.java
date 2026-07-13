package org.workflowsim.cbmw;

/** Focused invariants for CBMW's pre-run-only replacement policy. */
public final class CBMWPreemptionPolicyValidationTest {

    private CBMWPreemptionPolicyValidationTest() {}

    public static void main(String[] args) {
        assert CBMWBroker.isCurrentlyPreRunning(10.0, 10.1)
                : "A running task before SST must be eligible";
        assert !CBMWBroker.isCurrentlyPreRunning(10.0, 10.0)
                : "A task at SST must be protected";
        assert !CBMWBroker.isCurrentlyPreRunning(10.1, 10.0)
                : "A task after SST must be protected";

        double slack = CBMWBroker.calculateTaskDeadlineSlack(
                100.0, 40.0, 15.0);
        assert Math.abs(slack - 45.0) < 1.0e-9
                : "Task slack must subtract current time and remaining work";

        double safeAfterInterruption =
                CBMWBroker.calculatePostPreemptionSlack(
                        100.0, 40.0, 20.0, 30.0);
        assert Math.abs(safeAfterInterruption - 10.0) < 1.0e-9
                : "Post-preemption slack must subtract interruption time";
        assert CBMWBroker.hasSafePostPreemptionSlack(
                safeAfterInterruption, 0.0)
                : "Positive post-preemption slack must be eligible";

        double unsafeAfterInterruption =
                CBMWBroker.calculatePostPreemptionSlack(
                        100.0, 40.0, 35.0, 30.0);
        assert Math.abs(unsafeAfterInterruption + 5.0) < 1.0e-9
                : "Unsafe post-preemption slack calculation is incorrect";
        assert !CBMWBroker.hasSafePostPreemptionSlack(
                unsafeAfterInterruption, 0.0)
                : "Negative post-preemption slack must be rejected";
        assert CBMWBroker.hasSafePostPreemptionSlack(0.0, 0.0)
                : "Zero slack is safe when no margin is configured";
        assert !CBMWBroker.hasSafePostPreemptionSlack(4.9, 5.0)
                : "Slack below the configured margin must be rejected";
        assert !CBMWBroker.hasSafePostPreemptionSlack(
                Double.NaN, 0.0)
                : "Invalid slack must be rejected conservatively";

        assert CBMWBroker.compareVictimPriorityValues(
                20.0, 80.0, 9, 10.0, 90.0, 1) > 0
                : "Greater task slack must win";
        assert CBMWBroker.compareVictimPriorityValues(
                20.0, 90.0, 9, 20.0, 80.0, 1) > 0
                : "Later subdeadline must break an equal-slack tie";
        assert CBMWBroker.compareVictimPriorityValues(
                20.0, 90.0, 1, 20.0, 90.0, 9) > 0
                : "Smaller task ID must break the final tie";

        System.out.println("CBMWPreemptionPolicyValidationTest: PASS");
    }
}
