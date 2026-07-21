package org.workflowsim.cbmw.baselines;

import java.util.Arrays;
import org.workflowsim.Task;
import org.workflowsim.cbmw.WorkflowRecord;

/** Deterministic characterization tests for the common-market reference policy. */
public final class CEWBReferencePolicyValidationTest {

    private CEWBReferencePolicyValidationTest() {}

    public static void main(String[] args) {
        runAll();
        System.out.println("CEWBReferencePolicyValidationTest: PASS");
    }

    static void runAll() {
        System.setProperty("cbmw.cewb.reference.slack.base.sec", "400.4");
        testSingleTaskPcp();
        testChainPcp();
        testForkJoinPcp();
        testSlackBoundaries();
        testCycleRejected();
    }

    private static void testSingleTaskPcp() {
        Task task = new Task(101, 10_000L);
        WorkflowRecord workflow = workflow(10, 100.0, 150.0, task);
        workflow.setEstimatedExecTime(101, 10.0);
        new CEWBReferenceTaskPolicy().preprocess(
                workflow, workflow.getTaskList());
        checkClose(150.0, workflow.getLFT(101), "single-task LFT");
        checkClose(140.0, workflow.getLST(101), "single-task LST");
    }

    private static void testChainPcp() {
        Task first = new Task(201, 10_000L);
        Task second = new Task(202, 20_000L);
        link(first, second);
        WorkflowRecord workflow = workflow(11, 0.0, 60.0, first, second);
        workflow.setEstimatedExecTime(201, 10.0);
        workflow.setEstimatedExecTime(202, 20.0);
        new CEWBReferenceTaskPolicy().preprocess(
                workflow, workflow.getTaskList());
        checkClose(20.0, workflow.getLFT(201), "chain first subdeadline");
        checkClose(60.0, workflow.getLFT(202), "chain second subdeadline");
        checkClose(30.0, workflow.getCriticalPathLength(), "chain critical path");
    }

    private static void testForkJoinPcp() {
        Task entry = new Task(301, 10_000L);
        Task left = new Task(302, 20_000L);
        Task right = new Task(303, 10_000L);
        Task join = new Task(304, 10_000L);
        link(entry, left);
        link(entry, right);
        link(left, join);
        link(right, join);
        WorkflowRecord workflow = workflow(12, 0.0, 100.0,
                entry, left, right, join);
        workflow.setEstimatedExecTime(301, 10.0);
        workflow.setEstimatedExecTime(302, 20.0);
        workflow.setEstimatedExecTime(303, 10.0);
        workflow.setEstimatedExecTime(304, 10.0);
        new CEWBReferenceTaskPolicy().preprocess(
                workflow, workflow.getTaskList());
        check(workflow.getLFT(301) <= workflow.getLFT(302),
                "fork entry must not follow left child");
        check(workflow.getLFT(301) <= workflow.getLFT(303),
                "fork entry must not follow right child");
        check(workflow.getLFT(302) <= workflow.getLFT(304),
                "left child must not follow join");
        check(workflow.getLFT(303) <= workflow.getLFT(304),
                "right child must not follow join");
        checkClose(100.0, workflow.getLFT(304), "join subdeadline");
        checkClose(40.0, workflow.getCriticalPathLength(),
                "fork-join critical path");
    }

    private static void testSlackBoundaries() {
        CEWBReferenceTaskPolicy policy = new CEWBReferenceTaskPolicy();
        checkClass(policy, 400.4, CEWBCriticalityPolicy.ON_DEMAND);
        checkClass(policy, 400.400001,
                CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT);
        checkClass(policy, 800.799999,
                CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT);
        checkClass(policy, 800.8,
                CEWBCriticalityPolicy.MEDIUM_RELIABILITY_SPOT);
        checkClass(policy, 1601.599999,
                CEWBCriticalityPolicy.MEDIUM_RELIABILITY_SPOT);
        checkClass(policy, 1601.6,
                CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT);
    }

    private static void testCycleRejected() {
        Task first = new Task(401, 10_000L);
        Task second = new Task(402, 10_000L);
        link(first, second);
        link(second, first);
        WorkflowRecord workflow = workflow(13, 0.0, 100.0, first, second);
        workflow.setEstimatedExecTime(401, 10.0);
        workflow.setEstimatedExecTime(402, 10.0);
        boolean rejected = false;
        try {
            new CEWBReferenceTaskPolicy().preprocess(
                    workflow, workflow.getTaskList());
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "cyclic reference workflow must be rejected");
    }

    private static void checkClass(CEWBReferenceTaskPolicy policy, double slack,
                                   int expectedClass) {
        CEWBTaskDecision decision = policy.classifyDuration(
                slack + 10.0, 0.0, 10.0);
        check(decision.getResourceClass() == expectedClass,
                "slack " + slack + " expected class " + expectedClass
                        + " but got " + decision.getResourceClass());
    }

    private static WorkflowRecord workflow(int id, double arrival,
                                           double deadline, Task... tasks) {
        WorkflowRecord workflow = new WorkflowRecord(id, "synthetic.xml", arrival);
        workflow.setTaskList(Arrays.asList(tasks));
        workflow.setDeadline(deadline);
        return workflow;
    }

    private static void link(Task parent, Task child) {
        parent.getChildList().add(child);
        child.getParentList().add(parent);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkClose(double expected, double actual,
                                   String message) {
        if (Math.abs(expected - actual) > 1e-9) {
            throw new AssertionError(message + ": expected=" + expected
                    + " actual=" + actual);
        }
    }
}
