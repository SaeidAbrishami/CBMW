package org.workflowsim.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.Task;

/** Focused validation for entry-task on-demand provisioning feasibility. */
public final class CBMWEntryOnDemandDeadlineValidationTest {

    private CBMWEntryOnDemandDeadlineValidationTest() {}

    public static void main(String[] args) throws Exception {
        validateFeasibleBoundary();
        validateDeadlineExtensionInsteadOfRejection();
        System.out.println("CBMWEntryOnDemandDeadlineValidationTest: PASS");
    }

    private static void validateFeasibleBoundary() throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        WorkflowRecord workflow = createTwoTaskWorkflow(1, 120.0);
        runPlanner(workflow, pool);

        assert workflow.getAssignedVm(1)
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                : "Oversized entry task must use on-demand";
        assert Math.abs(workflow.getDeadline() - 180.0) < 1e-9
                : "Entry on-demand task must extend deadline by OPD";
        assert Math.abs(workflow.getScheduledStart(1) - 90.0) < 1e-9
                : "Replanned entry order must follow adjusted LST - OPD";
        assert Math.abs(workflow.getLFT(1) - 160.0) < 1e-9
                : "Entry LFT must use the adjusted workflow deadline";
    }

    private static void validateDeadlineExtensionInsteadOfRejection() throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        WorkflowRecord workflow = createTwoTaskWorkflow(2, 119.0);
        runPlanner(workflow, pool);

        assert Math.abs(workflow.getDeadline() - 179.0) < 1e-9
                : "Formerly rejected workflow must receive exactly one OPD extension";
        assert workflow.getAssignedVm(1)
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                : "Entry task that caused the extension must remain on-demand";
    }

    private static WorkflowRecord createTwoTaskWorkflow(int workflowId,
                                                         double deadline) {
        Task entry = new Task(1, 10_000L);
        Task exit = new Task(2, 20_000L);
        entry.addChild(exit);
        exit.addParent(entry);
        List<Task> tasks = Arrays.asList(entry, exit);

        WorkflowRecord workflow = new WorkflowRecord(
                workflowId, "validation.xml", 0.0);
        workflow.setTaskList(tasks);
        workflow.setDeadline(deadline);
        workflow.setEstimatedExecTime(1, 10.0);
        workflow.setEstimatedExecTime(2, 20.0);
        workflow.setTaskResources(1,
                HybridVmPool.RESERVED_CORES + 1, 1, "VALIDATION");
        workflow.setTaskResources(2, 1, 1, "VALIDATION");
        return workflow;
    }

    private static void runPlanner(WorkflowRecord workflow, HybridVmPool pool)
            throws Exception {
        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(
                        workflow, pool, new NegotiationModule(1.2));
        planner.setTaskList(workflow.getTaskList());
        planner.setVmList(pool.getAllVms());
        planner.run();
    }
}
