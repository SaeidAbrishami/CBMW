package org.workflowsim.cbmw.ablation;

import org.workflowsim.cbmw.CBMWBroker;

/** Keep the CBMW static plan and its exact dispatch events without adaptation. */
public final class CBMWNoAdvanceBroker extends CBMWBroker {
    public CBMWNoAdvanceBroker(String name, double tightness) throws Exception {
        super(name, tightness);
    }

    @Override
    protected boolean advanceFutureTasks() { return false; }

    @Override
    protected boolean migrateDueOnDemandTasks() { return false; }
}
