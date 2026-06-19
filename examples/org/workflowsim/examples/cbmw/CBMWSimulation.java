package org.workflowsim.examples.cbmw;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
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
 * deadlines against the same 200 real workflow arrivals from test_workflows/.
 * Arrival times come from poisson_distribution.json; deadlines are
 * arrivalTime + criticalPath * tightness; task runtimes use the perturbed
 * values from the matching .txt files.
 */
public class CBMWSimulation {

    private static final String   OUTPUT_DIR      = "Output";
    private static final String   CSV_OUTPUT      = OUTPUT_DIR + File.separator + "results.csv";
    private static final String[] ALGORITHMS      = {
            "CBMW", "NOSF", "CEWB", "StaticGreedy", "DynamicGreedy"
    };

    private static final String WORKFLOW_DIR    = "test_workflows";
    private static final String POISSON_FILE    = "poisson_distribution.json";
    private static final double SIM_BUFFER_SECS = 5000.0;
    private static final boolean GENERATE_GANTT = Boolean.parseBoolean(
            System.getProperty("cbmw.generate.gantt", "false"));
    private static final boolean EXPORT_DETAILS = Boolean.parseBoolean(
            System.getProperty("cbmw.export.details", "true"));

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
        new File(OUTPUT_DIR).mkdirs();

        StringBuilder csv = new StringBuilder(CBMWResultCollector.csvHeader()).append("\n");

        for (DeadlineClass deadline : DEADLINES) {
            List<WorkflowArrivalData> baseArrivals =
                    WorkflowLoader.load(WORKFLOW_DIR, POISSON_FILE, deadline.tightness);
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
                    runScenario(algo, load, deadline, arrivals, simDuration, csv);
                    System.out.println("Completed: " + load.name + " "
                            + deadline.name + " " + algo);
                }
            }
        }

        saveCsv(csv.toString());
        generateComparisonCharts();
    }

    // -----------------------------------------------------------------------
    // Scenario runner
    // -----------------------------------------------------------------------

    private static void runScenario(String algorithm,
                                     LoadScenario load,
                                     DeadlineClass deadline,
                                     List<WorkflowArrivalData> arrivals,
                                     double simDuration,
                                     StringBuilder csv) throws Exception {
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
        String logFile = OUTPUT_DIR + File.separator + label + "_detail.log";
        CBMWLogger.init(logFile);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        CBMWLogger.close();

        CBMWResultCollector collector = new CBMWResultCollector(broker.getAllWorkflows());
        collector.printReport(label);
        csv.append(collector.toCsvRow(scenario, load.name, deadline.name,
                algorithm, load.arrivalScale, deadline.tightness, 0,
                broker.getAccounting().getOnDemandUsageRatio())).append("\n");

        if (EXPORT_DETAILS) {
            File detailsDir = new File(OUTPUT_DIR, label + "_details");
            new CBMWDetailedResultExporter(
                    broker.getAllWorkflows(),
                    broker.getAccounting(),
                    broker.getVmPool(),
                    algorithm,
                    deadline.tightness,
                    simDuration).export(detailsDir);
            System.out.println("[details] Saved to " + detailsDir.getAbsolutePath());
        }

        if (GENERATE_GANTT) {
            generateGanttChart(label);
        }
    }

    // -----------------------------------------------------------------------
    // Broker factory
    // -----------------------------------------------------------------------

    private static AbstractWorkflowBroker createBroker(String algorithm,
                                                        double tightness) throws Exception {
        switch (algorithm) {
            case "CBMW":          return new CBMWBroker("CBMWBroker_0", tightness);
            case "NOSF":          return new NOSFBroker("NOSFBroker_0", tightness);
            case "CEWB":          return new CEWBBroker("CEWBBroker_0", tightness);
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

    private static void saveCsv(String content) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(CSV_OUTPUT, false))) {
            bw.write(content);
            System.out.println("[csv] Saved to " + new File(CSV_OUTPUT).getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[csv] Could not save: " + e.getMessage());
        }
    }

    private static void generateGanttChart(String label) {
        runPython("plot_gantt.py",
                CBMWLogger.getLogFile(),
                OUTPUT_DIR + File.separator + label + "_gantt.png");
    }

    private static void generateComparisonCharts() {
        runPython("plot_new_experiment.py", CSV_OUTPUT);
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

    private static void runPython(String... scriptAndArgs) {
        String[] cmd = new String[scriptAndArgs.length + 1];
        cmd[0] = "C:\\Users\\AsiaLapTop.Com\\AppData\\Local\\Programs\\Python\\Python312\\python.exe";
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
}
