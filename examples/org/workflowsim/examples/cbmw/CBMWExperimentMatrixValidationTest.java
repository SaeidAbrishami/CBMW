package org.workflowsim.examples.cbmw;

import java.util.Arrays;
import java.util.List;
import org.workflowsim.cbmw.WorkflowLoader.DatasetMode;

/** Focused validation for the 500/200 experiment matrix. */
public final class CBMWExperimentMatrixValidationTest {

    private CBMWExperimentMatrixValidationTest() {}

    public static void main(String[] args) {
        List<String> expected = Arrays.asList(
                "arrival15_alpha2",
                "arrival30_alpha1.2",
                "arrival30_alpha2",
                "arrival30_alpha3",
                "arrival30_alpha4",
                "arrival45_alpha2",
                "arrival60_alpha2");
        assert expected.equals(CBMWSimulation.defaultExperimentNames())
                : "Unexpected arrival/alpha matrix";

        assert CBMWSimulation.defaultScenarioCountForAlgorithm("CBMW") == 14;
        assert CBMWSimulation.defaultScenarioCountForAlgorithm("StaticGreedy") == 14;
        assert CBMWSimulation.defaultScenarioCountForAlgorithm("DynamicGreedy") == 14;
        assert CBMWSimulation.defaultScenarioCountForAlgorithm("NOSF") == 7;
        assert CBMWSimulation.defaultScenarioCountForAlgorithm("CEWB") == 7;
        assert CBMWSimulation.defaultScenarioCountForAlgorithm(
                "CEWB-ReferencePolicy") == 7;

        assert !CBMWSimulation.shouldRunDatasetMode("NOSF", DatasetMode.EDGE_200);
        assert !CBMWSimulation.shouldRunDatasetMode("CEWB", DatasetMode.EDGE_200);
        assert CBMWSimulation.shouldRunDatasetMode("CBMW", DatasetMode.EDGE_200);

        int total = CBMWSimulation.defaultScenarioCountForAlgorithm("CBMW")
                + CBMWSimulation.defaultScenarioCountForAlgorithm("NOSF")
                + CBMWSimulation.defaultScenarioCountForAlgorithm("CEWB")
                + CBMWSimulation.defaultScenarioCountForAlgorithm("StaticGreedy")
                + CBMWSimulation.defaultScenarioCountForAlgorithm("DynamicGreedy");
        assert total == 56 : "Default matrix must contain 56 algorithm scenarios";
        System.out.println("CBMWExperimentMatrixValidationTest: PASS");
    }
}
