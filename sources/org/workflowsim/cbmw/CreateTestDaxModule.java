package org.workflowsim.cbmw;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone DAX generator — run this manually to produce test workflows.
 * Never called by CBMWSimulation.
 *
 * Output: test_workflows/workflow_NNN_TOPOLOGY_Xtasks.xml  + manifest.csv
 */
public class CreateTestDaxModule {

    // -----------------------------------------------------------------------
    // Change these before running to control the test batch
    // -----------------------------------------------------------------------
    public static final double DEADLINE_FACTOR  = 1.5;   // deadline = CP * this
    public static final String OUTPUT_DIR       = "test_workflows";
    public static final int    TOTAL_WORKFLOWS  = 50;
    public static final int    MIN_TASKS        = 10;
    public static final int    MAX_TASKS        = 50;
    public static final double MIN_RUNTIME_SEC  = 10.0;
    public static final double MAX_RUNTIME_SEC  = 300.0;
    public static final long   SEED             = 42L;
    // -----------------------------------------------------------------------

    enum Topology { CHAIN, FORK_JOIN, RANDOM }

    public static void main(String[] args) throws Exception {
        File dir = new File(OUTPUT_DIR);
        dir.mkdirs();

        Random rng = new Random(SEED);
        String manifestPath = OUTPUT_DIR + File.separator + "manifest.csv";

        try (PrintWriter manifest = new PrintWriter(new FileWriter(manifestPath))) {
            manifest.println("filename,topology,numTasks,criticalPath,deadlineFactor,deadline");

            Topology[] topologies = Topology.values();
            for (int i = 0; i < TOTAL_WORKFLOWS; i++) {
                Topology topology = topologies[i % topologies.length];
                int numTasks = MIN_TASKS + rng.nextInt(MAX_TASKS - MIN_TASKS + 1);

                String filename = String.format("workflow_%03d_%s_%dtasks.xml",
                        i, topology.name(), numTasks);
                String filePath = OUTPUT_DIR + File.separator + filename;

                double cp = generateDax(filePath, topology, numTasks, rng);
                double deadline = cp * DEADLINE_FACTOR;

                manifest.printf("%s,%s,%d,%.3f,%.1f,%.3f%n",
                        filename, topology.name(), numTasks, cp, DEADLINE_FACTOR, deadline);

                System.out.printf("Generated %-45s  CP=%7.1fs  deadline=%7.1fs%n",
                        filename, cp, deadline);
            }
        }

        System.out.println("\nDone. Written to: " + new File(OUTPUT_DIR).getAbsolutePath());
    }

    // -----------------------------------------------------------------------
    // DAX generation — returns CP in seconds
    // -----------------------------------------------------------------------

    private static double generateDax(String path, Topology topology,
                                       int numTasks, Random rng) throws Exception {
        double[] runtimes = new double[numTasks];
        for (int i = 0; i < numTasks; i++) {
            runtimes[i] = MIN_RUNTIME_SEC
                    + rng.nextDouble() * (MAX_RUNTIME_SEC - MIN_RUNTIME_SEC);
        }

        // children.get(i) = list of direct child task indices
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) children.add(new ArrayList<>());

        switch (topology) {
            case CHAIN:    buildChain(children, numTasks);        break;
            case FORK_JOIN: buildForkJoin(children, numTasks);   break;
            case RANDOM:   buildRandom(children, numTasks, rng); break;
        }

        double cp = computeCP(runtimes, children, numTasks);
        writeDax(path, topology.name(), runtimes, children, numTasks);
        return cp;
    }

    // -----------------------------------------------------------------------
    // Topology builders
    // -----------------------------------------------------------------------

    /** T0 → T1 → T2 → ... → T(n-1) */
    private static void buildChain(List<List<Integer>> children, int n) {
        for (int i = 0; i < n - 1; i++) {
            children.get(i).add(i + 1);
        }
    }

    /** T0 (entry) → T1..T(n-2) (parallel) → T(n-1) (exit) */
    private static void buildForkJoin(List<List<Integer>> children, int n) {
        if (n == 2) {
            children.get(0).add(1);
            return;
        }
        int exit = n - 1;
        for (int i = 1; i < exit; i++) {
            children.get(0).add(i);
            children.get(i).add(exit);
        }
    }

    /**
     * Layered random DAG: tasks are divided into 3–5 layers; each task
     * connects to 1–2 tasks in the next layer.  Edges always go from lower
     * index to higher index so the reverse-order DP for CP is valid.
     */
    private static void buildRandom(List<List<Integer>> children, int n, Random rng) {
        int numLayers = 3 + rng.nextInt(3); // 3, 4, or 5
        int[] layerOf = new int[n];
        for (int i = 0; i < n; i++) {
            layerOf[i] = (int) ((long) i * numLayers / n);
        }

        for (int i = 0; i < n; i++) {
            if (layerOf[i] == numLayers - 1) continue;
            int connected = 0;
            for (int j = i + 1; j < n && connected < 2; j++) {
                if (layerOf[j] == layerOf[i] + 1) {
                    children.get(i).add(j);
                    connected++;
                    if (rng.nextBoolean()) break; // sometimes connect to just 1
                }
            }
            // guarantee at least one child
            if (children.get(i).isEmpty()) {
                for (int j = i + 1; j < n; j++) {
                    if (layerOf[j] == layerOf[i] + 1) {
                        children.get(i).add(j);
                        break;
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Critical-path computation (bottom-up DP)
    // -----------------------------------------------------------------------

    /**
     * Works because all edges go from lower to higher index,
     * so iterating from n-1 down to 0 processes children before parents.
     */
    private static double computeCP(double[] runtimes,
                                     List<List<Integer>> children, int n) {
        double[] dp = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double maxChild = 0.0;
            for (int c : children.get(i)) {
                if (dp[c] > maxChild) maxChild = dp[c];
            }
            dp[i] = runtimes[i] + maxChild;
        }
        double cp = 0.0;
        for (double v : dp) if (v > cp) cp = v;
        return cp;
    }

    // -----------------------------------------------------------------------
    // DAX XML writer
    // -----------------------------------------------------------------------

    private static void writeDax(String path, String topologyName,
                                  double[] runtimes,
                                  List<List<Integer>> children,
                                  int n) throws Exception {
        // Build reverse map: parents.get(i) = list of parent indices of task i
        List<List<Integer>> parents = new ArrayList<>();
        for (int i = 0; i < n; i++) parents.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            for (int c : children.get(i)) parents.get(c).add(i);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\"");
            pw.println("      xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            pw.println("      xsi:schemaLocation=\"http://pegasus.isi.edu/schema/DAX "
                    + "http://pegasus.isi.edu/schema/dax-2.1.xsd\"");
            pw.printf( "      version=\"2.1\" count=\"1\" index=\"0\" name=\"synthetic_%s\">%n",
                    topologyName);
            pw.println();

            // --- jobs ---
            for (int i = 0; i < n; i++) {
                String id = String.format("ID%07d", i + 1);
                pw.printf("  <job id=\"%s\" name=\"task\" runtime=\"%.4f\">%n",
                        id, runtimes[i]);
                pw.printf("    <uses name=\"f_%07d_in\"  link=\"input\"  size=\"1024\" type=\"data\"/>%n", i + 1);
                pw.printf("    <uses name=\"f_%07d_out\" link=\"output\" size=\"1024\" type=\"data\"/>%n", i + 1);
                pw.println("  </job>");
            }

            pw.println();

            // --- dependencies ---
            for (int i = 0; i < n; i++) {
                if (parents.get(i).isEmpty()) continue;
                pw.printf("  <child ref=\"ID%07d\">%n", i + 1);
                for (int p : parents.get(i)) {
                    pw.printf("    <parent ref=\"ID%07d\"/>%n", p + 1);
                }
                pw.println("  </child>");
            }

            pw.println();
            pw.println("</adag>");
        }
    }
}
