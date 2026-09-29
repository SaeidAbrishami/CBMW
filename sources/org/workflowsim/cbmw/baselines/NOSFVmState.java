package org.workflowsim.cbmw.baselines;

import java.util.ArrayDeque;
import java.util.Deque;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.cbmw.WorkflowRecord;

/** One NOSF VM: one running task and any number of FIFO waiting tasks. */
final class NOSFVmState {
    static final class QueuedTask {
        final Job job;
        final WorkflowRecord workflow;
        final int taskId;
        final double dataReadyTime;

        QueuedTask(Job job, WorkflowRecord workflow, int taskId,
                   double dataReadyTime) {
            this.job = job;
            this.workflow = workflow;
            this.taskId = taskId;
            this.dataReadyTime = dataReadyTime;
        }
    }

    final CondorVM vm;
    final NOSFVmType type;
    final double orderTime;
    final double readyTime;
    double plannedAvailableTime;
    double chargedCost;
    double busyTime;
    double runningPredictedFinish;
    double releaseAt = Double.POSITIVE_INFINITY;
    int completedTasks;
    boolean launched;
    boolean released;
    Job running;
    final Deque<QueuedTask> waiting = new ArrayDeque<>();

    NOSFVmState(CondorVM vm, NOSFVmType type, double orderTime, double readyTime) {
        this.vm = vm;
        this.type = type;
        this.orderTime = orderTime;
        this.readyTime = readyTime;
        this.plannedAvailableTime = readyTime;
    }

    boolean canAcceptWaitingTask() {
        return !released;
    }

    void replan(double now) {
        double cursor = running == null ? Math.max(now, readyTime)
                : Math.max(now, runningPredictedFinish);
        for (QueuedTask queued : waiting) {
            double start = Math.max(cursor, queued.dataReadyTime);
            queued.workflow.setScheduledStart(queued.taskId, start);
            cursor = start + type.runtime(
                    queued.workflow.getEstimatedExecTime(queued.taskId));
        }
        plannedAvailableTime = cursor;
    }

    double billedCost(double shutdown, double quantum) {
        double leased = Math.max(0.0, shutdown - orderTime);
        if (leased <= 0.0) return 0.0;
        return Math.ceil(leased / quantum) * quantum * type.pricePerSecond;
    }

    double currentBillingBoundary(double now, double quantum) {
        double leased = Math.max(0.0, now - orderTime);
        double quanta = Math.ceil(Math.max(leased, 1e-9) / quantum);
        return orderTime + quanta * quantum;
    }
}
