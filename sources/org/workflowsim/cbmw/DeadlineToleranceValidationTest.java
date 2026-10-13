package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.List;

/** Check the reporting cutoff and the full-500 and main-300 columns. */
public final class DeadlineToleranceValidationTest {
    private DeadlineToleranceValidationTest() {}

    public static void main(String[] args) {
        check(DeadlineOutcome.isMet(10.9999, 10.0), "subsecond overrun");
        check(!DeadlineOutcome.isMiss(10.9999, 10.0), "subsecond miss");
        check(DeadlineOutcome.isMiss(11.0, 10.0), "one-second miss");
        check(!DeadlineOutcome.isMet(11.0, 10.0), "one-second success");
        check(!DeadlineOutcome.isMet(Double.MAX_VALUE, 10.0), "unfinished workflow");

        List<WorkflowRecord> workflows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            WorkflowRecord workflow = new WorkflowRecord(i, "test.xml", i);
            workflow.setDeadline(i + 10.0);
            workflow.setCompletionTime(i + 10.0);
            workflow.setAccepted(true);
            workflow.setDeadlineMet(DeadlineOutcome.isMet(
                    workflow.getCompletionTime(), workflow.getDeadline()));
            workflows.add(workflow);
        }
        // One tolerated overrun inside the measured cohort, one reportable miss
        // inside it, and one reportable miss in the boundary workflows.
        complete(workflows.get(100), 0.9999);
        complete(workflows.get(101), 1.0);
        complete(workflows.get(400), 2.0);

        CBMWResultCollector collector = new CBMWResultCollector(
                workflows, new CBMWAccounting(), 0);
        CBMWResultCollector.ScenarioMetrics result = collector.toScenarioMetrics(
                "test_full500", "test", "test", "CBMW", 1.0, 1.0,
                0, 1L, "test", 0.0, 0.0);
        check(result.metDeadline == 498, "successful workflow count");
        check(result.deadlineMissCount == 2, "full-500 miss count");
        check(result.deadlineMissCountMain300 == 1, "main-300 miss count");
        check(Math.abs(result.maxDeadlineMissSeconds - 2.0) < 1e-10,
                "maximum lateness");
        check(Math.abs(result.avgDeadlineMissSeconds - 1.5) < 1e-10,
                "average lateness");
        check(Math.abs(result.timeViolation - 0.0006) < 1e-10,
                "normalized time violation");
        check(Math.abs(result.countViolation - 0.004) < 1e-10,
                "count violation");
        System.out.println("DeadlineToleranceValidationTest: PASS");
    }

    private static void complete(WorkflowRecord workflow, double overrun) {
        workflow.setCompletionTime(workflow.getDeadline() + overrun);
        workflow.setDeadlineMet(DeadlineOutcome.isMet(
                workflow.getCompletionTime(), workflow.getDeadline()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
