package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Manages the pool of reserved and on-demand CondorVMs.
 * Reserved VMs have ids 0..(NUM_RESERVED-1), on-demand start at NUM_RESERVED.
 */
public class HybridVmPool {

    public static final int    NUM_RESERVED = Integer.getInteger(
            "cbmw.reserved.instances", 5);
    public static final int    RESERVED_CORES = Integer.getInteger(
            "cbmw.reserved.cores", 192);
    public static final int    RESERVED_RAM_MB = Integer.getInteger(
            "cbmw.reserved.ram.mb", 384000);
    public static final int    TASK_CORES = Integer.getInteger(
            "cbmw.task.cores", 1);
    public static final int    TASK_RAM_MB = Integer.getInteger(
            "cbmw.task.ram.mb", 1);
    /** Paper model: each on-demand container is sized exactly for one task. */
    public static final int    ON_DEMAND_CORES = TASK_CORES;
    public static final int    ON_DEMAND_RAM_MB = TASK_RAM_MB;
    public static final double RESERVED_MIPS        = 1000.0;
    public static final double RESERVED_HOURLY_COST = Double.parseDouble(
            System.getProperty("cbmw.reserved.hourly.cost", "3.26"));
    public static final double ON_DEMAND_PER_SEC    = Double.parseDouble(
            System.getProperty("cbmw.ondemand.per.sec", "0.000340"));
    /** Modelled on-demand provisioning delay (seconds). sstji = lstji - OPD. */
    public static final double ON_DEMAND_PROVISIONING_DELAY = Double.parseDouble(
            System.getProperty("cbmw.ondemand.delay.sec", "120.0"));
    public static final double SCHEDULING_PERIOD = Double.parseDouble(
            System.getProperty("cbmw.scheduling.period.sec", "5.0"));
    public static final double PROVISIONER_PERIOD = Double.parseDouble(
            System.getProperty("cbmw.provisioner.period.sec", "15.0"));
    public static final double ON_DEMAND_MIN_BILLING_SECONDS = Double.parseDouble(
            System.getProperty("cbmw.ondemand.min.billing.sec", "60.0"));

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private final Map<Integer, CondorVM> vmsById = new HashMap<>();
    private final Map<Integer, Integer> onDemandIndexById = new HashMap<>();
    private final Set<Integer> activeOnDemandIds = new HashSet<>();
    private int nextOnDemandId = NUM_RESERVED;

    // Persistent slot bookings for reserved VMs across all workflow planning calls.
    // vmId -> list of [start, end, cores, ramMb] intervals
    private final Map<Integer, List<double[]>> reservedBookings = new HashMap<>();
    // taskId -> [vmId, start, end, cores, ramMb] so we can release by taskId
    private final Map<Integer, double[]> taskBookingIndex = new HashMap<>();
    // Runtime occupancy per VM. This is separate from booking profiles.
    private final Map<Integer, Integer> runningCoresByVm = new HashMap<>();
    private final Map<Integer, Integer> runningRamByVm = new HashMap<>();

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, RESERVED_CORES,
                    RESERVED_RAM_MB, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
            vmsById.put(i, vm);
            reservedBookings.put(i, new ArrayList<>());
            runningCoresByVm.put(i, 0);
            runningRamByVm.put(i, 0);
        }
    }

    public CondorVM getVmById(int id) {
        return vmsById.get(id);
    }

    /**
     * Removes a reserved VM that failed to register with the datacenter.
     * Clears its booking list and any task-to-VM index entries so the
     * planner and scheduler never attempt to use it again.
     */
    public void removeReservedVm(int vmId) {
        reservedVms.removeIf(vm -> vm.getId() == vmId);
        vmsById.remove(vmId);
        reservedBookings.remove(vmId);
        taskBookingIndex.entrySet().removeIf(e -> (int) e.getValue()[0] == vmId);
        runningCoresByVm.remove(vmId);
        runningRamByVm.remove(vmId);
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
            if (hasBookedCapacity(vm.getId(), now, execEndTime,
                    TASK_CORES, TASK_RAM_MB)) return vm;
        }
        return anyCapacity;
    }

    public CondorVM getAnyIdleOnDemandVm() {
        for (CondorVM vm : onDemandVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    /** Creates the paper's dedicated, task-sized on-demand container. */
    public CondorVM provisionOnDemandVm(int userId) {
        int id = nextOnDemandId++;
        CondorVM vm = new CondorVM(id, userId, RESERVED_MIPS, ON_DEMAND_CORES,
                ON_DEMAND_RAM_MB, 10000, 100000, "Xen",
                ON_DEMAND_PER_SEC, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        onDemandVms.add(vm);
        vmsById.put(id, vm);
        onDemandIndexById.put(id, onDemandVms.size() - 1);
        CBMWLogger.logf("VM-PROVISION",
                "on-demand vmId=%d provisioned totalOnDemand=%d", id, onDemandVms.size());
        return vm;
    }

    public void terminateOnDemandVm(int vmId) {
        activeOnDemandIds.remove(vmId);
        Integer index = onDemandIndexById.remove(vmId);
        if (index != null) {
            int lastIndex = onDemandVms.size() - 1;
            CondorVM last = onDemandVms.remove(lastIndex);
            if (index < lastIndex) {
                onDemandVms.set(index, last);
                onDemandIndexById.put(last.getId(), index);
            }
        }
        vmsById.remove(vmId);
        runningCoresByVm.remove(vmId);
        runningRamByVm.remove(vmId);
        CBMWLogger.logf("VM-TERMINATE",
                "on-demand vmId=%d terminated remainingOnDemand=%d", vmId, onDemandVms.size());
    }

    /** Records a time-slot booking for a reserved VM task. */
    public void bookSlot(int vmId, int taskId, double start, double end) {
        bookSlot(vmId, taskId, start, end, TASK_CORES, TASK_RAM_MB);
    }

    /** Records a time-slot booking for a reserved VM task. */
    public void bookSlot(int vmId, int taskId, double start, double end,
                         int cores, int ramMb) {
        List<double[]> list = reservedBookings.computeIfAbsent(vmId, k -> new ArrayList<>());
        list.add(new double[]{start, end, cores, ramMb});
        taskBookingIndex.put(taskId, new double[]{vmId, start, end, cores, ramMb});
        CBMWLogger.log("BOOK-SLOT",
                String.format("vm=%d task=%d [%.4f, %.4f] totalBookings=%d",
                        vmId, taskId, start, end, list.size()));
    }

    /** Replaces any existing booking for taskId with the actual reserved slot. */
    public void rebookSlot(int taskId, int vmId, double start, double end) {
        releaseSlot(taskId);
        bookSlot(vmId, taskId, start, end, TASK_CORES, TASK_RAM_MB);
    }

    /** Returns a snapshot of booked [start, end] intervals for a reserved VM. */
    public List<double[]> getBookings(int vmId) {
        List<double[]> list = reservedBookings.get(vmId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public boolean hasRuntimeCapacity(int vmId) {
        int coreCapacity = isReserved(vmId) ? RESERVED_CORES : ON_DEMAND_CORES;
        int ramCapacity = isReserved(vmId) ? RESERVED_RAM_MB : ON_DEMAND_RAM_MB;
        return runningCoresByVm.getOrDefault(vmId, 0) + TASK_CORES <= coreCapacity
                && runningRamByVm.getOrDefault(vmId, 0) + TASK_RAM_MB <= ramCapacity;
    }

    public void taskStarted(int vmId) {
        runningCoresByVm.merge(vmId, TASK_CORES, Integer::sum);
        runningRamByVm.merge(vmId, TASK_RAM_MB, Integer::sum);
        CondorVM vm = getVmById(vmId);
        if (vm != null) vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
    }

    public void taskFinished(int vmId) {
        int runningCores = Math.max(0, runningCoresByVm.getOrDefault(vmId, 0) - TASK_CORES);
        int runningRam = Math.max(0, runningRamByVm.getOrDefault(vmId, 0) - TASK_RAM_MB);
        runningCoresByVm.put(vmId, runningCores);
        runningRamByVm.put(vmId, runningRam);
        CondorVM vm = getVmById(vmId);
        if (vm != null && runningCores == 0) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    public int getRunningTaskCount(int vmId) {
        int cores = runningCoresByVm.getOrDefault(vmId, 0);
        return TASK_CORES > 0 ? cores / TASK_CORES : 0;
    }

    public int getRunningCores(int vmId) {
        return runningCoresByVm.getOrDefault(vmId, 0);
    }

    public int getRunningRamMb(int vmId) {
        return runningRamByVm.getOrDefault(vmId, 0);
    }

    public void activateOnDemandContainer(int vmId) {
        if (vmsById.containsKey(vmId) && !isReserved(vmId)) {
            activeOnDemandIds.add(vmId);
        }
    }

    public int getActiveOnDemandCount() {
        return activeOnDemandIds.size();
    }

    public int overlapCount(int vmId, double start, double end) {
        int count = 0;
        for (double[] interval : getBookings(vmId)) {
            if (start < interval[1] && end > interval[0]) count++;
        }
        return count;
    }

    public boolean hasBookedCapacity(int vmId, double start, double end,
                                     int cores, int ramMb) {
        int usedCores = 0;
        int usedRam = 0;
        for (double[] interval : getBookings(vmId)) {
            if (start < interval[1] && end > interval[0]) {
                usedCores += interval.length > 2 ? (int) interval[2] : TASK_CORES;
                usedRam += interval.length > 3 ? (int) interval[3] : TASK_RAM_MB;
            }
        }
        return usedCores + cores <= RESERVED_CORES
                && usedRam + ramMb <= RESERVED_RAM_MB;
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
            slots.removeIf(s -> Math.abs(s[0] - start) < 1e-6
                    && Math.abs(s[1] - end) < 1e-6);
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
