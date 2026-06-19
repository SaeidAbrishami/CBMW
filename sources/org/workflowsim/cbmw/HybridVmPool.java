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

    public static final int    NUM_RESERVED = Integer.getInteger(
            "cbmw.reserved.instances", 50);
    public static final int    RESERVED_CORES = Integer.getInteger(
            "cbmw.reserved.cores", 192);
    public static final int    RESERVED_RAM_MB = Integer.getInteger(
            "cbmw.reserved.ram.mb", 768 * 1024);
    public static final int    ON_DEMAND_CORES = Integer.getInteger(
            "cbmw.ondemand.cores", 32);
    public static final int    ON_DEMAND_RAM_MB = Integer.getInteger(
            "cbmw.ondemand.ram.mb", 64000);
    public static final int    TASK_CORES = Integer.getInteger(
            "cbmw.task.cores", 1);
    public static final int    TASK_RAM_MB = Integer.getInteger(
            "cbmw.task.ram.mb", 0);
    public static final double RESERVED_MIPS        = 1000.0;
    public static final double RESERVED_HOURLY_COST = Double.parseDouble(
            System.getProperty("cbmw.reserved.hourly.cost", "3.26"));
    public static final double ON_DEMAND_PER_SEC    = Double.parseDouble(
            System.getProperty("cbmw.ondemand.per.sec", "0.000340"));
    /** Modelled on-demand provisioning delay (seconds). sstji = lstji - OPD. */
    public static final double ON_DEMAND_PROVISIONING_DELAY = Double.parseDouble(
            System.getProperty("cbmw.ondemand.delay.sec", "120.0"));

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private int nextOnDemandId = NUM_RESERVED;

    // Persistent slot bookings for reserved VMs across all workflow planning calls.
    // vmId -> list of [start, end, taskId] triples
    private final Map<Integer, List<double[]>> reservedBookings = new HashMap<>();
    // taskId -> [vmId, start, end] so we can release by taskId on completion
    private final Map<Integer, double[]> taskBookingIndex = new HashMap<>();
    // Runtime core occupancy per VM. This is separate from booking profiles.
    private final Map<Integer, Integer> runningTasksByVm = new HashMap<>();

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, RESERVED_CORES,
                    RESERVED_RAM_MB, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
            reservedBookings.put(i, new ArrayList<>());
            runningTasksByVm.put(i, 0);
        }
    }

    public CondorVM getVmById(int id) {
        for (CondorVM vm : reservedVms) {
            if (vm.getId() == id) return vm;
        }
        for (CondorVM vm : onDemandVms) {
            if (vm.getId() == id) return vm;
        }
        return null;
    }

    /**
     * Removes a reserved VM that failed to register with the datacenter.
     * Clears its booking list and any task-to-VM index entries so the
     * planner and scheduler never attempt to use it again.
     */
    public void removeReservedVm(int vmId) {
        reservedVms.removeIf(vm -> vm.getId() == vmId);
        reservedBookings.remove(vmId);
        taskBookingIndex.entrySet().removeIf(e -> (int) e.getValue()[0] == vmId);
        runningTasksByVm.remove(vmId);
        CBMWLogger.log("VM-REMOVE",
                String.format("reserved vm=%d removed from pool remainingReserved=%d",
                        vmId, reservedVms.size()));
    }

    public CondorVM getAnyIdleReservedVm() {
        for (CondorVM vm : reservedVms) {
            if (hasRuntimeCapacity(vm.getId())) return vm;
        }
        return null;
    }

    /**
     * Returns the best idle reserved VM for advancing a task that would occupy
     * [now, execEndTime]. Prefers VMs with no conflicting bookings in that window;
     * falls back to any idle VM if none are conflict-free.
     */
    public CondorVM getIdleReservedVmForAdvance(double now, double execEndTime) {
        CondorVM anyCapacity = null;
        for (CondorVM vm : reservedVms) {
            if (!hasRuntimeCapacity(vm.getId())) continue;
            if (anyCapacity == null) anyCapacity = vm;
            if (overlapCount(vm.getId(), now, execEndTime) < RESERVED_CORES) return vm;
        }
        return anyCapacity;
    }

    public CondorVM getAnyIdleOnDemandVm() {
        for (CondorVM vm : onDemandVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    public CondorVM provisionOnDemandVm(int userId) {
        int id = nextOnDemandId++;
        CondorVM vm = new CondorVM(id, userId, RESERVED_MIPS, ON_DEMAND_CORES,
                ON_DEMAND_RAM_MB, 10000, 100000, "Xen",
                ON_DEMAND_PER_SEC, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        onDemandVms.add(vm);
        CBMWLogger.log("VM-PROVISION",
                String.format("on-demand vmId=%d provisioned totalOnDemand=%d", id, onDemandVms.size()));
        return vm;
    }

    public void terminateOnDemandVm(int vmId) {
        onDemandVms.removeIf(vm -> vm.getId() == vmId);
        runningTasksByVm.remove(vmId);
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

    public boolean hasRuntimeCapacity(int vmId) {
        int running = runningTasksByVm.getOrDefault(vmId, 0);
        int capacity = isReserved(vmId) ? RESERVED_CORES : ON_DEMAND_CORES;
        return running + TASK_CORES <= capacity;
    }

    public void taskStarted(int vmId) {
        runningTasksByVm.merge(vmId, 1, Integer::sum);
        CondorVM vm = getVmById(vmId);
        if (vm != null) vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
    }

    public void taskFinished(int vmId) {
        int running = Math.max(0, runningTasksByVm.getOrDefault(vmId, 0) - 1);
        runningTasksByVm.put(vmId, running);
        CondorVM vm = getVmById(vmId);
        if (vm != null && running == 0) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    public int getRunningTaskCount(int vmId) {
        return runningTasksByVm.getOrDefault(vmId, 0);
    }

    public int overlapCount(int vmId, double start, double end) {
        int count = 0;
        for (double[] interval : getBookings(vmId)) {
            if (start < interval[1] && end > interval[0]) count++;
        }
        return count;
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
