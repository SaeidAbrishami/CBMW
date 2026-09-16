package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Validates that incremental ready-queue ordering matches stable append+sort. */
public final class CBMWReadyQueueValidationTest {

    private static final Comparator<Entry> BY_SST =
            Comparator.comparingDouble(entry -> entry.sst);

    private CBMWReadyQueueValidationTest() {}

    public static void main(String[] args) {
        List<Entry> existing = new ArrayList<>(Arrays.asList(
                entry("old-a", 1.0), entry("old-b", 2.0),
                entry("old-c", 2.0), entry("old-d", 5.0)));
        List<Entry> additions = Arrays.asList(
                entry("new-a", 2.0), entry("new-b", 0.5),
                entry("new-c", 5.0), entry("new-d", 2.0));

        List<Entry> expected = new ArrayList<>(existing);
        expected.addAll(additions);
        expected.sort(BY_SST);

        CBMWBroker.stableMergeSorted(existing, additions, BY_SST);
        require(names(existing).equals(names(expected)),
                "stable merge differs from append+stable-sort");

        Entry resumed = entry("resumed", 2.0);
        expected.add(resumed);
        expected.sort(BY_SST);
        CBMWBroker.stableInsertSorted(existing, resumed, BY_SST);
        require(names(existing).equals(names(expected)),
                "stable insertion differs from append+stable-sort");

        System.out.println("CBMW ready-queue validation passed");
    }

    private static Entry entry(String name, double sst) {
        return new Entry(name, sst);
    }

    private static List<String> names(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) names.add(entry.name);
        return names;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Entry {
        private final String name;
        private final double sst;

        private Entry(String name, double sst) {
            this.name = name;
            this.sst = sst;
        }
    }
}
