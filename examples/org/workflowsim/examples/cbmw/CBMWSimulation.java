package org.workflowsim.examples.cbmw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.cloudbus.cloudsim.Datacenter;
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
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.cbmw.CBMWBroker;
import org.workflowsim.cbmw.CBMWLogger;
import org.workflowsim.cbmw.CBMWResultCollector;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowArrivalData;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Main simulation driver for the CBMW paper experiments.
 *
 * Scenarios: 3 arrival rates × 2 deadline tightness values × 3 algorithms ×
 *            10 seeds = 180 runs total.
 *
 * Usage: run main() for all scenarios, or adjust the loop variables below.
 */
public class CBMWSimulation {

    private static final String TEST_WORKFLOWS_DIR = "test_workflows";
    private static final String MANIFEST_PATH      = TEST_WORKFLOWS_DIR + File.separator + "manifest.csv";

    private static final double[] LAMBDAS    = {2.0, 3.0, 6.0};   // workflows/min
    private static final double[] TIGHTNESSES = {1.2, 3.0};       // tight, loose
    private static final int      NUM_SEEDS   = 10;
    private static final double   SIM_MINUTES = 60.0;

    public static void main(String[] args) throws Exception {
        StringBuilder csv = new StringBuilder(CBMWResultCollector.csvHeader()).append("\n");

        // --- Single scenario for testing ---
        runScenario("CBMW", 2.0, 1.2, 0, csv);
        System.out.println("\n===== CSV OUTPUT =====");
        System.out.println(csv.toString());

        // --- Full 180-scenario experiment (uncomment when ready) ---
//        for (double lambda : LAMBDAS) {
//            for (double tightness : TIGHTNESSES) {
//                for (int seed = 0; seed < NUM_SEEDS; seed++) {
//                    String row = runScenario("CBMW", lambda, tightness, seed, csv);
//                    System.out.println("Completed: CBMW lambda=" + lambda
//                            + " tightness=" + tightness + " seed=" + seed);
//                }
//            }
//        }
//        System.out.println("\n===== CSV OUTPUT =====");
//        System.out.println(csv.toString());
    }

    private static String runScenario(String algorithm, double lambda,
                                       double tightness, int seed,
                                       StringBuilder csv) throws Exception {
        double simDuration = SIM_MINUTES * 60.0;
        Parameters.setTightness(tightness);
        Parameters.setArrivalRate(lambda);
        Parameters.setSimDuration(simDuration);
        Parameters.setCostModel(Parameters.CostModel.VM);

        // ---- Init CloudSim ----
        int numUsers = 1;
        CloudSim.init(numUsers, Calendar.getInstance(), false);

        // ---- Datacenter with enough hosts for reserved + on-demand VMs ----
        WorkflowDatacenter datacenter = createDatacenter("Datacenter_0");

        // ---- Planner (no clustering, INVALID planning — CBMW does its own) ----
        OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);
        ClusteringParameters cp = new ClusteringParameters(
                0, 0, ClusteringParameters.ClusteringMethod.NONE, null);
        Parameters.init(HybridVmPool.NUM_RESERVED,
                (String) null, null, null,
                op, cp,
                Parameters.SchedulingAlgorithm.CBMW,
                Parameters.PlanningAlgorithm.INVALID,
                null, 0L);
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);

        WorkflowPlanner planner = new WorkflowPlanner("planner_0", 1);
        WorkflowEngine engine = planner.getWor11kflowEngine();

        // ---- CBMW Broker — replaces the engine's auto-created internal scheduler ----
        CBMWBroker broker = new CBMWBroker("CBMWBroker_0", tightness);
        engine.replaceScheduler(broker);          // broker IS now the engine's scheduler
        broker.submitVmList(broker.getVmPool().getReservedVms());
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);

        // ---- Register Poisson workflow arrivals (fired inside startEntity) ----
        scheduleArrivals(broker, lambda, simDuration, seed, tightness);
        broker.setSimEndTime(simDuration);

        // ---- Run ----
        CBMWLogger.init();
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        CBMWLogger.close();

        // ---- Collect results ----
        CBMWResultCollector collector = new CBMWResultCollector(broker.getAllWorkflows());
        String label = algorithm + "_lam" + (int) lambda + "_t" + tightness + "_seed" + seed;
        collector.printReport(label);
        csv.append(collector.toCsvRow(algorithm, lambda, tightness, seed)).append("\n");

        generateGanttChart(label);

        return label;
    }

    private static void scheduleArrivals(CBMWBroker broker, double lambda,
                                          double simDuration, int seed,
                                          double tightness) throws Exception {
        List<String[]> specs = loadManifest();
        if (specs.isEmpty()) {
            throw new RuntimeException("manifest.csv is empty or missing. "
                    + "Run CreateTestDaxModule first.");
        }

        Random rng = new Random(seed);
        double meanInterArrival = 60.0 / lambda;
        double t = 0.0;
        int index = 0;

        while (true) {
            t += -meanInterArrival * Math.log(1.0 - rng.nextDouble());
            if (t >= simDuration) break;

            String[] spec     = specs.get(index++ % specs.size());
            String daxPath    = TEST_WORKFLOWS_DIR + File.separator + spec[0]; // filename col
            double cpInFile   = Double.parseDouble(spec[3]);                   // criticalPath col
            double userDeadline = t + cpInFile * tightness;                    // absolute deadline

            broker.addArrival(t, daxPath, userDeadline);
        }
    }

    /**
     * Reads manifest.csv and returns all data rows (skips the header).
     * Each element is the array of comma-split columns for one workflow.
     */
    private static List<String[]> loadManifest() throws Exception {
        List<String[]> rows = new ArrayList<>();
        File f = new File(MANIFEST_PATH);
        if (!f.exists()) return rows;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) rows.add(line.split(","));
            }
        }
        return rows;
    }

    private static void generateGanttChart(String label) {
        String outFile = label + "_gantt.png";
        String[] cmds = { "python", "plot_gantt.py", CBMWLogger.LOG_FILE, outFile };
        try {
            ProcessBuilder pb = new ProcessBuilder(cmds);
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null)
                    System.out.println("[chart] " + line);
            }
            int exit = p.waitFor();
            if (exit != 0)
                System.out.println("[chart] Python exited with code " + exit);
        } catch (Exception e) {
            System.out.println("[chart] Could not generate chart: " + e.getMessage());
        }
    }

    private static WorkflowDatacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();

        // One large host that accommodates all reserved + on-demand VMs at peak
        int numPes  = 10000;
        // RAM: 50 reserved × 4096 MB + generous on-demand headroom
        long mips   = (long) HybridVmPool.RESERVED_MIPS;
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < numPes; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        Host host = new Host(0,
                new RamProvisionerSimple(Integer.MAX_VALUE),
                new BwProvisionerSimple(Long.MAX_VALUE / 2),
                Long.MAX_VALUE / 2,
                peList,
                new VmSchedulerSpaceShared(peList));
        hostList.add(host);

        String arch = "x86", os = "Linux", vmm = "Xen";
        double timeZone = 0.0, costPerSec = 0.0, costPerMem = 0.0,
               costPerStorage = 0.0, costPerBw = 0.0;

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                arch, os, vmm, hostList, timeZone,
                costPerSec, costPerMem, costPerStorage, costPerBw);

        return new WorkflowDatacenter(name, chars,
                new VmAllocationPolicySimple(hostList),
                new LinkedList<Storage>(), 0);
    }
}
