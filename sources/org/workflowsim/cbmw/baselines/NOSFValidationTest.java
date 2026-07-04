package org.workflowsim.cbmw.baselines;

import java.util.Arrays;
import java.util.Collections;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;

/** Fast invariant tests for the reference NOSF resource-selection rules. */
public final class NOSFValidationTest {
    private NOSFValidationTest() {}

    public static void main(String[] args) {
        NOSFVmType type = new NOSFVmType("test", 1, 1024, 1000.0, 0.01);
        NOSFResourceSelector selector = new NOSFResourceSelector(0.0, 60.0);

        NOSFResourceSelector.Choice fresh = selector.choose(0.0, 10.0, 1, 1,
                100.0, Collections.<NOSFVmState>emptyList(), Arrays.asList(type));
        require(fresh != null && fresh.newType == type && fresh.vm == null,
                "must provision when no active VM exists");
        require(fresh.feasible && close(fresh.incrementalCost, 0.60),
                "new VM must use minute-rounded cost");

        CondorVM vm = new CondorVM(42, 1, 1000.0, 1, 1024, 10000, 100000,
                "Xen", 0.01, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        NOSFVmState state = new NOSFVmState(vm, type, 0.0, 0.0);
        state.plannedAvailableTime = 20.0;
        state.plannedShutdownTime = 20.0;
        NOSFResourceSelector.Choice reuse = selector.choose(20.0, 10.0, 1, 1,
                100.0, Arrays.asList(state), Arrays.asList(type));
        require(reuse != null && reuse.vm == state && reuse.feasible,
                "must reuse a feasible active VM");
        require(close(reuse.incrementalCost, 0.0),
                "reuse inside paid minute must have zero incremental cost");

        NOSFResourceSelector.Choice risk = selector.choose(20.0, 10.0, 1, 1,
                25.0, Arrays.asList(state), Arrays.asList(type));
        require(risk != null && !risk.feasible,
                "infeasible task must be marked deadline-risk");
        require(risk.vm == state,
                "risk fallback must choose earliest/cheapest available option");

        NOSFVmType tooSmall = new NOSFVmType("small", 1, 512, 1000.0, 0.001);
        NOSFResourceSelector.Choice incompatible = selector.choose(0.0, 10.0,
                2, 1024, 100.0, Collections.<NOSFVmState>emptyList(),
                Arrays.asList(tooSmall));
        require(incompatible == null, "incompatible VM types must not be selected");

        System.out.println("NOSF invariant tests: PASS");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
