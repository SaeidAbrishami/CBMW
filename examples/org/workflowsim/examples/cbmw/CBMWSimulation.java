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
import org.workflowsim.cbmw.CBMWResultCollector;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.PaperRuntimeModel;
import org.workflowsim.cbmw.WorkflowArrivalData;
import org.workflowsim.cbmw.WorkflowLoader;
import org.workflowsim.cbmw.baselines.CEWBBroker;
import org.workflowsim.cbmw.baselines.DynamicGreedyBroker;
import org.workflowsim.cbmw.baselines.NOSFBroker;
import org.workflowsim.cbmw.baselines.StaticGreedyBroker;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Main simulation driver for CBMW paper experiments.
 *
 * Runs the new experiment matrix: CBMW plus paper and greedy baselines across
 * low/moderate/heavy load and tight/medium/loose
 * deadlines against the same first 50 workflow arrivals selected from the
 * configured 200-arrival source trace.
 * Arrival times come from poisson_distribution.json; deadlines are
 * arrivalTime + criticalPath * tightness; task runtimes use the perturbed
 * values from the matching .txt files.
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
            "Output/generated_datasets/test_workflows_sigma005_seed20260716");
    private static final String POISSON_FILE = System.getProperty(
            "cbmw.workflow.manifest", "poisson_distribution.json");
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
            "cbmw.max.workflows", 50);
    private static final boolean QUIET = Boolean.parseBoolean(
            System.getProperty("cbmw.quiet", "false"));

    private static final DeadlineClass[] DEADLINES = {
            new DeadlineClass("tight", 1.2),
            new DeadlineClass("medium", 2.0),
            new DeadlineClass("loose", 4.0)
    };

    /**
     * Arrival scale changes only inter-arrival spacing. Smaller scale means
     * denser arrivals and heavier load.
     */
    private static final LoadScenario[] LOADS = {
            new LoadScenario("low", 2.0),
            new LoadScenario("moderate", 1.0),
            new LoadScenario("heavy", 0.5)
    };

    public static void main(String[] args) throws Exception {
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
        System.out.println("[run] Workflow manifest: " + POISSON_FILE);
        if (ALGORITHMS.contains("CBMW")) {
            double multiplier = PaperRuntimeModel.conservativeEstimate(1.0);
            System.out.println(String.format(Locale.US,
                    "[run] CBMW runtime model: alpha=%.3f sigma/mu=%.3f"
                            + " z=%.4f cet/mu=%.4f beta=%.3f gamma=%.3f",
                    PaperRuntimeModel.QUANTILE,
                    PaperRuntimeModel.STDDEV_RATIO,
                    PaperRuntimeModel.getQuantileZ(), multiplier,
                    PaperRuntimeModel.NEGOTIATION_BETA,
                    PaperRuntimeModel.NEGOTIATION_GAMMA));
        }
        if (MAX_WORKFLOWS != Integer.MAX_VALUE) {
            System.out.println("[run] Max workflows per scenario: " + MAX_WORKFLOWS);
        }

        StringBuilder comparisonCsv =
                new StringBuilder(CBMWResultCollector.csvHeader()).append("\n");
        List<CBMWResultCollector.ScenarioMetrics> comparisonMetrics = new ArrayList<>();
        Map<String, StringBuilder> algorithmCsv = new LinkedHashMap<>();
        Map<String, List<CBMWResultCollector.ScenarioMetrics>> algorithmMetrics =
                new LinkedHashMap<>();
        int completedScenarios = 0;

        scenarioLoop:
        for (DeadlineClass deadline : DEADLINES) {
            List<WorkflowArrivalData> baseArrivals =
                    WorkflowLoader.load(WORKFLOW_DIR, POISSON_FILE, deadline.tightness);
            baseArrivals = limitWorkflows(baseArrivals);
            if (baseArrivals.isEmpty()) {
                System.out.println("No arrivals loaded. Check "
                        + WORKFLOW_DIR + File.separator + POISSON_FILE);
                return;
            }

            for (LoadScenario load : LOADS) {
                List<WorkflowArrivalData> arrivals =
                        scaleArrivals(baseArrivals, load.arrivalScale);
                double simDuration = arrivals.get(arrivals.size() - 1).getArrivalTime()
                        + SIM_BUFFER_SECS;

                for (String algo : ALGORITHMS) {
                    File algorithmDir = algorithmOutputDir(algo);
                    CBMWResultCollector.ScenarioMetrics row =
                            runScenario(algo, load, deadline, arrivals, simDuration,
                                    algorithmDir);

                    comparisonCsv.append(CBMWResultCollector.toCsvRow(row)).append("\n");
                    comparisonMetrics.add(row);
                    saveCsv(COMPARISON_CSV_OUTPUT, comparisonCsv.toString());
                    saveCsv(COMPARISON_AGGREGATE_CSV_OUTPUT,
                            buildAggregateCsv(comparisonMetrics));

                    StringBuilder algoCsv = algorithmCsv.computeIfAbsent(algo,
                            unused -> new StringBuilder(CBMWResultCollector.csvHeader())
                                    .append("\n"));
                    List<CBMWResultCollector.ScenarioMetrics> algoMetrics =
                            algorithmMetrics.computeIfAbsent(algo, unused -> new ArrayList<>());
                    algoCsv.append(CBMWResultCollector.toCsvRow(row)).append("\n");
                    algoMetrics.add(row);
                    saveCsv(new File(algorithmDir, "results.csv").getPath(),
                            algoCsv.toString());
                    saveCsv(new File(algorithmDir, "results_aggregate.csv").getPath(),
                            buildAggregateCsv(algoMetrics));

                    System.out.println("Completed: " + load.name + " "
                            + deadline.name + " " + algo);
                    completedScenarios++;
                    if (completedScenarios >= MAX_SCENARIOS) {
                        System.out.println("[run] Stopped after " + completedScenarios
                                + " scenario(s) because cbmw.max.scenarios="
                                + MAX_SCENARIOS);
                        break scenarioLoop;
                    }
                }
            }
        }

        saveCsv(COMPARISON_CSV_OUTPUT, comparisonCsv.toString());
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
                                      LoadScenario load,
                                      DeadlineClass deadline,
                                      List<WorkflowArrivalData> arrivals,
                                      double simDuration,
                                      File algorithmDir) throws Exception {
        ensureDir(algorithmDir);
        Parameters.setTightness(deadline.tightness);
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

        AbstractWorkflowBroker broker = createBroker(algorithm, deadline.tightness);
        engine.replaceScheduler(broker);
        broker.submitVmList(broker.getVmPool().getReservedVms());
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);

        for (WorkflowArrivalData arrival : arrivals) {
            broker.addArrival(arrival.getArrivalTime(),
                              arrival.getDaxPath(),
                              arrival.getUserDeadline());
        }
        broker.setSimEndTime(simDuration);

        String scenario = load.name + "_" + deadline.name;
        String label   = scenario + "_" + algorithm + "_t" + deadline.tightness;
        String logFile = new File(algorithmDir, label + "_detail.log").getPath();
        if (DETAIL_LOG) {
            CBMWLogger.init(logFile);
        } else {
            CBMWLogger.disable(logFile);
        }
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        CBMWLogger.close();

        CBMWResultCollector collector = new CBMWResultCollector(
                broker.getAllWorkflows(),
                broker.getAccounting(),
                broker.getVmPool().getReservedVms().size());
        collector.printReport(label, algorithm);
        CBMWResultCollector.ScenarioMetrics metrics = collector.toScenarioMetrics(
                scenario, load.name, deadline.name,
                algorithm, load.arrivalScale, deadline.tightness, 0,
                broker.getAccounting().getOnDemandUsageRatio(),
                broker.getAccounting().getSpotUsageRatio());

        CBMWDetailedResultExporter detailedExporter = new CBMWDetailedResultExporter(
                broker.getAllWorkflows(),
                broker.getAccounting(),
                broker.getVmPool(),
                algorithm,
                scenario,
                deadline.tightness,
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
            case "CEWB":          return new CEWBBroker("CEWBBroker_0", tightness, true);
            case "CEWB-Reconstructed":
                return new CEWBBroker("CEWBReconstructedBroker_0", tightness, false);
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
                    + "|" + row.algorithm;
            groups.computeIfAbsent(key, unused -> new Aggregate(row)).add(row);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("scenario,load,deadlineClass,algorithm,arrivalScale,tightness,runs,")
                .append("avgTotal,avgAccepted,avgRejected,avgMetDeadline,")
                .append("avgRejectedNegotiation,avgRejectedPlanning,")
                .append("avgAcceptanceRate,avgDeadlineRate,avgOverallSuccessRate,")
                .append("avgOnDemandCost,")
                .append("avgSpotCost,avgEstimatedRawCost,avgOfferedPrice,")
                .append("avgBrokerRevenue,avgBrokerProfit,")
                .append("avgReservedCost,avgTotalCost,avgMakespan,")
                .append("avgSimulationStartTime,avgSimulationDuration,")
                .append("avgSimulationDurationHours,")
                .append("avgReservedUtil,avgOnDemandUsageRatio,avgSpotUsageRatio,")
                .append("avgProvisionedOnDemandVms,avgOnDemandVmUtilization,")
                .append("avgDeadlineRiskTasks\n");
        for (Aggregate aggregate : groups.values()) {
            csv.append(aggregate.toCsvRow()).append("\n");
        }
        return csv.toString();
    }

    private static List<WorkflowArrivalData> scaleArrivals(
            List<WorkflowArrivalData> arrivals, double arrivalScale) {
        List<WorkflowArrivalData> scaled = new ArrayList<>();
        for (WorkflowArrivalData arrival : arrivals) {
            double deadlineSlack = arrival.getUserDeadline() - arrival.getArrivalTime();
            double scaledArrival = arrival.getArrivalTime() * arrivalScale;
            scaled.add(new WorkflowArrivalData(arrival.getDaxPath(),
                    scaledArrival, scaledArrival + deadlineSlack));
        }
        return scaled;
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

    private static class LoadScenario {
        final String name;
        final double arrivalScale;

        LoadScenario(String name, double arrivalScale) {
            this.name = name;
            this.arrivalScale = arrivalScale;
        }
    }

    private static class DeadlineClass {
        final String name;
        final double tightness;

        DeadlineClass(String name, double tightness) {
            this.name = name;
            this.tightness = tightness;
        }
    }

    private static class Aggregate {
        private final String scenario;
        private final String load;
        private final String deadlineClass;
        private final String algorithm;
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
        private double onDemandCost;
        private double spotCost;
        private double estimatedRawCost;
        private double offeredPrice;
        private double brokerRevenue;
        private double brokerProfit;
        private double reservedCost;
        private double totalCost;
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

        Aggregate(CBMWResultCollector.ScenarioMetrics first) {
            this.scenario = first.scenario;
            this.load = first.load;
            this.deadlineClass = first.deadlineClass;
            this.algorithm = first.algorithm;
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
            onDemandCost += row.onDemandCost;
            spotCost += row.spotCost;
            estimatedRawCost += row.estimatedRawCost;
            offeredPrice += row.offeredPrice;
            brokerRevenue += row.brokerRevenue;
            brokerProfit += row.brokerProfit;
            reservedCost += row.reservedCost;
            totalCost += row.totalCost;
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
        }

        String toCsvRow() {
            return String.format(Locale.US,
                    "%s,%s,%s,%s,%.4f,%.1f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,"
                            + "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,"
                            + "%.4f,%.4f,%.2f,%.4f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,"
                            + "%.4f,%.4f,%.2f",
                    scenario, load, deadlineClass, algorithm, arrivalScale,
                    tightness, runs, total / runs, accepted / runs,
                    rejected / runs, metDeadline / runs,
                    rejectedNegotiation / runs, rejectedPlanning / runs,
                    acceptanceRate / runs, deadlineRate / runs,
                    overallSuccessRate / runs,
                    onDemandCost / runs, spotCost / runs,
                    estimatedRawCost / runs, offeredPrice / runs,
                    brokerRevenue / runs, brokerProfit / runs,
                    reservedCost / runs, totalCost / runs, makespan / runs,
                    simulationStartTime / runs, simulationDuration / runs,
                    simulationDurationHours / runs,
                    reservedUtil / runs, onDemandUsageRatio / runs,
                    spotUsageRatio / runs, provisionedOnDemandVms / runs,
                    onDemandVmUtilization / runs, deadlineRiskTasks / runs);
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
