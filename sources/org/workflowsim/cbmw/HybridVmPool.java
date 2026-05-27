package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Manages the pool of reserved and on-demand CondorVMs.
 * Reserved VMs have ids 0..(NUM_RESERVED-1), on-demand start at NUM_RESERVED.
 */
public class HybridVmPool {

    public static final int    NUM_RESERVED        = 50;
    public static final double RESERVED_MIPS        = 1000.0;
    public static final double RESERVED_HOURLY_COST = 3.26;    // hpc7a.96xlarge $/hr
    public static final double ON_DEMAND_PER_SEC    = 0.000905; // Fargate $/sec

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private int nextOnDemandId = NUM_RESERVED;

    // Persistent slot bookings for reserved VMs across all workflow planning calls.
    // vmId -> list of [start, end, taskId] triples
    private final Map<Integer, List<double[]>> reservedBookings = new HashMap<>();
    // taskId -> [vmId, start, end] so we can release by taskId on completion
    private final Map<Integer, double[]> taskBookingIndex = new HashMap<>();

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, 1,
                    4096, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
            reservedBookings.put(i, new ArrayList<>());
        }
    }

    public CondorVM getVmById(int id) {
        if (id < NUM_RESERVED) return reservedVms.get(id);
        for (CondorVM vm : onDemandVms) {
            if (vm.getId() == id) return vm;
        }
        return null;
    }

    public CondorVM getAnyIdleReservedVm() {
        for (CondorVM vm : reservedVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    /**
     * Returns the best idle reserved VM for advancing a task that would occupy
     * [now, execEndTime]. Prefers VMs with no conflicting bookings in that window;
     * falls back to any idle VM if none are conflict-free.
     */
    public CondorVM getIdleReservedVmForAdvance(double now, double execEndTime) {
        CondorVM anyIdle = null;
        for (CondorVM vm : reservedVms) {
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE) continue;
            if (anyIdle == null) anyIdle = vm;
            boolean conflict = false;
            for (double[] interval : getBookings(vm.getId())) {
                if (interval[1] <= now) continue; // stale booking
                if (now < interval[1] && execEndTime > interval[0]) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return vm; // prefer first conflict-free idle VM
        }
        return anyIdle; // fall back to any idle VM
    }

    public CondorVM getAnyIdleOnDemandVm() {
        for (CondorVM vm : onDemandVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    public CondorVM provisionOnDemandVm(int userId) {
        int id = nextOnDemandId++;
        CondorVM vm = new CondorVM(id, userId, RESERVED_MIPS, 1,
                4096, 10000, 100000, "Xen",
                ON_DEMAND_PER_SEC, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        onDemandVms.add(vm);
        CBMWLogger.log("VM-PROVISION",
                String.format("on-demand vmId=%d provisioned totalOnDemand=%d", id, onDemandVms.size()));
        return vm;
    }

    public void terminateOnDemandVm(int vmId) {
        onDemandVms.removeIf(vm -> vm.getId() == vmId);
        CBMWLogger.log("VM-TERMINATE",
                String.format("on-demand vmId=%d terminated remainingOnDemand=%d", vmId, onDemandVms.size()));
    }

    /** Records a time-slot booking for a reserved VM task. */
    public void bookSlot(int vmId, int taskId, double start, double end) {
        List<double[]> list = reservedBookings.computeIfAbsent(vmId, k -> new ArrayList<>());
        list.add(new double[]{start, end});
        taskBookingIndex.put(taskId, new double[]{vmId, start, end});
        CBMWLogger.log("BOOK-SLOT",
                String.format("vm=%d task=%d [%.4f, %.4f] totalBookings=%d",
                        vmId, taskId, start, end, list.size()));
    }

    /** Returns a snapshot of booked [start, end] intervals for a reserved VM. */
    public List<double[]> getBookings(int vmId) {
        List<double[]> list = reservedBookings.get(vmId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * Removes the booking for a task that has completed, freeing the slot
     * so future workflows can use it.
     */
    public void releaseSlot(int taskId) {
        double[] entry = taskBookingIndex.remove(taskId);
        if (entry == null) {
            CBMWLogger.log("RELEASE-SLOT",
                    String.format("task=%d NOT FOUND in booking index (on-demand or already released)",
                            taskId));
            return;
        }
        int vmId = (int) entry[0];
        double start = entry[1], end = entry[2];
        List<double[]> slots = reservedBookings.get(vmId);
        int before = (slots != null) ? slots.size() : 0;
        if (slots != null) {
            slots.removeIf(s -> s[0] == start && s[1] == end);
        }
        int after = (slots != null) ? slots.size() : 0;
        CBMWLogger.log("RELEASE-SLOT",
                String.format("task=%d vm=%d [%.4f, %.4f] freed bookings=%d->%d",
                        taskId, vmId, start, end, before, after));
    }

    public boolean isReserved(int vmId) { return vmId < NUM_RESERVED; }

    public List<CondorVM> getReservedVms() { return reservedVms; }
    public List<CondorVM> getOnDemandVms()  { return onDemandVms; }

    public List<CondorVM> getAllVms() {
        List<CondorVM> all = new ArrayList<>(reservedVms);
        all.addAll(onDemandVms);
        return all;
    }
}
