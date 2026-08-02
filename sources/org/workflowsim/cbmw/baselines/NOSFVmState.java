package org.workflowsim.cbmw.baselines;

import org.workflowsim.CondorVM;
import org.workflowsim.Job;

/** One paper NOSF VM: at most one running and one waiting task. */
final class NOSFVmState {
    final CondorVM vm;
    final NOSFVmType type;
    final double orderTime;
    final double readyTime;
    double plannedAvailableTime;
    double chargedCost;
    double busyTime;
    double waitingDataReadyTime = Double.NaN;
    double releaseAt = Double.POSITIVE_INFINITY;
    int completedTasks;
    boolean launched;
    boolean released;
    Job running;
    Job waiting;

    NOSFVmState(CondorVM vm, NOSFVmType type, double orderTime, double readyTime) {
        this.vm = vm;
        this.type = type;
        this.orderTime = orderTime;
        this.readyTime = readyTime;
        this.plannedAvailableTime = readyTime;
    }

    boolean canAcceptWaitingTask() {
        return !released && waiting == null;
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
