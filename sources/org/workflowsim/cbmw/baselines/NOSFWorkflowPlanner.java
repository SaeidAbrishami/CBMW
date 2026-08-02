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
import org.workflowsim.cbmw.CBMWStaticPlanningAlgorithm;
import org.workflowsim.cbmw.WorkflowRecord;

/** Implements the paper's NOSF preprocessing (Alg. 1) and feedback (Alg. 2). */
final class NOSFWorkflowPlanner {

    enum PriorityPolicy { EST, EFT }

    private final Map<Integer, NOSFWorkflowState> workflows = new HashMap<>();
    private final NOSFTransferModel transferModel;
    private final PriorityPolicy priorityPolicy;

    NOSFWorkflowPlanner() {
        this(new NOSFTransferModel(), readPriorityPolicy());
    }

    NOSFWorkflowPlanner(NOSFTransferModel transferModel,
                        PriorityPolicy priorityPolicy) {
        this.transferModel = transferModel;
        this.priorityPolicy = priorityPolicy;
    }

    void preprocess(WorkflowRecord workflow, List<Task> tasks,
                    List<NOSFVmType> vmTypes) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("NOSF workflow is empty");
        }
        if (vmTypes == null || vmTypes.isEmpty()) {
            throw new IllegalArgumentException("NOSF requires at least one VM type");
        }

        double fastestScale = Double.POSITIVE_INFINITY;
        for (NOSFVmType type : vmTypes) {
            fastestScale = Math.min(fastestScale, type.runtime(1.0));
        }

        NOSFWorkflowState state = new NOSFWorkflowState(workflow.getWorkflowId());
        Map<Integer, Node> nodes = new HashMap<>();
        for (Task task : tasks) {
            int taskId = task.getCloudletId();
            double mean = workflow.getNominalExecTime(taskId);
            double weight = NOSFRuntimeModel.weight(mean);
            double fastestDuration = weight * fastestScale;
            NOSFTaskState taskState = new NOSFTaskState(task, mean,
                    NOSFRuntimeModel.sigma(mean), weight, fastestDuration);
            if (state.tasks.put(taskId, taskState) != null) {
                throw new IllegalArgumentException("Duplicate NOSF task " + taskId);
            }
            nodes.put(taskId, new Node(taskState));
        }

        for (Task task : tasks) {
            Node parent = nodes.get(task.getCloudletId());
            for (Task childTask : task.getChildList()) {
                Node child = nodes.get(childTask.getCloudletId());
                if (child == null) {
                    throw new IllegalArgumentException("NOSF child outside workflow: "
                            + childTask.getCloudletId());
                }
                addEdge(parent, child,
                        transferModel.crossVmDelay(task, childTask));
            }
        }

        Node entry = Node.synthetic(Integer.MIN_VALUE);
        Node exit = Node.synthetic(Integer.MAX_VALUE);
        for (Node node : nodes.values()) {
            if (node.parents.isEmpty()) addEdge(entry, node, 0.0);
            if (node.children.isEmpty()) addEdge(node, exit, 0.0);
        }

        List<Node> topological = topologicalOrder(entry, exit, nodes.size() + 2);
        calculateInitialTimes(topological, entry, exit,
                workflow.getArrivalTime(), workflow.getDeadline());
        entry.assigned = true;
        entry.subDeadline = workflow.getArrivalTime();
        exit.assigned = true;
        exit.subDeadline = workflow.getDeadline();
        assignParents(exit, new HashSet<Node>());

        double criticalPathFinish = workflow.getArrivalTime();
        for (Node node : nodes.values()) {
            if (!node.assigned || !Double.isFinite(node.subDeadline)) {
                throw new IllegalStateException(
                        "NOSF PCP did not assign task " + node.id);
            }
            NOSFTaskState taskState = node.taskState;
            taskState.initialEst = node.est;
            taskState.initialEft = node.eft;
            taskState.currentEst = node.est;
            taskState.currentEft = node.eft;
            taskState.latestCompletion = node.lct;
            taskState.subDeadline = node.subDeadline;
            taskState.delta = node.subDeadline - node.est;
            taskState.priority = priorityPolicy == PriorityPolicy.EST
                    ? node.est : node.eft;

            int taskId = node.id;
            Task task = taskState.task;
            task.setWorkflowId(workflow.getWorkflowId());
            task.setVmId(CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
            workflow.setAssignedVm(taskId,
                    CBMWStaticPlanningAlgorithm.ON_DEMAND_SENTINEL);
            workflow.setEST(taskId, node.est);
            workflow.setEFT(taskId, node.eft);
            workflow.setLFT(taskId, node.subDeadline);
            workflow.setLST(taskId, node.subDeadline - taskState.fastestDuration);
            workflow.setScheduledStart(taskId, node.est);
            criticalPathFinish = Math.max(criticalPathFinish, node.eft);
        }
        workflow.setCriticalPathLength(criticalPathFinish - workflow.getArrivalTime());
        workflows.put(workflow.getWorkflowId(), state);
    }

    /** Updates only immediate successors that have become ready, per Eqs. 16-18. */
    void feedback(WorkflowRecord workflow, int completedTaskId, double finishTime) {
        NOSFWorkflowState state = state(workflow);
        state.actualFinishTimes.put(completedTaskId, finishTime);
        NOSFTaskState completed = state.task(completedTaskId);

        for (Task child : completed.task.getChildList()) {
            if (!allParentsCompleted(workflow, child)) continue;
            NOSFTaskState childState = state.task(child.getCloudletId());
            double adjustedEft = Double.NEGATIVE_INFINITY;
            int childVm = workflow.getAssignedVm(childState.taskId);
            for (Task parent : child.getParentList()) {
                double parentFinish = state.actualFinishTimes.getOrDefault(
                        parent.getCloudletId(), workflow.getEFT(parent.getCloudletId()));
                int parentVm = workflow.getAssignedVm(parent.getCloudletId());
                adjustedEft = Math.max(adjustedEft, parentFinish
                        + transferModel.delay(parent, child, parentVm, childVm)
                        + childState.fastestDuration);
            }
            if (!Double.isFinite(adjustedEft)) {
                adjustedEft = finishTime + childState.fastestDuration;
            }
            double adjustedEst = adjustedEft - childState.fastestDuration;
            double adjustedSubDeadline = Math.min(
                    adjustedEst + childState.delta,
                    childState.latestCompletion);

            childState.currentEst = adjustedEst;
            childState.currentEft = adjustedEft;
            childState.subDeadline = adjustedSubDeadline;
            childState.priority = priorityPolicy == PriorityPolicy.EST
                    ? adjustedEst : adjustedEft;
            workflow.setEST(childState.taskId, adjustedEst);
            workflow.setEFT(childState.taskId, adjustedEft);
            workflow.setLFT(childState.taskId, adjustedSubDeadline);
            workflow.setLST(childState.taskId,
                    adjustedSubDeadline - childState.fastestDuration);
        }
    }

    double priority(WorkflowRecord workflow, int taskId) {
        return state(workflow).task(taskId).priority;
    }

    double subDeadline(WorkflowRecord workflow, int taskId) {
        return state(workflow).task(taskId).subDeadline;
    }

    double dataReadyTime(WorkflowRecord workflow, int taskId,
                         int candidateVmId, double now) {
        NOSFWorkflowState state = state(workflow);
        Task task = state.task(taskId).task;
        double ready = now;
        for (Task parent : task.getParentList()) {
            double parentFinish = state.actualFinishTimes.getOrDefault(
                    parent.getCloudletId(), workflow.getEFT(parent.getCloudletId()));
            int parentVm = workflow.getAssignedVm(parent.getCloudletId());
            ready = Math.max(ready, parentFinish + transferModel.delay(
                    parent, task, parentVm, candidateVmId));
        }
        return ready;
    }

    PriorityPolicy getPriorityPolicy() { return priorityPolicy; }

    NOSFTransferModel getTransferModel() { return transferModel; }

    void forget(int workflowId) { workflows.remove(workflowId); }

    private NOSFWorkflowState state(WorkflowRecord workflow) {
        NOSFWorkflowState state = workflows.get(workflow.getWorkflowId());
        if (state == null) {
            throw new IllegalStateException(
                    "NOSF workflow state not found for " + workflow.getWorkflowId());
        }
        return state;
    }

    private static boolean allParentsCompleted(WorkflowRecord workflow,
                                               Task task) {
        for (Task parent : task.getParentList()) {
            if (!workflow.isTaskCompleted(parent.getCloudletId())) return false;
        }
        return true;
    }

    private static PriorityPolicy readPriorityPolicy() {
        return PriorityPolicy.valueOf(System.getProperty(
                "nosf.priority.policy", PriorityPolicy.EST.name())
                .trim().toUpperCase());
    }

    private static void calculateInitialTimes(List<Node> topological,
                                              Node entry, Node exit,
                                              double arrival,
                                              double deadline) {
        entry.est = arrival;
        entry.eft = arrival;
        for (Node node : topological) {
            if (node == entry) continue;
            double est = arrival;
            for (Edge edge : node.parents) {
                est = Math.max(est, edge.other.eft + edge.transfer);
            }
            node.est = est;
            node.eft = est + node.duration;
        }

        exit.lct = deadline;
        for (int i = topological.size() - 1; i >= 0; i--) {
            Node node = topological.get(i);
            if (node == exit) continue;
            double lct = deadline;
            for (Edge edge : node.children) {
                lct = Math.min(lct,
                        edge.other.lct - edge.other.duration - edge.transfer);
            }
            node.lct = lct;
        }
    }

    private static void assignParents(Node input, Set<Node> recursionStack) {
        if (!recursionStack.add(input)) {
            throw new IllegalStateException("Cycle while assigning NOSF PCP paths");
        }
        try {
            while (hasUnassignedParent(input)) {
                List<Node> reversePath = new ArrayList<>();
                Node cursor = input;
                while (hasUnassignedParent(cursor)) {
                    Node parent = criticalUnassignedParent(cursor);
                    if (parent == null) {
                        throw new IllegalStateException(
                                "NOSF PCP could not select a critical parent");
                    }
                    reversePath.add(parent);
                    cursor = parent;
                }
                assignPath(reversePath);
                for (Node node : reversePath) assignParents(node, recursionStack);
            }
        } finally {
            recursionStack.remove(input);
        }
    }

    /** Applies Eq. (11) to one PCP stored from its last task back to its first. */
    private static void assignPath(List<Node> reversePath) {
        if (reversePath.isEmpty()) return;
        Node first = reversePath.get(reversePath.size() - 1);
        Node last = reversePath.get(0);
        double denominator = last.eft - first.est;
        if (!(denominator > 0.0)) {
            throw new IllegalStateException("Invalid NOSF PCP duration");
        }
        double pathDeadline = last.lct - first.est;
        for (int i = reversePath.size() - 1; i >= 0; i--) {
            Node node = reversePath.get(i);
            node.assigned = true;
            node.subDeadline = first.est
                    + (node.eft - first.est) / denominator * pathDeadline;
        }
    }

    private static boolean hasUnassignedParent(Node node) {
        for (Edge edge : node.parents) {
            if (!edge.other.assigned) return true;
        }
        return false;
    }

    private static Node criticalUnassignedParent(Node node) {
        Node selected = null;
        double selectedFinish = Double.NEGATIVE_INFINITY;
        for (Edge edge : node.parents) {
            Node parent = edge.other;
            if (parent.assigned) continue;
            double finish = parent.eft + edge.transfer;
            if (selected == null || finish > selectedFinish
                    || (finish == selectedFinish && parent.id < selected.id)) {
                selected = parent;
                selectedFinish = finish;
            }
        }
        return selected;
    }

    private static List<Node> topologicalOrder(Node entry, Node exit,
                                               int expectedCount) {
        Set<Node> all = new HashSet<>();
        ArrayDeque<Node> discover = new ArrayDeque<>();
        discover.add(entry);
        while (!discover.isEmpty()) {
            Node node = discover.removeFirst();
            if (!all.add(node)) continue;
            for (Edge edge : node.children) discover.add(edge.other);
        }
        if (!all.contains(exit) || all.size() != expectedCount) {
            throw new IllegalArgumentException("NOSF workflow graph is disconnected");
        }

        Map<Node, Integer> indegree = new HashMap<>();
        PriorityQueue<Node> ready = new PriorityQueue<>(
                Comparator.comparingInt(node -> node.id));
        for (Node node : all) {
            indegree.put(node, node.parents.size());
            if (node.parents.isEmpty()) ready.add(node);
        }
        List<Node> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            Node node = ready.remove();
            order.add(node);
            for (Edge edge : node.children) {
                Node child = edge.other;
                int remaining = indegree.get(child) - 1;
                indegree.put(child, remaining);
                if (remaining == 0) ready.add(child);
            }
        }
        if (order.size() != all.size()) {
            throw new IllegalArgumentException("NOSF workflow contains a cycle");
        }
        return order;
    }

    private static void addEdge(Node parent, Node child, double transfer) {
        parent.children.add(new Edge(child, transfer));
        child.parents.add(new Edge(parent, transfer));
    }

    private static final class Edge {
        final Node other;
        final double transfer;

        Edge(Node other, double transfer) {
            this.other = other;
            this.transfer = transfer;
        }
    }

    private static final class Node {
        final int id;
        final NOSFTaskState taskState;
        final double duration;
        final List<Edge> parents = new ArrayList<>();
        final List<Edge> children = new ArrayList<>();
        double est;
        double eft;
        double lct;
        double subDeadline = Double.NaN;
        boolean assigned;

        Node(NOSFTaskState taskState) {
            this.id = taskState.taskId;
            this.taskState = taskState;
            this.duration = taskState.fastestDuration;
        }

        private Node(int id) {
            this.id = id;
            this.taskState = null;
            this.duration = 0.0;
        }

        static Node synthetic(int id) { return new Node(id); }
    }
}
