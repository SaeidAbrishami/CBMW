package org.workflowsim.cbmw.baselines;

import org.workflowsim.Task;

/** Paper decision parameters for one NOSF task. */
final class NOSFTaskState {
    final Task task;
    final int taskId;
    final double mean;
    final double sigma;
    final double weight;
    final double fastestDuration;

    double initialEst;
    double initialEft;
    double currentEst;
    double currentEft;
    double latestCompletion;
    double subDeadline;
    double delta;
    double priority;

    NOSFTaskState(Task task, double mean, double sigma, double weight,
                  double fastestDuration) {
        this.task = task;
        this.taskId = task.getCloudletId();
        this.mean = mean;
        this.sigma = sigma;
        this.weight = weight;
        this.fastestDuration = fastestDuration;
    }
}
