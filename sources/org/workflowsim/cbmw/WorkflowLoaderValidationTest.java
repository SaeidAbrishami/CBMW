package org.workflowsim.cbmw;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Focused validation for recursive partitioned-dataset loading. */
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
            validateMissingPairDetection(root);
            System.out.println("WorkflowLoaderValidationTest: PASS");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void createDataset(Path root) throws Exception {
        Files.createDirectories(root.resolve("first_100"));
        Files.createDirectories(root.resolve("middle_300"));
        Files.createDirectories(root.resolve("last_100"));

        StringBuilder manifest = new StringBuilder("{\n");
        for (int i = 0; i < 500; i++) {
            String partition = i < 100 ? "first_100"
                    : i < 400 ? "middle_300" : "last_100";
            String name = String.format("Workflow_%03d", i);
            Path directory = root.resolve(partition);
            Files.write(directory.resolve(name + ".xml"),
                    ("<adag><job id=\"ID000\" runtime=\"1.0\"/></adag>\n")
                            .getBytes(StandardCharsets.UTF_8));
            Files.write(directory.resolve(name + ".txt"),
                    "1.0\n".getBytes(StandardCharsets.UTF_8));
            manifest.append("  \"").append(name).append("\": ")
                    .append(10.0 + i * 3.25);
            manifest.append(i == 499 ? "\n" : ",\n");
        }
        manifest.append("}\n");
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
    }

    private static void validateEdgeDataset(Path root) throws Exception {
        List<WorkflowArrivalData> arrivals = WorkflowLoader.load(
                root.toString(), "poisson_distribution.json", 2.0,
                WorkflowLoader.DatasetMode.EDGE_200);
        assert arrivals.size() == 200 : "EDGE_200 must load 200 workflows";
        for (WorkflowArrivalData arrival : arrivals) {
            String normalized = arrival.getDaxPath().replace('\\', '/');
            assert normalized.contains("/first_100/")
                    || normalized.contains("/last_100/")
                    : "EDGE_200 included a middle workflow: " + normalized;
        }
        assert arrivals.get(0).getArrivalTime() == 10.0
                : "Edge dataset must preserve first_100 arrival times";
        assert arrivals.get(199).getArrivalTime() == 10.0 + 499 * 3.25
                : "Edge dataset must preserve last_100 arrival times";
    }

    private static void validateConfiguredMode(Path root) throws Exception {
        String previous = System.getProperty(WorkflowLoader.DATASET_MODE_PROPERTY);
        try {
            System.setProperty(WorkflowLoader.DATASET_MODE_PROPERTY, "EDGE_200");
            List<WorkflowArrivalData> arrivals = WorkflowLoader.load(
                    root.toString(), "poisson_distribution.json", 2.0);
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
        Path duplicateDirectory = root.resolve("middle_300").resolve("duplicate");
        Files.createDirectories(duplicateDirectory);
        Path original = root.resolve("middle_300").resolve("Workflow_100.xml");
        Path duplicate = duplicateDirectory.resolve("Workflow_100.xml");
        Files.copy(original, duplicate, StandardCopyOption.REPLACE_EXISTING);
        try {
            expectFailure(root, "exactly one .xml");
        } finally {
            Files.deleteIfExists(duplicate);
            Files.deleteIfExists(duplicateDirectory);
        }
    }

    private static void validateMissingPairDetection(Path root) throws Exception {
        Path txt = root.resolve("last_100").resolve("Workflow_499.txt");
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
