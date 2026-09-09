package org.workflowsim.examples.cbmw;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.cbmw.AbstractWorkflowBroker;
import org.workflowsim.cbmw.CBMWBroker;
import org.workflowsim.cbmw.CBMWDetailedResultExporter;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWPerformanceMetrics;
import org.workflowsim.cbmw.CBMWResultCollector;
import org.workflowsim.cbmw.ExperimentRunContext;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.PaperRuntimeModel;
import org.workflowsim.cbmw.WorkflowArrivalData;
import org.workflowsim.cbmw.WorkflowLoader;
import org.workflowsim.cbmw.WorkflowLoader.DatasetMode;
import org.workflowsim.cbmw.baselines.CEWBBroker;
import org.workflowsim.cbmw.baselines.CEWBPolicyMode;
import org.workflowsim.cbmw.baselines.DynamicGreedyBroker;
import org.workflowsim.cbmw.baselines.NOSFBroker;
import org.workflowsim.cbmw.baselines.NOSFConfiguration;
import org.workflowsim.cbmw.baselines.StaticGreedyBroker;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Main simulation driver for CBMW paper experiments.
 *
 * Runs 24 scenarios per algorithm: four arrival manifests, three tightness
 * values, and FULL_500/EDGE_200. Manifest timestamps are used without scaling
 * or rebasing; actual task runtimes come from matching TXT files by default.
 */
public class CBMWSimulation {

    private static final String OUTPUT_ROOT = System.getProperty("cbmw.output.dir", "Output");
    private static final String ALGORITHM_OUTPUT_ROOT =
            OUTPUT_ROOT + File.separator + "algorithms";
    private static final String COMPARISON_OUTPUT_DIR =
            OUTPUT_ROOT + File.separator + "comparison";
    private static final String COMPARISON_CSV_OUTPUT =
            COMPARISON_OUTPUT_DIR + File.separator + "results.csv";
    private static final String COMPARISON_AGGREGATE_CSV_OUTPUT =
            COMPARISON_OUTPUT_DIR + File.separator + "results_aggregate.csv";
    private static final String[] DEFAULT_ALGORITHMS = {
            "CBMW", "NOSF", "CEWB", "StaticGreedy", "DynamicGreedy"
    };
    private static final List<String> ALGORITHMS = configuredAlgorithms();

    private static final String WORKFLOW_DIR = System.getProperty(
            "cbmw.workflow.dir",
            "test_workflows/workflows");
    private static final double SIM_BUFFER_SECS = 5000.0;
    private static final boolean GENERATE_GANTT = Boolean.parseBoolean(
            System.getProperty("cbmw.generate.gantt", "false"));
    private static final boolean GENERATE_COMPARISON = Boolean.parseBoolean(
            System.getProperty("cbmw.generate.comparison", "true"));
    private static final boolean EXPORT_DETAILS = Boolean.parseBoolean(
            System.getProperty("cbmw.export.details", "true"));
    private static final boolean DETAIL_LOG = Boolean.parseBoolean(
            System.getProperty("cbmw.detail.log",
                    Boolean.toString(EXPORT_DETAILS || GENERATE_GANTT)));
    private static final int MAX_SCENARIOS = Integer.getInteger(
            "cbmw.max.scenarios", Integer.MAX_VALUE);
    private static final int MAX_WORKFLOWS = Integer.getInteger(
            "cbmw.max.workflows", 500);
    private static final boolean QUIET = Boolean.parseBoolean(
            System.getProperty("cbmw.quiet", "false"));
    private static final int REPETITIONS = Integer.getInteger(
            "cbmw.repetitions", NOSFConfiguration.defaultRepetitions());
    private static final int RUN_START = Integer.getInteger(
            "cbmw.run.start", 0);
    private static final long BASE_SEED = Long.getLong(
            "cbmw.seed.base", 20260716L);
    private static final boolean RESAMPLE_RUNTIMES = Boolean.parseBoolean(
            System.getProperty("cbmw.runtime.resample", "false"));

    private static final ExperimentScenario[] EXPERIMENTS = {
            new ExperimentScenario(15.0, 1.2),
            new ExperimentScenario(15.0, 2.0),
            new ExperimentScenario(15.0, 4.0),
            new ExperimentScenario(30.0, 1.2),
            new ExperimentScenario(30.0, 2.0),
            new ExperimentScenario(30.0, 4.0),
            new ExperimentScenario(45.0, 1.2),
            new ExperimentScenario(45.0, 2.0),
            new ExperimentScenario(45.0, 4.0),
            new ExperimentScenario(60.0, 1.2),
            new ExperimentScenario(60.0, 2.0),
            new ExperimentScenario(60.0, 4.0)
    };
    private static final List<DatasetMode> DATASET_MODES = configuredDatasetModes();

    public static void main(String[] args) throws Exception {
        if (REPETITIONS <= 0) {
            throw new IllegalArgumentException("cbmw.repetitions must be positive");
        }
        if (RUN_START < 0) {
            throw new IllegalArgumentException("cbmw.run.start must be non-negative");
        }
        if (QUIET) Log.disable();
        ensureDir(new File(OUTPUT_ROOT));
        ensureDir(new File(ALGORITHM_OUTPUT_ROOT));
        ensureDir(new File(COMPARISON_OUTPUT_DIR));
        for (String algorithm : ALGORITHMS) {
            File taskCsv = new File(algorithmOutputDir(algorithm), "task_execution.csv");
            if (taskCsv.exists() && !taskCsv.delete()) {
                throw new IllegalStateException("Could not reset " + taskCsv);
            }
        }
        System.out.println("[run] Algorithms: " + ALGORITHMS);
        System.out.println("[run] Algorithm outputs: "
                + new File(ALGORITHM_OUTPUT_ROOT).getAbsolutePath());
        System.out.println("[run] Comparison outputs: "
                + new File(COMPARISON_OUTPUT_DIR).getAbsolutePath());
        System.out.println("[run] Workflow source: "
                + new File(WORKFLOW_DIR).getAbsolutePath());
        System.out.println("[run] Using exact scenario-specific manifest timestamps");
        System.out.println("[run] Dataset modes: " + DATASET_MODES);
        System.out.println("[run] Experiment configurations: "
                + EXPERIMENTS.length + " arrival/alpha pairs");
        System.out.println("[run] NOSF profile: " + NOSFConfiguration.profileName());
        System.out.println("[run] Repetitions: " + REPETITIONS
                + " starting at run " + RUN_START
                + " baseSeed=" + BASE_SEED
                + " resampleRuntimes=" + RESAMPLE_RUNTIMES);
        if (ALGORITHMS.contains("CBMW")) {
            double multiplier = PaperRuntimeModel.conservativeEstimate(1.0);
            System.out.println(String.format(Locale.US,
                    "[run] CBMW runtime model: planningAlpha=%.3f"
                            + " cet/mu=%.4f beta=%.3f gamma=%.3f",
                    PaperRuntimeModel.PLANNING_ALPHA, multiplier,
                    PaperRuntimeModel.NEGOTIATION_BETA,
                    PaperRuntimeModel.NEGOTIATION_GAMMA));
        }
        if (MAX_WORKFLOWS != Integer.MAX_VALUE) {
            System.out.println("[run] Max workflows per scenario: " + MAX_WORKFLOWS);
        }

        List<CBMWResultCollector.ScenarioMetrics> comparisonMetrics = new ArrayList<>();
        Map<String, List<CBMWResultCollector.ScenarioMetrics>> algorithmMetrics =
                new LinkedHashMap<>();
        Map<String, List<WorkflowArrivalData>> arrivalCache = new LinkedHashMap<>();
        int completedScenarios = 0;

        scenarioLoop:
        for (String algo : ALGORITHMS) {
            for (DatasetMode datasetMode : DATASET_MODES) {
                if (!shouldRunDatasetMode(algo, datasetMode)) {
                    System.out.println("[run] Skipping " + datasetMode + " for " + algo);
                    continue;
                }
                for (ExperimentScenario experiment : EXPERIMENTS) {
                    String selected = System.getProperty("cbmw.scenarios", "").trim();
                    String scenarioName = experiment.name + "_" + datasetTag(datasetMode);
                    if (!selected.isEmpty() && !Arrays.asList(selected.split(",")).contains(scenarioName)) continue;
                    double arrivalScale = 1.0;
                    List<WorkflowArrivalData> arrivals = limitWorkflows(
                            cachedArrivals(arrivalCache, experiment, datasetMode));
                    if (arrivals.isEmpty()) {
                        throw new IllegalStateException("No arrivals loaded for "
                                + datasetMode + " " + experiment.name);
                    }
                    double simDuration = arrivals.get(arrivals.size() - 1)
                            .getArrivalTime() + SIM_BUFFER_SECS;

                    for (int replicate = 0; replicate < REPETITIONS; replicate++) {
                        int run = RUN_START + replicate;
                        long runSeed = ExperimentRunContext.seedForRun(BASE_SEED, run);
                        File algorithmDir = algorithmOutputDir(algo);
                        CBMWResultCollector.ScenarioMetrics row =
                                runScenario(algo, experiment, datasetMode,
                                        arrivalScale, arrivals, simDuration,
                                        algorithmDir, run, runSeed);

                        comparisonMetrics.add(row);
                        saveCsv(COMPARISON_CSV_OUTPUT,
                                buildScenarioCsv(comparisonMetrics));
                        saveCsv(COMPARISON_AGGREGATE_CSV_OUTPUT,
                                buildAggregateCsv(comparisonMetrics));

                        List<CBMWResultCollector.ScenarioMetrics> algoMetrics =
                                algorithmMetrics.computeIfAbsent(algo,
                                        unused -> new ArrayList<>());
                        algoMetrics.add(row);
                        saveCsv(new File(algorithmDir, "results.csv").getPath(),
                                buildScenarioCsv(algoMetrics));
                        saveCsv(new File(algorithmDir, "results_aggregate.csv").getPath(),
                                buildAggregateCsv(algoMetrics));

                        System.out.println("Completed: " + experiment.name + " "
                                + datasetMode + " " + algo + " run=" + run);
                        completedScenarios++;
                        if (completedScenarios >= MAX_SCENARIOS) {
                            System.out.println("[run] Stopped after " + completedScenarios
                                    + " scenario run(s) because cbmw.max.scenarios="
                                    + MAX_SCENARIOS);
                            break scenarioLoop;
                        }
                    }
                }
            }
        }

        saveCsv(COMPARISON_CSV_OUTPUT, buildScenarioCsv(comparisonMetrics));
        saveCsv(COMPARISON_AGGREGATE_CSV_OUTPUT, buildAggregateCsv(comparisonMetrics));
        if (GENERATE_COMPARISON) {
            generateComparisonCharts();
        }
    }

    // -----------------------------------------------------------------------
    // Scenario runner
    // -----------------------------------------------------------------------

    private static CBMWResultCollector.ScenarioMetrics runScenario(
                                      String algorithm,
                                      ExperimentScenario experiment,
                                      DatasetMode datasetMode,
                                      double arrivalScale,
                                      List<WorkflowArrivalData> arrivals,
                                      double simDuration,
                                      File algorithmDir,
                                      int run,
                                      long runSeed) throws Exception {
        ensureDir(algorithmDir);
        ExperimentRunContext.configure(run, runSeed, RESAMPLE_RUNTIMES,
                NOSFConfiguration.profileName());
        Parameters.setTightness(experiment.alpha);
        Parameters.setSimDuration(simDuration);
        Parameters.setCostModel(Parameters.CostModel.VM);

        CloudSim.init(1, Calendar.getInstance(), false);

        WorkflowDatacenter datacenter = createDatacenter("Datacenter_0");

        OverheadParameters  op = new OverheadParameters(0, null, null, null, null, 0);
        ClusteringParameters cp = new ClusteringParameters(
                0, 0, ClusteringParameters.ClusteringMethod.NONE, null);
        Parameters.init(HybridVmPool.NUM_RESERVED, (String) null, null, null,
                op, cp,
                Parameters.SchedulingAlgorithm.CBMW,
                Parameters.PlanningAlgorithm.INVALID,
                null, 0L);
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);

        WorkflowPlanner planner = new WorkflowPlanner("planner_0", 1);
        WorkflowEngine  engine  = planner.getWorkflowEngine();

        String scenario = experiment.name + "_" + datasetTag(datasetMode);
        String label = scenario + "_" + algorithm + "_t" + experiment.alpha
                + "_r" + run;
        String logFile = new File(algorithmDir, label + "_detail.log").getPath();
        if (DETAIL_LOG) {
            CBMWLogger.init(logFile);
        } else {
            CBMWLogger.disable(logFile);
        }

        AbstractWorkflowBroker broker = createBroker(algorithm, experiment.alpha);
        engine.replaceScheduler(broker);
        broker.submitVmList(broker.getVmPool().getReservedVms());
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);

        for (WorkflowArrivalData arrival : arrivals) {
            broker.addArrival(arrival.getArrivalTime(),
                              arrival.getDaxPath(),
                              arrival.getUserDeadline());
        }
        broker.setSimEndTime(simDuration);

        CBMWPerformanceMetrics.beginScenario(
                scenario, algorithm, arrivals.size(), run);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        CBMWPerformanceMetrics.finishScenario();
        CBMWLogger.close();

        CBMWResultCollector collector = new CBMWResultCollector(
                broker.getAllWorkflows(),
                broker.getAccounting(),
                broker.getVmPool().getReservedVms().size());
        collector.printReport(label, algorithm);
        CBMWResultCollector.ScenarioMetrics metrics = collector.toScenarioMetrics(
                scenario, experiment.arrivalName, experiment.alphaName,
                algorithm, arrivalScale, experiment.alpha, run,
                runSeed, NOSFConfiguration.profileName(),
                broker.getAccounting().getOnDemandUsageRatio(),
                broker.getAccounting().getSpotUsageRatio());

        CBMWDetailedResultExporter detailedExporter = new CBMWDetailedResultExporter(
                broker.getAllWorkflows(),
                broker.getAccounting(),
                broker.getVmPool(),
                algorithm,
                scenario,
                run,
                runSeed,
                NOSFConfiguration.profileName(),
                experiment.alpha,
                simDuration);
        detailedExporter.appendTaskCsv(
                new File(algorithmDir, "task_execution.csv"));
        System.out.println("[tasks] Appended to "
                + new File(algorithmDir, "task_execution.csv").getAbsolutePath());

        if (EXPORT_DETAILS) {
            File detailsDir = new File(algorithmDir, label + "_details");
            detailedExporter.export(detailsDir);
            System.out.println("[details] Saved to " + detailsDir.getAbsolutePath());
        }

        if (GENERATE_GANTT) {
            generateGanttChart(label, algorithmDir);
        }
        return metrics;
    }

    // -----------------------------------------------------------------------
    // Broker factory
    // -----------------------------------------------------------------------

    private static AbstractWorkflowBroker createBroker(String algorithm,
                                                        double tightness) throws Exception {
        switch (algorithm) {
            case "CBMW":          return new CBMWBroker("CBMWBroker_0", tightness);
            case "NOSF":          return new NOSFBroker("NOSFBroker_0", tightness);
            case "CEWB":
                return new CEWBBroker("CEWBBroker_0", tightness,
                        CEWBPolicyMode.CURRENT);
            case "CEWB-ReferencePolicy":
                return new CEWBBroker("CEWBReferencePolicyBroker_0", tightness,
                        CEWBPolicyMode.REFERENCE_POLICY);
            case "CEWB-ReferenceAdapted":
                return new CEWBBroker("CEWBReferenceAdaptedBroker_0", tightness,
                        CEWBPolicyMode.REFERENCE_ADAPTED);
            case "CEWB-Reconstructed":
                return new CEWBBroker("CEWBReconstructedBroker_0", tightness,
                        CEWBPolicyMode.RECONSTRUCTED);
            case "StaticGreedy":  return new StaticGreedyBroker("StaticGreedyBroker_0", tightness);
            case "DynamicGreedy": return new DynamicGreedyBroker("DynamicGreedyBroker_0", tightness);
            default: throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        }
    }

    // -----------------------------------------------------------------------
    // Datacenter setup
    // -----------------------------------------------------------------------

    private static WorkflowDatacenter createDatacenter(String name) throws Exception {
        long mips = (long) HybridVmPool.RESERVED_MIPS;
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        Host host = new Host(0,
                new RamProvisionerSimple(Integer.MAX_VALUE),
                new BwProvisionerSimple(Long.MAX_VALUE / 2),
                Long.MAX_VALUE / 2, peList,
                new VmSchedulerSpaceShared(peList));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 0.0, 0.0, 0.0, 0.0, 0.0);

        return new WorkflowDatacenter(name, chars,
                new VmAllocationPolicySimple(hostList),
                new LinkedList<Storage>(), 0);
    }

    // -----------------------------------------------------------------------
    // Output helpers
    // -----------------------------------------------------------------------

    private static void saveCsv(String path, String content) {
        File file = new File(path);
        ensureDir(file.getParentFile());
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path, false))) {
            bw.write(content);
            System.out.println("[csv] Saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[csv] Could not save: " + e.getMessage());
        }
    }

    private static String buildScenarioCsv(
            List<CBMWResultCollector.ScenarioMetrics> rows) {
        CBMWResultCollector.applyPairedMarginalCosts(rows);
        StringBuilder csv = new StringBuilder(CBMWResultCollector.csvHeader())
                .append("\n");
        for (CBMWResultCollector.ScenarioMetrics row : rows) {
            csv.append(CBMWResultCollector.toCsvRow(row)).append("\n");
        }
        return csv.toString();
    }

    private static void generateGanttChart(String label, File algorithmDir) {
        runPython("plot_gantt.py",
                CBMWLogger.getLogFile(),
                new File(algorithmDir, label + "_gantt.png").getPath());
    }

    private static void generateComparisonCharts() {
        runPython("plot_new_experiment.py",
                COMPARISON_AGGREGATE_CSV_OUTPUT,
                COMPARISON_OUTPUT_DIR);
    }

    private static File algorithmOutputDir(String algorithm) {
        File dir = new File(ALGORITHM_OUTPUT_ROOT, safePathName(algorithm));
        ensureDir(dir);
        return dir;
    }

    private static String safePathName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            System.out.println("[output] Could not create directory: "
                    + dir.getAbsolutePath());
        }
    }

    private static String buildAggregateCsv(
            List<CBMWResultCollector.ScenarioMetrics> rows) {
        Map<String, Aggregate> groups = new LinkedHashMap<>();
        for (CBMWResultCollector.ScenarioMetrics row : rows) {
            String key = row.scenario + "|" + row.load + "|" + row.deadlineClass
                    + "|" + row.algorithm + "|" + row.nosfProfile;
            groups.computeIfAbsent(key, unused -> new Aggregate(row)).add(row);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("scenario,load,deadlineClass,algorithm,nosfProfile,"
                        + "arrivalScale,tightness,runs,")
                .append("avgTotal,avgAccepted,avgRejected,avgMetDeadline,")
                .append("avgRejectedNegotiation,avgRejectedPlanning,")
                .append("avgAcceptanceRate,avgDeadlineRate,avgOverallSuccessRate,")
                .append("avgCountViolation,avgTimeViolation,")
                .append("avgOnDemandCost,")
                .append("avgSpotCost,avgEstimatedRawCost,avgOfferedPrice,")
                .append("avgBrokerRevenue,avgBrokerProfit,")
                .append("avgReservedCost,avgTotalCost,avgMarginalCost,avgMakespan,")
                .append("avgSimulationStartTime,avgSimulationDuration,")
                .append("avgSimulationDurationHours,")
                .append("avgReservedUtil,avgOnDemandUsageRatio,avgSpotUsageRatio,")
                .append("avgProvisionedOnDemandVms,avgOnDemandVmUtilization,")
                .append("avgDeadlineRiskTasks,")
                .append("avgReservedInstanceCount,avgReservedCoresPerInstance,")
                .append("avgReservedRamMbPerInstance,avgReservedTotalCores,")
                .append("avgReservedTotalRamMb,avgReservedCoreSeconds,")
                .append("avgReservedRamMbSeconds,avgReservedMeanCoreUtil,")
                .append("avgReservedMinCoreUtil,avgReservedMaxCoreUtil,")
                .append("avgReservedMeanRamUtil,avgReservedMinRamUtil,")
                .append("avgReservedMaxRamUtil,avgOnDemandAverageUptime,")
                .append("avgOnDemandTotalCores,avgOnDemandTotalRamMb,")
                .append("avgOnDemandCoreSeconds,avgOnDemandRamMbSeconds,")
                .append("avgMeanUtilizedCores,avgMinUtilizedCores,")
                .append("avgMaxUtilizedCores,avgMeanUtilizedRamMb,")
                .append("avgMinUtilizedRamMb,avgMaxUtilizedRamMb,")
                .append("minTotalCost,maxTotalCost,stddevTotalCost,")
                .append("minOnDemandVmUtilization,maxOnDemandVmUtilization,")
                .append("stddevOnDemandVmUtilization,")
                .append("minCountViolation,maxCountViolation,stddevCountViolation,")
                .append("minTimeViolation,maxTimeViolation,stddevTimeViolation\n");
        for (Aggregate aggregate : groups.values()) {
            csv.append(aggregate.toCsvRow()).append("\n");
        }
        return csv.toString();
    }

    static String manifestFor(double mean, DatasetMode mode) {
        String tag = mode == DatasetMode.FULL_500 ? "500workflows" : "edge200";
        String property = "cbmw.workflow.manifest." + compactNumber(mean) + "." + mode.name();
        return System.getProperty(property, "dax_poisson_arrivals_mean"
                + compactNumber(mean) + "s_" + tag + ".json");
    }

    private static List<WorkflowArrivalData> cachedArrivals(
            Map<String, List<WorkflowArrivalData>> cache,
            ExperimentScenario experiment, DatasetMode datasetMode) throws Exception {
        String manifest = manifestFor(experiment.targetMeanInterArrivalSeconds, datasetMode);
        String key = manifest + "|" + experiment.alpha + "|" + datasetMode;
        List<WorkflowArrivalData> cached = cache.get(key);
        if (cached != null) return cached;
        System.out.println("[run] Loading " + manifest + " tightness=" + experiment.alpha);
        List<WorkflowArrivalData> loaded = WorkflowLoader.load(
                WORKFLOW_DIR, manifest, experiment.alpha, datasetMode);
        cache.put(key, loaded);
        return loaded;
    }

    private static List<WorkflowArrivalData> limitWorkflows(
            List<WorkflowArrivalData> arrivals) {
        if (MAX_WORKFLOWS == Integer.MAX_VALUE || arrivals.size() <= MAX_WORKFLOWS) {
            return arrivals;
        }
        return new ArrayList<>(arrivals.subList(0, MAX_WORKFLOWS));
    }

    private static List<String> configuredAlgorithms() {
        String configured = System.getProperty("cbmw.algorithms", "").trim();
        if (configured.isEmpty()) {
            return Arrays.asList(DEFAULT_ALGORITHMS);
        }

        List<String> algorithms = new ArrayList<>();
        for (String item : configured.split(",")) {
            String algorithm = item.trim();
            if (!algorithm.isEmpty()) algorithms.add(algorithm);
        }
        return algorithms.isEmpty() ? Arrays.asList(DEFAULT_ALGORITHMS) : algorithms;
    }

    private static List<DatasetMode> configuredDatasetModes() {
        String configured = System.getProperty(
                WorkflowLoader.DATASET_MODE_PROPERTY, "").trim();
        if (!configured.isEmpty()) {
            return Arrays.asList(DatasetMode.parse(configured));
        }
        return Arrays.asList(DatasetMode.FULL_500, DatasetMode.EDGE_200);
    }

    static boolean shouldRunDatasetMode(String algorithm, DatasetMode mode) {
        return true;
    }

    static int defaultScenarioCountForAlgorithm(String algorithm) {
        return EXPERIMENTS.length
                * (shouldRunDatasetMode(algorithm, DatasetMode.EDGE_200) ? 2 : 1);
    }

    static List<String> defaultExperimentNames() {
        List<String> names = new ArrayList<>();
        for (ExperimentScenario experiment : EXPERIMENTS) {
            names.add(experiment.name);
        }
        return names;
    }

    private static String datasetTag(DatasetMode mode) {
        return mode == DatasetMode.FULL_500 ? "full500" : "edge200";
    }

    private static String compactNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static class ExperimentScenario {
        final double targetMeanInterArrivalSeconds;
        final double alpha;
        final String arrivalName;
        final String alphaName;
        final String name;

        ExperimentScenario(double targetMeanInterArrivalSeconds, double alpha) {
            if (!Double.isFinite(targetMeanInterArrivalSeconds)
                    || targetMeanInterArrivalSeconds <= 0.0) {
                throw new IllegalArgumentException(
                        "Target mean inter-arrival time must be positive");
            }
            if (!Double.isFinite(alpha) || alpha <= 0.0) {
                throw new IllegalArgumentException("Alpha must be positive");
            }
            this.targetMeanInterArrivalSeconds = targetMeanInterArrivalSeconds;
            this.alpha = alpha;
            this.arrivalName = "arrival" + compactNumber(targetMeanInterArrivalSeconds);
            this.alphaName = "alpha" + compactNumber(alpha);
            this.name = arrivalName + "_" + alphaName;
        }
    }

    private static class Aggregate {
        private final String scenario;
        private final String load;
        private final String deadlineClass;
        private final String algorithm;
        private final String nosfProfile;
        private final double arrivalScale;
        private final double tightness;
        private int runs;
        private double total;
        private double accepted;
        private double rejected;
        private double metDeadline;
        private double rejectedNegotiation;
        private double rejectedPlanning;
        private double acceptanceRate;
        private double deadlineRate;
        private double overallSuccessRate;
        private double countViolation;
        private double timeViolation;
        private double onDemandCost;
        private double spotCost;
        private double estimatedRawCost;
        private double offeredPrice;
        private double brokerRevenue;
        private double brokerProfit;
        private double reservedCost;
        private double totalCost;
        private double marginalCost;
        private int marginalCostRuns;
        private double makespan;
        private double simulationStartTime;
        private double simulationDuration;
        private double simulationDurationHours;
        private double reservedUtil;
        private double onDemandUsageRatio;
        private double spotUsageRatio;
        private double provisionedOnDemandVms;
        private double onDemandVmUtilization;
        private double deadlineRiskTasks;
        private double reservedInstanceCount;
        private double reservedCoresPerInstance;
        private double reservedRamMbPerInstance;
        private double reservedTotalCores;
        private double reservedTotalRamMb;
        private double reservedCoreSeconds;
        private double reservedRamMbSeconds;
        private double reservedMeanCoreUtil;
        private double reservedMinCoreUtil;
        private double reservedMaxCoreUtil;
        private double reservedMeanRamUtil;
        private double reservedMinRamUtil;
        private double reservedMaxRamUtil;
        private double onDemandAverageUptime;
        private double onDemandTotalCores;
        private double onDemandTotalRamMb;
        private double onDemandCoreSeconds;
        private double onDemandRamMbSeconds;
        private double meanUtilizedCores;
        private double minUtilizedCores;
        private double maxUtilizedCores;
        private double meanUtilizedRamMb;
        private double minUtilizedRamMb;
        private double maxUtilizedRamMb;
        private final RunningStats totalCostStats = new RunningStats();
        private final RunningStats onDemandUtilStats = new RunningStats();
        private final RunningStats countViolationStats = new RunningStats();
        private final RunningStats timeViolationStats = new RunningStats();

        Aggregate(CBMWResultCollector.ScenarioMetrics first) {
            this.scenario = first.scenario;
            this.load = first.load;
            this.deadlineClass = first.deadlineClass;
            this.algorithm = first.algorithm;
            this.nosfProfile = first.nosfProfile;
            this.arrivalScale = first.arrivalScale;
            this.tightness = first.tightness;
        }

        void add(CBMWResultCollector.ScenarioMetrics row) {
            runs++;
            total += row.total;
            accepted += row.accepted;
            rejected += row.rejected;
            metDeadline += row.metDeadline;
            rejectedNegotiation += row.rejectedNegotiation;
            rejectedPlanning += row.rejectedPlanning;
            acceptanceRate += row.acceptanceRate;
            deadlineRate += row.deadlineRate;
            overallSuccessRate += row.overallSuccessRate;
            countViolation += row.countViolation;
            timeViolation += row.timeViolation;
            onDemandCost += row.onDemandCost;
            spotCost += row.spotCost;
            estimatedRawCost += row.estimatedRawCost;
            offeredPrice += row.offeredPrice;
            brokerRevenue += row.brokerRevenue;
            brokerProfit += row.brokerProfit;
            reservedCost += row.reservedCost;
            totalCost += row.totalCost;
            if (Double.isFinite(row.marginalCost)) {
                marginalCost += row.marginalCost;
                marginalCostRuns++;
            }
            makespan += row.makespan;
            simulationStartTime += row.simulationStartTime;
            simulationDuration += row.simulationDuration;
            simulationDurationHours += row.simulationDurationHours;
            reservedUtil += row.reservedUtil;
            onDemandUsageRatio += row.onDemandUsageRatio;
            spotUsageRatio += row.spotUsageRatio;
            provisionedOnDemandVms += row.provisionedOnDemandVms;
            onDemandVmUtilization += row.onDemandVmUtilization;
            deadlineRiskTasks += row.deadlineRiskTasks;
            reservedInstanceCount += row.reservedInstanceCount;
            reservedCoresPerInstance += row.reservedCoresPerInstance;
            reservedRamMbPerInstance += row.reservedRamMbPerInstance;
            reservedTotalCores += row.reservedTotalCores;
            reservedTotalRamMb += row.reservedTotalRamMb;
            reservedCoreSeconds += row.reservedCoreSeconds;
            reservedRamMbSeconds += row.reservedRamMbSeconds;
            reservedMeanCoreUtil += row.reservedMeanCoreUtil;
            reservedMinCoreUtil += row.reservedMinCoreUtil;
            reservedMaxCoreUtil += row.reservedMaxCoreUtil;
            reservedMeanRamUtil += row.reservedMeanRamUtil;
            reservedMinRamUtil += row.reservedMinRamUtil;
            reservedMaxRamUtil += row.reservedMaxRamUtil;
            onDemandAverageUptime += row.onDemandAverageUptime;
            onDemandTotalCores += row.onDemandTotalCores;
            onDemandTotalRamMb += row.onDemandTotalRamMb;
            onDemandCoreSeconds += row.onDemandCoreSeconds;
            onDemandRamMbSeconds += row.onDemandRamMbSeconds;
            meanUtilizedCores += row.meanUtilizedCores;
            minUtilizedCores += row.minUtilizedCores;
            maxUtilizedCores += row.maxUtilizedCores;
            meanUtilizedRamMb += row.meanUtilizedRamMb;
            minUtilizedRamMb += row.minUtilizedRamMb;
            maxUtilizedRamMb += row.maxUtilizedRamMb;
            totalCostStats.add(row.totalCost);
            onDemandUtilStats.add(row.onDemandVmUtilization);
            countViolationStats.add(row.countViolation);
            timeViolationStats.add(row.timeViolation);
        }

        String toCsvRow() {
            return String.join(",",
                    scenario, load, deadlineClass, algorithm, nosfProfile,
                    f4(arrivalScale), f1(tightness), Integer.toString(runs),
                    f2(total / runs), f2(accepted / runs), f2(rejected / runs),
                    f2(metDeadline / runs), f2(rejectedNegotiation / runs),
                    f2(rejectedPlanning / runs), f4(acceptanceRate / runs),
                    f4(deadlineRate / runs), f4(overallSuccessRate / runs),
                    f4(countViolation / runs), f4(timeViolation / runs),
                    f4(onDemandCost / runs), f4(spotCost / runs),
                    f4(estimatedRawCost / runs), f4(offeredPrice / runs),
                    f4(brokerRevenue / runs), f4(brokerProfit / runs),
                    f2(reservedCost / runs), f4(totalCost / runs),
                    marginalCostRuns == 0 ? "" : f4(marginalCost / marginalCostRuns),
                    f2(makespan / runs), f2(simulationStartTime / runs),
                    f2(simulationDuration / runs),
                    f4(simulationDurationHours / runs), f4(reservedUtil / runs),
                    f4(onDemandUsageRatio / runs), f4(spotUsageRatio / runs),
                    f4(provisionedOnDemandVms / runs),
                    f4(onDemandVmUtilization / runs), f2(deadlineRiskTasks / runs),
                    f4(reservedInstanceCount / runs),
                    f4(reservedCoresPerInstance / runs),
                    f4(reservedRamMbPerInstance / runs),
                    f4(reservedTotalCores / runs),
                    f4(reservedTotalRamMb / runs),
                    f4(reservedCoreSeconds / runs),
                    f4(reservedRamMbSeconds / runs),
                    f4(reservedMeanCoreUtil / runs),
                    f4(reservedMinCoreUtil / runs),
                    f4(reservedMaxCoreUtil / runs),
                    f4(reservedMeanRamUtil / runs),
                    f4(reservedMinRamUtil / runs),
                    f4(reservedMaxRamUtil / runs),
                    f4(onDemandAverageUptime / runs),
                    f4(onDemandTotalCores / runs),
                    f4(onDemandTotalRamMb / runs),
                    f4(onDemandCoreSeconds / runs),
                    f4(onDemandRamMbSeconds / runs),
                    f4(meanUtilizedCores / runs),
                    f4(minUtilizedCores / runs),
                    f4(maxUtilizedCores / runs),
                    f4(meanUtilizedRamMb / runs),
                    f4(minUtilizedRamMb / runs),
                    f4(maxUtilizedRamMb / runs),
                    f4(totalCostStats.min()), f4(totalCostStats.max()),
                    f4(totalCostStats.stddev()), f4(onDemandUtilStats.min()),
                    f4(onDemandUtilStats.max()), f4(onDemandUtilStats.stddev()),
                    f4(countViolationStats.min()), f4(countViolationStats.max()),
                    f4(countViolationStats.stddev()), f4(timeViolationStats.min()),
                    f4(timeViolationStats.max()), f4(timeViolationStats.stddev()));
        }
    }

    private static String f1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String f2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String f4(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static final class RunningStats {
        private int count;
        private double mean;
        private double sumSquaredDeviation;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            sumSquaredDeviation += delta * (value - mean);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double min() { return count == 0 ? 0.0 : min; }

        double max() { return count == 0 ? 0.0 : max; }

        double stddev() {
            return count < 2 ? 0.0
                    : Math.sqrt(sumSquaredDeviation / (count - 1));
        }
    }

    private static void runPython(String... scriptAndArgs) {
        String[] cmd = new String[scriptAndArgs.length + 1];
        cmd[0] = pythonExecutable();
        System.arraycopy(scriptAndArgs, 0, cmd, 1, scriptAndArgs.length);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) System.out.println("[python] " + line);
            }
            int exit = p.waitFor();
            if (exit != 0) System.out.println("[python] exited with code " + exit);
        } catch (Exception e) {
            System.out.println("[python] " + e.getMessage());
        }
    }

    private static String pythonExecutable() {
        String configured = System.getProperty("cbmw.python", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.US);
        return os.contains("win") ? "python" : "python3";
    }
}
