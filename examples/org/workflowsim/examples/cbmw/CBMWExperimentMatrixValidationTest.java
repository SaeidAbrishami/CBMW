package org.workflowsim.examples.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.cbmw.WorkflowArrivalData;
import org.workflowsim.cbmw.WorkflowLoader.DatasetMode;

/** Focused validation for the 500/200 experiment matrix. */
public final class CBMWExperimentMatrixValidationTest {

    private CBMWExperimentMatrixValidationTest() {}

    public static void main(String[] args) {
        List<String> expected = Arrays.asList(
                "arrival15_alpha1.2",
                "arrival15_alpha2",
                "arrival15_alpha4",
                "arrival30_alpha1.2",
                "arrival30_alpha2",
                "arrival30_alpha4",
                "arrival45_alpha1.2",
                "arrival45_alpha2",
                "arrival45_alpha4",
                "arrival60_alpha1.2",
                "arrival60_alpha2",
                "arrival60_alpha4");
        assert expected.equals(CBMWSimulation.defaultExperimentNames());
        for (String algorithm : Arrays.asList("CBMW", "NOSF", "CEWB", "StaticGreedy", "DynamicGreedy")) {
            assert CBMWSimulation.defaultScenarioCountForAlgorithm(algorithm) == 24;
            assert CBMWSimulation.shouldRunDatasetMode(algorithm, DatasetMode.EDGE_200);
        }
        for (int mean : new int[] {15, 30, 45, 60}) {
            assert CBMWSimulation.manifestFor(mean, DatasetMode.FULL_500).equals(
                    "dax_poisson_arrivals_mean" + mean + "s_500workflows.json");
            assert CBMWSimulation.manifestFor(mean, DatasetMode.EDGE_200).equals(
                    "dax_poisson_arrivals_mean" + mean + "s_edge200.json");
        }
        System.out.println("CBMWExperimentMatrixValidationTest: PASS");
    }
}
