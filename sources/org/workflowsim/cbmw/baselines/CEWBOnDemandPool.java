package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paper-style CEWB on-demand resource pool.
 *
 * <p>Physical VMs are large, reusable resources.  A task receives a logical
 * container on idle CPU/RAM and pays only the container deployment delay when
 * the physical VM is already running.  VM provisioning is therefore charged
 * once per physical instance rather than once per task.</p>
 */
final class CEWBOnDemandPool {

    static final int VM_CORES = intProperty(
            "cbmw.cewb.ondemand.vm.cores", 32);
    static final int VM_RAM_MB = intProperty(
            "cbmw.cewb.ondemand.vm.ram.mb", 64 * 1024);
    static final double VM_PROVISIONING_SECONDS = property(
            "cbmw.cewb.ondemand.vm.provisioning.sec", 60.0);
    static final double CONTAINER_DEPLOYMENT_SECONDS = property(
            "cbmw.cewb.container.delay.sec", 0.4);
    static final double PROVISIONING_INTERVAL_SECONDS = property(
            "cbmw.cewb.provisioning.interval.sec", 100.0);
    static final double VM_PRICE_PER_SECOND = property(
            "cbmw.cewb.ondemand.vm.price.per.sec", 1.792 / 3600.0);
    static final double MIN_BILLING_SECONDS = property(
            "cbmw.cewb.ondemand.vm.min.billing.sec", 60.0);
    static final int INITIAL_READY_INSTANCES = intProperty(
            "cbmw.cewb.ondemand.initial.ready.instances", 1);
    static final int MIN_READY_INSTANCES = intProperty(
            "cbmw.cewb.ondemand.min.ready.instances", 1);
    static final int MAX_INSTANCES = intProperty(
            "cbmw.cewb.ondemand.max.instances", 10_000);

    static final class Instance {
        private final int id;
        private final double orderedAt;
        private final double readyAt;
        private boolean launched;
        private boolean active = true;
        private int usedCores;
        private int usedRamMb;
        private double idleSince = Double.NaN;
        private double destroyAt = Double.NaN;
        private final Map<Integer, Double> workflowCoreSeconds = new HashMap<>();

        private Instance(int id, double orderedAt, double readyAt,
                         boolean launched) {
            this.id = id;
            this.orderedAt = orderedAt;
            this.readyAt = readyAt;
            this.launched = launched;
        }

        int getId() { return id; }
        double getOrderedAt() { return orderedAt; }
        double getReadyAt() { return readyAt; }
        boolean isLaunched() { return launched; }
        boolean isActive() { return active; }
        int getUsedCores() { return usedCores; }
        int getFreeCores() { return VM_CORES - usedCores; }
        int getFreeRamMb() { return VM_RAM_MB - usedRamMb; }
        double getDestroyAt() { return destroyAt; }
    }

    static final class Offer {
        private final Instance instance;
        private final int workflowId;
        private final int cores;
        private final int ramMb;
        private final double allocatedAt;
        private boolean released;

        private Offer(Instance instance, int workflowId, int cores, int ramMb,
                      double allocatedAt) {
            this.instance = instance;
            this.workflowId = workflowId;
            this.cores = cores;
            this.ramMb = ramMb;
            this.allocatedAt = allocatedAt;
        }

        int getInstanceId() { return instance.id; }
        int getCores() { return cores; }
        double getContainerDelaySeconds() { return CONTAINER_DEPLOYMENT_SECONDS; }
        double getAllocatedPricePerSecond() {
            return VM_PRICE_PER_SECOND * cores / (double) VM_CORES;
        }
    }

    private final List<Instance> instances = new ArrayList<>();
    private int nextInstanceId = 2_000_000;
    private long provisionedInstances;
    private long containerAllocations;
    private long unavailableAllocations;
    private long terminatedInstances;
    private boolean costsSettled;
    private double settledPhysicalCost;
    private double settledCapacityCoreSeconds;
    private double settledCapacityRamMbSeconds;

    CEWBOnDemandPool() {
        requirePositive("VM cores", VM_CORES);
        requirePositive("VM RAM", VM_RAM_MB);
        requireFiniteNonNegative("VM provisioning delay", VM_PROVISIONING_SECONDS);
        requireFiniteNonNegative("container deployment delay",
                CONTAINER_DEPLOYMENT_SECONDS);
        if (!(PROVISIONING_INTERVAL_SECONDS > 0.0)
                || !Double.isFinite(PROVISIONING_INTERVAL_SECONDS)) {
            throw new IllegalArgumentException(
                    "CEWB provisioning interval must be finite and positive");
        }
        requireFiniteNonNegative("VM price", VM_PRICE_PER_SECOND);
        requireFiniteNonNegative("minimum billing", MIN_BILLING_SECONDS);
        if (INITIAL_READY_INSTANCES < 0 || MIN_READY_INSTANCES < 0
                || MAX_INSTANCES <= 0
                || INITIAL_READY_INSTANCES > MAX_INSTANCES
                || MIN_READY_INSTANCES > MAX_INSTANCES) {
            throw new IllegalArgumentException("Invalid CEWB on-demand pool bounds");
        }
    }

    List<Instance> initialize(double now) {
        List<Instance> created = new ArrayList<>();
        for (int i = 0; i < INITIAL_READY_INSTANCES; i++) {
            created.add(create(now, now, true));
        }
        return created;
    }

    List<Instance> activateReady(double now) {
        List<Instance> activated = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance.active && !instance.launched
                    && now + 1e-9 >= instance.readyAt) {
                instance.launched = true;
                activated.add(instance);
            }
        }
        return activated;
    }

    /** Implements the core-counting step of paper Algorithm 2. */
    List<Instance> provisionFor(int requiredCores, int requiredRamMb,
                                double now) {
        if (requiredCores < 0 || requiredRamMb < 0) {
            throw new IllegalArgumentException("CEWB required capacity is negative");
        }
        int idleCores = 0;
        int idleRam = 0;
        for (Instance instance : instances) {
            if (instance.active && instance.launched) {
                idleCores += instance.getFreeCores();
                idleRam += instance.getFreeRamMb();
            } else if (instance.active) {
                // Pending instances will be available before the next normal
                // provisioning cycle; count them to avoid duplicate orders.
                idleCores += VM_CORES;
                idleRam += VM_RAM_MB;
            }
        }
        int coreDeficit = Math.max(0, requiredCores - idleCores);
        int ramDeficit = Math.max(0, requiredRamMb - idleRam);
        int count = Math.max(ceilDiv(coreDeficit, VM_CORES),
                ceilDiv(ramDeficit, VM_RAM_MB));
        int active = getActiveInstanceCount();
        count = Math.min(count, Math.max(0, MAX_INSTANCES - active));
        List<Instance> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            created.add(create(now, now + VM_PROVISIONING_SECONDS, false));
        }
        return created;
    }

    Offer acquire(int workflowId, int cores, int ramMb, double now) {
        validateRequirement(cores, ramMb);
        Instance selected = null;
        for (Instance candidate : instances) {
            if (!candidate.active || !candidate.launched
                    || candidate.getFreeCores() < cores
                    || candidate.getFreeRamMb() < ramMb) {
                continue;
            }
            if (selected == null || bestFit(candidate, cores, ramMb,
                    selected, cores, ramMb) < 0) {
                selected = candidate;
            }
        }
        if (selected == null) {
            unavailableAllocations++;
            return null;
        }
        selected.usedCores += cores;
        selected.usedRamMb += ramMb;
        selected.idleSince = Double.NaN;
        containerAllocations++;
        return new Offer(selected, workflowId, cores, ramMb, now);
    }

    void release(Offer offer, double now) {
        if (offer == null || offer.released) {
            throw new IllegalStateException("CEWB on-demand offer released twice");
        }
        Instance instance = offer.instance;
        if (!instance.active || instance.usedCores < offer.cores
                || instance.usedRamMb < offer.ramMb) {
            throw new IllegalStateException(
                    "CEWB on-demand release has no matching allocation");
        }
        instance.usedCores -= offer.cores;
        instance.usedRamMb -= offer.ramMb;
        double elapsed = Math.max(0.0, now - offer.allocatedAt);
        instance.workflowCoreSeconds.merge(offer.workflowId,
                elapsed * offer.cores, Double::sum);
        offer.released = true;
    }

    /**
     * Marks unused VMs during one cycle and terminates them only after they
     * remain completely idle for a second provisioning interval.
     */
    List<Instance> maintainIdle(double now, boolean queuedDemand) {
        if (queuedDemand) return Collections.emptyList();
        List<Instance> ready = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance.active && instance.launched) ready.add(instance);
        }
        ready.sort(Comparator.comparingInt(Instance::getId));
        List<Instance> terminated = new ArrayList<>();
        int retained = ready.size();
        for (Instance instance : ready) {
            if (retained <= MIN_READY_INSTANCES) break;
            if (instance.usedCores != 0 || instance.usedRamMb != 0) {
                instance.idleSince = Double.NaN;
                continue;
            }
            if (!Double.isFinite(instance.idleSince)) {
                instance.idleSince = now;
            } else if (now - instance.idleSince + 1e-9
                    >= PROVISIONING_INTERVAL_SECONDS) {
                terminate(instance, now);
                terminated.add(instance);
                retained--;
            }
        }
        return terminated;
    }

    List<Instance> terminateAll(double now) {
        List<Instance> terminated = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance.active) {
                if (instance.usedCores != 0 || instance.usedRamMb != 0) {
                    throw new IllegalStateException(
                            "CEWB terminated an on-demand VM with active containers");
                }
                terminate(instance, now);
                terminated.add(instance);
            }
        }
        return terminated;
    }

    /** Allocates physical VM rental cost by each workflow's core-time share. */
    Map<Integer, Double> settleCosts() {
        if (costsSettled) return Collections.emptyMap();
        costsSettled = true;
        Map<Integer, Double> weights = new HashMap<>();
        double totalWeight = 0.0;
        double totalCost = 0.0;
        double capacityCoreSeconds = 0.0;
        double capacityRamMbSeconds = 0.0;
        for (Instance instance : instances) {
            if (!instance.launched || !Double.isFinite(instance.destroyAt)) continue;
            double uptime = Math.max(MIN_BILLING_SECONDS,
                    instance.destroyAt - instance.readyAt);
            totalCost += uptime * VM_PRICE_PER_SECOND;
            capacityCoreSeconds += uptime * VM_CORES;
            capacityRamMbSeconds += uptime * VM_RAM_MB;
            for (Map.Entry<Integer, Double> entry
                    : instance.workflowCoreSeconds.entrySet()) {
                weights.merge(entry.getKey(), entry.getValue(), Double::sum);
                totalWeight += entry.getValue();
            }
        }
        settledPhysicalCost = totalCost;
        settledCapacityCoreSeconds = capacityCoreSeconds;
        settledCapacityRamMbSeconds = capacityRamMbSeconds;
        Map<Integer, Double> allocated = new HashMap<>();
        if (totalWeight > 0.0) {
            for (Map.Entry<Integer, Double> entry : weights.entrySet()) {
                allocated.put(entry.getKey(),
                        totalCost * entry.getValue() / totalWeight);
            }
        }
        return allocated;
    }

    double predictedContainerDelay(int cores, int ramMb, double now) {
        validateRequirement(cores, ramMb);
        double earliest = Double.POSITIVE_INFINITY;
        for (Instance instance : instances) {
            if (!instance.active) continue;
            if (instance.launched && instance.getFreeCores() >= cores
                    && instance.getFreeRamMb() >= ramMb) {
                return CONTAINER_DEPLOYMENT_SECONDS;
            }
            if (!instance.launched) earliest = Math.min(earliest, instance.readyAt);
        }
        if (Double.isFinite(earliest)) {
            return Math.max(0.0, earliest - now) + CONTAINER_DEPLOYMENT_SECONDS;
        }
        return VM_PROVISIONING_SECONDS + CONTAINER_DEPLOYMENT_SECONDS;
    }

    int getActiveInstanceCount() {
        int count = 0;
        for (Instance instance : instances) if (instance.active) count++;
        return count;
    }

    int getRunningContainerCount() {
        int count = 0;
        for (Instance instance : instances) {
            if (instance.active) count += instance.usedCores > 0 ? 1 : 0;
        }
        return count;
    }

    double getSettledPhysicalCost() { return settledPhysicalCost; }
    double getSettledCapacityCoreSeconds() { return settledCapacityCoreSeconds; }
    double getSettledCapacityRamMbSeconds() {
        return settledCapacityRamMbSeconds;
    }

    int getActiveCoreCapacity() {
        int total = 0;
        for (Instance instance : instances) {
            if (instance.active && instance.launched) total += VM_CORES;
        }
        return total;
    }

    int getActiveRamMbCapacity() {
        int total = 0;
        for (Instance instance : instances) {
            if (instance.active && instance.launched) total += VM_RAM_MB;
        }
        return total;
    }

    int getUsedCores() {
        int total = 0;
        for (Instance instance : instances) {
            if (instance.active && instance.launched) total += instance.usedCores;
        }
        return total;
    }

    int getUsedRamMb() {
        int total = 0;
        for (Instance instance : instances) {
            if (instance.active && instance.launched) total += instance.usedRamMb;
        }
        return total;
    }

    String configurationSummary() {
        return "onDemandVm=" + VM_CORES + "core/" + VM_RAM_MB + "MB"
                + " vmDelay=" + VM_PROVISIONING_SECONDS
                + "s containerDelay=" + CONTAINER_DEPLOYMENT_SECONDS
                + "s provisionInterval=" + PROVISIONING_INTERVAL_SECONDS
                + "s initialReady=" + INITIAL_READY_INSTANCES
                + " minReady=" + MIN_READY_INSTANCES;
    }

    String diagnosticSummary() {
        return "onDemandPhysicalProvisioned=" + provisionedInstances
                + " onDemandPhysicalTerminated=" + terminatedInstances
                + " onDemandContainerAllocations=" + containerAllocations
                + " onDemandUnavailable=" + unavailableAllocations
                + " onDemandPhysicalCost=" + settledPhysicalCost;
    }

    private Instance create(double orderedAt, double readyAt, boolean launched) {
        Instance instance = new Instance(nextInstanceId++, orderedAt, readyAt,
                launched);
        instances.add(instance);
        provisionedInstances++;
        return instance;
    }

    private void terminate(Instance instance, double now) {
        instance.active = false;
        instance.destroyAt = Math.max(now,
                instance.readyAt + MIN_BILLING_SECONDS);
        terminatedInstances++;
    }

    private static int bestFit(Instance left, int leftCores, int leftRam,
                               Instance right, int rightCores, int rightRam) {
        int coreCompare = Integer.compare(left.getFreeCores() - leftCores,
                right.getFreeCores() - rightCores);
        if (coreCompare != 0) return coreCompare;
        int ramCompare = Integer.compare(left.getFreeRamMb() - leftRam,
                right.getFreeRamMb() - rightRam);
        if (ramCompare != 0) return ramCompare;
        return Integer.compare(left.id, right.id);
    }

    private static int ceilDiv(int value, int divisor) {
        return value <= 0 ? 0 : (value + divisor - 1) / divisor;
    }

    private static void validateRequirement(int cores, int ramMb) {
        if (cores <= 0 || ramMb <= 0 || cores > VM_CORES || ramMb > VM_RAM_MB) {
            throw new IllegalArgumentException(
                    "CEWB task requirement " + cores + " cores/" + ramMb
                            + "MB does not fit " + VM_CORES + " cores/"
                            + VM_RAM_MB + "MB physical on-demand VM");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name,
                Double.toString(fallback)));
    }

    private static int intProperty(String name, int fallback) {
        return Integer.getInteger(name, fallback);
    }
}
