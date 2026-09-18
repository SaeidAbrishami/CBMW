package org.workflowsim.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.Task;

/** Ensures planning does not add a second provisioning delay for on-demand entries. */
public final class CBMWEntryOnDemandDeadlineValidationTest {

    private CBMWEntryOnDemandDeadlineValidationTest() {}

    public static void main(String[] args) throws Exception {
        validateFeasibleBoundary();
        validateNoSecondDeadlineExtension();
        System.out.println("CBMWEntryOnDemandDeadlineValidationTest: PASS");
    }

    private static void validateFeasibleBoundary() throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        // The loader now applies the universal 60-second OPD before planning.
        WorkflowRecord workflow = createTwoTaskWorkflow(1, 180.0);
        runPlanner(workflow, pool);

        assert workflow.getAssignedVm(1)
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                : "Oversized entry task must use on-demand";
        assert Math.abs(workflow.getDeadline() - 180.0) < 1e-9
                : "Planning must not add a second OPD to an already adjusted deadline";
        assert Math.abs(workflow.getScheduledStart(1) - 90.0) < 1e-9
                : "Entry order must follow LST - OPD";
        assert Math.abs(workflow.getLFT(1) - 160.0) < 1e-9
                : "Entry LFT must use the universally adjusted workflow deadline";
    }

    private static void validateNoSecondDeadlineExtension() throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        WorkflowRecord workflow = createTwoTaskWorkflow(2, 179.0);
        runPlanner(workflow, pool);

        assert Math.abs(workflow.getDeadline() - 179.0) < 1e-9
                : "An on-demand entry must not cause an additional OPD extension";
        assert workflow.getAssignedVm(1)
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                : "Oversized entry task must remain on-demand";
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
