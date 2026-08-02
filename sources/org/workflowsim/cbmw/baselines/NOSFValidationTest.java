package org.workflowsim.cbmw.baselines;

import java.util.Arrays;
import java.util.Collections;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/** Fast equation and invariant tests for paper-faithful NOSF. */
public final class NOSFValidationTest {
    private NOSFValidationTest() {}

    public static void main(String[] args) {
        testRuntimeWeight();
        testSharedProvisioningDelay();
        testPaperResourceSelection();
        testOneWaitingTaskAndBootBilling();
        testPcpPreprocessingAndFeedback();
        testFeedbackTouchesOnlyReadyImmediateSuccessors();
        System.out.println("NOSF paper equations and invariants: PASS");
    }

    private static void testRuntimeWeight() {
        double mean = 100.0;
        require(close(NOSFRuntimeModel.weight(mean),
                        mean + NOSFRuntimeModel.sigma(mean)),
                "NOSF must use w(lambda)=mu+sigma");
    }

    private static void testSharedProvisioningDelay() {
        require(close(NOSFBroker.PROVISIONING_DELAY,
                        HybridVmPool.ON_DEMAND_PROVISIONING_DELAY),
                "NOSF must inherit CBMW's on-demand provisioning delay");
    }

    private static void testPaperResourceSelection() {
        NOSFVmType type = new NOSFVmType("test", 1, 1024, 1000.0, 0.01);
        NOSFResourceSelector selector = new NOSFResourceSelector(0.0, 60.0);

        NOSFResourceSelector.Choice fresh = selector.choose(0.0, 10.0, 1, 1,
                100.0, Collections.<NOSFVmState>emptyList(), Arrays.asList(type));
        require(fresh != null && fresh.newType == type && fresh.vm == null,
                "must provision when no active VM exists");
        require(fresh.feasible && close(fresh.selectionCost, 0.10),
                "paper selection cost must be price times predicted execution");
        require(close(fresh.incrementalRentalCost, 0.60),
                "actual new-VM rental estimate must be billing-rounded");

        CondorVM vm = vm(42, type);
        NOSFVmState state = new NOSFVmState(vm, type, 0.0, 0.0);
        state.plannedAvailableTime = 20.0;
        NOSFResourceSelector.Choice reuse = selector.choose(20.0, 10.0, 1, 1,
                100.0, Arrays.asList(state), Arrays.asList(type));
        require(reuse != null && reuse.vm == state && reuse.feasible,
                "must reuse a feasible active VM");
        require(close(reuse.incrementalRentalCost, 0.0),
                "reuse inside a paid minute must add no rental cost");

        NOSFResourceSelector.Choice risk = selector.choose(20.0, 10.0, 1, 1,
                25.0, Arrays.asList(state), Arrays.asList(type));
        require(risk != null && !risk.feasible && risk.vm == null
                        && risk.newType == type,
                "infeasible task must lease a new highest-ranking VM");

        NOSFVmType tooSmall = new NOSFVmType("small", 1, 512, 1000.0, 0.001);
        NOSFResourceSelector.Choice incompatible = selector.choose(0.0, 10.0,
                2, 1024, 100.0, Collections.<NOSFVmState>emptyList(),
                Arrays.asList(tooSmall));
        require(incompatible == null,
                "incompatible VM types must not be selected");
    }

    private static void testOneWaitingTaskAndBootBilling() {
        NOSFVmType type = new NOSFVmType("test", 1, 1024, 1000.0, 0.01);
        NOSFVmState state = new NOSFVmState(vm(7, type), type, 0.0, 90.0);
        require(close(state.billedCost(100.0, 60.0), 1.20),
                "boot time must be included in the leased/billed interval");
        require(state.canAcceptWaitingTask(), "empty VM must accept one waiting task");
        state.waiting = new Job(77, 1000);
        require(!state.canAcceptWaitingTask(),
                "VM with a waiting task must reject another waiting task");
    }

    private static void testPcpPreprocessingAndFeedback() {
        Task first = task(1, 10.0);
        Task second = task(2, 20.0);
        link(first, second);
        WorkflowRecord workflow = workflow(1, 0.0, 60.0, first, second);
        NOSFVmType type = new NOSFVmType("fast", 1, 1024, 1000.0, 0.01);
        NOSFWorkflowPlanner planner = new NOSFWorkflowPlanner(
                new NOSFTransferModel(), NOSFWorkflowPlanner.PriorityPolicy.EST);
        planner.preprocess(workflow, Arrays.asList(first, second),
                Arrays.asList(type));

        double firstWeight = NOSFRuntimeModel.weight(10.0);
        double secondWeight = NOSFRuntimeModel.weight(20.0);
        double total = firstWeight + secondWeight;
        require(close(workflow.getEST(1), 0.0), "entry EST must equal arrival");
        require(close(workflow.getEFT(2), total),
                "chain EFT must use paper weights");
        require(close(workflow.getLFT(1), 60.0 * firstWeight / total),
                "Eq. 11 must proportionally assign the first PCP subdeadline");
        require(close(workflow.getLFT(2), 60.0),
                "exit subdeadline must equal workflow deadline");

        workflow.markTaskCompleted(1);
        planner.feedback(workflow, 1, 15.0);
        require(close(workflow.getEST(2), 15.0),
                "Eq. 17 must rebase successor EST on actual parent finish");
        require(close(workflow.getLFT(2), 60.0),
                "Eq. 18 must cap adjusted subdeadline at original LCT");
    }

    private static void testFeedbackTouchesOnlyReadyImmediateSuccessors() {
        Task left = task(10, 10.0);
        Task right = task(11, 10.0);
        Task join = task(12, 10.0);
        link(left, join);
        link(right, join);
        WorkflowRecord workflow = workflow(2, 0.0, 60.0, left, right, join);
        NOSFVmType type = new NOSFVmType("fast", 1, 1024, 1000.0, 0.01);
        NOSFWorkflowPlanner planner = new NOSFWorkflowPlanner(
                new NOSFTransferModel(), NOSFWorkflowPlanner.PriorityPolicy.EST);
        planner.preprocess(workflow, Arrays.asList(left, right, join),
                Arrays.asList(type));
        double originalEst = workflow.getEST(12);

        workflow.markTaskCompleted(10);
        planner.feedback(workflow, 10, 40.0);
        require(close(workflow.getEST(12), originalEst),
                "feedback must not update a successor whose other parent is incomplete");
    }

    private static WorkflowRecord workflow(int id, double arrival, double deadline,
                                           Task... tasks) {
        WorkflowRecord workflow = new WorkflowRecord(id, "unit-test", arrival);
        workflow.setDeadline(deadline);
        workflow.setTaskList(Arrays.asList(tasks));
        for (Task task : tasks) {
            double mean = task.getCloudletLength() / 1000.0;
            workflow.setNominalExecTime(task.getCloudletId(), mean);
            workflow.setEstimatedExecTime(task.getCloudletId(),
                    NOSFRuntimeModel.weight(mean));
            workflow.setTaskResources(task.getCloudletId(), 1, 1, "test");
        }
        return workflow;
    }

    private static Task task(int id, double seconds) {
        return new Task(id, (long) (seconds * 1000.0));
    }

    private static void link(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static CondorVM vm(int id, NOSFVmType type) {
        return new CondorVM(id, 1, type.mipsPerCore, type.cores, type.ramMb,
                10000, 100000, "Xen", type.pricePerSecond,
                0.0, 0.0, 0.0, new CloudletSchedulerSpaceShared());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-7;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
