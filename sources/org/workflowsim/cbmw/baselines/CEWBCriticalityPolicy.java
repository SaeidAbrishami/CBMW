package org.workflowsim.cbmw.baselines;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * CEWB task timing and reliability classification.
 *
 * The bundled CEWB reference defines HEFT-style ranks and proportional
 * sub-deadlines as the WorkflowSim adaptation, then maps normalized slack to
 * reliability classes. Thresholds remain configurable because the original
 * paper's exact values are not available in this repository.
 */
final class CEWBCriticalityPolicy implements CEWBTaskPolicy {

    static final int ON_DEMAND = 0;
    static final int HIGH_RELIABILITY_SPOT = 1;
    static final int MEDIUM_RELIABILITY_SPOT = 2;
    static final int LOW_RELIABILITY_SPOT = 3;

    private final double onDemandThreshold = property(
            "cbmw.cewb.criticality.ondemand", 0.75);
    private final double highSpotThreshold = property(
            "cbmw.cewb.criticality.high.spot", 0.50);
    private final double mediumSpotThreshold = property(
            "cbmw.cewb.criticality.medium.spot", 0.25);
    private final Map<Integer, Double> upwardRanks = new HashMap<>();
    private final Map<Integer, Double> downwardRanks = new HashMap<>();

    CEWBCriticalityPolicy() {
        if (!(onDemandThreshold >= highSpotThreshold
                && highSpotThreshold >= mediumSpotThreshold
                && mediumSpotThreshold >= 0.0
                && onDemandThreshold <= 1.0)) {
            throw new IllegalArgumentException(
                    "CEWB criticality thresholds must descend within [0,1]");
        }
    }

    @Override
    public void preprocess(WorkflowRecord workflow, List<Task> tasks) {
        for (Task task : tasks) upwardRank(task, workflow);
        for (Task task : tasks) downwardRank(task, workflow);

        double criticalPath = 0.0;
        for (Task task : tasks) {
            criticalPath = Math.max(criticalPath,
                    downwardRanks.get(task.getCloudletId())
                            + upwardRanks.get(task.getCloudletId()));
        }
        if (!Double.isFinite(criticalPath) || criticalPath <= 0.0) {
            throw new IllegalStateException("CEWB critical path must be positive");
        }
        workflow.setCriticalPathLength(criticalPath);

        double span = workflow.getDeadline() - workflow.getArrivalTime();
        for (Task task : tasks) {
            int id = task.getCloudletId();
            double duration = workflow.getEstimatedExecTime(id);
            double progress = (downwardRanks.get(id) + duration) / criticalPath;
            double subDeadline = workflow.getArrivalTime()
                    + Math.min(1.0, Math.max(0.0, progress)) * span;
            double est = workflow.getArrivalTime() + downwardRanks.get(id);
            workflow.setEST(id, est);
            workflow.setEFT(id, est + duration);
            workflow.setLFT(id, subDeadline);
            workflow.setLST(id, subDeadline - duration);
            workflow.setScheduledStart(id, workflow.getArrivalTime());
        }
    }

    @Override
    public Map<Integer, CEWBTaskDecision> classify(List<Task> readyTasks,
                                                   WorkflowRecord workflow,
                                                   double now) {
        Map<Integer, Double> slacks = new HashMap<>();
        double maxPositiveSlack = 0.0;
        for (Task task : readyTasks) {
            int id = task.getCloudletId();
            double slack = workflow.getLFT(id)
                    - (now + workflow.getEstimatedExecTime(id));
            slacks.put(id, slack);
            maxPositiveSlack = Math.max(maxPositiveSlack, slack);
        }

        Map<Integer, CEWBTaskDecision> decisions = new HashMap<>();
        for (Task task : readyTasks) {
            int id = task.getCloudletId();
            double slack = slacks.get(id);
            double normalized = maxPositiveSlack > 0.0
                    ? Math.max(0.0, slack) / maxPositiveSlack : 0.0;
            double criticality = slack <= 0.0 ? 1.0 : 1.0 - normalized;
            int resourceClass = classify(slack, criticality);
            decisions.put(id, new CEWBTaskDecision(slack, criticality,
                    resourceClass, reasonFor(resourceClass)));
        }
        return decisions;
    }

    @Override
    public double getUpwardRank(int taskId) {
        return upwardRanks.getOrDefault(taskId, 0.0);
    }

    @Override
    public String getName() {
        return "heft-normalized-criticality";
    }

    private static String reasonFor(int resourceClass) {
        switch (resourceClass) {
            case ON_DEMAND: return "CRITICALITY_ON_DEMAND";
            case HIGH_RELIABILITY_SPOT: return "CRITICALITY_HIGH_SPOT";
            case MEDIUM_RELIABILITY_SPOT: return "CRITICALITY_MEDIUM_SPOT";
            default: return "CRITICALITY_LOW_SPOT";
        }
    }

    private int classify(double slack, double criticality) {
        if (slack <= 0.0 || criticality >= onDemandThreshold) return ON_DEMAND;
        if (criticality >= highSpotThreshold) return HIGH_RELIABILITY_SPOT;
        if (criticality >= mediumSpotThreshold) return MEDIUM_RELIABILITY_SPOT;
        return LOW_RELIABILITY_SPOT;
    }

    private double upwardRank(Task task, WorkflowRecord workflow) {
        int id = task.getCloudletId();
        Double cached = upwardRanks.get(id);
        if (cached != null) return cached;
        double childMaximum = 0.0;
        for (Task child : task.getChildList()) {
            childMaximum = Math.max(childMaximum, upwardRank(child, workflow));
        }
        double result = workflow.getEstimatedExecTime(id) + childMaximum;
        upwardRanks.put(id, result);
        return result;
    }

    private double downwardRank(Task task, WorkflowRecord workflow) {
        int id = task.getCloudletId();
        Double cached = downwardRanks.get(id);
        if (cached != null) return cached;
        double result = 0.0;
        for (Task parent : task.getParentList()) {
            result = Math.max(result, downwardRank(parent, workflow)
                    + workflow.getEstimatedExecTime(parent.getCloudletId()));
        }
        downwardRanks.put(id, result);
        return result;
    }

    private static double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name,
                Double.toString(fallback)));
    }
}
