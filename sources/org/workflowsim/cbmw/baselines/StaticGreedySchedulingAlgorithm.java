package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.ProvisioningModule;
import org.workflowsim.cbmw.WorkflowRecord;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/** Executes StaticGreedy's fixed plan without CBMW-style reassignment. */
public class StaticGreedySchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private static final double EPS = 1e-9;
    private final HybridVmPool pool;
    private final Map<Integer, WorkflowRecord> workflows;
    private final ProvisioningModule provisioner;
    private double nextWakeTime = Double.POSITIVE_INFINITY;

    public StaticGreedySchedulingAlgorithm(
            HybridVmPool pool,
            Map<Integer, WorkflowRecord> workflows,
            ProvisioningModule provisioner) {
        this.pool = pool;
        this.workflows = workflows;
        this.provisioner = provisioner;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
        double now = CloudSim.clock();
        nextWakeTime = Double.POSITIVE_INFINITY;
        List<Cloudlet> ready = new ArrayList<>((List<Cloudlet>) getCloudletList());
        ready.sort(Comparator
                .comparingDouble((Cloudlet cl) -> plannedStart((Job) cl))
                .thenComparingInt(Cloudlet::getCloudletId));

        for (Cloudlet cloudlet : ready) {
            Job job = (Job) cloudlet;
            int taskId = primaryTaskId(job);
            WorkflowRecord workflow = workflows.get(workflowId(job));
            if (workflow == null) continue;

            double plannedStart = workflow.getScheduledStart(taskId);
            if (plannedStart > now + EPS) {
                nextWakeTime = Math.min(nextWakeTime, plannedStart);
                continue;
            }

            int plannedVm = workflow.getAssignedVm(taskId);
            if (plannedVm != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                CondorVM vm = pool.getVmById(plannedVm);
                if (vm != null && pool.hasRuntimeCapacity(plannedVm, taskId)) {
                    job.setVmId(plannedVm);
                    getScheduledList().add(job);
                }
                continue;
            }

            CondorVM container = provisioner.getProvisionedVm(job.getCloudletId());
            if (container != null
                    && pool.isOnDemandContainerActive(container.getId())) {
                job.setVmId(container.getId());
                getScheduledList().add(job);
            }
        }
    }

    public double getNextWakeTime() {
        return nextWakeTime;
    }

    private double plannedStart(Job job) {
        WorkflowRecord workflow = workflows.get(workflowId(job));
        return workflow != null
                ? workflow.getScheduledStart(primaryTaskId(job))
                : Double.POSITIVE_INFINITY;
    }

    private int workflowId(Job job) {
        return job.getTaskList().isEmpty()
                ? -1 : job.getTaskList().get(0).getWorkflowId();
    }

    private int primaryTaskId(Job job) {
        return job.getTaskList().isEmpty()
                ? job.getCloudletId()
                : job.getTaskList().get(0).getCloudletId();
    }
}
