package org.workflowsim.cbmw.ablation;

import org.workflowsim.cbmw.CBMWBroker;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.WorkflowRecord;

/** Earliest static plan executed at its exact starts with no advancement. */
public final class CBMWEarlyNoAdvanceBroker extends CBMWBroker {
    public CBMWEarlyNoAdvanceBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected CBMWStaticPlanningAlgorithm createStaticPlanner(WorkflowRecord workflow) {
        return new CBMWEarlyPlanningAlgorithm(workflow, vmPool, negotiation);
    }

    @Override
    protected boolean advanceFutureTasks() { return false; }

    @Override
    protected boolean migrateDueOnDemandTasks() { return false; }
}
