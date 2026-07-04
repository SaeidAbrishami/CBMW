package org.workflowsim.cbmw.baselines;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * Legacy WorkflowSim entry point. Reference NOSF requires broker-level VM
 * lifecycle, billing, sub-deadline, and feedback state and is implemented by
 * {@link NOSFBroker}; silently running the former FCFS algorithm would produce
 * results that are not NOSF.
 */
public class NOSFSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() throws Exception {
        throw new UnsupportedOperationException(
                "Reference NOSF is implemented by NOSFBroker; configure the CBMW experiment driver");
    }
}
