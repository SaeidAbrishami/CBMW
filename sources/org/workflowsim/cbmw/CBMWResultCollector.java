package org.workflowsim.cbmw;

import java.util.List;
import java.util.Locale;
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
        double reservedUtil = reservedUtilization();

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
                + "totalCost,makespan,simulationStartTime,simulationDuration,"
                + "simulationDurationHours,reservedUtil,onDemandUsageRatio,spotUsageRatio,"
                + "provisionedOnDemandVms,onDemandVmUtilization,deadlineRiskTasks";
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

        return new ScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, runSeed, nosfProfile,
                total, accepted, rejected, met,
                rejectedNegotiation, rejectedPlanning, acceptanceRate,
                deadlineRate, overallSuccessRate, countViolation, timeViolation,
                odCost, spotCost, rawEstimate, offeredPrice,
                brokerRevenue, brokerProfit, reservedCost,
                totalCost, makespan(), simulationStartTime(), simulationDuration(),
                reservedUtilization(), onDemandUsageRatio, spotUsageRatio,
                accounting.getProvisionedOnDemandVmCount(),
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
                f4(metrics.totalCost), f2(metrics.makespan),
                f2(metrics.simulationStartTime), f2(metrics.simulationDuration),
                f4(metrics.simulationDurationHours), f4(metrics.reservedUtil),
                f4(metrics.onDemandUsageRatio), f4(metrics.spotUsageRatio),
                Integer.toString(metrics.provisionedOnDemandVms),
                f4(metrics.onDemandVmUtilization),
                Integer.toString(metrics.deadlineRiskTasks));
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

    private double reservedUtilization() {
        double totalCpu = allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getTotalReservedCpuTime).sum();
        double capacity = HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES
                * makespan();
        return capacity > 0 ? totalCpu / capacity : 0.0;
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
                               double makespan, double simulationStartTime,
                               double simulationDuration, double reservedUtil,
                                double onDemandUsageRatio,
                                double spotUsageRatio,
                                int provisionedOnDemandVms,
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
            this.makespan = makespan;
            this.simulationStartTime = simulationStartTime;
            this.simulationDuration = simulationDuration;
            this.simulationDurationHours = simulationDuration / 3600.0;
            this.reservedUtil = reservedUtil;
            this.onDemandUsageRatio = onDemandUsageRatio;
            this.spotUsageRatio = spotUsageRatio;
            this.provisionedOnDemandVms = provisionedOnDemandVms;
            this.onDemandVmUtilization = onDemandVmUtilization;
            this.deadlineRiskTasks = deadlineRiskTasks;
        }
    }
}
