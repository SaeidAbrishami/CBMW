package org.workflowsim.cbmw.baselines;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.workflowsim.Task;
import org.workflowsim.cbmw.WorkflowRecord;

/**
 * Adapts the reference implementation's PCP sub-deadlines and absolute slack
 * classes to this repository's workflow/runtime model. Spot offers, prices,
 * capacities, and interruptions remain owned by {@link CEWBSpotMarket}.
 */
final class CEWBReferenceTaskPolicy implements CEWBTaskPolicy {

    private final double slackBase = property(
            "cbmw.cewb.reference.slack.base.sec", 400.4);
    private final Map<Integer, Double> upwardRanks = new HashMap<>();

    CEWBReferenceTaskPolicy() {
        if (!Double.isFinite(slackBase) || slackBase <= 0.0) {
            throw new IllegalArgumentException(
                    "cbmw.cewb.reference.slack.base.sec must be positive");
        }
    }

    @Override
    public void preprocess(WorkflowRecord workflow, List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Reference CEWB workflow is empty");
        }
        upwardRanks.clear();

        Map<Integer, Node> nodes = new HashMap<>();
        for (Task task : tasks) {
            int id = task.getCloudletId();
            double duration = workflow.getEstimatedExecTime(id);
            if (!Double.isFinite(duration) || duration <= 0.0) {
                throw new IllegalArgumentException(
                        "Invalid reference CEWB task duration for task " + id);
            }
            if (nodes.put(id, new Node(id, duration)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate reference CEWB task id " + id);
            }
        }

        for (Task task : tasks) {
            Node node = nodes.get(task.getCloudletId());
            for (Task childTask : task.getChildList()) {
                Node child = nodes.get(childTask.getCloudletId());
                if (child == null) {
                    throw new IllegalArgumentException(
                            "Reference CEWB child is outside the workflow: "
                                    + childTask.getCloudletId());
                }
                addEdge(node, child);
            }
        }

        Node entry = new Node(Integer.MIN_VALUE, 0.0);
        Node exit = new Node(Integer.MAX_VALUE, 0.0);
        for (Node node : nodes.values()) {
            if (node.parents.isEmpty()) addEdge(entry, node);
            if (node.children.isEmpty()) addEdge(node, exit);
        }

        List<Node> topological = topologicalOrder(entry, exit, nodes.size() + 2);
        double span = workflow.getDeadline() - workflow.getArrivalTime();
        if (!Double.isFinite(span) || span <= 0.0) {
            throw new IllegalArgumentException(
                    "Reference CEWB workflow deadline span must be positive");
        }

        calculateInitialEst(topological, entry);
        calculateInitialLft(topological, exit, span);
        entry.assigned = true;
        entry.subDeadline = 0.0;
        exit.assigned = true;
        exit.subDeadline = span;
        assignParents(exit, new HashSet<Node>());

        double criticalPath = 0.0;
        Map<Node, Double> earliestFinish = new HashMap<>();
        for (Node node : topological) {
            double parentFinish = 0.0;
            for (Node parent : node.parents) {
                parentFinish = Math.max(parentFinish,
                        earliestFinish.getOrDefault(parent, 0.0));
            }
            double finish = parentFinish + node.duration;
            earliestFinish.put(node, finish);
            if (node == exit) criticalPath = finish;
        }
        workflow.setCriticalPathLength(criticalPath);

        double arrival = workflow.getArrivalTime();
        for (Node node : nodes.values()) {
            if (!node.assigned || !Double.isFinite(node.subDeadline)) {
                throw new IllegalStateException(
                        "Reference PCP did not assign task " + node.id);
            }
            double est = arrival + node.est;
            double lft = arrival + node.subDeadline;
            workflow.setEST(node.id, est);
            workflow.setEFT(node.id, est + node.duration);
            workflow.setLFT(node.id, lft);
            workflow.setLST(node.id, lft - node.duration);
            workflow.setScheduledStart(node.id, arrival);
            upwardRanks.put(node.id, upwardRank(node, new HashMap<Node, Double>()));
        }
    }

    @Override
    public Map<Integer, CEWBTaskDecision> classify(List<Task> readyTasks,
                                                   WorkflowRecord workflow,
                                                   double now) {
        Map<Integer, CEWBTaskDecision> decisions = new HashMap<>();
        for (Task task : readyTasks) {
            int id = task.getCloudletId();
            double slack = workflow.getLFT(id) - now
                    - workflow.getEstimatedExecTime(id);
            int resourceClass;
            String reason;
            if (slack <= slackBase) {
                resourceClass = CEWBCriticalityPolicy.ON_DEMAND;
                reason = "SLACK_ON_DEMAND";
            } else if (slack < 2.0 * slackBase) {
                resourceClass = CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT;
                reason = "SLACK_HIGH_SPOT";
            } else if (slack < 4.0 * slackBase) {
                resourceClass = CEWBCriticalityPolicy.MEDIUM_RELIABILITY_SPOT;
                reason = "SLACK_MEDIUM_SPOT";
            } else {
                resourceClass = CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT;
                reason = "SLACK_LOW_SPOT";
            }
            double criticality;
            if (slack <= 0.0) criticality = 1.0;
            else criticality = Math.max(0.0,
                    1.0 - Math.min(1.0, slack / (4.0 * slackBase)));
            decisions.put(id, new CEWBTaskDecision(slack, criticality,
                    resourceClass, reason));
        }
        return decisions;
    }

    CEWBTaskDecision classifyDuration(double subDeadline, double now,
                                      double estimatedDuration) {
        if (!Double.isFinite(subDeadline) || !Double.isFinite(now)
                || !Double.isFinite(estimatedDuration)
                || estimatedDuration <= 0.0) {
            throw new IllegalArgumentException(
                    "Invalid reference CEWB remaining-duration classification");
        }
        double slack = subDeadline - now - estimatedDuration;
        int resourceClass;
        String reason;
        if (slack <= slackBase) {
            resourceClass = CEWBCriticalityPolicy.ON_DEMAND;
            reason = "SLACK_ON_DEMAND";
        } else if (slack < 2.0 * slackBase) {
            resourceClass = CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT;
            reason = "SLACK_HIGH_SPOT";
        } else if (slack < 4.0 * slackBase) {
            resourceClass = CEWBCriticalityPolicy.MEDIUM_RELIABILITY_SPOT;
            reason = "SLACK_MEDIUM_SPOT";
        } else {
            resourceClass = CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT;
            reason = "SLACK_LOW_SPOT";
        }
        double criticality = slack <= 0.0 ? 1.0 : Math.max(0.0,
                1.0 - Math.min(1.0, slack / (4.0 * slackBase)));
        return new CEWBTaskDecision(slack, criticality, resourceClass, reason);
    }

    @Override
    public double getUpwardRank(int taskId) {
        return upwardRanks.getOrDefault(taskId, 0.0);
    }

    @Override
    public String getName() {
        return "reference-pcp-absolute-slack";
    }

    private static void calculateInitialEst(List<Node> topological, Node entry) {
        entry.est = 0.0;
        for (Node node : topological) {
            if (node == entry) continue;
            Node selected = null;
            for (Node parent : node.parents) {
                if (selected == null || parent.est > selected.est) selected = parent;
            }
            if (selected == null) {
                throw new IllegalStateException("Reference PCP node has no parent");
            }
            node.est = selected.est + selected.duration;
        }
    }

    private static void calculateInitialLft(List<Node> topological, Node exit,
                                            double deadlineSpan) {
        exit.lft = deadlineSpan;
        for (int i = topological.size() - 1; i >= 0; i--) {
            Node node = topological.get(i);
            if (node == exit) continue;
            Node selected = null;
            for (Node child : node.children) {
                if (selected == null || child.lft < selected.lft) selected = child;
            }
            if (selected == null) {
                throw new IllegalStateException("Reference PCP node has no child");
            }
            node.lft = selected.lft - selected.duration;
        }
    }

    private static void assignParents(Node input, Set<Node> recursionStack) {
        if (!recursionStack.add(input)) {
            throw new IllegalStateException("Cycle while assigning reference PCP");
        }
        try {
            while (hasUnassignedParent(input)) {
                List<Node> path = new ArrayList<>();
                Node cursor = input;
                while (hasUnassignedParent(cursor)) {
                    Node parent = criticalUnassignedParent(cursor);
                    if (parent == null) {
                        throw new IllegalStateException(
                                "Reference PCP could not select a critical parent");
                    }
                    path.add(parent);
                    cursor = parent;
                }
                assignPath(path);
                for (Node node : path) {
                    for (Node child : node.children) {
                        if (!child.assigned) child.est = node.subDeadline;
                    }
                    for (Node parent : node.parents) {
                        if (!parent.assigned) parent.lft = node.subDeadline;
                    }
                    assignParents(node, recursionStack);
                }
            }
        } finally {
            recursionStack.remove(input);
        }
    }

    private static void assignPath(List<Node> path) {
        if (path.isEmpty()) return;
        double start = path.get(path.size() - 1).est;
        double end = path.get(0).lft;
        double totalDuration = 0.0;
        for (Node node : path) totalDuration += node.duration;
        if (!(totalDuration > 0.0) || !Double.isFinite(start)
                || !Double.isFinite(end)) {
            throw new IllegalStateException(
                    "Reference PCP path has an invalid time domain: start="
                            + start + " end=" + end + " first=" + path.get(0).id
                            + " last=" + path.get(path.size() - 1).id
                            + " nodes=" + path.size());
        }
        // The active reference code does not clamp an inverted partial-path
        // domain. Preserve that behavior: it can pull a non-critical task's
        // sub-deadline earlier and will consequently classify it as urgent.
        double available = end - start;
        double lastDeadline = start;
        for (int i = path.size() - 1; i >= 0; i--) {
            Node node = path.get(i);
            node.assigned = true;
            lastDeadline += node.duration * available / totalDuration;
            node.subDeadline = lastDeadline;
        }
    }

    private static boolean hasUnassignedParent(Node node) {
        for (Node parent : node.parents) if (!parent.assigned) return true;
        return false;
    }

    private static Node criticalUnassignedParent(Node node) {
        Node selected = null;
        for (Node parent : node.parents) {
            if (!parent.assigned
                    && (selected == null || parent.est > selected.est)) {
                selected = parent;
            }
        }
        return selected;
    }

    private static double upwardRank(Node node, Map<Node, Double> cache) {
        Double cached = cache.get(node);
        if (cached != null) return cached;
        double childMax = 0.0;
        for (Node child : node.children) {
            childMax = Math.max(childMax, upwardRank(child, cache));
        }
        double result = node.duration + childMax;
        cache.put(node, result);
        return result;
    }

    private static List<Node> topologicalOrder(Node entry, Node exit,
                                               int expectedCount) {
        Set<Node> all = new HashSet<>();
        ArrayDeque<Node> discover = new ArrayDeque<>();
        discover.add(entry);
        while (!discover.isEmpty()) {
            Node node = discover.removeFirst();
            if (!all.add(node)) continue;
            discover.addAll(node.children);
        }
        if (!all.contains(exit) || all.size() != expectedCount) {
            throw new IllegalArgumentException(
                    "Reference CEWB workflow graph is disconnected");
        }

        Map<Node, Integer> indegree = new HashMap<>();
        PriorityQueue<Node> ready = new PriorityQueue<>(Comparator.comparingInt(
                node -> node.id));
        for (Node node : all) {
            indegree.put(node, node.parents.size());
            if (node.parents.isEmpty()) ready.add(node);
        }
        List<Node> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            Node node = ready.remove();
            result.add(node);
            for (Node child : node.children) {
                int remaining = indegree.get(child) - 1;
                indegree.put(child, remaining);
                if (remaining == 0) ready.add(child);
            }
        }
        if (result.size() != all.size()) {
            throw new IllegalArgumentException(
                    "Reference CEWB workflow contains a cycle");
        }
        return result;
    }

    private static void addEdge(Node parent, Node child) {
        if (!parent.children.contains(child)) parent.children.add(child);
        if (!child.parents.contains(parent)) child.parents.add(parent);
    }

    private static double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name,
                Double.toString(fallback)));
    }

    private static final class Node {
        private final int id;
        private final double duration;
        private final List<Node> parents = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();
        private double est;
        private double lft;
        private double subDeadline = Double.NaN;
        private boolean assigned;

        Node(int id, double duration) {
            this.id = id;
            this.duration = duration;
        }
    }
}
