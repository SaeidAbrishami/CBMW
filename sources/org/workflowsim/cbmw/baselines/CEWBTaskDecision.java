package org.workflowsim.cbmw.baselines;

/** Immutable result of classifying a ready CEWB task. */
final class CEWBTaskDecision {

    private final double slack;
    private final double criticality;
    private final int resourceClass;
    private final String reason;

    CEWBTaskDecision(double slack, double criticality, int resourceClass,
                     String reason) {
        if (!Double.isFinite(slack) || !Double.isFinite(criticality)
                || criticality < 0.0 || criticality > 1.0
                || resourceClass < CEWBCriticalityPolicy.ON_DEMAND
                || resourceClass > CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT) {
            throw new IllegalArgumentException("Invalid CEWB task decision");
        }
        this.slack = slack;
        this.criticality = criticality;
        this.resourceClass = resourceClass;
        this.reason = reason == null ? "" : reason;
    }

    double getSlack() { return slack; }
    double getCriticality() { return criticality; }
    int getResourceClass() { return resourceClass; }
    String getReason() { return reason; }
}
