package org.workflowsim.cbmw;

import java.util.Collections;
import org.workflowsim.Task;

/** Fractional planned starts and on-demand requests must retain exact times. */
public final class CBMWExactSstValidationTest {
    private CBMWExactSstValidationTest() {}

    public static void main(String[] args) throws Exception {
        checkPlan(false, 76.75, 66.75);
        checkPlan(true, 91.75, 81.75);
        System.out.println("CBMW exact-SST planning validation passed");
    }

    private static void checkPlan(boolean onDemand, double deadline,
                                  double expectedSst) throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        Task task = new Task(onDemand ? 2 : 1, 10_000L);
        WorkflowRecord workflow = new WorkflowRecord(
                onDemand ? 2 : 1, "fractional.xml", 1.25);
        workflow.setTaskList(Collections.singletonList(task));
        workflow.setDeadline(deadline);
        workflow.setEstimatedExecTime(task.getCloudletId(), 10.0);
        workflow.setTaskResources(task.getCloudletId(),
                onDemand ? HybridVmPool.RESERVED_CORES + 1 : 1,
                1, "VALIDATION");

        CBMWStaticPlanningAlgorithm planner = new CBMWStaticPlanningAlgorithm(
                workflow, pool, new NegotiationModule(1.2));
        planner.setTaskList(workflow.getTaskList());
        planner.setVmList(pool.getAllVms());
        planner.run();

        int id = task.getCloudletId();
        assertClose(workflow.getScheduledStart(id), expectedSst);
        assertClose(workflow.getLST(id), expectedSst);
        if (onDemand) {
            if (workflow.getAssignedVm(id)
                    != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                throw new AssertionError("Oversized task must use on-demand");
            }
            assertClose(workflow.getPlannedProvisionOrder(id),
                    expectedSst - HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
            assertClose(workflow.getPlannedContainerReady(id), expectedSst);
        } else if (workflow.getAssignedVm(id)
                == CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
            throw new AssertionError("Small task must fit on reserved VM");
        }
    }

    private static void assertClose(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
