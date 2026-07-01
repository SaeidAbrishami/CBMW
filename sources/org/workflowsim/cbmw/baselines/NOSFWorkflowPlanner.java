package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.Task;
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.WorkflowRecord;

/** Critical-path preprocessing and completion feedback for reconstructed NOSF. */
final class NOSFWorkflowPlanner {

    private final Map<Integer, Map<Integer, Double>> actualFinishTimes =
            new HashMap<>();

    void preprocess(WorkflowRecord wfr, List<Task> tasks) {
        Map<Integer, Double> finishMemo = new HashMap<>();
        double predictedFinish = wfr.getArrivalTime();
        for (Task task : tasks) {
            predictedFinish = Math.max(predictedFinish,
                    computePredictedFinish(task, wfr, finishMemo));
        }
        distributeSubDeadlines(wfr, tasks, wfr.getArrivalTime(), predictedFinish);

        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            task.setWorkflowId(wfr.getWorkflowId());
            task.setVmId(CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
            wfr.setAssignedVm(taskId,
                    CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
            wfr.setScheduledStart(taskId, wfr.getEST(taskId));
        }
    }

    void feedback(WorkflowRecord wfr, int completedTaskId, double finishTime) {
        actualFinishes(wfr).put(completedTaskId, finishTime);
        Task completedTask = null;
        for (Task task : wfr.getTaskList()) {
            if (task.getCloudletId() == completedTaskId) {
                completedTask = task;
                break;
            }
        }
        if (completedTask == null) return;

        Set<Task> successors = new LinkedHashSet<>();
        collectDescendants(completedTask, successors);
        if (successors.isEmpty()) return;

        Map<Integer, Double> finishMemo = new HashMap<>();
        double predictedFinish = finishTime;
        for (Task task : successors) {
            predictedFinish = Math.max(predictedFinish,
                    computePredictedFinish(task, wfr, finishMemo));
        }
        distributeSubDeadlines(wfr, new ArrayList<>(successors), finishTime,
                predictedFinish);
    }

    private double computePredictedFinish(Task task, WorkflowRecord wfr,
                                          Map<Integer, Double> memo) {
        int taskId = task.getCloudletId();
        Double actualFinish = actualFinishes(wfr).get(taskId);
        if (actualFinish != null) return actualFinish;
        Double cached = memo.get(taskId);
        if (cached != null) return cached;

        double est = wfr.getArrivalTime();
        for (Task parent : task.getParentList()) {
            est = Math.max(est, computePredictedFinish(parent, wfr, memo));
        }
        double eft = est + wfr.getEstimatedExecTime(task);
        wfr.setEST(taskId, est);
        wfr.setEFT(taskId, eft);
        memo.put(taskId, eft);
        return eft;
    }

    private void distributeSubDeadlines(WorkflowRecord wfr, List<Task> tasks,
                                        double origin, double predictedFinish) {
        double predictedSpan = Math.max(1e-9, predictedFinish - origin);
        double deadlineSpan = Math.max(0.0, wfr.getDeadline() - origin);

        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            if (wfr.isTaskCompleted(taskId)) continue;
            double progress = (wfr.getEFT(taskId) - origin) / predictedSpan;
            progress = Math.max(0.0, Math.min(1.0, progress));
            double subDeadline = origin + deadlineSpan * progress;
            subDeadline = Math.max(wfr.getEFT(taskId), subDeadline);
            subDeadline = Math.min(wfr.getDeadline(), subDeadline);
            double duration = wfr.getEstimatedExecTime(task);
            wfr.setLFT(taskId, subDeadline);
            wfr.setLST(taskId, subDeadline - duration);
        }
    }

    private Map<Integer, Double> actualFinishes(WorkflowRecord wfr) {
        return actualFinishTimes.computeIfAbsent(wfr.getWorkflowId(),
                ignored -> new HashMap<>());
    }

    private void collectDescendants(Task task, Set<Task> descendants) {
        for (Task child : task.getChildList()) {
            if (descendants.add(child)) {
                collectDescendants(child, descendants);
            }
        }
    }
}
