package org.workflowsim.cbmw.baselines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** UTC, hourly USD spot prices for one instance type and one Availability Zone. */
final class CEWBSpotPriceTrace {
    private final double[] seconds;
    private final double[] dollarsPerSecond;
    private final double offset;
    private final double length;
    private final double trainingMinimumPerSecond;

    CEWBSpotPriceTrace(String augustFile, String julyFile, double offsetSeconds) {
        if (augustFile == null || augustFile.isBlank()
                || julyFile == null || julyFile.isBlank()) {
            throw new IllegalArgumentException(
                    "CEWB Spot replay requires both July training and August trace files");
        }
        try {
            if (!Double.isFinite(offsetSeconds) || offsetSeconds < 0) {
                throw new IllegalArgumentException("Invalid Spot trace offset");
            }
            List<String> july = Files.readAllLines(Path.of(julyFile));
            double minimum = Double.POSITIVE_INFINITY;
            for (String row : july) {
                String[] fields = row.split("\t");
                if (fields.length != 2 || !fields[0].startsWith("2026-07-")) {
                    throw new IllegalArgumentException("Invalid July training row: " + row);
                }
                minimum = Math.min(minimum, Double.parseDouble(fields[1]));
            }
            if (!(minimum > 0) || !Double.isFinite(minimum)) {
                throw new IllegalArgumentException("Empty/invalid July Spot training file");
            }
            trainingMinimumPerSecond = minimum / 3600.0;
            List<String> august = Files.readAllLines(Path.of(augustFile));
            List<Double> times = new ArrayList<>();
            List<Double> prices = new ArrayList<>();
            Instant start = Instant.parse("2026-08-01T00:00:00Z");
            for (String row : august) {
                String[] fields = row.split("\t");
                if (fields.length != 2 || !fields[0].startsWith("2026-08-")) {
                    throw new IllegalArgumentException("Invalid August replay row: " + row);
                }
                double time = Instant.parse(fields[0]).getEpochSecond() - start.getEpochSecond();
                double price = Double.parseDouble(fields[1]) / 3600.0;
                if (!(price > 0) || !Double.isFinite(price)
                        || (!times.isEmpty() && time <= times.get(times.size() - 1))) {
                    throw new IllegalArgumentException("Invalid Spot price/order: " + row);
                }
                times.add(time);
                prices.add(price);
            }
            if (times.isEmpty() || times.get(0) != 0) {
                throw new IllegalArgumentException("August Spot trace must start at midnight UTC");
            }
            seconds = new double[times.size()];
            dollarsPerSecond = new double[prices.size()];
            for (int i = 0; i < times.size(); i++) {
                seconds[i] = times.get(i);
                dollarsPerSecond[i] = prices.get(i);
            }
            offset = offsetSeconds;
            length = Instant.parse("2026-09-01T00:00:00Z").getEpochSecond()
                    - start.getEpochSecond();
            if (offset >= length) throw new IllegalArgumentException("Spot trace offset exceeds August");
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read CEWB Spot trace", e);
        }
    }

    double trainingMinimumPerSecond() { return trainingMinimumPerSecond; }

    private int indexAt(double simulationSeconds) {
        double t = simulationSeconds + offset;
        if (!Double.isFinite(t) || t < 0 || t >= length) {
            throw new IllegalStateException("CEWB replay exceeds August 2026 at simulation second "
                    + simulationSeconds + " (trace offset=" + offset + ")");
        }
        int lo = 0, hi = seconds.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (seconds[mid] <= t) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    double priceAt(double t) { return dollarsPerSecond[indexAt(t)]; }

    double nextExceeding(double start, double maximumPrice) {
        int i = indexAt(start);
        if (dollarsPerSecond[i] > maximumPrice) return start;
        for (i++; i < seconds.length; i++) {
            if (dollarsPerSecond[i] > maximumPrice) return seconds[i] - offset;
        }
        return Double.POSITIVE_INFINITY;
    }

    double cost(double from, double to) {
        if (to < from || from < 0) throw new IllegalArgumentException("Invalid Spot rental interval");
        if (to == from) return 0;
        // Validate both endpoints, including the final billed second.
        indexAt(from);
        indexAt(Math.nextDown(to));
        double result = 0;
        double t = from;
        while (t < to) {
            int i = indexAt(t);
            double next = i + 1 < seconds.length
                    ? seconds[i + 1] - offset : length - offset;
            double end = Math.min(to, next);
            result += (end - t) * dollarsPerSecond[i];
            t = end;
        }
        return result;
    }

    /** EC2 fixes the Spot rate at the beginning of each instance-hour. */
    double billInstance(double from, double to, boolean awsInterrupted) {
        if (to < from) throw new IllegalArgumentException("Invalid Spot instance lifetime");
        if (awsInterrupted && to - from < 3600) return 0.0;
        double billedEnd = Math.max(to, from + 60.0);
        double total = 0;
        for (double hourStart = from; hourStart < billedEnd; hourStart += 3600) {
            double end = Math.min(hourStart + 3600, billedEnd);
            total += (end - hourStart) * priceAt(hourStart);
        }
        indexAt(Math.nextDown(billedEnd));
        return total;
    }

    String description() {
        return "August2026 offset=" + offset + "s julyMinimum=$"
                + trainingMinimumPerSecond * 3600 + "/h";
    }
}
