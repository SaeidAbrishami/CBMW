package org.workflowsim.cbmw.baselines;

import org.workflowsim.Task;
import org.workflowsim.cbmw.HybridVmPool;
import org.workflowsim.cbmw.WorkflowRecord;

/** Configurable reconstruction of the three pricing-policy families in CEWB. */
final class CEWBPricingPolicy {

    enum Mode {
        CONSTANT_PROFIT,
        CONSTANT_DISCOUNT,
        PREDICTION_BASED
    }

    private final Mode mode;
    private final double profitMargin = property("cbmw.cewb.pricing.profit.margin", 0.10);
    private final double discount = property("cbmw.cewb.pricing.discount", 0.10);
    private final double predictionRisk = property("cbmw.cewb.pricing.prediction.risk", 0.05);

    CEWBPricingPolicy() {
        String configured = System.getProperty("cbmw.cewb.pricing.policy",
                "CONSTANT_PROFIT").trim().toUpperCase();
        mode = Mode.valueOf(configured);
        requireFraction("profit margin", profitMargin);
        requireFraction("discount", discount);
        requireFraction("prediction risk", predictionRisk);
    }

    Mode getMode() { return mode; }

    void quoteBeforeExecution(WorkflowRecord workflow) {
        double onDemandReference = 0.0;
        for (Task task : workflow.getTaskList()) {
            int id = task.getCloudletId();
            double duration = workflow.getEstimatedExecTime(id);
            double price = HybridVmPool.onDemandPricePerSecond(
                    workflow.getTaskCores(id), workflow.getTaskRamMb(id));
            onDemandReference += Math.max(
                    HybridVmPool.ON_DEMAND_MIN_BILLING_SECONDS, duration) * price;
        }
        double quote;
        if (mode == Mode.CONSTANT_DISCOUNT) {
            quote = onDemandReference * (1.0 - discount);
        } else if (mode == Mode.PREDICTION_BASED) {
            quote = onDemandReference * (1.0 - discount + predictionRisk);
        } else {
            quote = onDemandReference * (1.0 + profitMargin);
        }
        workflow.setEstimatedOnDemandCost(onDemandReference);
        workflow.setEstimatedRawCost(onDemandReference);
        workflow.setOfferedPrice(Math.max(0.0, quote));
        workflow.setPriceAccepted(true);
    }

    void settleAfterExecution(WorkflowRecord workflow) {
        double executionCost = workflow.getTotalOnDemandCost()
                + workflow.getTotalSpotCost();
        workflow.setEstimatedRawCost(executionCost);
        if (mode == Mode.CONSTANT_PROFIT) {
            workflow.setOfferedPrice(executionCost * (1.0 + profitMargin));
        }
        workflow.setBrokerRevenue(workflow.getOfferedPrice());
        workflow.setBrokerProfit(workflow.getOfferedPrice() - executionCost);
    }

    private static void requireFraction(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("CEWB " + name + " must be within [0,1]");
        }
    }

    private static double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name,
                Double.toString(fallback)));
    }
}
