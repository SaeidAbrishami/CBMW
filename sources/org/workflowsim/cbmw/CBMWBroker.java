package org.workflowsim.cbmw;

import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;

/**
 * CBMW broker: four-module algorithm (Negotiate → Static Plan → Dynamic
 * Dispatch → Provision).
 *
 * Inherits all CloudSim wiring, DAX parsing, and result tracking from
 * AbstractWorkflowBroker. This class owns only the CBMW-specific logic:
 *   - planWorkflow: deadline-aware backward sweep-line static planner
 *   - processCloudletUpdate: LST/deadline-aware dynamic dispatcher
 *   - onTaskComplete: releases the booking slot on reserved VMs
 */
public class CBMWBroker extends AbstractWorkflowBroker {

    private final CBMWDynamicSchedulingAlgorithm dynamicScheduler;

    public CBMWBroker(String name, double tightness) throws Exception {
        super(name, tightness);
        negotiation.setBeta(PaperRuntimeModel.NEGOTIATION_BETA);
        negotiation.setGamma(PaperRuntimeModel.NEGOTIATION_GAMMA);
        this.dynamicScheduler = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
    }

    @Override
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return PaperRuntimeModel.conservativeEstimate(meanExecutionTime);
    }

    // -----------------------------------------------------------------------
    // Module 2 — Static Planning (backward sweep-line)
    // -----------------------------------------------------------------------

    @Override
    protected boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks) {
        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(wfr, vmPool, negotiation);
        planner.setTaskList(tasks);
        planner.setVmList(vmPool.getAllVms());
        try {
            planner.run();
        } catch (Exception e) {
            Log.printLine(getName() + ": static planner error: " + e.getMessage());
            wfr.setAccepted(false);
            return false;
        }
        schedulePlannedOnDemandOrders(wfr, tasks);
        return true;
    }

    private void schedulePlannedOnDemandOrders(WorkflowRecord wfr, List<Task> tasks) {
        double now = CloudSim.clock();
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            if (wfr.getAssignedVm(taskId)
                    != CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL) {
                continue;
            }
            double orderTime = wfr.getScheduledStart(taskId);
            schedule(getId(), Math.max(0.0, orderTime - now),
                    WorkflowSimTags.CBMW_ON_DEMAND_ORDER, taskId);
            CBMWLogger.logf("CBMW-ONDEMAND-ORDER-SCHEDULED",
                    "wf=%d task=%d orderAt=%.4f now=%.4f",
                    wfr.getWorkflowId(), taskId, orderTime, now);
        }
    }

    @Override
    protected void finalizeWorkflowNegotiation(WorkflowRecord wfr) {
        negotiation.quoteExecutionPrice(wfr);
    }

    // -----------------------------------------------------------------------
    // Module 3 — Dynamic Dispatch
    // -----------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    protected void processCloudletUpdate(SimEvent ev) {
        recordReadyQueue((List<Cloudlet>) getCloudletList());
        dynamicScheduler.setCloudletList(getCloudletList());
        dynamicScheduler.setVmList(getVmsCreatedList());
        dynamicScheduler.getScheduledList().clear();

        try {
            dynamicScheduler.run();
        } catch (Exception e) {
            Log.printLine("CBMW dynamic scheduler error: " + e.getMessage());
        }

        dispatchScheduledJobs(dynamicScheduler.getScheduledList());
    }

    // -----------------------------------------------------------------------
    // Module 4 — Release booking slot on reserved-VM completion
    // -----------------------------------------------------------------------

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == WorkflowSimTags.CBMW_ON_DEMAND_ORDER) {
            int taskId = (Integer) ev.getData();
            orderLogicalOnDemandContainer(taskId);
            sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            return;
        }
        super.processEvent(ev);
    }

    @Override
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
    }
}
