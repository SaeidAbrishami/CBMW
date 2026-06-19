package org.workflowsim.cbmw;

import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.Task;

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
        this.dynamicScheduler = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
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
            return false;
        }
        return true;
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
    protected void onTaskComplete(Cloudlet cl) {
        vmPool.releaseSlot(cl.getCloudletId());
    }
}
