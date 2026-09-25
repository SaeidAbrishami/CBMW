package org.workflowsim.cbmw;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Wall-clock progress for one dataset/scenario, independent of simulated time. */
public final class CBMWProgressReporter implements AutoCloseable {
    private final String scenario;
    private final long totalTasks;
    private volatile long startedAtNanos;
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    // Only the CloudSim simulation thread writes these sets.
    private final Set<Integer> startedIds = new HashSet<>();
    private final Set<Integer> completedIds = new HashSet<>();
    private final ScheduledExecutorService timer;

    public CBMWProgressReporter(String scenario, List<WorkflowArrivalData> arrivals)
            throws Exception {
        this.scenario = scenario;
        this.totalTasks = countTasks(arrivals);
        ThreadFactory daemon = runnable -> {
            Thread thread = new Thread(runnable, "cbmw-progress");
            thread.setDaemon(true);
            return thread;
        };
        timer = Executors.newSingleThreadScheduledExecutor(daemon);
    }

    public void start() {
        long interval = Long.getLong("cbmw.progress.interval.sec", 60L);
        if (interval < 1L) {
            throw new IllegalArgumentException("cbmw.progress.interval.sec must be positive");
        }
        startedAtNanos = System.nanoTime();
        print("start");
        timer.scheduleAtFixedRate(() -> print("running"), interval, interval,
                TimeUnit.SECONDS);
    }

    public void taskStarted(int taskId) {
        if (startedIds.add(taskId)) started.incrementAndGet();
    }

    public void taskCompleted(int taskId) {
        if (completedIds.add(taskId)) completed.incrementAndGet();
    }

    public void workflowRejected(int taskCount) {
        rejected.addAndGet(taskCount);
    }

    @Override
    public void close() {
        timer.shutdownNow();
        try {
            timer.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        print("final");
    }

    private void print(String phase) {
        long eligible = Math.max(0L, totalTasks - rejected.get());
        long done = completed.get();
        double percent = eligible == 0L ? 100.0
                : Math.min(100.0, 100.0 * done / eligible);
        long elapsed = TimeUnit.NANOSECONDS.toSeconds(
                System.nanoTime() - startedAtNanos);
        System.out.println(String.format(Locale.US,
                "[progress] scenario=%s phase=%s elapsed=%ds started=%d"
                        + " completed=%d/%d (%.2f%%) rejectedTasks=%d",
                scenario, phase, elapsed, started.get(), done, eligible,
                percent, rejected.get()));
    }

    private static long countTasks(List<WorkflowArrivalData> arrivals)
            throws Exception {
        Map<String, Integer> cached = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        long count = 0L;
        for (WorkflowArrivalData arrival : arrivals) {
            String path = arrival.getDaxPath();
            Integer jobs = cached.get(path);
            if (jobs == null) {
                jobs = countDaxJobs(factory, path);
                cached.put(path, jobs);
            }
            count += jobs;
        }
        return count;
    }

    private static int countDaxJobs(XMLInputFactory factory, String path)
            throws Exception {
        try (InputStream input = new FileInputStream(path)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            int jobs = 0;
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "job".equalsIgnoreCase(reader.getLocalName())) {
                        jobs++;
                    }
                }
            } finally {
                reader.close();
            }
            return jobs;
        }
    }
}
