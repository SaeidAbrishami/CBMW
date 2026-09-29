package org.workflowsim.cbmw.ablation;

import org.workflowsim.cbmw.CBMWBroker;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.WorkflowRecord;

/** CBMW with earliest feasible static reserved placement. */
public final class CBMWEarlyBroker extends CBMWBroker {
    public CBMWEarlyBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected CBMWStaticPlanningAlgorithm createStaticPlanner(WorkflowRecord workflow) {
        return new CBMWEarlyPlanningAlgorithm(workflow, vmPool, negotiation);
    }
}
