package org.workflowsim.cbmw.ablation;

import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.NegotiationModule;
import org.workflowsim.cbmw.WorkflowRecord;

/** Forward resource-profile sweep with the same timing and fallback model. */
public final class CBMWEarlyPlanningAlgorithm extends CBMWStaticPlanningAlgorithm {
    public CBMWEarlyPlanningAlgorithm(WorkflowRecord workflow, HybridVmPool pool,
                                      NegotiationModule negotiation) {
        super(workflow, pool, negotiation, Placement.EARLIEST);
    }
}
