package org.workflowsim.cbmw.baselines;

import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.cbmw.WorkflowRecord;

/** Timing and ready-task classification policy used by the CEWB broker. */
interface CEWBTaskPolicy {

    void preprocess(WorkflowRecord workflow, List<Task> tasks);

    Map<Integer, CEWBTaskDecision> classify(List<Task> readyTasks,
                                            WorkflowRecord workflow,
                                            double now);

    double getUpwardRank(int taskId);

    String getName();
}
