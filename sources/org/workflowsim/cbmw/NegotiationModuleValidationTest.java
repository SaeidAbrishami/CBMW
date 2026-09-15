package org.workflowsim.cbmw;

/** Focused validation for deadline-feasibility floating-point comparisons. */
public final class NegotiationModuleValidationTest {

    private static final double DEFAULT_EPSILON = 1.0e-12;

    private NegotiationModuleValidationTest() {}

    public static void main(String[] args) {
        require(NegotiationModule.isDeadlineFeasible(
                1599.312, 1599.312, DEFAULT_EPSILON),
                "An exact deadline boundary must be accepted");

        require(NegotiationModule.isDeadlineFeasible(
                1599.3120000000004, 1599.312, DEFAULT_EPSILON),
                "Sub-nanosecond floating-point drift must be accepted");

        double tolerance = NegotiationModule.feasibilityTolerance(
                1599.312, 1599.312, DEFAULT_EPSILON);
        require(NegotiationModule.isDeadlineFeasible(
                1599.312 + tolerance * 0.5, 1599.312, DEFAULT_EPSILON),
                "A difference within tolerance must be accepted");

        require(!NegotiationModule.isDeadlineFeasible(
                1599.322, 1599.312, DEFAULT_EPSILON),
                "A meaningful deadline deficit must be rejected");

        require(!NegotiationModule.isDeadlineFeasible(
                1599.3120000000004, 1599.312, 0.0),
                "Zero epsilon must retain strict comparison behavior");

        requireThrows(-1.0);
        requireThrows(Double.NaN);
        requireThrows(Double.POSITIVE_INFINITY);

        System.out.println("NegotiationModuleValidationTest: PASS");
    }

    private static void requireThrows(double relativeEpsilon) {
        boolean threw = false;
        try {
            NegotiationModule.isDeadlineFeasible(1.0, 1.0, relativeEpsilon);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "Invalid relative epsilon must be rejected: "
                + relativeEpsilon);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
