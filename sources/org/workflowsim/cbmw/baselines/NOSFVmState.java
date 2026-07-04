package org.workflowsim.cbmw.baselines;

import java.util.ArrayDeque;
import java.util.Queue;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;

/** Mutable scheduling and execution state for one reusable NOSF VM. */
final class NOSFVmState {
    final CondorVM vm;
    final NOSFVmType type;
    final double orderTime;
    final double readyTime;
    final Queue<Job> queue = new ArrayDeque<>();
    double plannedAvailableTime;
    double plannedShutdownTime;
    double chargedCost;
    double busyTime;
    int completedTasks;
    boolean launched;
    Job running;

    NOSFVmState(CondorVM vm, NOSFVmType type, double orderTime, double readyTime) {
        this.vm = vm;
        this.type = type;
        this.orderTime = orderTime;
        this.readyTime = readyTime;
        this.plannedAvailableTime = readyTime;
        this.plannedShutdownTime = readyTime;
    }

    double billedCost(double shutdown, double quantum) {
        double active = Math.max(0.0, shutdown - readyTime);
        if (active <= 0.0) return 0.0;
        return Math.ceil(active / quantum) * quantum * type.pricePerSecond;
    }
}
