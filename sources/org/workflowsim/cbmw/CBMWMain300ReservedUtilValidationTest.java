package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.workflowsim.Task;

/** Checks measured-workflow attribution and the independent full-run column. */
public final class CBMWMain300ReservedUtilValidationTest {
    private CBMWMain300ReservedUtilValidationTest() {}

    public static void main(String[] args) {
        List<WorkflowRecord> workflows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            WorkflowRecord workflow = new WorkflowRecord(i, "wf" + i, i);
            workflow.setDeadline(i + 500.0);
            workflows.add(workflow);
        }
        complete(workflows.get(100), 130.0);
        complete(workflows.get(200), 200.0);
        complete(workflows.get(399), 420.0);

        List<TaskExecutionRecord> tasks = new ArrayList<>();
        tasks.add(task(1, 100, 4, "Reserved", 100.0, 130.0));
        tasks.add(task(2, 399, 2, "Reserved", 390.0, 425.0));
        tasks.add(task(3, 99, 100, "Reserved", 110.0, 420.0));
        tasks.add(task(4, 400, 100, "Reserved", 110.0, 420.0));
        tasks.add(task(5, 200, 20, "On-Demand", 110.0, 200.0));

        double actual = CBMWResultCollector.main300ReservedUtil(workflows, tasks, 2);
        double expected = 180.0 / (2 * HybridVmPool.RESERVED_CORES * 320.0);
        requireClose(actual, expected);

        CBMWAccounting accounting = new CBMWAccounting();
        accounting.recordUtilizationSnapshot(new UtilizationSnapshot(
                0.0, 2 * HybridVmPool.RESERVED_CORES, 0,
                2 * HybridVmPool.RESERVED_RAM_MB, 0,
                HybridVmPool.RESERVED_CORES / 2, 0, 0, 0));
        accounting.recordUtilizationSnapshot(new UtilizationSnapshot(
                420.0, 2 * HybridVmPool.RESERVED_CORES, 0,
                2 * HybridVmPool.RESERVED_RAM_MB, 0, 0, 0, 0, 0));
        CBMWResultCollector collector = new CBMWResultCollector(workflows, accounting, 2);
        CBMWResultCollector.ScenarioMetrics full = collector.toScenarioMetrics(
                "arrival15_alpha2_full500", "arrival15", "alpha2", "CBMW",
                1.0, 2.0, 0, 7L, "", 0.0, 0.0);
        requireClose(full.reservedUtil, 0.25);

        String[] header = CBMWResultCollector.csvHeader().split(",", -1);
        String[] fullRow = CBMWResultCollector.toCsvRow(full).split(",", -1);
        if (header.length != fullRow.length) throw new AssertionError("CSV column count");
        int column = -1;
        for (int i = 0; i < header.length; i++) {
            if ("reservedUtilMain300".equals(header[i])) column = i;
        }
        if (column < 0 || !"0.0000".equals(fullRow[column])) {
            throw new AssertionError("Missing main-300 column on full run");
        }
        CBMWResultCollector.ScenarioMetrics edge = collector.toScenarioMetrics(
                "arrival15_alpha2_edge200", "arrival15", "alpha2", "CBMW",
                1.0, 2.0, 0, 7L, "", 0.0, 0.0);
        if (!CBMWResultCollector.toCsvRow(edge).split(",", -1)[column].isEmpty()) {
            throw new AssertionError("Edge-200 row must have blank main-300 utilization");
        }
        System.out.println("CBMWMain300ReservedUtilValidationTest: PASS");
    }

    private static void complete(WorkflowRecord workflow, double time) {
        workflow.setAccepted(true);
        Task task = new Task(workflow.getWorkflowId(), 1L);
        workflow.setTaskList(Collections.singletonList(task));
        workflow.markTaskCompleted(task.getCloudletId());
        workflow.setCompletionTime(time);
    }

    private static TaskExecutionRecord task(int taskId, int workflowId, int cores,
                                            String type, double start, double finish) {
        TaskExecutionRecord record = new TaskExecutionRecord(
                taskId, "task", workflowId, "workflow.xml", 0.0, "ACCEPTED",
                cores, 1024, "TEST", 1.0, 0.0, 1.0, 1.0, 1.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0,
                null, "Unassigned", Collections.emptyList());
        record.markSubmitted(start, taskId, type, "TEST");
        record.markFinished(start, finish, "SUCCESS");
        return record;
    }

    private static void requireClose(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
