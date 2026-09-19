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

/** Validates cancellation of a future o0 order after a reserved advance. */
public final class CBMWAdvanceOnDemandOrderCancellationValidationTest {

    private CBMWAdvanceOnDemandOrderCancellationValidationTest() {}

    public static void main(String[] args) throws Exception {
        int workflowId = 11;
        int taskId = 1100;
        HybridVmPool pool = new HybridVmPool(0);
        pool.registerTaskResources(taskId, 1, 1);

        WorkflowRecord workflow = new WorkflowRecord(
                workflowId, "advance-validation.xml", 0.0);
        workflow.setAssignedVm(taskId,
                CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
        workflow.setScheduledStart(taskId, 100.0);
        workflow.setEstimatedExecTime(taskId, 10.0);

        Map<Integer, WorkflowRecord> workflows = new HashMap<>();
        workflows.put(workflowId, workflow);
        ProvisioningModule provisioner = new ProvisioningModule(pool, 0);
        Set<Integer> cancelledOrders = new HashSet<>();
        CBMWDynamicSchedulingAlgorithm scheduler =
                new CBMWDynamicSchedulingAlgorithm(pool, workflows,
                        provisioner, new HashSet<Integer>(), cancelledOrders);

        Job job = createJob(taskId, workflowId);
        List<Cloudlet> ready = new ArrayList<>();
        ready.add(job);
        scheduler.setCloudletList(ready);
        scheduler.setVmList(pool.getAllVms());
        scheduler.run();

        assert scheduler.getScheduledList().size() == 1
                : "A future o0 task should advance when reserved capacity is idle";
        assert pool.isReserved(job.getVmId())
                : "The advanced task must run on reserved capacity";
        assert cancelledOrders.contains(taskId)
                : "The later static o0 order must be cancelled";
        assert provisioner.getProvisionedVm(taskId) == null
                : "Advancing must not provision an on-demand container";

        System.out.println("CBMWAdvanceOnDemandOrderCancellationValidationTest: PASS");
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
