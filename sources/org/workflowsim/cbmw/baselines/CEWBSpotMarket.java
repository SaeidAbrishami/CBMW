package org.workflowsim.cbmw.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.workflowsim.cbmw.HybridVmPool;

/** Configurable spot instance classes, prices, capacity, and reliability. */
final class CEWBSpotMarket {

    /**
     * Environmental normalization only: by default CEWB receives the same
     * physical core envelope as the prepaid CBMW reserved pool. The CEWB
     * selection, bid, reliability, and fallback rules are unchanged.
     */
    private static final int DEFAULT_TOTAL_SPOT_CORES = Integer.getInteger(
            "cbmw.cewb.spot.total.cores",
            HybridVmPool.NUM_RESERVED * HybridVmPool.RESERVED_CORES);
    private static final int SPOT_CLASS_COUNT = 3;
    private static final double TRACE_IDLE_SECONDS = 100.0;

    static final class Offer {
        private final SpotType type;
        private final SpotInstance instance;
        private final double pricePerSecond;
        private final double startupSeconds;
        private final double predictedExecutionSeconds;
        private final double executionSeconds;
        private final double interruptionDelaySeconds;
        private final double successProbability;
        private final int allocatedCores;
        private final int allocatedRamMb;
        private final double allocationShare;

        Offer(SpotType type, SpotInstance instance, double pricePerSecond,
              double startupSeconds, double predictedExecutionSeconds,
              double executionSeconds,
              double interruptionDelaySeconds, double successProbability,
              int allocatedCores, int allocatedRamMb,
              boolean sharedContainers) {
            this.type = type;
            this.instance = instance;
            this.pricePerSecond = pricePerSecond;
            this.startupSeconds = startupSeconds;
            this.predictedExecutionSeconds = predictedExecutionSeconds;
            this.executionSeconds = executionSeconds;
            this.interruptionDelaySeconds = interruptionDelaySeconds;
            this.successProbability = successProbability;
            this.allocatedCores = allocatedCores;
            this.allocatedRamMb = allocatedRamMb;
            this.allocationShare = sharedContainers ? Math.max(
                    allocatedCores / (double) type.cores,
                    allocatedRamMb / (double) type.ramMb) : 1.0;
            if (!Double.isFinite(pricePerSecond) || pricePerSecond < 0.0
                    || !Double.isFinite(startupSeconds) || startupSeconds < 0.0
                    || !Double.isFinite(predictedExecutionSeconds)
                    || predictedExecutionSeconds <= 0.0
                    || !Double.isFinite(executionSeconds) || executionSeconds <= 0.0
                    || !Double.isFinite(interruptionDelaySeconds)
                    || interruptionDelaySeconds < 0.0
                    || !Double.isFinite(successProbability)
                    || successProbability < 0.0 || successProbability > 1.0) {
                throw new IllegalArgumentException("Invalid CEWB spot offer");
            }
        }

        String getTypeName() { return type.name; }
        int getReliabilityClass() { return type.reliabilityClass; }
        int getInstanceId() { return instance.id; }
        double getPricePerSecond() { return pricePerSecond; }
        double getStartupSeconds() { return startupSeconds; }
        double getExecutionSeconds() { return executionSeconds; }
        double getPredictedExecutionSeconds() {
            return predictedExecutionSeconds;
        }
        double getSuccessProbability() { return successProbability; }
        boolean willBeInterrupted() {
            return interruptionDelaySeconds < executionSeconds;
        }
        double getAttemptRuntimeSeconds() {
            return Math.min(executionSeconds, interruptionDelaySeconds);
        }
        double getEventDelaySeconds() {
            return startupSeconds + getAttemptRuntimeSeconds();
        }
        double getAttemptCost() {
            return getEventDelaySeconds() * pricePerSecond * allocationShare;
        }
        double getCostForElapsed(double elapsed) {
            return Math.max(0.0, elapsed) * pricePerSecond * allocationShare;
        }
    }

    private static final class SpotInstance {
        private final int id;
        private final SpotType type;
        private final double pricePerSecond;
        private final double interruptionTime;
        private final double readyAt;
        private double lastUsedAt;
        private double endedAt = Double.NaN;
        private boolean awsInterrupted;
        private boolean capacityReclamation;
        private final Map<Integer, Double> workflowCoreSeconds = new HashMap<>();
        private int usedCores;
        private int usedRamMb;
        private boolean active = true;

        SpotInstance(int id, SpotType type, double pricePerSecond,
                     double interruptionTime, double readyAt) {
            this.id = id;
            this.type = type;
            this.pricePerSecond = pricePerSecond;
            this.interruptionTime = interruptionTime;
            this.readyAt = readyAt;
            this.lastUsedAt = readyAt;
        }

        boolean fits(int cores, int ramMb) {
            return active && usedCores + cores <= type.cores
                    && usedRamMb + ramMb <= type.ramMb;
        }
    }

    private static final class SpotType {
        private final String name;
        private final int cores;
        private final int ramMb;
        private final double mips;
        private final double basePricePerSecond;
        private final double meanTimeBetweenInterruptions;
        private final int capacity;
        private final int reliabilityClass;

        SpotType(String name, int cores, int ramMb, double mips,
                 double basePricePerSecond,
                 double meanTimeBetweenInterruptions, int capacity,
                 int reliabilityClass) {
            this.name = name;
            this.cores = cores;
            this.ramMb = ramMb;
            this.mips = mips;
            this.basePricePerSecond = basePricePerSecond;
            this.meanTimeBetweenInterruptions = meanTimeBetweenInterruptions;
            this.capacity = capacity;
            this.reliabilityClass = reliabilityClass;
        }
    }

    private static final String SYNTHETIC_MODE = "SYNTHETIC";
    private static final double STARTUP_SECONDS = property(
            "cbmw.cewb.spot.startup.sec",
            System.getProperty("cbmw.cewb.spot.trace.august", "").isEmpty()
                    && !SYNTHETIC_MODE.equalsIgnoreCase(System.getProperty(
                            "cbmw.cewb.spot.mode", "")) ? 20.0 : 60.0);
    private static final double MIN_PRICE_FACTOR = property(
            "cbmw.cewb.spot.price.factor.min", 0.80);
    private static final double MAX_PRICE_FACTOR = property(
            "cbmw.cewb.spot.price.factor.max", 1.20);
    private static final double MIN_SUCCESS_PROBABILITY = property(
            "cbmw.cewb.spot.min.success.prob", 0.80);
    private static final double MAX_BID_TO_ON_DEMAND_RATIO = property(
            "cbmw.cewb.spot.max.bid.ratio", 0.80);

    private final List<SpotType> types = new ArrayList<>();
    private final Map<String, Integer> activeByType = new HashMap<>();
    private final Map<Integer, SpotInstance> instances = new HashMap<>();
    private final Random random;
    private final boolean sharedContainers;
    private final CEWBSpotPriceTrace trace;
    private final boolean synthetic;
    private final double syntheticDiscountMin;
    private final double syntheticDiscountMax;
    private final double syntheticHourlyReclamation;
    private final double syntheticHazard;
    private final double syntheticTrainingMinimumPerSecond;
    private final double traceHourlyReclamation;
    private final double traceHazard;
    private final List<SpotInstance> physicalHistory = new ArrayList<>();
    private double physicalCost;
    private boolean physicalSettled;
    private int nextInstanceId = 1_000_000;
    private long acquireCalls;
    private long acquiredOffers;
    private long noOfferCalls;
    private long saturatedCalls;
    private long maximumPriceRejections;
    private long capacityReclaimedVms;
    private long priceInterruptedVms;
    private int activeInstances;
    private int activeCores;
    private int peakActiveInstances;
    private int peakActiveCores;

    CEWBSpotMarket(long seed) {
        this(seed, true);
    }

    CEWBSpotMarket(long seed, boolean sharedContainers) {
        if (DEFAULT_TOTAL_SPOT_CORES <= 0) {
            throw new IllegalArgumentException(
                    "cbmw.cewb.spot.total.cores must be positive");
        }
        random = new Random(seed);
        this.sharedContainers = sharedContainers;
        synthetic = SYNTHETIC_MODE.equalsIgnoreCase(System.getProperty(
                "cbmw.cewb.spot.mode", ""));
        syntheticDiscountMin = property("cbmw.cewb.spot.synthetic.discount.min", 0.30);
        syntheticDiscountMax = property("cbmw.cewb.spot.synthetic.discount.max", 0.70);
        syntheticHourlyReclamation = property("cbmw.cewb.spot.synthetic.reclamation.hourly", 0.05);
        syntheticTrainingMinimumPerSecond = property(
                "cbmw.cewb.spot.synthetic.training.minimum.per.hour", 0.3237) / 3600.0;
        traceHourlyReclamation = property(
                "cbmw.cewb.spot.trace.reclamation.hourly", 0.0);
        if (!Double.isFinite(syntheticDiscountMin) || !Double.isFinite(syntheticDiscountMax)
                || syntheticDiscountMin < 0 || syntheticDiscountMax >= 1
                || syntheticDiscountMin > syntheticDiscountMax
                || !Double.isFinite(syntheticHourlyReclamation)
                || syntheticHourlyReclamation < 0 || syntheticHourlyReclamation >= 1
                || !Double.isFinite(syntheticTrainingMinimumPerSecond)
                || syntheticTrainingMinimumPerSecond <= 0
                || !Double.isFinite(traceHourlyReclamation)
                || traceHourlyReclamation < 0 || traceHourlyReclamation >= 1) {
            throw new IllegalArgumentException("Invalid synthetic Spot discount or one-hour reclamation probability");
        }
        syntheticHazard = -Math.log1p(-syntheticHourlyReclamation) / 3600.0;
        traceHazard = -Math.log1p(-traceHourlyReclamation) / 3600.0;
        String traceFile = System.getProperty("cbmw.cewb.spot.trace.august", "").trim();
        if (synthetic && !traceFile.isEmpty()) {
            throw new IllegalArgumentException("Choose historical trace or synthetic Spot mode, not both");
        }
        if (synthetic) {
            trace = null;
            int totalCapacity = intProperty("cbmw.cewb.spot.synthetic.capacity",
                    Math.max(1, DEFAULT_TOTAL_SPOT_CORES / 32));
            if (totalCapacity < 1) {
                throw new IllegalArgumentException("Synthetic Spot VM cap must be positive");
            }
            // Paper Eq. 3 has three maximum-price classes (c2, c3, c4).
            for (int reliability = 3; reliability >= 1; reliability--) {
                String name = reliability == 3 ? "economy"
                        : reliability == 2 ? "standard" : "performance";
                int capacity = totalCapacity / 3
                        + (reliability == 1 ? totalCapacity % 3 : 0);
                types.add(new SpotType(name, 32, 128 * 1024, 1000.0,
                        0.0, 0.0, capacity, reliability));
            }
        } else if (traceFile.isEmpty()) {
            trace = null;
            types.add(type("economy", 1, 1024, 900.0, 0.000085, 1800.0,
                    CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT));
            types.add(type("standard", 2, 4096, 1000.0, 0.000140, 3600.0,
                    CEWBCriticalityPolicy.MEDIUM_RELIABILITY_SPOT));
            types.add(type("performance", 4, 8192, 1500.0, 0.000240, 7200.0,
                    CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT));
        } else {
            trace = new CEWBSpotPriceTrace(traceFile,
                    System.getProperty("cbmw.cewb.spot.trace.july"),
                    property("cbmw.cewb.spot.trace.offset.sec", 0));
            // Paper Eq. 3 maximum prices: minimum July price plus a quarter, half,
            // or three quarters of the gap to the Ohio on-demand VM price.
            for (int reliability = 3; reliability >= 1; reliability--) {
                String name = reliability == 3 ? "economy"
                        : reliability == 2 ? "standard" : "performance";
                types.add(new SpotType(name, 32, 128 * 1024, 1000.0,
                        0.0, 0.0, intProperty("cbmw.cewb.spot." + name
                                + ".capacity", matchedDefaultCapacity(32)),
                        reliability));
            }
        }
        validateConfiguration();
    }

    Offer acquire(int taskCores, int taskRamMb, double estimatedRuntime,
                  long cloudletLength,
                  double now, double subDeadline,
                  double onDemandPricePerSecond) {
        return acquire(taskCores, taskRamMb, estimatedRuntime, cloudletLength,
                now, subDeadline, onDemandPricePerSecond,
                CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT);
    }

    Offer acquire(int taskCores, int taskRamMb, double estimatedRuntime,
                  long cloudletLength,
                  double now, double subDeadline,
                  double onDemandPricePerSecond, int requiredReliabilityClass) {
        validateAcquireRequest(taskCores, taskRamMb, estimatedRuntime,
                cloudletLength, now, subDeadline, onDemandPricePerSecond);
        if (requiredReliabilityClass < CEWBCriticalityPolicy.HIGH_RELIABILITY_SPOT
                || requiredReliabilityClass
                        > CEWBCriticalityPolicy.LOW_RELIABILITY_SPOT) {
            throw new IllegalArgumentException("Invalid CEWB spot reliability class: "
                    + requiredReliabilityClass);
        }
        acquireCalls++;
        if (isPhysicalMode()) expireIdle(now);
        List<Offer> candidates = new ArrayList<>();
        boolean hasResourceFit = false;
        boolean hasCapacity = false;
        for (SpotType type : types) {
            // A lower class number means a more reliable resource. A task may
            // use its requested class or a more reliable spot class.
            if (type.reliabilityClass > requiredReliabilityClass) continue;
            if (taskCores > type.cores || taskRamMb > type.ramMb) continue;
            hasResourceFit = true;

            double price = synthetic
                    ? onDemandPricePerSecond * (1 - syntheticDiscountMin
                            - random.nextDouble() * (syntheticDiscountMax - syntheticDiscountMin))
                    : trace == null ? currentPrice(type) : trace.priceAt(now);
            double trainingMinimum = synthetic ? syntheticTrainingMinimumPerSecond
                    : trace == null ? 0 : trace.trainingMinimumPerSecond();
            double maximumPrice = isPhysicalMode() ? trainingMinimum
                    + (4 - type.reliabilityClass) / 4.0
                    * (onDemandPricePerSecond - trainingMinimum) : 0;
            double predictedExecution = estimatedRuntime * 1000.0 / type.mips;
            double execution = HybridVmPool.executionTimeSeconds(
                    cloudletLength, taskCores, type.mips);
            double successProbability = synthetic
                    ? Math.exp(-syntheticHazard * predictedExecution)
                    : trace != null ? Math.exp(-traceHazard * predictedExecution)
                    : type.meanTimeBetweenInterruptions == 0.0 ? 0.0
                    : Math.exp(-predictedExecution
                            / type.meanTimeBetweenInterruptions);
            if (!isPhysicalMode() && successProbability < MIN_SUCCESS_PROBABILITY) continue;
            if (trace != null && price > maximumPrice) {
                maximumPriceRejections++;
                continue;
            }

            for (SpotInstance instance : sharedContainers
                    ? instances.values() : java.util.Collections.<SpotInstance>emptyList()) {
                if (instance.type != type || !instance.fits(taskCores, taskRamMb)) {
                    continue;
                }
                hasCapacity = true;
                if (now + predictedExecution > subDeadline) continue;
                if (!isPhysicalMode() && instance.pricePerSecond
                        > onDemandPricePerSecond * MAX_BID_TO_ON_DEMAND_RATIO) continue;
                candidates.add(new Offer(type, instance,
                        trace == null ? instance.pricePerSecond : price,
                        0.0, predictedExecution, execution,
                        Math.max(0.0, instance.interruptionTime - now),
                        successProbability, taskCores, taskRamMb,
                        sharedContainers));
            }

            if (activeByType.getOrDefault(type.name, 0) < type.capacity
                    && (isPhysicalMode() || price <= onDemandPricePerSecond
                            * MAX_BID_TO_ON_DEMAND_RATIO)) {
                hasCapacity = true;
                // Synthetic prices are sampled for new VM requests only. A
                // running VM retains the price accepted at its launch.
                if (synthetic && price > maximumPrice) {
                    maximumPriceRejections++;
                    continue;
                }
                if (now + STARTUP_SECONDS + predictedExecution > subDeadline) continue;
                double priceCrossingDelay = trace == null ? Double.MAX_VALUE / 4
                        : Math.max(0, Math.min(Double.MAX_VALUE / 4,
                                trace.nextExceeding(now, maximumPrice)
                                        - now - STARTUP_SECONDS));
                double capacityDelay = trace == null || traceHazard == 0
                        ? Double.MAX_VALUE / 4
                        : Math.min(Double.MAX_VALUE / 4,
                                sampleInterruptionDelay(1 / traceHazard));
                double interruptionDelay = synthetic
                        ? syntheticHazard == 0 ? Double.MAX_VALUE / 4
                        : sampleInterruptionDelay(1 / syntheticHazard)
                        : trace != null
                        ? Math.min(priceCrossingDelay, capacityDelay)
                        : sampleInterruptionDelay(type.meanTimeBetweenInterruptions);
                SpotInstance instance = new SpotInstance(nextInstanceId, type, price,
                        now + STARTUP_SECONDS + interruptionDelay,
                        now + STARTUP_SECONDS);
                instance.capacityReclamation = synthetic || trace != null
                        && capacityDelay < priceCrossingDelay;
                candidates.add(new Offer(type, instance, price,
                        STARTUP_SECONDS, predictedExecution, execution,
                        interruptionDelay, successProbability,
                        taskCores, taskRamMb, sharedContainers));
            }
        }
        if (candidates.isEmpty()) {
            noOfferCalls++;
            if (hasResourceFit && !hasCapacity) saturatedCalls++;
            return null;
        }

        candidates.sort(Comparator.comparingDouble(offer ->
                offer.getPricePerSecond() * offer.getPredictedExecutionSeconds()
                        / Math.max(offer.getSuccessProbability(), 1e-9)));
        Offer selected = candidates.get(0);
        SpotInstance instance = selected.instance;
        if (!instances.containsKey(instance.id)) {
            instances.put(instance.id, instance);
            if (isPhysicalMode()) physicalHistory.add(instance);
            nextInstanceId++;
            int activeForType = activeByType.merge(
                    selected.getTypeName(), 1, Integer::sum);
            if (activeForType > selected.type.capacity) {
                throw new IllegalStateException("CEWB spot capacity exceeded for "
                        + selected.getTypeName() + ": " + activeForType + "/"
                        + selected.type.capacity);
            }
            activeInstances++;
            activeCores += selected.type.cores;
            peakActiveInstances = Math.max(peakActiveInstances, activeInstances);
            peakActiveCores = Math.max(peakActiveCores, activeCores);
            if (activeCores > getConfiguredTotalCores()) {
                throw new IllegalStateException("CEWB active spot cores exceeded configured pool: "
                        + activeCores + "/" + getConfiguredTotalCores());
            }
        }
        instance.usedCores += selected.allocatedCores;
        instance.usedRamMb += selected.allocatedRamMb;
        acquiredOffers++;
        return selected;
    }

    void release(Offer offer) {
        if (offer == null) {
            throw new IllegalArgumentException("Cannot release a null CEWB offer");
        }
        SpotInstance instance = offer.instance;
        if (!instance.active) return;
        if (instance.usedCores < offer.allocatedCores
                || instance.usedRamMb < offer.allocatedRamMb) {
            throw new IllegalStateException("CEWB spot container released without"
                    + " a matching allocation: " + offer.getTypeName());
        }
        instance.usedCores -= offer.allocatedCores;
        instance.usedRamMb -= offer.allocatedRamMb;
        if (!sharedContainers) terminateInstance(instance,
                instance.lastUsedAt);
    }

    boolean isPhysicalMode() { return trace != null || synthetic; }

    void recordUsage(Offer offer, int workflowId, double from, double to) {
        if (!isPhysicalMode()) return;
        SpotInstance instance = offer.instance;
        double runtime = Math.max(0, to - from);
        instance.workflowCoreSeconds.merge(workflowId,
                runtime * offer.allocatedCores, Double::sum);
        instance.lastUsedAt = Math.max(instance.lastUsedAt, to);
    }

    private void expireIdle(double now) {
        for (SpotInstance instance : new ArrayList<>(instances.values())) {
            if (instance.usedCores != 0) continue;
            double idleExpiry = instance.lastUsedAt + TRACE_IDLE_SECONDS;
            if (now >= instance.interruptionTime
                    && instance.interruptionTime <= idleExpiry) {
                registerProviderInterruption(instance);
                terminateInstance(instance, instance.interruptionTime);
            } else if (now >= idleExpiry) {
                terminateInstance(instance, idleExpiry);
            }
        }
    }

    void revoke(Offer offer) {
        SpotInstance instance = offer.instance;
        if (!instance.active) return;
        registerProviderInterruption(instance);
        terminateInstance(instance, Math.max(instance.readyAt,
                instance.interruptionTime));
    }

    private void registerProviderInterruption(SpotInstance instance) {
        if (instance.awsInterrupted) return;
        instance.awsInterrupted = true;
        if (instance.capacityReclamation) capacityReclaimedVms++;
        else priceInterruptedVms++;
    }

    private void terminateInstance(SpotInstance instance, double now) {
        if (!instance.active) return;
        instance.active = false;
        instance.endedAt = now;
        instances.remove(instance.id);
        int active = activeByType.getOrDefault(instance.type.name, 0);
        activeByType.put(instance.type.name, Math.max(0, active - 1));
        activeInstances--;
        activeCores -= instance.type.cores;
    }

    void terminateAll() {
        instances.clear();
        activeByType.clear();
        activeInstances = 0;
        activeCores = 0;
    }

    Map<Integer, Double> settlePhysicalCosts(double now) {
        Map<Integer, Double> result = new HashMap<>();
        if (!isPhysicalMode()) return result;
        if (physicalSettled) throw new IllegalStateException("CEWB physical Spot costs settled twice");
        physicalSettled = true;
        for (SpotInstance instance : new ArrayList<>(instances.values())) {
            if (instance.usedCores != 0) throw new IllegalStateException("Active Spot task at settlement");
            terminateInstance(instance, Math.min(now, instance.lastUsedAt + TRACE_IDLE_SECONDS));
        }
        for (SpotInstance instance : physicalHistory) {
            double cost = synthetic
                    ? CEWBSpotSyntheticPricing.billInstance(instance.readyAt,
                            instance.endedAt, instance.pricePerSecond,
                            instance.awsInterrupted)
                    : trace.billInstance(instance.readyAt, instance.endedAt,
                            instance.awsInterrupted);
            physicalCost += cost;
            double weight = 0;
            for (double value : instance.workflowCoreSeconds.values()) weight += value;
            if (weight <= 0) throw new IllegalStateException("Unattributed Spot VM cost");
            for (Map.Entry<Integer, Double> entry : instance.workflowCoreSeconds.entrySet()) {
                result.merge(entry.getKey(), cost * entry.getValue() / weight, Double::sum);
            }
        }
        return result;
    }

    int getConfiguredTotalCores() {
        int total = 0;
        for (SpotType type : types) total += type.capacity * type.cores;
        return total;
    }

    int getConfiguredTotalInstances() {
        int total = 0;
        for (SpotType type : types) total += type.capacity;
        return total;
    }

    int getActiveInstances() { return activeInstances; }
    int getActiveCores() { return activeCores; }
    int getActiveRamMb() {
        int total = 0;
        for (SpotInstance instance : instances.values()) {
            if (instance.active) total += instance.type.ramMb;
        }
        return total;
    }
    int getUsedCores() {
        int total = 0;
        for (SpotInstance instance : instances.values()) {
            if (instance.active) total += instance.usedCores;
        }
        return total;
    }
    int getUsedRamMb() {
        int total = 0;
        for (SpotInstance instance : instances.values()) {
            if (instance.active) total += instance.usedRamMb;
        }
        return total;
    }
    int getPeakActiveInstances() { return peakActiveInstances; }
    int getPeakActiveCores() { return peakActiveCores; }
    long getAcquireCalls() { return acquireCalls; }
    long getAcquiredOffers() { return acquiredOffers; }
    long getNoOfferCalls() { return noOfferCalls; }
    long getSaturatedCalls() { return saturatedCalls; }

    String configurationSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("targetCores=").append(DEFAULT_TOTAL_SPOT_CORES)
                .append(" configuredCores=").append(getConfiguredTotalCores())
                .append(" instances=").append(getConfiguredTotalInstances());
        for (SpotType type : types) {
            summary.append(' ').append(type.name).append('=')
                    .append(type.capacity).append('x').append(type.cores)
                    .append("core");
        }
        if (trace != null) summary.append(" trace=").append(trace.description())
                .append(" maximumPriceClasses=[0.25,0.50,0.75] gapToOnDemand")
                .append(" oneHourCapacityReclamation=").append(traceHourlyReclamation);
        if (synthetic) summary.append(" syntheticDiscount=[")
                .append(syntheticDiscountMin).append(',').append(syntheticDiscountMax)
                .append("] oneHourReclamation=").append(syntheticHourlyReclamation)
                .append(" trainingMinimum=$")
                .append(syntheticTrainingMinimumPerSecond * 3600).append("/h")
                .append(" maximumPriceClasses=[0.25,0.50,0.75] gapToOnDemand");
        return summary.toString();
    }

    String diagnosticSummary() {
        return "acquireCalls=" + acquireCalls
                + " acquired=" + acquiredOffers
                + " noOffer=" + noOfferCalls
                + " saturated=" + saturatedCalls
                + " maxPriceRejected=" + maximumPriceRejections
                + " capacityReclaimedVMs=" + capacityReclaimedVms
                + " priceInterruptedVMs=" + priceInterruptedVms
                + " peakInstances=" + peakActiveInstances
                + " peakCores=" + peakActiveCores
                + "/" + getConfiguredTotalCores()
                + " activeAtEnd=" + activeInstances
                + (!isPhysicalMode() ? "" : " spotPhysicalCost=" + physicalCost
                        + " spotPhysicalInstances=" + physicalHistory.size());
    }

    private SpotType type(String name, int defaultCores, int defaultRamMb,
                          double defaultMips, double defaultPrice,
                          double defaultMtbi, int reliabilityClass) {
        String prefix = "cbmw.cewb.spot." + name + ".";
        double globalMtbi = property("cbmw.cewb.spot.mtbi.sec", Double.NaN);
        int cores = intProperty(prefix + "cores", defaultCores);
        return new SpotType(name,
                cores,
                intProperty(prefix + "ram.mb", defaultRamMb),
                property(prefix + "mips", defaultMips),
                property(prefix + "price.per.sec", defaultPrice),
                Double.isFinite(globalMtbi) ? globalMtbi
                        : property(prefix + "mtbi.sec", defaultMtbi),
                intProperty(prefix + "capacity",
                        matchedDefaultCapacity(cores)),
                reliabilityClass);
    }

    private static int matchedDefaultCapacity(int coresPerInstance) {
        int perClassCoreBudget = DEFAULT_TOTAL_SPOT_CORES / SPOT_CLASS_COUNT;
        return Math.max(1, perClassCoreBudget / Math.max(1, coresPerInstance));
    }

    private void validateConfiguration() {
        int enabledClasses = 0;
        for (SpotType type : types) {
            if (type.cores <= 0 || type.ramMb <= 0 || type.mips <= 0.0
                    || type.basePricePerSecond < 0.0
                    || type.meanTimeBetweenInterruptions < 0.0
                    || type.capacity < 0) {
                throw new IllegalArgumentException("Invalid CEWB spot class "
                        + type.name + " (cores=" + type.cores
                        + ", ramMb=" + type.ramMb + ", mips=" + type.mips
                        + ", price=" + type.basePricePerSecond + ", mtbi="
                        + type.meanTimeBetweenInterruptions + ", capacity="
                        + type.capacity + ")");
            }
            if (type.capacity > 0) enabledClasses++;
        }
        if (enabledClasses == 0 || getConfiguredTotalCores() <= 0) {
            throw new IllegalArgumentException(
                    "CEWB spot market must enable at least one class");
        }
    }

    private static void validateAcquireRequest(
            int taskCores, int taskRamMb, double estimatedRuntime,
            long cloudletLength, double now, double subDeadline,
            double onDemandPricePerSecond) {
        if (taskCores <= 0 || taskRamMb <= 0 || cloudletLength <= 0
                || !Double.isFinite(estimatedRuntime) || estimatedRuntime <= 0.0
                || !Double.isFinite(now) || !Double.isFinite(subDeadline)
                || subDeadline < now
                || !Double.isFinite(onDemandPricePerSecond)
                || onDemandPricePerSecond < 0.0) {
            throw new IllegalArgumentException("Invalid CEWB spot request: cores="
                    + taskCores + " ramMb=" + taskRamMb + " runtime="
                    + estimatedRuntime + " length=" + cloudletLength
                    + " now=" + now + " subDeadline=" + subDeadline
                    + " onDemandPrice=" + onDemandPricePerSecond);
        }
    }

    private double currentPrice(SpotType type) {
        double low = Math.min(MIN_PRICE_FACTOR, MAX_PRICE_FACTOR);
        double high = Math.max(MIN_PRICE_FACTOR, MAX_PRICE_FACTOR);
        return type.basePricePerSecond * (low + random.nextDouble() * (high - low));
    }

    private double sampleInterruptionDelay(double meanSeconds) {
        if (meanSeconds <= 0.0) return 0.0;
        double sample = Math.max(1e-12, 1.0 - random.nextDouble());
        return -Math.log(sample) * meanSeconds;
    }

    private static double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name,
                Double.toString(fallback)));
    }

    private static int intProperty(String name, int fallback) {
        return Integer.getInteger(name, fallback);
    }
}
