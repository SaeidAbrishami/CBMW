package org.workflowsim.cbmw;

/** Focused validation for atomic, capacity-safe reserved rebooking. */
public final class CBMWSafeReservedRebookingValidationTest {

    private CBMWSafeReservedRebookingValidationTest() {}

    public static void main(String[] args) {
        HybridVmPool pool = new HybridVmPool(0);
        int delayedTaskId = 1000;
        pool.registerTaskResources(delayedTaskId, 1, 1);
        pool.bookSlot(0, delayedTaskId, 0.0, 5.0, 1, 1);

        for (int vmId = 0; vmId < HybridVmPool.NUM_RESERVED; vmId++) {
            int blockerTaskId = 2000 + vmId;
            pool.registerTaskResources(blockerTaskId,
                    HybridVmPool.RESERVED_CORES,
                    HybridVmPool.RESERVED_RAM_MB);
            pool.bookSlot(vmId, blockerTaskId, 10.0, 20.0,
                    HybridVmPool.RESERVED_CORES,
                    HybridVmPool.RESERVED_RAM_MB);
        }

        HybridVmPool.ReservedSlot deferred = pool.reserveEarliestSlack(
                delayedTaskId, 0, 12.0, 4.0);
        require(deferred != null, "a later reserved gap must be found");
        require(Math.abs(deferred.getStart() - 20.0) <= 1e-9,
                "the task must wait until the blocking reservations end");
        require(deferred.getVmId() == 0,
                "the preferred VM must win an equal-start tie");

        HybridVmPool.ReservedSlot selfExcluded = pool.reserveEarliestSlack(
                delayedTaskId, 0, 20.0, 4.0);
        require(selfExcluded != null,
                "the task's existing reservation must be excluded");
        require(Math.abs(selfExcluded.getStart() - 20.0) <= 1e-9,
                "excluding the task itself must preserve its safe slot");

        System.out.println("CBMW safe reserved rebooking validation passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
