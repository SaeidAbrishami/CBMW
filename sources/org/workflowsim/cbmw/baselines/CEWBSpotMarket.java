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

    CEWBSpotMarket(long seed) {
        random = new Random(seed);
        types.add(type("economy", 1, 1024, 900.0, 0.000085, 1800.0, 64));
        types.add(type("standard", 2, 4096, 1000.0, 0.000140, 3600.0, 32));
        types.add(type("performance", 4, 8192, 1500.0, 0.000240, 7200.0, 16));
    }

    Offer acquire(int taskCores, int taskRamMb, double estimatedRuntime,
                  long cloudletLength,
                  double now, double subDeadline,
                  double onDemandPricePerSecond) {
        List<Offer> candidates = new ArrayList<>();
        for (SpotType type : types) {
            if (taskCores > type.cores || taskRamMb > type.ramMb) continue;
            if (activeByType.getOrDefault(type.name, 0) >= type.capacity) continue;

            double price = currentPrice(type);
            if (price > onDemandPricePerSecond * MAX_BID_TO_ON_DEMAND_RATIO) continue;
            double predictedExecution = estimatedRuntime * 1000.0 / type.mips;
            double execution = HybridVmPool.executionTimeSeconds(
                    cloudletLength, taskCores, type.mips);
            double successProbability = Math.exp(
                    -predictedExecution / type.meanTimeBetweenInterruptions);
            if (successProbability < MIN_SUCCESS_PROBABILITY) continue;
            if (now + STARTUP_SECONDS + predictedExecution > subDeadline) continue;

            double interruptionDelay = sampleInterruptionDelay(
                    type.meanTimeBetweenInterruptions);
            candidates.add(new Offer(type, nextInstanceId, price,
                    STARTUP_SECONDS, predictedExecution, execution,
                    interruptionDelay,
                    successProbability));
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(offer ->
                offer.getPricePerSecond() * offer.getPredictedExecutionSeconds()
                        / Math.max(offer.getSuccessProbability(), 1e-9)));
        Offer selected = candidates.get(0);
        nextInstanceId++;
        activeByType.merge(selected.getTypeName(), 1, Integer::sum);
        return selected;
    }

    void release(Offer offer) {
        activeByType.computeIfPresent(offer.getTypeName(),
                (name, active) -> Math.max(0, active - 1));
    }

    private SpotType type(String name, int defaultCores, int defaultRamMb,
                          double defaultMips, double defaultPrice,
                          double defaultMtbi, int defaultCapacity) {
        String prefix = "cbmw.cewb.spot." + name + ".";
        double globalMtbi = property("cbmw.cewb.spot.mtbi.sec", Double.NaN);
        return new SpotType(name,
                intProperty(prefix + "cores", defaultCores),
                intProperty(prefix + "ram.mb", defaultRamMb),
                property(prefix + "mips", defaultMips),
                property(prefix + "price.per.sec", defaultPrice),
                Double.isFinite(globalMtbi) ? globalMtbi
                        : property(prefix + "mtbi.sec", defaultMtbi),
                intProperty(prefix + "capacity", defaultCapacity));
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
