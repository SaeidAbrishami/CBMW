package org.workflowsim.cbmw;

import java.util.List;
import org.cloudbus.cloudsim.Log;

/** Collects and prints per-scenario statistics. */
public class CBMWResultCollector {

    private final List<WorkflowRecord> allWorkflows;
    private final CBMWAccounting accounting;

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows) {
        this(allWorkflows, new CBMWAccounting());
    }

    public CBMWResultCollector(List<WorkflowRecord> allWorkflows,
                               CBMWAccounting accounting) {
        this.allWorkflows = allWorkflows;
        this.accounting = accounting;
    }

    public void printReport(String scenario) {
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
        double deadlineRate = accepted == 0 ? 0.0 : (double) met / accepted;
        double acceptanceRate = total == 0 ? 0.0 : (double) accepted / total;
        double overallSuccessRate = total == 0 ? 0.0 : (double) met / total;
        double reservedCost = reservedCost();
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
        Log.printLine(String.format("Reserved prepaid cost ($): %.2f (excluded)", reservedCost));
        Log.printLine(String.format("Total cost ($)           : %.4f",
                odCost + spotCost));
        Log.printLine(String.format("Makespan (sim s)         : %.2f", makespan));
    }

    public static String csvHeader() {
        return "scenario,load,deadlineClass,algorithm,arrivalScale,tightness,run,"
                + "total,accepted,rejected,metDeadline,rejectedNegotiation,"
                + "rejectedPlanning,acceptanceRate,deadlineRate,overallSuccessRate,"
                + "onDemandCost,spotCost,estimatedRawCost,"
                + "offeredPrice,brokerRevenue,brokerProfit,reservedCost,"
                + "totalCost,makespan,reservedUtil,onDemandUsageRatio,spotUsageRatio,"
                + "provisionedOnDemandVms,onDemandVmUtilization,deadlineRiskTasks";
    }

    public String toCsvRow(String scenario, String load, String deadlineClass,
                           String algorithm, double arrivalScale,
                           double tightness, int run, double onDemandUsageRatio,
                           double spotUsageRatio) {
        return toCsvRow(toScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, onDemandUsageRatio, spotUsageRatio));
    }

    public ScenarioMetrics toScenarioMetrics(String scenario, String load,
                                             String deadlineClass,
                                             String algorithm,
                                             double arrivalScale,
                                             double tightness,
                                             int run,
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
        long rejectedNegotiation = rejectionCount("NEGOTIATION_DEADLINE_INFEASIBLE");
        long rejectedPlanning = rejectionCount("PLANNING_FAILED");
        double odCost = totalOnDemandCost();
        double spotCost = totalSpotCost();
        double rawEstimate = totalEstimatedRawCost();
        double offeredPrice = totalOfferedPrice();
        double brokerRevenue = totalBrokerRevenue();
        double brokerProfit = totalBrokerProfit();
        double reservedCost = reservedCost();
        double totalCost = odCost + spotCost;

        return new ScenarioMetrics(scenario, load, deadlineClass, algorithm,
                arrivalScale, tightness, run, total, accepted, rejected, met,
                rejectedNegotiation, rejectedPlanning, acceptanceRate,
                deadlineRate, overallSuccessRate,
                odCost, spotCost, rawEstimate, offeredPrice,
                brokerRevenue, brokerProfit, reservedCost,
                totalCost, makespan(),
                reservedUtilization(), onDemandUsageRatio, spotUsageRatio,
                accounting.getProvisionedOnDemandVmCount(),
                accounting.getOnDemandVmUtilization(),
                accounting.getDeadlineRiskTaskCount());
    }

    public static String toCsvRow(ScenarioMetrics metrics) {
        return String.format("%s,%s,%s,%s,%.4f,%.1f,%d,%d,%d,%d,%d,%d,%d,"
                        + "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,"
                        + "%.4f,%.4f,%.2f,%.4f,%.2f,%.4f,%.4f,%.4f,%d,%.4f,%d",
                metrics.scenario, metrics.load, metrics.deadlineClass,
                metrics.algorithm, metrics.arrivalScale, metrics.tightness,
                metrics.run, metrics.total, metrics.accepted, metrics.rejected,
                metrics.metDeadline, metrics.rejectedNegotiation,
                metrics.rejectedPlanning, metrics.acceptanceRate,
                metrics.deadlineRate, metrics.overallSuccessRate,
                metrics.onDemandCost, metrics.spotCost,
                metrics.estimatedRawCost, metrics.offeredPrice,
                metrics.brokerRevenue, metrics.brokerProfit,
                metrics.reservedCost, metrics.totalCost, metrics.makespan,
                metrics.reservedUtil, metrics.onDemandUsageRatio,
                metrics.spotUsageRatio, metrics.provisionedOnDemandVms,
                metrics.onDemandVmUtilization, metrics.deadlineRiskTasks);
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

    private double reservedCost() {
        // Paper Section 3.3 treats reserved resources as prepaid: their lease
        // cost cannot be changed by the scheduler and is excluded from Eq. (1).
        return 0.0;
    }

    private double makespan() {
        return allWorkflows.stream()
                .mapToDouble(WorkflowRecord::getCompletionTime)
                .filter(t -> t < Double.MAX_VALUE).max().orElse(0.0);
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
        public final long total;
        public final long accepted;
        public final long rejected;
        public final long metDeadline;
        public final long rejectedNegotiation;
        public final long rejectedPlanning;
        public final double acceptanceRate;
        public final double deadlineRate;
        public final double overallSuccessRate;
        public final double onDemandCost;
        public final double spotCost;
        public final double estimatedRawCost;
        public final double offeredPrice;
        public final double brokerRevenue;
        public final double brokerProfit;
        public final double reservedCost;
        public final double totalCost;
        public final double makespan;
        public final double reservedUtil;
        public final double onDemandUsageRatio;
        public final double spotUsageRatio;
        public final int provisionedOnDemandVms;
        public final double onDemandVmUtilization;
        public final int deadlineRiskTasks;

        public ScenarioMetrics(String scenario, String load, String deadlineClass,
                               String algorithm, double arrivalScale,
                               double tightness, int run, long total,
                               long accepted, long rejected, long metDeadline,
                               long rejectedNegotiation, long rejectedPlanning,
                               double acceptanceRate, double deadlineRate,
                               double overallSuccessRate,
                               double onDemandCost, double spotCost,
                               double estimatedRawCost, double offeredPrice,
                               double brokerRevenue, double brokerProfit,
                               double reservedCost, double totalCost,
                               double makespan, double reservedUtil,
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
            this.total = total;
            this.accepted = accepted;
            this.rejected = rejected;
            this.metDeadline = metDeadline;
            this.rejectedNegotiation = rejectedNegotiation;
            this.rejectedPlanning = rejectedPlanning;
            this.acceptanceRate = acceptanceRate;
            this.deadlineRate = deadlineRate;
            this.overallSuccessRate = overallSuccessRate;
            this.onDemandCost = onDemandCost;
            this.spotCost = spotCost;
            this.estimatedRawCost = estimatedRawCost;
            this.offeredPrice = offeredPrice;
            this.brokerRevenue = brokerRevenue;
            this.brokerProfit = brokerProfit;
            this.reservedCost = reservedCost;
            this.totalCost = totalCost;
            this.makespan = makespan;
            this.reservedUtil = reservedUtil;
            this.onDemandUsageRatio = onDemandUsageRatio;
            this.spotUsageRatio = spotUsageRatio;
            this.provisionedOnDemandVms = provisionedOnDemandVms;
            this.onDemandVmUtilization = onDemandVmUtilization;
            this.deadlineRiskTasks = deadlineRiskTasks;
        }
    }
}
