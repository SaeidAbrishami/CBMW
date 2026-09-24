package org.workflowsim.cbmw;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Focused validation for flat dataset loading and arrival-ordered views. */
public final class WorkflowLoaderValidationTest {

    private WorkflowLoaderValidationTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("workflow-loader-validation");
        try {
            createDataset(root);
            validateFullDataset(root);
            validateEdgeDataset(root);
            validateConfiguredMode(root);
            validateDuplicateDetection(root);
            validateMalformedManifest(root);
            validateMissingPairDetection(root);
            System.out.println("WorkflowLoaderValidationTest: PASS");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void createDataset(Path root) throws Exception {
        StringBuilder manifest = new StringBuilder("{\n");
        for (int i = 499; i >= 0; i--) {
            String name = String.format("Workflow_%03d", i);
            Files.write(root.resolve(name + ".xml"),
                    ("<adag><job id=\"ID000\" runtime=\"1.0\"/></adag>\n")
                            .getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve(name + ".txt"),
                    "1.0\n".getBytes(StandardCharsets.UTF_8));
            manifest.append("  \"").append(name).append("\": ")
                    .append(10.0 + i * 3.25);
            manifest.append(i == 0 ? "\n" : ",\n");
        }
        manifest.append("}\n");
        StringBuilder edge = new StringBuilder("[\n");
        for (int i = 199; i >= 0; i--) {
            int id = i < 100 ? i : i + 300;
            edge.append("{\"workflow_name\":\"Workflow_")
                    .append(String.format("%03d", id))
                    .append(".xml\",\"arrival_time_seconds\":")
                    .append(10.0 + i * 3.25).append("}")
                    .append(i == 0 ? "\n" : ",\n");
        }
        edge.append("]");
        Files.write(root.resolve("edge.json"), edge.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("poisson_distribution.json"),
                manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void validateFullDataset(Path root) throws Exception {
        List<WorkflowArrivalData> arrivals = WorkflowLoader.load(
                root.toString(), "poisson_distribution.json", 2.0,
                WorkflowLoader.DatasetMode.FULL_500);
        assert arrivals.size() == 500 : "FULL_500 must load 500 workflows";
        assert arrivals.get(0).getArrivalTime() == 10.0
                : "Full dataset must preserve the first absolute arrival";
        assert arrivals.get(499).getArrivalTime() == 10.0 + 499 * 3.25
                : "Full dataset must preserve the last absolute arrival";
        assert arrivals.get(0).getDaxPath().endsWith("Workflow_000.xml")
                : "FULL_500 must be ordered by arrival time, not manifest order";
    }

    private static void validateEdgeDataset(Path root) throws Exception {
        List<WorkflowArrivalData> arrivals = WorkflowLoader.load(
                root.toString(), "edge.json", 2.0,
                WorkflowLoader.DatasetMode.EDGE_200);
        assert arrivals.size() == 200 : "EDGE_200 must load 200 workflows";
        assert arrivals.get(0).getArrivalTime() == 10.0
                : "Edge dataset must preserve the earliest arrivals";
        assert arrivals.get(99).getArrivalTime() == 10.0 + 99 * 3.25
                : "Edge dataset must preserve workflow 100's arrival";
        assert arrivals.get(100).getArrivalTime() == 10.0 + 100 * 3.25
                : "The first late workflow must be rebased to workflow 101's arrival";
        assert arrivals.get(199).getArrivalTime() == 10.0 + 199 * 3.25
                : "The late block must preserve its internal inter-arrival times";
        assert arrivals.get(99).getDaxPath().endsWith("Workflow_099.xml")
                : "EDGE_200 must include the earliest 100 workflows";
        assert arrivals.get(100).getDaxPath().endsWith("Workflow_400.xml")
                : "EDGE_200 must include the latest 100 workflows";
        assert arrivals.get(199).getDaxPath().endsWith("Workflow_499.xml")
                : "EDGE_200 must end with the latest workflow";
        assert Math.abs(arrivals.get(100).getUserDeadline()
                    - arrivals.get(100).getArrivalTime()
                    - (HybridVmPool.ON_DEMAND_PROVISIONING_DELAY
                            + 2.0 * (1.0 + PaperRuntimeModel.PLANNING_ALPHA)))
                < 1e-9 : "Deadlines must include one OPD and the conservative CP";

        List<WorkflowArrivalData> fullAfterEdge = WorkflowLoader.load(
                root.toString(), "poisson_distribution.json", 2.0,
                WorkflowLoader.DatasetMode.FULL_500);
        assert fullAfterEdge.get(499).getArrivalTime() == 10.0 + 499 * 3.25
                : "EDGE_200 loading must not mutate FULL_500 arrivals";
    }

    private static void validateConfiguredMode(Path root) throws Exception {
        String previous = System.getProperty(WorkflowLoader.DATASET_MODE_PROPERTY);
        try {
            System.setProperty(WorkflowLoader.DATASET_MODE_PROPERTY, "EDGE_200");
            List<WorkflowArrivalData> arrivals = WorkflowLoader.load(
                    root.toString(), "edge.json", 2.0);
            assert arrivals.size() == 200
                    : "Configured dataset mode must affect the legacy load API";
        } finally {
            if (previous == null) {
                System.clearProperty(WorkflowLoader.DATASET_MODE_PROPERTY);
            } else {
                System.setProperty(WorkflowLoader.DATASET_MODE_PROPERTY, previous);
            }
        }
    }

    private static void validateDuplicateDetection(Path root) throws Exception {
        Path duplicateDirectory = root.resolve("duplicate");
        Files.createDirectories(duplicateDirectory);
        Path original = root.resolve("Workflow_100.xml");
        Path duplicate = duplicateDirectory.resolve("Workflow_100.xml");
        Files.copy(original, duplicate, StandardCopyOption.REPLACE_EXISTING);
        try {
            expectFailure(root, "exactly one .xml");
        } finally {
            Files.deleteIfExists(duplicate);
            Files.deleteIfExists(duplicateDirectory);
        }
    }

    private static void validateMalformedManifest(Path root) throws Exception {
        String valid = new String(Files.readAllBytes(root.resolve("edge.json")), StandardCharsets.UTF_8);
        for (String invalid : new String[] {
                valid.replace("Workflow_499.xml", "Workflow_498.xml"),
                valid.replace("656.75", "-1"),
                valid.substring(0, valid.length() - 1),
                valid.replace("arrival_time_seconds", "unknown")}) {
            Files.write(root.resolve("invalid.json"), invalid.getBytes(StandardCharsets.UTF_8));
            boolean rejected = false;
            try {
                WorkflowLoader.load(root.toString(), "invalid.json", 2.0, WorkflowLoader.DatasetMode.EDGE_200);
            } catch (IllegalArgumentException expected) { rejected = true; }
            assert rejected : "Invalid manifest must fail";
        }
    }

    private static void validateMissingPairDetection(Path root) throws Exception {
        Path txt = root.resolve("Workflow_499.txt");
        Files.delete(txt);
        expectFailure(root, "exactly one .txt");
    }

    private static void expectFailure(Path root, String expectedMessage)
            throws Exception {
        boolean failedAsExpected = false;
        try {
            WorkflowLoader.load(root.toString(), "poisson_distribution.json", 2.0,
                    WorkflowLoader.DatasetMode.FULL_500);
        } catch (IllegalArgumentException expected) {
            failedAsExpected = expected.getMessage().contains(expectedMessage);
        }
        assert failedAsExpected : "Expected validation failure containing: "
                + expectedMessage;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths
                    .sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
