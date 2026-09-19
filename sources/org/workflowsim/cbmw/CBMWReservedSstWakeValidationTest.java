package org.workflowsim.cbmw;

/** Focused state-machine validation for CBMW's exact reserved-SST wake. */
public final class CBMWReservedSstWakeValidationTest {

    private CBMWReservedSstWakeValidationTest() {}

    public static void main(String[] args) {
        CBMWBroker.ReservedSstWakeController controller =
                new CBMWBroker.ReservedSstWakeController();

        CBMWBroker.ReservedSstWake first = controller.arm(10.0);
        require(first != null, "the first future SST must arm a wake");
        require(controller.arm(10.0) == null,
                "an equal SST must not create a duplicate effective wake");

        CBMWBroker.ReservedSstWake earlier = controller.arm(8.0);
        require(earlier != null,
                "a newly discovered earlier SST must supersede the old wake");
        require(!controller.consume(first),
                "a superseded wake must be rejected as stale");
        require(controller.consume(earlier),
                "the current wake must be consumed at its target");
        require(!Double.isFinite(controller.getTargetTime()),
                "consuming a wake must clear the active target");

        CBMWBroker.ReservedSstWake later = controller.arm(12.0);
        require(later != null, "a later SST must arm after consumption");
        controller.clear();
        require(!controller.consume(later),
                "clearing the queue must invalidate the pending wake");

        System.out.println("CBMW reserved-SST wake validation passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
