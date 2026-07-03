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

    static final class Offer {
        private final SpotType type;
        private final int instanceId;
        private final double pricePerSecond;
        private final double startupSeconds;
        private final double predictedExecutionSeconds;
        private final double executionSeconds;
        private final double interruptionDelaySeconds;
        private final double successProbability;

        Offer(SpotType type, int instanceId, double pricePerSecond,
              double startupSeconds, double predictedExecutionSeconds,
              double executionSeconds,
              double interruptionDelaySeconds, double successProbability) {
            this.type = type;
            this.instanceId = instanceId;
            this.pricePerSecond = pricePerSecond;
            this.startupSeconds = startupSeconds;
            this.predictedExecutionSeconds = predictedExecutionSeconds;
            this.executionSeconds = executionSeconds;
            this.interruptionDelaySeconds = interruptionDelaySeconds;
            this.successProbability = successProbability;
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
        int getInstanceId() { return instanceId; }
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
            return getEventDelaySeconds() * pricePerSecond;
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

        SpotType(String name, int cores, int ramMb, double mips,
                 double basePricePerSecond,
                 double meanTimeBetweenInterruptions, int capacity) {
            this.name = name;
            this.cores = cores;
            this.ramMb = ramMb;
            this.mips = mips;
            this.basePricePerSecond = basePricePerSecond;
            this.meanTimeBetweenInterruptions = meanTimeBetweenInterruptions;
            this.capacity = capacity;
        }
    }

    private static final double STARTUP_SECONDS = property(
            "cbmw.cewb.spot.startup.sec", 20.0);
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
    private final Random random;
    private int nextInstanceId = 1_000_000;
    private long acquireCalls;
    private long acquiredOffers;
    private long noOfferCalls;
    private long saturatedCalls;
    private int activeInstances;
    private int activeCores;
    private int peakActiveInstances;
    private int peakActiveCores;

    CEWBSpotMarket(long seed) {
        if (DEFAULT_TOTAL_SPOT_CORES <= 0) {
            throw new IllegalArgumentException(
                    "cbmw.cewb.spot.total.cores must be positive");
        }
        random = new Random(seed);
        types.add(type("economy", 1, 1024, 900.0, 0.000085, 1800.0));
        types.add(type("standard", 2, 4096, 1000.0, 0.000140, 3600.0));
        types.add(type("performance", 4, 8192, 1500.0, 0.000240, 7200.0));
        validateConfiguration();
    }

    Offer acquire(int taskCores, int taskRamMb, double estimatedRuntime,
                  long cloudletLength,
                  double now, double subDeadline,
                  double onDemandPricePerSecond) {
        validateAcquireRequest(taskCores, taskRamMb, estimatedRuntime,
                cloudletLength, now, subDeadline, onDemandPricePerSecond);
        acquireCalls++;
        List<Offer> candidates = new ArrayList<>();
        boolean hasResourceFit = false;
        boolean hasCapacity = false;
        for (SpotType type : types) {
            if (taskCores > type.cores || taskRamMb > type.ramMb) continue;
            hasResourceFit = true;
            if (activeByType.getOrDefault(type.name, 0) >= type.capacity) continue;
            hasCapacity = true;

            double price = currentPrice(type);
            if (price > onDemandPricePerSecond * MAX_BID_TO_ON_DEMAND_RATIO) continue;
            double predictedExecution = estimatedRuntime * 1000.0 / type.mips;
            double execution = HybridVmPool.executionTimeSeconds(
                    cloudletLength, taskCores, type.mips);
            double successProbability = type.meanTimeBetweenInterruptions == 0.0
                    ? 0.0 : Math.exp(-predictedExecution
                            / type.meanTimeBetweenInterruptions);
            if (successProbability < MIN_SUCCESS_PROBABILITY) continue;
            if (now + STARTUP_SECONDS + predictedExecution > subDeadline) continue;

            double interruptionDelay = sampleInterruptionDelay(
                    type.meanTimeBetweenInterruptions);
            candidates.add(new Offer(type, nextInstanceId, price,
                    STARTUP_SECONDS, predictedExecution, execution,
                    interruptionDelay, successProbability));
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
        acquiredOffers++;
        return selected;
    }

    void release(Offer offer) {
        if (offer == null) {
            throw new IllegalArgumentException("Cannot release a null CEWB offer");
        }
        int active = activeByType.getOrDefault(offer.getTypeName(), 0);
        if (active <= 0 || activeInstances <= 0
                || activeCores < offer.type.cores) {
            throw new IllegalStateException("CEWB spot offer released without"
                    + " a matching active allocation: " + offer.getTypeName());
        }
        activeByType.put(offer.getTypeName(), active - 1);
        activeInstances--;
        activeCores -= offer.type.cores;
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
        return summary.toString();
    }

    String diagnosticSummary() {
        return "acquireCalls=" + acquireCalls
                + " acquired=" + acquiredOffers
                + " noOffer=" + noOfferCalls
                + " saturated=" + saturatedCalls
                + " peakInstances=" + peakActiveInstances
                + " peakCores=" + peakActiveCores
                + "/" + getConfiguredTotalCores()
                + " activeAtEnd=" + activeInstances;
    }

    private SpotType type(String name, int defaultCores, int defaultRamMb,
                          double defaultMips, double defaultPrice,
                          double defaultMtbi) {
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
                        matchedDefaultCapacity(cores)));
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
