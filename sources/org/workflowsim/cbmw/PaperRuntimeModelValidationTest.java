package org.workflowsim.cbmw;

/** Focused invariants for CBMW's additive planning-runtime margin. */
public final class PaperRuntimeModelValidationTest {

    private static final double EPSILON = 1.0e-9;

    private PaperRuntimeModelValidationTest() {}

    public static void main(String[] args) {
        assertClose(PaperRuntimeModel.PLANNING_ALPHA, 0.20,
                "Default planning alpha must be 20 percent");
        assertClose(PaperRuntimeModel.conservativeEstimate(100.0), 120.0,
                "Planning runtime must add alpha times the mean");
        assertClose(PaperRuntimeModel.conservativeEstimate(0.0), 0.0,
                "Zero runtime must remain zero");
        assertClose(PaperRuntimeModel.NEGOTIATION_FEASIBILITY_REL_EPSILON,
                1.0e-12,
                "Default negotiation feasibility epsilon must be reproducible");

        System.out.println("PaperRuntimeModelValidationTest: PASS");
    }

    private static void assertClose(double actual, double expected,
                                    String message) {
        assert Math.abs(actual - expected) < EPSILON
                : message + ": expected=" + expected + " actual=" + actual;
    }
}
