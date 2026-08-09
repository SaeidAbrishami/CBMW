package org.workflowsim.cbmw;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cloudbus.cloudsim.Log;

/** Collects and prints per-scenario statistics. */
public class CBMWResultCollector {

    private final List<WorkflowRecord> allWorkflows;
    private final CBMWAccounting accounting;
    private final int reservedVmCount;

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows) {
        this(allWorkflows, new CBMWAccounting());
    }

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows,
                               CBMWAccounting accounting) {
        this(allWorkflows, accounting, HybridVmPool.NUM_RESERVED);
    }

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows,
                               CBMWAccounting accounting,
                               int reservedVmCount) {
        this.allWorkflows = allWorkflows;
        this.accounting = accounting;
        this.reservedVmCount = reservedVmCount;
    }

    public void printReport(String scenario) {
        printReport(scenario, "");
    }

    public void printReport(String scenario, String algorithm) {
        long total = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long rejected = total - accepted;
        long met = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        long rejectedNegotiation = rejectionCount("NEGOTIATION_DEADLINE_INFEASIBLE");
        long rejectedPlanning = rejectionCount("PLANNING_FAILED");
        double odCost = totalOnDemandCost();
        double spotCost = totalSpotCost();
        double rawEstimate = totalEstimatedRawCost();
        double offeredPrice = totalOfferedPrice();
        double brokerRevenue = totalBrokerRevenue();
        double brokerProfit = totalBrokerProfit();
        double makespan = makespan();
        double simulationStartTime = simulationStartTime();
        double simulationDuration = simulationDuration();
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double acceptanceRate = total == 0 ? 0.0 : (double) accepted / total;
        double overallSuccessRate = total == 0 ? 0.0 : (double) met / total;
        double reservedCost = reservedCost(algorithm);
        double totalCost = odCost + spotCost + reservedCost;
        double measurementStart = simulationStartTime();
        double measurementEnd = Math.max(measurementStart, makespan);
        ResourceAccountingSummary resources = accounting.summarizeResources(
                measurementStart, measurementEnd);
        double reservedUtil = includesReservedLeaseCost(algorithm)
                ? resources.getReservedCoreUtilization().getMean() : 0.0;

        Log.printLine("\n========== RESULTS: " + scenario + " ==========");
        Log.printLine(String.format("Workflows total/accepted : %d / %d", total, accepted));
        Log.printLine(String.format("Workflows rejected       : %d (negotiation=%d, planning=%d)",
                rejected, rejectedNegotiation, rejectedPlanning));
        Log.printLine(String.format("Acceptance rate          : %.3f (%d / %d)",
                acceptanceRate, accepted, total));
        Log.printLine(String.format("Accepted deadline rate   : %.3f (%d / %d)",
                deadlineRate, met, accepted));
        Log.printLine(String.format("Overall success rate     : %.3f (%d / %d)",
                overallSuccessRate, met, total));
        Log.printLine(String.format("Reserved VM utilization  : %.1f%%",
                reservedUtil * 100));
        Log.printLine(String.format("On-demand cost ($)       : %.4f", odCost));
        Log.printLine(String.format("Spot cost ($)            : %.4f", spotCost));
        Log.printLine(String.format("Negotiated raw cost ($)  : %.4f", rawEstimate));
        Log.printLine(String.format("Offered price ($)        : %.4f", offeredPrice));
        Log.printLine(String.format("Broker revenue ($)       : %.4f", brokerRevenue));
        Log.printLine(String.format("Broker profit ($)        : %.4f", brokerProfit));
        Log.printLine(String.format("Reserved lease cost ($)  : %.4f%s",
                reservedCost,
                includesReservedLeaseCost(algorithm) ? "" : " (excluded)"));
        Log.printLine(String.format("Total cost ($)           : %.4f",
                totalCost));
        Log.printLine("Marginal cost ($)        : pending paired 200-workflow result");
        Log.printLine(String.format("Makespan (sim s)         : %.2f", makespan));
        Log.printLine(String.format("Simulation start (sim s) : %.2f", simulationStartTime));
        Log.printLine(String.format("Simulation duration      : %.2f s (%.2f h)",
                simulationDuration, simulationDuration / 3600.0));
    }

    public static String csvHeader() {
        return "scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,"
                + "runSeed,nosfProfile,"
                + "total,accepted,rejected,metDeadline,rejectedNegotiation,"
                + "rejectedPlanning,acceptanceRate,deadlineRate,overallSuccessRate,"
                + "countViolation,timeViolation,"
                + "onDemandCost,spotCost,estimatedRawCost,"
                + "offeredPrice,brokerRevenue,brokerProfit,reservedCost,"
                + "totalCost,marginalCost,makespan,simulationStartTime,simulationDuration,"
                + "simulationDurationHours,reservedUtil,onDemandUsageRatio,spotUsageRatio,"
                + "provisionedOnDemandVms,onDemandVmUtilization,deadlineRiskTasks,"
                + "reservedInstanceCount,reservedCoresPerInstance,"
                + "reservedRamMbPerInstance,reservedTotalCores,reservedTotalRamMb,"
                + "reservedCoreSeconds,reservedRamMbSeconds,"
                + "reservedMeanCoreUtil,reservedMinCoreUtil,reservedMaxCoreUtil,"
                + "reservedMeanRamUtil,reservedMinRamUtil,reservedMaxRamUtil,"
                + "onDemandAverageUptime,onDemandTotalCores,onDemandTotalRamMb,"
                + "onDemandCoreSeconds,onDemandRamMbSeconds,"
                + "meanUtilizedCores,minUtilizedCores,maxUtilizedCores,"
                + "meanUtilizedRamMb,minUtilizedRamMb,maxUtilizedRamMb";
    }

    public String toCsvRow(String scenario, String load, String deadlineClass,
                           String algorithm, double arrivalScale,
                           double tightness, int run, double onDemandUsageRatio,
                           double spotUsageRatio) {
        return toCsvRow(toScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, ExperimentRunContext.getSeed(),
                ExperimentRunContext.getProfile(), onDemandUsageRatio,
                spotUsageRatio));
    }

    public ScenarioMetrics toScenarioMetrics(String scenario, String load,
                                             String deadlineClass,
                                             String algorithm,
                                             double arrivalScale,
                                             double tightness,
                                             int run,
                                             long runSeed,
                                             String nosfProfile,
                                             double onDemandUsageRatio,
                                             double spotUsageRatio) {
        long total = allWorkflows.size();
        long accepted = allWorkflows.stream().filter(WorkflowRecord::isAccepted).count();
        long rejected = total - accepted;
        long met = allWorkflows.stream()
                .filter(w -> w.isAccepted() && w.isDeadlineMet()).count();
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double acceptanceRate = total == 0 ? 0.0 : (double) accepted / total;
        double overallSuccessRate = total == 0 ? 0.0 : (double) met / total;
        double countViolation = total == 0 ? 0.0 : (double) (total - met) / total;
        double timeViolation = paperTimeViolation();
        long rejectedNegotiation = rejectionCount("NEGOTIATION_DEADLINE_INFEASIBLE");
        long rejectedPlanning = rejectionCount("PLANNING_FAILED");
        double odCost = totalOnDemandCost();
        double spotCost = totalSpotCost();
        double rawEstimate = totalEstimatedRawCost();
        double offeredPrice = totalOfferedPrice();
        double brokerRevenue = totalBrokerRevenue();
        double brokerProfit = totalBrokerProfit();
        double reservedCost = reservedCost(algorithm);
        double totalCost = odCost + spotCost + reservedCost;
        double start = simulationStartTime();
        double end = Math.max(start, makespan());
        ResourceAccountingSummary resources = accounting.summarizeResources(start, end);
        int reportReservedVmCount = includesReservedLeaseCost(algorithm)
                ? Math.max(0, reservedVmCount) : 0;

        return new ScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, runSeed, nosfProfile,
                total, accepted, rejected, met,
                rejectedNegotiation, rejectedPlanning, acceptanceRate,
                deadlineRate, overallSuccessRate, countViolation, timeViolation,
                odCost, spotCost, rawEstimate, offeredPrice,
                brokerRevenue, brokerProfit, reservedCost,
                totalCost, Double.NaN, makespan(), start, simulationDuration(),
                onDemandUsageRatio, spotUsageRatio,
                resources, reportReservedVmCount,
                accounting.getOnDemandVmUtilization(),
                accounting.getDeadlineRiskTaskCount());
    }

    public static String toCsvRow(ScenarioMetrics metrics) {
        return String.join(",",
                metrics.scenario, metrics.load, metrics.deadlineClass,
                metrics.algorithm, f4(metrics.arrivalScale), f1(metrics.tightness),
                Integer.toString(metrics.run), Long.toString(metrics.runSeed),
                metrics.nosfProfile,
                Long.toString(metrics.total), Long.toString(metrics.accepted),
                Long.toString(metrics.rejected), Long.toString(metrics.metDeadline),
                Long.toString(metrics.rejectedNegotiation),
                Long.toString(metrics.rejectedPlanning),
                f4(metrics.acceptanceRate), f4(metrics.deadlineRate),
                f4(metrics.overallSuccessRate), f4(metrics.countViolation),
                f4(metrics.timeViolation), f4(metrics.onDemandCost),
                f4(metrics.spotCost), f4(metrics.estimatedRawCost),
                f4(metrics.offeredPrice), f4(metrics.brokerRevenue),
                f4(metrics.brokerProfit), f2(metrics.reservedCost),
                f4(metrics.totalCost), optionalF4(metrics.marginalCost),
                f2(metrics.makespan),
                f2(metrics.simulationStartTime), f2(metrics.simulationDuration),
                f4(metrics.simulationDurationHours), f4(metrics.reservedUtil),
                f4(metrics.onDemandUsageRatio), f4(metrics.spotUsageRatio),
                Integer.toString(metrics.provisionedOnDemandVms),
                f4(metrics.onDemandVmUtilization),
                Integer.toString(metrics.deadlineRiskTasks),
                Integer.toString(metrics.reservedInstanceCount),
                Integer.toString(metrics.reservedCoresPerInstance),
                Integer.toString(metrics.reservedRamMbPerInstance),
                Integer.toString(metrics.reservedTotalCores),
                Long.toString(metrics.reservedTotalRamMb),
                f4(metrics.reservedCoreSeconds),
                f4(metrics.reservedRamMbSeconds),
                f4(metrics.reservedMeanCoreUtil),
                f4(metrics.reservedMinCoreUtil),
                f4(metrics.reservedMaxCoreUtil),
                f4(metrics.reservedMeanRamUtil),
                f4(metrics.reservedMinRamUtil),
                f4(metrics.reservedMaxRamUtil),
                f4(metrics.onDemandAverageUptime),
                Long.toString(metrics.onDemandTotalCores),
                Long.toString(metrics.onDemandTotalRamMb),
                f4(metrics.onDemandCoreSeconds),
                f4(metrics.onDemandRamMbSeconds),
                f4(metrics.meanUtilizedCores),
                f4(metrics.minUtilizedCores),
                f4(metrics.maxUtilizedCores),
                f4(metrics.meanUtilizedRamMb),
                f4(metrics.minUtilizedRamMb),
                f4(metrics.maxUtilizedRamMb));
    }

    /**
     * Applies the report definition marginalCost = totalCost(full500) -
     * totalCost(edge200). Only the full-500 row receives a value. Rows remain
     * blank (NaN) until an exact algorithm/scenario/run/seed pair exists.
     */
    public static void applyPairedMarginalCosts(List<ScenarioMetrics> rows) {
        Map<String, ScenarioMetrics> fullRows = new HashMap<>();
        Map<String, ScenarioMetrics> edgeRows = new HashMap<>();
        for (ScenarioMetrics row : rows) {
            row.marginalCost = Double.NaN;
            if (row.total == 500 && row.scenario.endsWith("_full500")) {
                fullRows.put(marginalPairKey(row, "_full500"), row);
            } else if (row.total == 200 && row.scenario.endsWith("_edge200")) {
                edgeRows.put(marginalPairKey(row, "_edge200"), row);
            }
        }
        for (Map.Entry<String, ScenarioMetrics> entry : fullRows.entrySet()) {
            ScenarioMetrics edge = edgeRows.get(entry.getKey());
            if (edge != null) {
                ScenarioMetrics full = entry.getValue();
                full.marginalCost = full.totalCost - edge.totalCost;
            }
        }
    }

    private static String marginalPairKey(ScenarioMetrics row, String suffix) {
        String baseScenario = row.scenario.substring(
                0, row.scenario.length() - suffix.length());
        return String.join("|", baseScenario, row.load, row.deadlineClass,
                row.algorithm, Double.toString(row.arrivalScale),
                Double.toString(row.tightness), Integer.toString(row.run),
                Long.toString(row.runSeed), row.nosfProfile);
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

    private static String optionalF4(double value) {
        return Double.isFinite(value) ? f4(value) : "";
    }

    /** Paper Eq. 20: mean positive normalized deadline overrun. */
    private double paperTimeViolation() {
        if (allWorkflows.isEmpty()) return 0.0;
        double sum = 0.0;
        for (WorkflowRecord workflow : allWorkflows) {
            double completion = workflow.getCompletionTime();
            double span = workflow.getDeadline() - workflow.getArrivalTime();
            if (completion < Double.MAX_VALUE && span > 0.0) {
                sum += Math.max(0.0, completion - workflow.getDeadline()) / span;
            }
        }
        return sum / allWorkflows.size();
    }

    private double totalOnDemandCost() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalOnDemandCost).sum();
    }

    private double totalSpotCost() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalSpotCost).sum();
    }

    private double totalEstimatedRawCost() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getEstimatedRawCost).sum();
    }

    private double totalOfferedPrice() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getOfferedPrice).sum();
    }

    private double totalBrokerRevenue() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getBrokerRevenue).sum();
    }

    private double totalBrokerProfit() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getBrokerProfit).sum();
    }

    private long rejectionCount(String reason) {
        return allWorkflows.stream()
                .filter(w -> reason.equals(w.getRejectionReason())).count();
    }

    private double reservedCost(String algorithm) {
        return reservedLeaseCost(algorithm, reservedVmCount, simulationDuration());
    }

    public static double reservedLeaseCost(String algorithm,
                                           int reservedVmCount,
                                           double uptimeSeconds) {
        if (!includesReservedLeaseCost(algorithm) || uptimeSeconds <= 0.0) {
            return 0.0;
        }
        return Math.max(0, reservedVmCount)
                * (uptimeSeconds / 3600.0)
                * HybridVmPool.RESERVED_HOURLY_COST;
    }

    public static boolean includesReservedLeaseCost(String algorithm) {
        return "CBMW".equals(algorithm)
                || "StaticGreedy".equals(algorithm)
                || "DynamicGreedy".equals(algorithm);
    }

    private double makespan() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0.0);
    }

    /** First workload arrival; makespan remains the absolute final completion time. */
    private double simulationStartTime() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getArrivalTime)
                .min().orElse(0.0);
    }

    /** Simulated elapsed workload time from first arrival to final completion. */
    private double simulationDuration() {
        return Math.max(0.0, makespan() - simulationStartTime());
    }

    /** Raw per-run metrics that can be aggregated after the experiment. */
    public static class ScenarioMetrics {
        public final String scenario;
        public final String load;
        public final String deadlineClass;
        public final String algorithm;
        public final double arrivalScale;
        public final double tightness;
        public final int run;
        public final long runSeed;
        public final String nosfProfile;
        public final long total;
        public final long accepted;
        public final long rejected;
        public final long metDeadline;
        public final long rejectedNegotiation;
        public final long rejectedPlanning;
        public final double acceptanceRate;
        public final double deadlineRate;
        public final double overallSuccessRate;
        public final double countViolation;
        public final double timeViolation;
        public final double onDemandCost;
        public final double spotCost;
        public final double estimatedRawCost;
        public final double offeredPrice;
        public final double brokerRevenue;
        public final double brokerProfit;
        public final double reservedCost;
        public final double totalCost;
        public double marginalCost;
        public final double makespan;
        public final double simulationStartTime;
        public final double simulationDuration;
        public final double simulationDurationHours;
        public final double reservedUtil;
        public final double onDemandUsageRatio;
        public final double spotUsageRatio;
        public final int provisionedOnDemandVms;
        public final double onDemandVmUtilization;
        public final int deadlineRiskTasks;
        public final ResourceAccountingSummary resourceSummary;
        public final int reservedInstanceCount;
        public final int reservedCoresPerInstance;
        public final int reservedRamMbPerInstance;
        public final int reservedTotalCores;
        public final long reservedTotalRamMb;
        public final double reservedCoreSeconds;
        public final double reservedRamMbSeconds;
        public final double reservedMeanCoreUtil;
        public final double reservedMinCoreUtil;
        public final double reservedMaxCoreUtil;
        public final double reservedMeanRamUtil;
        public final double reservedMinRamUtil;
        public final double reservedMaxRamUtil;
        public final double onDemandAverageUptime;
        public final long onDemandTotalCores;
        public final long onDemandTotalRamMb;
        public final double onDemandCoreSeconds;
        public final double onDemandRamMbSeconds;
        public final double meanUtilizedCores;
        public final double minUtilizedCores;
        public final double maxUtilizedCores;
        public final double meanUtilizedRamMb;
        public final double minUtilizedRamMb;
        public final double maxUtilizedRamMb;

        public ScenarioMetrics(String scenario, String load, String deadlineClass,
                               String algorithm, double arrivalScale,
                               double tightness, int run, long runSeed,
                               String nosfProfile, long total,
                               long accepted, long rejected, long metDeadline,
                               long rejectedNegotiation, long rejectedPlanning,
                               double acceptanceRate, double deadlineRate,
                               double overallSuccessRate,
                               double countViolation, double timeViolation,
                               double onDemandCost, double spotCost,
                               double estimatedRawCost, double offeredPrice,
                               double brokerRevenue, double brokerProfit,
                               double reservedCost, double totalCost,
                               double marginalCost,
                               double makespan, double simulationStartTime,
                               double simulationDuration,
                               double onDemandUsageRatio,
                               double spotUsageRatio,
                               ResourceAccountingSummary resourceSummary,
                               int reservedInstanceCount,
                               double onDemandVmUtilization,
                               int deadlineRiskTasks) {
            this.scenario = scenario;
            this.load = load;
            this.deadlineClass = deadlineClass;
            this.algorithm = algorithm;
            this.arrivalScale = arrivalScale;
            this.tightness = tightness;
            this.run = run;
            this.runSeed = runSeed;
            this.nosfProfile = nosfProfile;
            this.total = total;
            this.accepted = accepted;
            this.rejected = rejected;
            this.metDeadline = metDeadline;
            this.rejectedNegotiation = rejectedNegotiation;
            this.rejectedPlanning = rejectedPlanning;
            this.acceptanceRate = acceptanceRate;
            this.deadlineRate = deadlineRate;
            this.overallSuccessRate = overallSuccessRate;
            this.countViolation = countViolation;
            this.timeViolation = timeViolation;
            this.onDemandCost = onDemandCost;
            this.spotCost = spotCost;
            this.estimatedRawCost = estimatedRawCost;
            this.offeredPrice = offeredPrice;
            this.brokerRevenue = brokerRevenue;
            this.brokerProfit = brokerProfit;
            this.reservedCost = reservedCost;
            this.totalCost = totalCost;
            this.marginalCost = marginalCost;
            this.makespan = makespan;
            this.simulationStartTime = simulationStartTime;
            this.simulationDuration = simulationDuration;
            this.simulationDurationHours = simulationDuration / 3600.0;
            this.onDemandUsageRatio = onDemandUsageRatio;
            this.spotUsageRatio = spotUsageRatio;
            this.resourceSummary = resourceSummary;
            this.reservedInstanceCount = reservedInstanceCount;
            this.reservedCoresPerInstance = reservedInstanceCount > 0
                    ? HybridVmPool.RESERVED_CORES : 0;
            this.reservedRamMbPerInstance = reservedInstanceCount > 0
                    ? HybridVmPool.RESERVED_RAM_MB : 0;
            this.reservedTotalCores = reservedInstanceCount
                    * reservedCoresPerInstance;
            this.reservedTotalRamMb = (long) reservedInstanceCount
                    * reservedRamMbPerInstance;
            this.reservedCoreSeconds = reservedTotalCores * simulationDuration;
            this.reservedRamMbSeconds = reservedTotalRamMb * simulationDuration;
            this.reservedMeanCoreUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedCoreUtilization().getMean() : 0.0;
            this.reservedMinCoreUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedCoreUtilization().getMin() : 0.0;
            this.reservedMaxCoreUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedCoreUtilization().getMax() : 0.0;
            this.reservedMeanRamUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedRamUtilization().getMean() : 0.0;
            this.reservedMinRamUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedRamUtilization().getMin() : 0.0;
            this.reservedMaxRamUtil = reservedInstanceCount > 0
                    ? resourceSummary.getReservedRamUtilization().getMax() : 0.0;
            this.reservedUtil = reservedMeanCoreUtil;
            this.provisionedOnDemandVms = resourceSummary.getOnDemandInstanceCount();
            this.onDemandVmUtilization = onDemandVmUtilization;
            this.deadlineRiskTasks = deadlineRiskTasks;
            this.onDemandAverageUptime = resourceSummary.getAverageOnDemandUptime();
            this.onDemandTotalCores = resourceSummary.getTotalOnDemandCores();
            this.onDemandTotalRamMb = resourceSummary.getTotalOnDemandRamMb();
            this.onDemandCoreSeconds = resourceSummary
                    .getOnDemandCapacityCoreSeconds();
            this.onDemandRamMbSeconds = resourceSummary
                    .getOnDemandCapacityRamMbSeconds();
            this.meanUtilizedCores = resourceSummary.getUtilizedCores().getMean();
            this.minUtilizedCores = resourceSummary.getUtilizedCores().getMin();
            this.maxUtilizedCores = resourceSummary.getUtilizedCores().getMax();
            this.meanUtilizedRamMb = resourceSummary.getUtilizedRamMb().getMean();
            this.minUtilizedRamMb = resourceSummary.getUtilizedRamMb().getMin();
            this.maxUtilizedRamMb = resourceSummary.getUtilizedRamMb().getMax();
        }
    }
}
