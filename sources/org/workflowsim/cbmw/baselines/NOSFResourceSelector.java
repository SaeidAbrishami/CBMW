package org.workflowsim.cbmw.baselines;

import java.util.List;

/** Pure NOSF feasibility and minimum incremental-cost selection logic. */
final class NOSFResourceSelector {
    private static final double EPS = 1e-9;

    static final class Choice {
        final NOSFVmState vm;
        final NOSFVmType newType;
        final double start;
        final double finish;
        final double incrementalCost;
        final boolean feasible;

        Choice(NOSFVmState vm, NOSFVmType newType, double start, double finish,
               double incrementalCost, boolean feasible) {
            this.vm = vm;
            this.newType = newType;
            this.start = start;
            this.finish = finish;
            this.incrementalCost = incrementalCost;
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
        Choice best = null;
        for (NOSFVmState state : active) {
            if (!state.type.canRun(cores, ramMb)) continue;
            double start = Math.max(now, state.plannedAvailableTime);
            double finish = start + state.type.runtime(baseRuntime);
            if (finish > subDeadline + EPS) continue;
            double oldCost = state.billedCost(state.plannedShutdownTime, billingQuantum);
            double newCost = state.billedCost(
                    Math.max(state.plannedShutdownTime, finish), billingQuantum);
            Choice candidate = new Choice(state, null, start, finish,
                    newCost - oldCost, true);
            if (better(candidate, best)) best = candidate;
        }
        if (best != null) return best;

        for (NOSFVmType type : types) {
            if (!type.canRun(cores, ramMb)) continue;
            double start = now + provisioningDelay;
            double finish = start + type.runtime(baseRuntime);
            if (finish > subDeadline + EPS) continue;
            double cost = billedCost(start, finish, type.pricePerSecond);
            Choice candidate = new Choice(null, type, start, finish, cost, true);
            if (better(candidate, best)) best = candidate;
        }
        if (best != null) return best;

        // Explicit deadline-risk policy: choose the earliest finish, then cost.
        for (NOSFVmState state : active) {
            if (!state.type.canRun(cores, ramMb)) continue;
            double start = Math.max(now, state.plannedAvailableTime);
            double finish = start + state.type.runtime(baseRuntime);
            double oldCost = state.billedCost(state.plannedShutdownTime, billingQuantum);
            double newCost = state.billedCost(
                    Math.max(state.plannedShutdownTime, finish), billingQuantum);
            Choice candidate = new Choice(state, null, start, finish,
                    newCost - oldCost, false);
            if (earlier(candidate, best)) best = candidate;
        }
        for (NOSFVmType type : types) {
            if (!type.canRun(cores, ramMb)) continue;
            double start = now + provisioningDelay;
            double finish = start + type.runtime(baseRuntime);
            Choice candidate = new Choice(null, type, start, finish,
                    billedCost(start, finish, type.pricePerSecond), false);
            if (earlier(candidate, best)) best = candidate;
        }
        return best;
    }

    private double billedCost(double start, double finish, double price) {
        double active = Math.max(0.0, finish - start);
        return Math.ceil(active / billingQuantum) * billingQuantum * price;
    }

    private boolean better(Choice a, Choice b) {
        if (b == null) return true;
        if (Math.abs(a.incrementalCost - b.incrementalCost) > EPS) {
            return a.incrementalCost < b.incrementalCost;
        }
        if (Math.abs(a.finish - b.finish) > EPS) return a.finish < b.finish;
        return stableId(a) < stableId(b);
    }

    private boolean earlier(Choice a, Choice b) {
        if (b == null) return true;
        if (Math.abs(a.finish - b.finish) > EPS) return a.finish < b.finish;
        return better(a, b);
    }

    private int stableId(Choice choice) {
        return choice.vm != null ? choice.vm.vm.getId() : Integer.MAX_VALUE;
    }
}
