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
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWResultCollector;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowArrivalData;
import org.workflowsim.cbmw.WorkflowLoader;
import org.workflowsim.cbmw.baselines.DynamicGreedyBroker;
import org.workflowsim.cbmw.baselines.StaticGreedyBroker;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Main simulation driver for CBMW paper experiments.
 *
 * Runs one scenario: all three algorithms (CBMW, StaticGreedy, DynamicGreedy)
 * against the same real workflow arrivals from test_workflows/.
 * Arrival times come from poisson_distribution.json; deadlines are
 * arrivalTime + criticalPath * TIGHTNESS; task runtimes use the perturbed
 * values from the matching .txt files.
 */
public class CBMWSimulation {

    private static final String   OUTPUT_DIR      = "Output";
    private static final String   WORKFLOW_DIR    = "test_workflows";
    private static final double   TIGHTNESS       = 2.0;
    private static final double   SIM_BUFFER_SECS = 5000.0;
    private static final String[] ALGORITHMS      = {"CBMW", "StaticGreedy", "DynamicGreedy"};
    private static final String   CSV_OUTPUT      = OUTPUT_DIR + File.separator + "results.csv";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();

        List<WorkflowArrivalData> arrivals = WorkflowLoader.load(WORKFLOW_DIR, TIGHTNESS);
        if (arrivals.isEmpty()) {
            System.out.println("No arrivals loaded. Check "
                    + WORKFLOW_DIR + File.separator + "poisson_distribution.json");
            return;
        }

        double simDuration = arrivals.get(arrivals.size() - 1).getArrivalTime() + SIM_BUFFER_SECS;
        StringBuilder csv = new StringBuilder(CBMWResultCollector.csvHeader()).append("\n");

        for (String algo : ALGORITHMS) {
            runScenario(algo, arrivals, simDuration, csv);
            System.out.println("Completed: " + algo);
        }

        saveCsv(csv.toString());
        generateComparisonCharts();
    }

    // -----------------------------------------------------------------------
    // Scenario runner
    // -----------------------------------------------------------------------

    private static void runScenario(String algorithm,
                                     List<WorkflowArrivalData> arrivals,
                                     double simDuration,
                                     StringBuilder csv) throws Exception {
        Parameters.setTightness(TIGHTNESS);
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

        AbstractWorkflowBroker broker = createBroker(algorithm, TIGHTNESS);
        engine.replaceScheduler(broker);
        broker.submitVmList(broker.getVmPool().getReservedVms());
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);

        for (WorkflowArrivalData arrival : arrivals) {
            broker.addArrival(arrival.getArrivalTime(),
                              arrival.getDaxPath(),
                              arrival.getUserDeadline());
        }
        broker.setSimEndTime(simDuration);

        String label   = algorithm + "_t" + TIGHTNESS;
        String logFile = OUTPUT_DIR + File.separator + label + "_detail.log";
        CBMWLogger.init(logFile);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        CBMWLogger.close();

        CBMWResultCollector collector = new CBMWResultCollector(broker.getAllWorkflows());
        collector.printReport(label);
        csv.append(collector.toCsvRow(algorithm, 0.0, TIGHTNESS, 0)).append("\n");

        generateGanttChart(label);
    }

    // -----------------------------------------------------------------------
    // Broker factory
    // -----------------------------------------------------------------------

    private static AbstractWorkflowBroker createBroker(String algorithm,
                                                        double tightness) throws Exception {
        switch (algorithm) {
            case "CBMW":          return new CBMWBroker("CBMWBroker_0", tightness);
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
        for (int i = 0; i < 10000; i++) {
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
        runPython("plot_comparison.py", CSV_OUTPUT);
    }

    private static void runPython(String... scriptAndArgs) {
        String[] cmd = new String[scriptAndArgs.length + 1];
        cmd[0] = "python";
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
