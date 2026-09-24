package org.workflowsim.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/** A reserved successor must not cause a feasible workflow to be rejected. */
public final class CBMWGreedyRecoveryValidationTest {
    private CBMWGreedyRecoveryValidationTest() {}

    public static void main(String[] args) throws Exception {
        HybridVmPool pool = new HybridVmPool(0);
        int blocker = -100;
        for (CondorVM vm : pool.getReservedVms()) {
            pool.bookSlot(vm.getId(), blocker--, 0.0, 60.0,
                    vm.getNumberOfPes(), vm.getRam());
            pool.bookSlot(vm.getId(), blocker--, 95.0, 200.0,
                    vm.getNumberOfPes(), vm.getRam());
        }

        Task parent = new Task(1, 30_000L);
        Task child = new Task(2, 20_000L);
        parent.addChild(child);
        child.addParent(parent);
        List<Task> tasks = Arrays.asList(parent, child);
        WorkflowRecord workflow = new WorkflowRecord(1, "recovery.xml", 0.0);
        workflow.setTaskList(tasks);
        workflow.setDeadline(120.0); // 60 + 1.2 * (30 + 20)
        workflow.setEstimatedExecTime(1, 30.0);
        workflow.setEstimatedExecTime(2, 20.0);
        workflow.setTaskResources(1, 1, 1, "VALIDATION");
        workflow.setTaskResources(2, 1, 1, "VALIDATION");

        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(workflow, pool,
                        new NegotiationModule(1.2));
        planner.setTaskList(tasks);
        planner.setVmList(pool.getAllVms());
        planner.run();

        double parentFinish = workflow.getScheduledStart(1) + 30.0;
        double childFinish = workflow.getScheduledStart(2) + 20.0;
        if (parentFinish > workflow.getScheduledStart(2) + 1e-9
                || childFinish > workflow.getDeadline() + 1e-9
                || workflow.getLFT(1) > workflow.getScheduledStart(2) + 1e-9
                || workflow.getLFT(2) > workflow.getDeadline() + 1e-9
                || workflow.getScheduledStart(1) % 5.0 != 0.0
                || workflow.getScheduledStart(2) % 5.0 != 0.0
                || workflow.getAssignedVm(2)
                        != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL
                || workflow.getPlannedProvisionOrder(2) < 0.0) {
            throw new AssertionError("Recovery failed to build a feasible periodic plan");
        }
        System.out.println("CBMWGreedyRecoveryValidationTest: PASS");
    }
}
