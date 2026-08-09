package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable time-integrated CPU/RAM accounting for one scenario. */
public final class ResourceAccountingSummary {

    /** Integral plus time-weighted mean and instantaneous extrema. */
    public static final class Statistic {
        private final double integral;
        private final double mean;
        private final double min;
        private final double max;

        private Statistic(double integral, double mean, double min, double max) {
            this.integral = integral;
            this.mean = mean;
            this.min = min;
            this.max = max;
        }

        public double getIntegral() { return integral; }
        public double getMean() { return mean; }
        public double getMin() { return min; }
        public double getMax() { return max; }
    }

    private final double measurementStart;
    private final double measurementEnd;
    private final int onDemandInstanceCount;
    private final double averageOnDemandUptime;
    private final long totalOnDemandCores;
    private final long totalOnDemandRamMb;
    private final double onDemandCapacityCoreSeconds;
    private final double onDemandCapacityRamMbSeconds;
    private final Statistic reservedCapacityCores;
    private final Statistic reservedCapacityRamMb;
    private final Statistic onDemandCapacityCores;
    private final Statistic onDemandCapacityRamMb;
    private final Statistic spotCapacityCores;
    private final Statistic spotCapacityRamMb;
    private final Statistic reservedUsedCores;
    private final Statistic reservedUsedRamMb;
    private final Statistic onDemandUsedCores;
    private final Statistic onDemandUsedRamMb;
    private final Statistic spotUsedCores;
    private final Statistic spotUsedRamMb;
    private final Statistic utilizedCores;
    private final Statistic utilizedRamMb;
    private final Statistic reservedCoreUtilization;
    private final Statistic reservedRamUtilization;
    private final Statistic overallCoreUtilization;
    private final Statistic overallRamUtilization;

    private ResourceAccountingSummary(double measurementStart,
                                      double measurementEnd,
                                      OnDemandLifecycle lifecycle,
                                      Timeline timeline) {
        this.measurementStart = measurementStart;
        this.measurementEnd = measurementEnd;
        this.onDemandInstanceCount = lifecycle.count;
        this.averageOnDemandUptime = lifecycle.count == 0
                ? 0.0 : lifecycle.totalUptime / lifecycle.count;
        this.totalOnDemandCores = lifecycle.totalCores;
        this.totalOnDemandRamMb = lifecycle.totalRamMb;
        this.onDemandCapacityCoreSeconds = lifecycle.capacityCoreSeconds;
        this.onDemandCapacityRamMbSeconds = lifecycle.capacityRamMbSeconds;
        this.reservedCapacityCores = timeline.reservedCapacityCores;
        this.reservedCapacityRamMb = timeline.reservedCapacityRamMb;
        this.onDemandCapacityCores = timeline.onDemandCapacityCores;
        this.onDemandCapacityRamMb = timeline.onDemandCapacityRamMb;
        this.spotCapacityCores = timeline.spotCapacityCores;
        this.spotCapacityRamMb = timeline.spotCapacityRamMb;
        this.reservedUsedCores = timeline.reservedUsedCores;
        this.reservedUsedRamMb = timeline.reservedUsedRamMb;
        this.onDemandUsedCores = timeline.onDemandUsedCores;
        this.onDemandUsedRamMb = timeline.onDemandUsedRamMb;
        this.spotUsedCores = timeline.spotUsedCores;
        this.spotUsedRamMb = timeline.spotUsedRamMb;
        this.utilizedCores = timeline.utilizedCores;
        this.utilizedRamMb = timeline.utilizedRamMb;
        this.reservedCoreUtilization = timeline.reservedCoreUtilization;
        this.reservedRamUtilization = timeline.reservedRamUtilization;
        this.overallCoreUtilization = timeline.overallCoreUtilization;
        this.overallRamUtilization = timeline.overallRamUtilization;
    }

    static ResourceAccountingSummary from(List<UtilizationSnapshot> snapshots,
                                          List<OnDemandInstanceRecord> onDemand,
                                          double measurementStart,
                                          double measurementEnd,
                                          double coreSecondsOverride,
                                          double ramMbSecondsOverride) {
        validateWindow(measurementStart, measurementEnd);
        OnDemandLifecycle lifecycle = summarizeOnDemand(onDemand,
                measurementStart, measurementEnd,
                coreSecondsOverride, ramMbSecondsOverride);
        Timeline timeline = Timeline.integrate(snapshots,
                measurementStart, measurementEnd);
        return new ResourceAccountingSummary(measurementStart, measurementEnd,
                lifecycle, timeline);
    }

    public double getMeasurementStart() { return measurementStart; }
    public double getMeasurementEnd() { return measurementEnd; }
    public double getDuration() { return measurementEnd - measurementStart; }
    public int getOnDemandInstanceCount() { return onDemandInstanceCount; }
    public double getAverageOnDemandUptime() { return averageOnDemandUptime; }
    public long getTotalOnDemandCores() { return totalOnDemandCores; }
    public long getTotalOnDemandRamMb() { return totalOnDemandRamMb; }
    public double getOnDemandCapacityCoreSeconds() {
        return onDemandCapacityCoreSeconds;
    }
    public double getOnDemandCapacityRamMbSeconds() {
        return onDemandCapacityRamMbSeconds;
    }
    public Statistic getReservedCapacityCores() { return reservedCapacityCores; }
    public Statistic getReservedCapacityRamMb() { return reservedCapacityRamMb; }
    public Statistic getOnDemandCapacityCores() { return onDemandCapacityCores; }
    public Statistic getOnDemandCapacityRamMb() { return onDemandCapacityRamMb; }
    public Statistic getSpotCapacityCores() { return spotCapacityCores; }
    public Statistic getSpotCapacityRamMb() { return spotCapacityRamMb; }
    public Statistic getReservedUsedCores() { return reservedUsedCores; }
    public Statistic getReservedUsedRamMb() { return reservedUsedRamMb; }
    public Statistic getOnDemandUsedCores() { return onDemandUsedCores; }
    public Statistic getOnDemandUsedRamMb() { return onDemandUsedRamMb; }
    public Statistic getSpotUsedCores() { return spotUsedCores; }
    public Statistic getSpotUsedRamMb() { return spotUsedRamMb; }
    public Statistic getUtilizedCores() { return utilizedCores; }
    public Statistic getUtilizedRamMb() { return utilizedRamMb; }
    public Statistic getReservedCoreUtilization() {
        return reservedCoreUtilization;
    }
    public Statistic getReservedRamUtilization() {
        return reservedRamUtilization;
    }
    public Statistic getOverallCoreUtilization() {
        return overallCoreUtilization;
    }
    public Statistic getOverallRamUtilization() {
        return overallRamUtilization;
    }

    private static OnDemandLifecycle summarizeOnDemand(
            List<OnDemandInstanceRecord> records,
            double start, double end,
            double coreSecondsOverride,
            double ramMbSecondsOverride) {
        OnDemandLifecycle result = new OnDemandLifecycle();
        for (OnDemandInstanceRecord record : records) {
            if (!Double.isFinite(record.getLaunchTime())) continue;
            double uptime = record.getUptime();
            if (!Double.isFinite(uptime)) {
                uptime = record.getUptime(start, end);
            }
            result.count++;
            result.totalUptime += uptime;
            result.totalCores += record.getCores();
            result.totalRamMb += record.getRamMb();
            result.capacityCoreSeconds += record.getCores() * uptime;
            result.capacityRamMbSeconds += record.getRamMb() * uptime;
        }
        if (Double.isFinite(coreSecondsOverride)) {
            result.capacityCoreSeconds = coreSecondsOverride;
        }
        if (Double.isFinite(ramMbSecondsOverride)) {
            result.capacityRamMbSeconds = ramMbSecondsOverride;
        }
        return result;
    }

    private static void validateWindow(double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) {
            throw new IllegalArgumentException(
                    "Measurement window must be finite and ordered");
        }
    }

    private static final class OnDemandLifecycle {
        int count;
        double totalUptime;
        long totalCores;
        long totalRamMb;
        double capacityCoreSeconds;
        double capacityRamMbSeconds;
    }

    private interface SnapshotValue {
        double value(UtilizationSnapshot snapshot);
    }

    private static final class Timeline {
        final Statistic reservedCapacityCores;
        final Statistic reservedCapacityRamMb;
        final Statistic onDemandCapacityCores;
        final Statistic onDemandCapacityRamMb;
        final Statistic spotCapacityCores;
        final Statistic spotCapacityRamMb;
        final Statistic reservedUsedCores;
        final Statistic reservedUsedRamMb;
        final Statistic onDemandUsedCores;
        final Statistic onDemandUsedRamMb;
        final Statistic spotUsedCores;
        final Statistic spotUsedRamMb;
        final Statistic utilizedCores;
        final Statistic utilizedRamMb;
        final Statistic reservedCoreUtilization;
        final Statistic reservedRamUtilization;
        final Statistic overallCoreUtilization;
        final Statistic overallRamUtilization;

        private Timeline(Map<String, Statistic> values) {
            reservedCapacityCores = values.get("reservedCapacityCores");
            reservedCapacityRamMb = values.get("reservedCapacityRamMb");
            onDemandCapacityCores = values.get("onDemandCapacityCores");
            onDemandCapacityRamMb = values.get("onDemandCapacityRamMb");
            spotCapacityCores = values.get("spotCapacityCores");
            spotCapacityRamMb = values.get("spotCapacityRamMb");
            reservedUsedCores = values.get("reservedUsedCores");
            reservedUsedRamMb = values.get("reservedUsedRamMb");
            onDemandUsedCores = values.get("onDemandUsedCores");
            onDemandUsedRamMb = values.get("onDemandUsedRamMb");
            spotUsedCores = values.get("spotUsedCores");
            spotUsedRamMb = values.get("spotUsedRamMb");
            utilizedCores = values.get("utilizedCores");
            utilizedRamMb = values.get("utilizedRamMb");
            reservedCoreUtilization = values.get("reservedCoreUtilization");
            reservedRamUtilization = values.get("reservedRamUtilization");
            overallCoreUtilization = values.get("overallCoreUtilization");
            overallRamUtilization = values.get("overallRamUtilization");
        }

        static Timeline integrate(List<UtilizationSnapshot> input,
                                  double start, double end) {
            TreeMap<Double, UtilizationSnapshot> states = new TreeMap<>();
            List<UtilizationSnapshot> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(UtilizationSnapshot::getTime));
            for (UtilizationSnapshot snapshot : sorted) {
                states.put(snapshot.getTime(), snapshot);
            }

            UtilizationSnapshot current;
            Map.Entry<Double, UtilizationSnapshot> before = states.floorEntry(start);
            if (before != null) {
                current = before.getValue();
            } else {
                current = emptyInitialState(start, states);
            }

            Map<String, StatAccumulator> accumulators = accumulators();
            observe(accumulators, current);
            double cursor = start;
            for (Map.Entry<Double, UtilizationSnapshot> entry
                    : states.tailMap(start, false).entrySet()) {
                double time = entry.getKey();
                if (time > end) break;
                integrate(accumulators, current, time - cursor);
                current = entry.getValue();
                cursor = time;
                observe(accumulators, current);
            }
            integrate(accumulators, current, end - cursor);

            double duration = end - start;
            Map<String, Statistic> finished = new TreeMap<>();
            for (Map.Entry<String, StatAccumulator> entry
                    : accumulators.entrySet()) {
                finished.put(entry.getKey(), entry.getValue().finish(duration));
            }
            return new Timeline(finished);
        }

        private static UtilizationSnapshot emptyInitialState(
                double start, TreeMap<Double, UtilizationSnapshot> states) {
            int reservedCores = 0;
            int reservedRamMb = 0;
            if (!states.isEmpty()) {
                UtilizationSnapshot first = states.firstEntry().getValue();
                reservedCores = first.getTotalReservedCores();
                reservedRamMb = first.getTotalReservedRamMb();
            }
            return new UtilizationSnapshot(start,
                    reservedCores, 0, 0, reservedRamMb, 0, 0,
                    0, 0, 0, 0, 0, 0);
        }

        private static Map<String, StatAccumulator> accumulators() {
            Map<String, StatAccumulator> values = new TreeMap<>();
            add(values, "reservedCapacityCores", UtilizationSnapshot::getTotalReservedCores);
            add(values, "reservedCapacityRamMb", UtilizationSnapshot::getTotalReservedRamMb);
            add(values, "onDemandCapacityCores", UtilizationSnapshot::getTotalOnDemandCores);
            add(values, "onDemandCapacityRamMb", UtilizationSnapshot::getTotalOnDemandRamMb);
            add(values, "spotCapacityCores", UtilizationSnapshot::getTotalSpotCores);
            add(values, "spotCapacityRamMb", UtilizationSnapshot::getTotalSpotRamMb);
            add(values, "reservedUsedCores", UtilizationSnapshot::getRunningReservedCores);
            add(values, "reservedUsedRamMb", UtilizationSnapshot::getRunningReservedRamMb);
            add(values, "onDemandUsedCores", UtilizationSnapshot::getRunningOnDemandCores);
            add(values, "onDemandUsedRamMb", UtilizationSnapshot::getRunningOnDemandRamMb);
            add(values, "spotUsedCores", UtilizationSnapshot::getRunningSpotCores);
            add(values, "spotUsedRamMb", UtilizationSnapshot::getRunningSpotRamMb);
            add(values, "utilizedCores", UtilizationSnapshot::getRunningCores);
            add(values, "utilizedRamMb", UtilizationSnapshot::getRunningRamMb);
            add(values, "reservedCoreUtilization", UtilizationSnapshot::getReservedUtilization);
            add(values, "reservedRamUtilization", UtilizationSnapshot::getReservedRamUtilization);
            add(values, "overallCoreUtilization", UtilizationSnapshot::getOverallUtilization);
            add(values, "overallRamUtilization", UtilizationSnapshot::getOverallRamUtilization);
            return values;
        }

        private static void add(Map<String, StatAccumulator> values,
                                String name, SnapshotValue reader) {
            values.put(name, new StatAccumulator(reader));
        }

        private static void observe(Map<String, StatAccumulator> values,
                                    UtilizationSnapshot snapshot) {
            for (StatAccumulator accumulator : values.values()) {
                accumulator.observe(snapshot);
            }
        }

        private static void integrate(Map<String, StatAccumulator> values,
                                      UtilizationSnapshot snapshot,
                                      double duration) {
            if (duration < 0.0) {
                throw new IllegalStateException("Utilization snapshots moved backward");
            }
            for (StatAccumulator accumulator : values.values()) {
                accumulator.integrate(snapshot, duration);
            }
        }
    }

    private static final class StatAccumulator {
        private final SnapshotValue reader;
        private double integral;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;
        private double last;
        private boolean observed;

        StatAccumulator(SnapshotValue reader) {
            this.reader = reader;
        }

        void observe(UtilizationSnapshot snapshot) {
            double value = reader.value(snapshot);
            last = value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            observed = true;
        }

        void integrate(UtilizationSnapshot snapshot, double duration) {
            integral += reader.value(snapshot) * duration;
        }

        Statistic finish(double duration) {
            if (!observed) return new Statistic(0.0, 0.0, 0.0, 0.0);
            double mean = duration > 0.0 ? integral / duration : last;
            return new Statistic(integral, mean, min, max);
        }
    }
}
