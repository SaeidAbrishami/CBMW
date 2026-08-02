package org.workflowsim.cbmw.baselines;

import java.util.HashMap;
import java.util.Map;

/** Per-workflow state used by the paper NOSF preprocessing and feedback rules. */
final class NOSFWorkflowState {
    final int workflowId;
    final Map<Integer, NOSFTaskState> tasks = new HashMap<>();
    final Map<Integer, Double> actualFinishTimes = new HashMap<>();

    NOSFWorkflowState(int workflowId) {
        this.workflowId = workflowId;
    }

    NOSFTaskState task(int taskId) {
        NOSFTaskState state = tasks.get(taskId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown NOSF task " + taskId
                    + " in workflow " + workflowId);
        }
        return state;
    }
}
