package org.workflowsim.examples.cbmw;

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

    private static final String BASE_PATH =
            "P:\\University\\workflow sim paper\\WorkflowSim-1.0\\config\\dax\\";

    private static final String[] WORKFLOW_TYPES = {
        BASE_PATH + "Montage_100.xml",
        BASE_PATH + "CyberShake_100.xml",
        BASE_PATH + "Inspiral_100.xml",
        BASE_PATH + "Epigenomics_100.xml",
        BASE_PATH + "Sipht_100.xml"
    };

    private static final double[] LAMBDAS    = {2.0, 3.0, 6.0};   // workflows/min
    private static final double[] TIGHTNESSES = {1.2, 3.0};       // tight, loose
    private static final int      NUM_SEEDS   = 10;
    private static final double   SIM_MINUTES = 60.0;

    public static void main(String[] args) throws Exception {
        StringBuilder csv = new StringBuilder(CBMWResultCollector.csvHeader()).append("\n");

        for (double lambda : LAMBDAS) {
            for (double tightness : TIGHTNESSES) {
                for (int seed = 0; seed < NUM_SEEDS; seed++) {
                    String row = runScenario("CBMW", lambda, tightness, seed, csv);
                    System.out.println("Completed: CBMW lambda=" + lambda
                            + " tightness=" + tightness + " seed=" + seed);
                }
            }
        }

        System.out.println("\n===== CSV OUTPUT =====");
        System.out.println(csv.toString());
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
        WorkflowEngine engine = planner.getWorkflowEngine();

        // ---- CBMW Broker ----
        CBMWBroker broker = new CBMWBroker("CBMWBroker_0", tightness);
        broker.setWorkflowEngineId(engine.getId());

        // Submit reserved VMs
        engine.submitVmList(broker.getVmPool().getReservedVms(), 0);
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);

        // ---- Schedule Poisson workflow arrivals ----
        scheduleArrivals(broker, lambda, simDuration, seed);

        // Schedule simulation end signal
        broker.schedule(broker.getId(), simDuration, WorkflowSimTags.SIM_END, null);

        // ---- Run ----
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        // ---- Collect results ----
        CBMWResultCollector collector = new CBMWResultCollector(broker.getAllWorkflows());
        String label = algorithm + "_lam" + (int) lambda + "_t" + tightness + "_seed" + seed;
        collector.printReport(label);
        csv.append(collector.toCsvRow(algorithm, lambda, tightness, seed)).append("\n");

        return label;
    }

    private static void scheduleArrivals(CBMWBroker broker, double lambda,
                                          double simDuration, int seed) {
        Random rng = new Random(seed);
        double meanInterArrival = 60.0 / lambda; // seconds between arrivals
        double t = 0.0;
        int index = 0;

        while (true) {
            t += -meanInterArrival * Math.log(1.0 - rng.nextDouble());
            if (t >= simDuration) break;
            String dax = WORKFLOW_TYPES[index++ % WORKFLOW_TYPES.length];
            broker.schedule(broker.getId(), t, WorkflowSimTags.WORKFLOW_ARRIVE,
                    new WorkflowArrivalData(dax, t));
        }
    }

    private static WorkflowDatacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();

        // One large host that accommodates all reserved + a generous on-demand buffer
        int numPes  = 2000;
        long mips   = (long) HybridVmPool.RESERVED_MIPS;
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < numPes; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        Host host = new Host(0,
                new RamProvisionerSimple(1024 * 1024),   // 1 TiB
                new BwProvisionerSimple(100_000),
                1_000_000,
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
