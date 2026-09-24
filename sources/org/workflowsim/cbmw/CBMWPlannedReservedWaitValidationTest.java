package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Job;
import org.workflowsim.Task;

/** A blocked reserved task waits for capacity without losing its assignment. */
public final class CBMWPlannedReservedWaitValidationTest {

    private CBMWPlannedReservedWaitValidationTest() {}

    public static void main(String[] args) throws Exception {
        final int workflowId = 7;
        final int waitingTaskId = 500;
        final int blockerTaskId = 600;
        final int plannedVmId = 0;

        HybridVmPool pool = new HybridVmPool(0);
        pool.registerTaskResources(waitingTaskId, 1, 1);
        pool.registerTaskResources(blockerTaskId,
                HybridVmPool.RESERVED_CORES, HybridVmPool.RESERVED_RAM_MB);
        pool.taskStarted(plannedVmId, blockerTaskId);

        WorkflowRecord workflow = new WorkflowRecord(
                workflowId, "validation.xml", 0.0);
        workflow.setAssignedVm(waitingTaskId, plannedVmId);
        workflow.setScheduledStart(waitingTaskId, 0.0);
        workflow.setEstimatedExecTime(waitingTaskId, 10.0);

        Map<Integer, WorkflowRecord> workflows = new HashMap<>();
        workflows.put(workflowId, workflow);
        ProvisioningModule provisioner = new ProvisioningModule(pool, 0);
        Set<Integer> waitingForPlanned = new HashSet<>();
        waitingForPlanned.add(waitingTaskId);

        CBMWDynamicSchedulingAlgorithm scheduler =
                new CBMWDynamicSchedulingAlgorithm(pool, workflows,
                        provisioner, waitingForPlanned);
        Job waiting = createJob(waitingTaskId, workflowId);
        List<Cloudlet> ready = new ArrayList<>();
        ready.add(waiting);
        scheduler.setCloudletList(ready);
        scheduler.setVmList(pool.getAllVms());

        scheduler.run();
        assert scheduler.getScheduledList().isEmpty()
                : "The occupied VM must not dispatch another task";
        assert waitingForPlanned.contains(waitingTaskId)
                : "The blocked task must remain pending";
        assert provisioner.getProvisionedVm(waitingTaskId) == null
                : "A reserved-planned waiting task must not provision on-demand";

        pool.taskFinished(plannedVmId, blockerTaskId);
        scheduler.run();
        assert !waitingForPlanned.contains(waitingTaskId)
                : "A dispatched task must leave the waiting set";
        assert scheduler.getScheduledList().size() == 1
                : "The task must dispatch when its planned VM releases capacity";
        assert waiting.getVmId() == plannedVmId
                : "The waiting task must start on its originally planned VM";
        assert provisioner.getProvisionedVm(waitingTaskId) == null
                : "Dispatch on the planned VM must not create on-demand capacity";

        System.out.println("CBMWPlannedReservedWaitValidationTest: PASS");
    }

    private static Job createJob(int taskId, int workflowId) {
        Job job = new Job(taskId, 10_000L);
        Task task = new Task(taskId, 10_000L);
        task.setWorkflowId(workflowId);
        List<Task> tasks = new ArrayList<>();
        tasks.add(task);
        job.setTaskList(tasks);
        return job;
    }
}
