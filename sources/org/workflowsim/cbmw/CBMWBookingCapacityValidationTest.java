package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Compares read-only advance checks with removing and restoring the booking. */
public final class CBMWBookingCapacityValidationTest {
    private CBMWBookingCapacityValidationTest() {}

    public static void main(String[] args) {
        HybridVmPool pool = new HybridVmPool(0);
        Random random = new Random(20260926L);
        Map<Integer, double[]> booked = new HashMap<>();
        for (int taskId = 1; taskId <= 400; taskId++) {
            int vmId = taskId % 3;
            int cores = 8 + random.nextInt(75);
            int ramMb = cores * 512;
            double start = random.nextInt(300);
            double end = start + 5 + random.nextInt(60);
            if (!pool.hasBookedCapacity(vmId, start, end, cores, ramMb)) continue;
            pool.registerTaskResources(taskId, cores, ramMb);
            pool.bookSlot(vmId, taskId, start, end, cores, ramMb);
            booked.put(taskId, new double[]{vmId, start, end, cores, ramMb});
        }

        List<Integer> taskIds = new ArrayList<>(booked.keySet());
        for (int trial = 0; trial < 2000; trial++) {
            int taskId = taskIds.get(random.nextInt(taskIds.size()));
            double[] old = booked.get(taskId);
            int targetVm = random.nextInt(3);
            double start = random.nextInt(330);
            double end = start + 1 + random.nextInt(60);
            int oldVmCount = pool.getBookingCount((int) old[0]);
            boolean actual = pool.canMoveBookingNow(taskId, targetVm, start, end);
            require(pool.getBookingCount((int) old[0]) == oldVmCount,
                    "an advance check changed the booking count");
            pool.releaseSlot(taskId);
            boolean expected = pool.hasBookedCapacity(targetVm, start, end,
                    (int) old[3], (int) old[4]);
            pool.bookSlot((int) old[0], taskId, old[1], old[2],
                    (int) old[3], (int) old[4]);
            require(actual == expected, "advance capacity differs at trial " + trial);
        }
        System.out.println("CBMW booking capacity validation passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
