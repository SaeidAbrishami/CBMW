package org.workflowsim.cbmw.baselines;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Literal NOSF Algorithm 3 feasibility and suitable-VM selection. */
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
            if (finish > subDeadline + EPS) continue;
            double oldCost = state.billedCost(available, billingQuantum);
            double newCost = state.billedCost(finish, billingQuantum);
            Choice candidate = new Choice(state, null, start, finish,
                    state.type.pricePerSecond * runtime,
                    Math.max(0.0, newCost - oldCost),
                    Math.max(0.0, start - available), dataReady, true);
            if (better(candidate, best)) best = candidate;
        }
        if (best != null) return best;

        for (NOSFVmType type : types) {
            if (!type.canRun(cores, ramMb)) continue;
            double start = Math.max(now + provisioningDelay, newVmDataReady);
            double runtime = type.runtime(baseRuntime);
            double finish = start + runtime;
            if (finish > subDeadline + EPS) continue;
            Choice candidate = new Choice(null, type, start, finish,
                    type.pricePerSecond * runtime,
                    billedCost(now, finish, type.pricePerSecond),
                    Math.max(0.0, start - (now + provisioningDelay)),
                    newVmDataReady, true);
            if (better(candidate, best)) best = candidate;
        }
        if (best != null) return best;

        // Algorithm 3, lines 16-17: lease a new highest-ranking compatible VM.
        NOSFVmType fastest = null;
        for (NOSFVmType type : types) {
            if (!type.canRun(cores, ramMb)) continue;
            if (fastest == null
                    || type.runtime(baseRuntime) < fastest.runtime(baseRuntime) - EPS
                    || (Math.abs(type.runtime(baseRuntime)
                            - fastest.runtime(baseRuntime)) <= EPS
                        && type.pricePerSecond < fastest.pricePerSecond)) {
                fastest = type;
            }
        }
        if (fastest == null) return null;
        double start = Math.max(now + provisioningDelay, newVmDataReady);
        double runtime = fastest.runtime(baseRuntime);
        double finish = start + runtime;
        return new Choice(null, fastest, start, finish,
                fastest.pricePerSecond * runtime,
                billedCost(now, finish, fastest.pricePerSecond),
                Math.max(0.0, start - (now + provisioningDelay)),
                newVmDataReady, false);
    }

    private double billedCost(double order, double finish, double price) {
        double leased = Math.max(0.0, finish - order);
        return Math.ceil(leased / billingQuantum) * billingQuantum * price;
    }

    /** Paper suitable VM: minimum execution cost, then minimum idle time. */
    private boolean better(Choice a, Choice b) {
        if (b == null) return true;
        if (Math.abs(a.selectionCost - b.selectionCost) > EPS) {
            return a.selectionCost < b.selectionCost;
        }
        if (Math.abs(a.idleTime - b.idleTime) > EPS) {
            return a.idleTime < b.idleTime;
        }
        if (Math.abs(a.finish - b.finish) > EPS) return a.finish < b.finish;
        return stableId(a) < stableId(b);
    }

    private int stableId(Choice choice) {
        return choice.vm != null ? choice.vm.vm.getId() : Integer.MAX_VALUE;
    }
}
