package org.workflowsim.cbmw.baselines;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Minimize incremental hourly rental among subdeadline-feasible VM choices. */
final class NOSFResourceSelector {
    private static final double EPS = 1e-9;

    static final class Choice {
        final NOSFVmState vm;
        final NOSFVmType newType;
        final double start;
        final double finish;
        final double selectionCost;
        final double incrementalRentalCost;
        final double idleTime;
        final double dataReadyTime;
        final boolean feasible;

        Choice(NOSFVmState vm, NOSFVmType newType, double start, double finish,
               double selectionCost, double incrementalRentalCost,
               double idleTime, double dataReadyTime, boolean feasible) {
            this.vm = vm;
            this.newType = newType;
            this.start = start;
            this.finish = finish;
            this.selectionCost = selectionCost;
            this.incrementalRentalCost = incrementalRentalCost;
            this.idleTime = idleTime;
            this.dataReadyTime = dataReadyTime;
            this.feasible = feasible;
        }
    }

    private final double provisioningDelay;
    private final double billingQuantum;

    NOSFResourceSelector(double provisioningDelay, double billingQuantum) {
        this.provisioningDelay = provisioningDelay;
        this.billingQuantum = billingQuantum;
    }

    Choice choose(double now, double baseRuntime, int cores, int ramMb,
                  double subDeadline, List<NOSFVmState> active,
                  List<NOSFVmType> types) {
        return choose(now, baseRuntime, cores, ramMb, subDeadline, active, types,
                Collections.<Integer, Double>emptyMap(), now);
    }

    Choice choose(double now, double baseRuntime, int cores, int ramMb,
                  double subDeadline, List<NOSFVmState> active,
                  List<NOSFVmType> types,
                  Map<Integer, Double> dataReadyByVm,
                  double newVmDataReady) {
        Choice best = null;
        for (NOSFVmState state : active) {
            if (!state.canAcceptWaitingTask()
                    || !state.type.canRun(cores, ramMb)) continue;
            double available = Math.max(now,
                    Math.max(state.readyTime, state.plannedAvailableTime));
            double dataReady = dataReadyByVm.getOrDefault(state.vm.getId(), now);
            double start = Math.max(available, dataReady);
            double runtime = state.type.runtime(baseRuntime);
            double finish = start + runtime;
            double oldCost = state.billedCost(available, billingQuantum);
            double newCost = state.billedCost(finish, billingQuantum);
            boolean feasible = finish <= subDeadline + EPS;
            Choice candidate = new Choice(state, null, start, finish,
                    state.type.pricePerSecond * runtime,
                    Math.max(0.0, newCost - oldCost),
                    Math.max(0.0, start - available), dataReady, feasible);
            if (better(candidate, best)) best = candidate;
        }

        for (NOSFVmType type : types) {
            if (!type.canRun(cores, ramMb)) continue;
            double start = Math.max(now + provisioningDelay, newVmDataReady);
            double runtime = type.runtime(baseRuntime);
            double finish = start + runtime;
            boolean feasible = finish <= subDeadline + EPS;
            Choice candidate = new Choice(null, type, start, finish,
                    type.pricePerSecond * runtime,
                    billedCost(now + provisioningDelay, finish,
                            type.pricePerSecond),
                    Math.max(0.0, start - (now + provisioningDelay)),
                    newVmDataReady, feasible);
            if (better(candidate, best)) best = candidate;
        }
        return best;
    }

    private double billedCost(double ready, double finish, double price) {
        double leased = Math.max(0.0, finish - ready);
        return Math.ceil(leased / billingQuantum) * billingQuantum * price;
    }

    private boolean better(Choice a, Choice b) {
        if (b == null) return true;
        // For rigid tasks, a longer active-VM queue can save a whole rental
        // hour while still satisfying the task's NOSF subdeadline.
        if (a.feasible != b.feasible) return a.feasible;
        if (a.feasible
                && Math.abs(a.incrementalRentalCost - b.incrementalRentalCost) > EPS) {
            return a.incrementalRentalCost < b.incrementalRentalCost;
        }
        // When no resource can meet the subdeadline, minimize lateness rather
        // than buying a cheaper VM that finishes even later.
        if (Math.abs(a.finish - b.finish) > EPS) return a.finish < b.finish;
        if (Math.abs(a.incrementalRentalCost - b.incrementalRentalCost) > EPS) {
            return a.incrementalRentalCost < b.incrementalRentalCost;
        }
        return stableId(a) < stableId(b);
    }

    private int stableId(Choice choice) {
        return choice.vm != null ? choice.vm.vm.getId() : Integer.MAX_VALUE;
    }
}
