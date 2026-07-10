package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    public static final double ON_DEMAND_CPU_PER_CORE_SEC = Double.parseDouble(
            System.getProperty("cbmw.ondemand.cpu.per.core.sec",
                    Double.toString(ON_DEMAND_PER_SEC)));
    public static final double ON_DEMAND_MEMORY_PER_GB_SEC = Double.parseDouble(
            System.getProperty("cbmw.ondemand.memory.per.gb.sec", "0.0"));
    /** Modelled on-demand provisioning delay (seconds). sstji = lstji - OPD. */
    public static final double ON_DEMAND_PROVISIONING_DELAY = Double.parseDouble(
            System.getProperty("cbmw.ondemand.delay.sec", "90.0"));
    public static final double SCHEDULING_PERIOD = Double.parseDouble(
            System.getProperty("cbmw.scheduling.period.sec", "5.0"));
    public static final double ON_DEMAND_MIN_BILLING_SECONDS = Double.parseDouble(
            System.getProperty("cbmw.ondemand.min.billing.sec", "60.0"));

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private final Map<Integer, CondorVM> vmsById = new HashMap<>();
    private final Map<Integer, Integer> onDemandIndexById = new HashMap<>();
    private final Set<Integer> activeOnDemandIds = new HashSet<>();
    private int nextOnDemandId = NUM_RESERVED;

    // Persistent slot bookings for reserved VMs across all workflow planning calls.
    // vmId -> list of [start, end, cores, ramMb, taskId] intervals
    private final Map<Integer, List<double[]>> reservedBookings = new HashMap<>();
    // vmId -> interval-start -> [used cores, used RAM] until the next entry.
    private final Map<Integer, TreeMap<Double, int[]>> reservedProfiles = new HashMap<>();
    // taskId -> [vmId, start, end, cores, ramMb] so we can release by taskId
    private final Map<Integer, double[]> taskBookingIndex = new HashMap<>();
    // Runtime occupancy per VM. This is separate from booking profiles.
    private final Map<Integer, Integer> runningCoresByVm = new HashMap<>();
    private final Map<Integer, Integer> runningRamByVm = new HashMap<>();
    private final Map<Integer, Integer> runningTasksByVm = new HashMap<>();
    private final Map<Integer, Integer> taskCoresById = new HashMap<>();
    private final Map<Integer, Integer> taskRamById = new HashMap<>();

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, RESERVED_CORES,
                    RESERVED_RAM_MB, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
            vmsById.put(i, vm);
            reservedBookings.put(i, new ArrayList<>());
            TreeMap<Double, int[]> profile = new TreeMap<>();
            profile.put(0.0, new int[]{0, 0});
            reservedProfiles.put(i, profile);
            runningCoresByVm.put(i, 0);
            runningRamByVm.put(i, 0);
            runningTasksByVm.put(i, 0);
        }
    }

    public void registerTaskResources(int taskId, int cores, int ramMb) {
        taskCoresById.put(taskId, cores);
        taskRamById.put(taskId, ramMb);
    }

    public int getTaskCores(int taskId) {
        return taskCoresById.getOrDefault(taskId, TASK_CORES);
    }

    public int getTaskRamMb(int taskId) {
        return taskRamById.getOrDefault(taskId, TASK_RAM_MB);
    }

    public double getOnDemandPricePerSecond(int taskId) {
        return onDemandPricePerSecond(getTaskCores(taskId), getTaskRamMb(taskId));
    }

    public static double onDemandPricePerSecond(int cores, int ramMb) {
        return cores * ON_DEMAND_CPU_PER_CORE_SEC
                + (ramMb / 1024.0) * ON_DEMAND_MEMORY_PER_GB_SEC;
    }

    /**
     * CloudSim rigid-task wall time. Cloudlet length is work per PE, so both
     * total work and total MIPS scale with requested cores and wall time stays
     * equal to length-per-PE divided by MIPS-per-core.
     */
    public static double executionTimeSeconds(long cloudletLength,
                                              int cores,
                                              double mipsPerCore) {
        int effectiveCores = Math.max(1, cores);
        if (!Double.isFinite(mipsPerCore) || mipsPerCore <= 0.0) {
            throw new IllegalArgumentException("mipsPerCore must be finite and positive");
        }
        double totalWork = (double) cloudletLength * effectiveCores;
        double totalMips = mipsPerCore * effectiveCores;
        return totalWork / totalMips;
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
        reservedProfiles.remove(vmId);
        taskBookingIndex.entrySet().removeIf(e -> (int) e.getValue()[0] == vmId);
        runningCoresByVm.remove(vmId);
        runningRamByVm.remove(vmId);
        runningTasksByVm.remove(vmId);
        CBMWLogger.log("VM-REMOVE",
                String.format("reserved vm=%d removed from pool remainingReserved=%d",
                        vmId, reservedVms.size()));
    }

    public CondorVM getAnyIdleReservedVm(int taskId) {
        for (CondorVM vm : reservedVms) {
            if (hasRuntimeCapacity(vm.getId(), taskId)) return vm;
        }
        return null;
    }

    /**
     * Returns a reserved VM only when the task fits both current capacity and
     * the complete booked resource profile over [now, execEndTime].
     */
    public CondorVM getIdleReservedVmForAdvance(double now, double execEndTime,
                                                int taskId) {
        int cores = getTaskCores(taskId);
        int ramMb = getTaskRamMb(taskId);
        for (CondorVM vm : reservedVms) {
            if (!hasRuntimeCapacity(vm.getId(), taskId)) continue;
            if (hasBookedCapacity(vm.getId(), now, execEndTime,
                    cores, ramMb)) return vm;
        }
        return null;
    }

    public CondorVM getAnyIdleOnDemandVm() {
        for (CondorVM vm : onDemandVms) {
            if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) return vm;
        }
        return null;
    }

    /** Creates the paper's dedicated, task-sized on-demand container. */
    public CondorVM provisionOnDemandVm(int userId, int taskId) {
        int cores = getTaskCores(taskId);
        int ramMb = getTaskRamMb(taskId);
        return provisionOnDemandVm(userId, taskId, cores, ramMb,
                RESERVED_MIPS, onDemandPricePerSecond(cores, ramMb));
    }

    /** Creates a logical on-demand VM with an algorithm-selected type. */
    public CondorVM provisionOnDemandVm(int userId, int taskId, int cores,
                                        int ramMb, double mipsPerCore,
                                        double pricePerSecond) {
        int id = nextOnDemandId++;
        CondorVM vm = new CondorVM(id, userId, mipsPerCore, cores,
                ramMb, 10000, 100000, "Xen",
                pricePerSecond, 0.0, 0.0, 0.0,
                new CloudletSchedulerSpaceShared());
        onDemandVms.add(vm);
        vmsById.put(id, vm);
        onDemandIndexById.put(id, onDemandVms.size() - 1);
        runningCoresByVm.put(id, 0);
        runningRamByVm.put(id, 0);
        runningTasksByVm.put(id, 0);
        CBMWLogger.logf("VM-PROVISION",
                "on-demand vmId=%d task=%d cores=%d ramMb=%d totalOnDemand=%d",
                id, taskId, cores, ramMb, onDemandVms.size());
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
        runningTasksByVm.remove(vmId);
        CBMWLogger.logf("VM-TERMINATE",
                "on-demand vmId=%d terminated remainingOnDemand=%d", vmId, onDemandVms.size());
    }

    /** Records a time-slot booking for a reserved VM task. */
    public void bookSlot(int vmId, int taskId, double start, double end) {
        bookSlot(vmId, taskId, start, end,
                getTaskCores(taskId), getTaskRamMb(taskId));
    }

    /** Records a time-slot booking for a reserved VM task. */
    public void bookSlot(int vmId, int taskId, double start, double end,
                         int cores, int ramMb) {
        List<double[]> list = reservedBookings.computeIfAbsent(vmId, k -> new ArrayList<>());
        list.add(new double[]{start, end, cores, ramMb, taskId});
        updateProfile(vmId, start, end, cores, ramMb);
        taskBookingIndex.put(taskId, new double[]{vmId, start, end, cores, ramMb});
        CBMWLogger.log("BOOK-SLOT",
                String.format("vm=%d task=%d [%.4f, %.4f] totalBookings=%d",
                        vmId, taskId, start, end, list.size()));
    }

    /** Replaces any existing booking for taskId with the actual reserved slot. */
    public void rebookSlot(int taskId, int vmId, double start, double end) {
        releaseSlot(taskId);
        bookSlot(vmId, taskId, start, end,
                getTaskCores(taskId), getTaskRamMb(taskId));
    }

    /** Returns a snapshot of booked [start, end] intervals for a reserved VM. */
    public List<double[]> getBookings(int vmId) {
        List<double[]> list = reservedBookings.get(vmId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public boolean hasRuntimeCapacity(int vmId, int taskId) {
        CondorVM vm = getVmById(vmId);
        if (vm == null) return false;
        return runningCoresByVm.getOrDefault(vmId, 0) + getTaskCores(taskId)
                    <= vm.getNumberOfPes()
                && runningRamByVm.getOrDefault(vmId, 0) + getTaskRamMb(taskId)
                    <= vm.getRam();
    }

    public void taskStarted(int vmId, int taskId) {
        runningCoresByVm.merge(vmId, getTaskCores(taskId), Integer::sum);
        runningRamByVm.merge(vmId, getTaskRamMb(taskId), Integer::sum);
        runningTasksByVm.merge(vmId, 1, Integer::sum);
        CondorVM vm = getVmById(vmId);
        if (vm != null) vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
    }

    public void taskFinished(int vmId, int taskId) {
        int runningCores = Math.max(0, runningCoresByVm.getOrDefault(vmId, 0)
                - getTaskCores(taskId));
        int runningRam = Math.max(0, runningRamByVm.getOrDefault(vmId, 0)
                - getTaskRamMb(taskId));
        runningCoresByVm.put(vmId, runningCores);
        runningRamByVm.put(vmId, runningRam);
        runningTasksByVm.put(vmId,
                Math.max(0, runningTasksByVm.getOrDefault(vmId, 0) - 1));
        CondorVM vm = getVmById(vmId);
        if (vm != null && runningCores == 0) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    public int getRunningTaskCount(int vmId) {
        return runningTasksByVm.getOrDefault(vmId, 0);
    }

    public int getRunningCores(int vmId) {
        return runningCoresByVm.getOrDefault(vmId, 0);
    }

    public int getRunningRamMb(int vmId) {
        return runningRamByVm.getOrDefault(vmId, 0);
    }

    public int getTotalRunningCores(boolean onDemand) {
        int total = 0;
        for (CondorVM vm : onDemand ? onDemandVms : reservedVms) {
            total += getRunningCores(vm.getId());
        }
        return total;
    }

    public int getTotalRunningRamMb(boolean onDemand) {
        int total = 0;
        for (CondorVM vm : onDemand ? onDemandVms : reservedVms) {
            total += getRunningRamMb(vm.getId());
        }
        return total;
    }

    public void activateOnDemandContainer(int vmId) {
        if (vmsById.containsKey(vmId) && !isReserved(vmId)) {
            activeOnDemandIds.add(vmId);
        }
    }

    public int getActiveOnDemandCount() {
        return activeOnDemandIds.size();
    }

    public boolean isOnDemandContainerActive(int vmId) {
        return activeOnDemandIds.contains(vmId);
    }

    public int getActiveOnDemandCores() {
        int total = 0;
        for (Integer vmId : activeOnDemandIds) {
            CondorVM vm = getVmById(vmId);
            if (vm != null) total += vm.getNumberOfPes();
        }
        return total;
    }

    public int getActiveOnDemandRamMb() {
        int total = 0;
        for (Integer vmId : activeOnDemandIds) {
            CondorVM vm = getVmById(vmId);
            if (vm != null) total += vm.getRam();
        }
        return total;
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
        if (cores > RESERVED_CORES || ramMb > RESERVED_RAM_MB) return false;
        TreeMap<Double, int[]> profile = reservedProfiles.get(vmId);
        if (profile == null) return false;
        double cursor = start;
        while (cursor < end - 1e-9) {
            Map.Entry<Double, int[]> segment = profile.floorEntry(cursor);
            if (segment == null) return false;
            int[] used = segment.getValue();
            if (used[0] + cores > RESERVED_CORES
                    || used[1] + ramMb > RESERVED_RAM_MB) return false;
            Double next = profile.higherKey(segment.getKey());
            if (next == null || next >= end) break;
            cursor = Math.max(cursor + 1e-9, next);
        }
        return true;
    }

    public double findLatestFeasibleSlot(int vmId, double earliest, double lft,
                                         double duration, int cores, int ramMb) {
        if (duration < 0.0 || cores > RESERVED_CORES || ramMb > RESERVED_RAM_MB) {
            return -1.0;
        }
        TreeMap<Double, int[]> profile = reservedProfiles.get(vmId);
        if (profile == null) return -1.0;
        double cursor = lft;
        double feasibleEnd = lft;
        double feasibleDuration = 0.0;
        while (cursor > earliest + 1e-9) {
            Map.Entry<Double, int[]> segment = profile.floorEntry(Math.nextDown(cursor));
            if (segment == null) break;
            double segmentStart = Math.max(earliest, segment.getKey());
            int[] used = segment.getValue();
            if (used[0] + cores <= RESERVED_CORES
                    && used[1] + ramMb <= RESERVED_RAM_MB) {
                feasibleDuration += cursor - segmentStart;
                if (feasibleDuration + 1e-9 >= duration) {
                    return feasibleEnd - duration;
                }
            } else {
                feasibleDuration = 0.0;
                feasibleEnd = segmentStart;
            }
            cursor = segmentStart;
        }
        return duration == 0.0 && lft >= earliest ? lft : -1.0;
    }

    public double findEarliestFeasibleSlot(int vmId, double earliest,
                                           double duration, int cores, int ramMb) {
        if (duration < 0.0 || cores > RESERVED_CORES || ramMb > RESERVED_RAM_MB) {
            return Double.MAX_VALUE;
        }
        TreeMap<Double, int[]> profile = reservedProfiles.get(vmId);
        if (profile == null) return Double.MAX_VALUE;
        double cursor = earliest;
        double feasibleStart = earliest;
        double feasibleDuration = 0.0;
        while (true) {
            Map.Entry<Double, int[]> segment = profile.floorEntry(cursor);
            if (segment == null) return Double.MAX_VALUE;
            Double next = profile.higherKey(segment.getKey());
            double segmentEnd = next == null ? Double.POSITIVE_INFINITY : next;
            int[] used = segment.getValue();
            if (used[0] + cores <= RESERVED_CORES
                    && used[1] + ramMb <= RESERVED_RAM_MB) {
                if (!Double.isFinite(segmentEnd)
                        || feasibleDuration + segmentEnd - cursor + 1e-9 >= duration) {
                    return feasibleStart;
                }
                feasibleDuration += segmentEnd - cursor;
            } else {
                feasibleDuration = 0.0;
                feasibleStart = segmentEnd;
            }
            cursor = segmentEnd;
        }
    }

    /** Earliest feasible slot that also completes by latestFinish. */
    public double findEarliestFeasibleSlot(int vmId, double earliest,
                                           double latestFinish, double duration,
                                           int cores, int ramMb) {
        if (!Double.isFinite(latestFinish) || latestFinish < earliest) {
            return Double.MAX_VALUE;
        }
        double start = findEarliestFeasibleSlot(
                vmId, earliest, duration, cores, ramMb);
        return Double.isFinite(start) && start + duration <= latestFinish + 1e-9
                ? start : Double.MAX_VALUE;
    }

    private void updateProfile(int vmId, double start, double end,
                               int coreDelta, int ramDelta) {
        TreeMap<Double, int[]> profile = reservedProfiles.get(vmId);
        if (profile == null || end <= start) return;
        splitProfile(profile, start);
        splitProfile(profile, end);
        for (int[] used : profile.subMap(start, true, end, false).values()) {
            used[0] += coreDelta;
            used[1] += ramDelta;
        }
        mergeProfileBoundary(profile, start);
        mergeProfileBoundary(profile, end);
    }

    private void splitProfile(TreeMap<Double, int[]> profile, double time) {
        if (profile.containsKey(time)) return;
        Map.Entry<Double, int[]> previous = profile.floorEntry(time);
        int[] used = previous == null ? new int[]{0, 0} : previous.getValue();
        profile.put(time, new int[]{used[0], used[1]});
    }

    private void mergeProfileBoundary(TreeMap<Double, int[]> profile, double time) {
        Map.Entry<Double, int[]> current = profile.floorEntry(time);
        if (current == null) return;
        Map.Entry<Double, int[]> previous = profile.lowerEntry(current.getKey());
        if (previous != null && sameUsage(previous.getValue(), current.getValue())) {
            profile.remove(current.getKey());
        }
    }

    private boolean sameUsage(int[] left, int[] right) {
        return left[0] == right[0] && left[1] == right[1];
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
        int cores = entry.length > 3 ? (int) entry[3] : TASK_CORES;
        int ramMb = entry.length > 4 ? (int) entry[4] : TASK_RAM_MB;
        updateProfile(vmId, start, end, -cores, -ramMb);
        List<double[]> slots = reservedBookings.get(vmId);
        int before = (slots != null) ? slots.size() : 0;
        if (slots != null) {
            slots.removeIf(s -> s.length > 4 && (int) s[4] == taskId);
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
