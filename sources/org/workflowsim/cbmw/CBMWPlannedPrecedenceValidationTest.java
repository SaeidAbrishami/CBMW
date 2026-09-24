package org.workflowsim.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/** Regression validation for resource-aware parent/child planned precedence. */
public final class CBMWPlannedPrecedenceValidationTest {

    private CBMWPlannedPrecedenceValidationTest() {}

    public static void main(String[] args) throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        reserveLateCapacity(pool);

        Task parent = new Task(1, 50_000L);
        Task child = new Task(2, 20_000L);
        parent.addChild(child);
        child.addParent(parent);

        WorkflowRecord workflow = new WorkflowRecord(
                1, "planned-precedence-validation.xml", 0.0);
        List<Task> tasks = Arrays.asList(parent, child);
        workflow.setTaskList(tasks);
        workflow.setDeadline(200.0);
        workflow.setEstimatedExecTime(1, 50.0);
        workflow.setEstimatedExecTime(2, 20.0);
        workflow.setTaskResources(1, 1, 1, "VALIDATION");
        workflow.setTaskResources(2, 1, 1, "VALIDATION");

        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(
                        workflow, pool, new NegotiationModule(1.2));
        planner.setTaskList(tasks);
        planner.setVmList(pool.getAllVms());
        planner.run();

        double parentFinish = plannedExecutionStart(workflow, 1)
                + workflow.getEstimatedExecTime(1);
        double childStart = plannedExecutionStart(workflow, 2);

        assert parentFinish <= childStart + 1e-9
                : "Parent finish must not exceed child planned start: "
                        + parentFinish + " > " + childStart;
        assert workflow.getScheduledStart(1) % HybridVmPool.SCHEDULING_PERIOD == 0
                && workflow.getScheduledStart(2) % HybridVmPool.SCHEDULING_PERIOD == 0
                : "Planned execution starts must be dispatch instants";

        System.out.println("CBMWPlannedPrecedenceValidationTest: PASS");
    }

    private static void reserveLateCapacity(HybridVmPool pool) {
        int blockerId = -10_000;
        for (CondorVM vm : pool.getReservedVms()) {
            pool.bookSlot(vm.getId(), blockerId--, 100.0, 300.0,
                    vm.getNumberOfPes(), vm.getRam());
        }
    }

    private static double plannedExecutionStart(WorkflowRecord workflow,
                                                int taskId) {
        return workflow.getScheduledStart(taskId);
    }
}
