package org.workflowsim.cbmw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** Immutable result of moving a reserved task to a capacity-safe slot. */
    public static final class ReservedSlot {
        private final int vmId;
        private final double start;
        private final double end;

        private ReservedSlot(int vmId, double start, double end) {
            this.vmId = vmId;
            this.start = start;
            this.end = end;
        }

        public int getVmId() { return vmId; }
        public double getStart() { return start; }
        public double getEnd() { return end; }
    }

    public static final int    NUM_RESERVED = Integer.getInteger(
            "cbmw.reserved.instances", 5);
    public static final int    RESERVED_CORES = Integer.getInteger(
            "cbmw.reserved.cores", 192);
    public static final int    RESERVED_RAM_MB = Integer.getInteger(
            "cbmw.reserved.ram.mb", 384 * 1024);
    public static final int    TASK_CORES = Integer.getInteger(
            "cbmw.task.cores", 1);
    public static final int    TASK_RAM_MB = Integer.getInteger(
            "cbmw.task.ram.mb", 2048);
    /** Paper model: each on-demand container is sized exactly for one task. */
    public static final int    ON_DEMAND_CORES = TASK_CORES;
    public static final int    ON_DEMAND_RAM_MB = TASK_RAM_MB;
    public static final double RESERVED_MIPS        = 1000.0;
    public static final double RESERVED_PER_SEC = reservedPricePerSecond();
    public static final double RESERVED_HOURLY_COST = RESERVED_PER_SEC * 3600.0;
    public static final double ON_DEMAND_PER_SEC    = Double.parseDouble(
            System.getProperty("cbmw.ondemand.per.sec", "0.00001"));
    public static final double ON_DEMAND_CPU_PER_CORE_SEC = Double.parseDouble(
            System.getProperty("cbmw.ondemand.cpu.per.core.sec",
                    Double.toString(ON_DEMAND_PER_SEC)));
    public static final double ON_DEMAND_MEMORY_PER_GB_SEC = Double.parseDouble(
            System.getProperty("cbmw.ondemand.memory.per.gb.sec", "0.000001"));
    /** Modeled advance provisioning delay; SST denotes execution start. */
    public static final double ON_DEMAND_PROVISIONING_DELAY = Double.parseDouble(
            System.getProperty("cbmw.ondemand.delay.sec", "60.0"));
    public static final double SCHEDULING_PERIOD = Double.parseDouble(
            System.getProperty("cbmw.scheduling.period.sec", "5.0"));
    public static final double ON_DEMAND_MIN_BILLING_SECONDS = Double.parseDouble(
            System.getProperty("cbmw.ondemand.min.billing.sec", "60.0"));

    private static double reservedPricePerSecond() {
        String perSecond = System.getProperty("cbmw.reserved.per.sec");
        if (perSecond != null) {
            return Double.parseDouble(perSecond);
        }
        String legacyHourly = System.getProperty("cbmw.reserved.hourly.cost");
        return legacyHourly == null
                ? 0.0017
                : Double.parseDouble(legacyHourly) / 3600.0;
    }

    private final List<CondorVM> reservedVms  = new ArrayList<>();
    private final List<CondorVM> onDemandVms  = new ArrayList<>();
    private final Map<Integer, CondorVM> vmsById = new HashMap<>();
    private final Map<Integer, Integer> onDemandIndexById = new HashMap<>();
    private final Set<Integer> activeOnDemandIds = new HashSet<>();
    private int nextOnDemandId = NUM_RESERVED;

    // Persistent slot bookings for reserved VMs across all workflow planning calls.
    // vmId -> taskId -> [start, end, cores, ramMb, taskId]. Insertion order
    // preserves the existing getBookings snapshot order while allowing direct removal.
    private final Map<Integer, Map<Integer, double[]>> reservedBookings = new HashMap<>();
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
    private long totalRunningReservedCores;
    private long totalRunningReservedRamMb;
    private long totalRunningOnDemandCores;
    private long totalRunningOnDemandRamMb;
    private long activeOnDemandCores;
    private long activeOnDemandRamMb;

    public HybridVmPool(int userId) {
        for (int i = 0; i < NUM_RESERVED; i++) {
            CondorVM vm = new CondorVM(i, userId, RESERVED_MIPS, RESERVED_CORES,
                    RESERVED_RAM_MB, 10000, 100000, "Xen",
                    0.0, 0.0, 0.0, 0.0,
                    new CloudletSchedulerSpaceShared());
            reservedVms.add(vm);
            vmsById.put(i, vm);
            reservedBookings.put(i, new LinkedHashMap<>());
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
        totalRunningReservedCores = Math.max(0L,
                totalRunningReservedCores - runningCoresByVm.getOrDefault(vmId, 0));
        totalRunningReservedRamMb = Math.max(0L,
                totalRunningReservedRamMb - runningRamByVm.getOrDefault(vmId, 0));
        reservedVms.removeIf(vm -> vm.getId() == vmId);
        vmsById.remove(vmId);
        reservedBookings.remove(vmId);
        reservedProfiles.remove(vmId);
        taskBookingIndex.entrySet().removeIf(e -> (int) e.getValue()[0] == vmId);
        runningCoresByVm.remove(vmId);
        runningRamByVm.remove(vmId);
        runningTasksByVm.remove(vmId);
        assert aggregateCountersMatch();
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
        CondorVM vm = getVmById(vmId);
        if (activeOnDemandIds.remove(vmId) && vm != null) {
            activeOnDemandCores = Math.max(0L,
                    activeOnDemandCores - vm.getNumberOfPes());
            activeOnDemandRamMb = Math.max(0L,
                    activeOnDemandRamMb - vm.getRam());
        }
        totalRunningOnDemandCores = Math.max(0L,
                totalRunningOnDemandCores - runningCoresByVm.getOrDefault(vmId, 0));
        totalRunningOnDemandRamMb = Math.max(0L,
                totalRunningOnDemandRamMb - runningRamByVm.getOrDefault(vmId, 0));
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
        assert aggregateCountersMatch();
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
        Map<Integer, double[]> bookings = reservedBookings.computeIfAbsent(
                vmId, k -> new LinkedHashMap<>());
        bookings.put(taskId, new double[]{start, end, cores, ramMb, taskId});
        updateProfile(vmId, start, end, cores, ramMb);
        taskBookingIndex.put(taskId, new double[]{vmId, start, end, cores, ramMb});
        CBMWLogger.logf("BOOK-SLOT", "vm=%d task=%d [%.4f, %.4f] totalBookings=%d",
                vmId, taskId, start, end, bookings.size());
    }

    /** Replaces any existing booking for taskId with the actual reserved slot. */
    public void rebookSlot(int taskId, int vmId, double start, double end) {
        releaseSlot(taskId);
        bookSlot(vmId, taskId, start, end,
                getTaskCores(taskId), getTaskRamMb(taskId));
    }

    /** Tests an early start while ignoring only this task's old booking. */
    public boolean canMoveBookingNow(int taskId, int vmId,
                                     double start, double end) {
        return hasBookedCapacityIgnoringTask(vmId, start, end,
                getTaskCores(taskId), getTaskRamMb(taskId),
                taskBookingIndex.get(taskId));
    }

    /** Replace this task's booking only after checking all other commitments. */
    public boolean moveBookingNow(int taskId, int vmId,
                                  double start, double end) {
        if (!canMoveBookingNow(taskId, vmId, start, end)) return false;
        rebookSlot(taskId, vmId, start, end);
        return true;
    }

    /**
     * Atomically moves a task's booking to the earliest reserved-capacity gap.
     * The task's old booking is removed while searching so it is not counted
     * against itself. The preferred VM wins equal-start ties; otherwise the
     * globally earliest slot is selected. If no slot exists, the old booking
     * is restored unchanged.
     */
    public ReservedSlot reserveEarliestSlack(int taskId, int preferredVmId,
                                              double earliestStart,
                                              double duration) {
        int cores = getTaskCores(taskId);
        int ramMb = getTaskRamMb(taskId);
        double[] previous = taskBookingIndex.get(taskId);
        double[] saved = previous != null ? previous.clone() : null;
        if (previous != null) releaseSlot(taskId);

        ReservedSlot best = null;
        for (CondorVM vm : reservedVms) {
            double start = findEarliestFeasibleSlot(vm.getId(), earliestStart,
                    duration, cores, ramMb);
            if (!Double.isFinite(start) || start == Double.MAX_VALUE) continue;

            if (best == null || start < best.start - 1e-9
                    || (Math.abs(start - best.start) <= 1e-9
                        && preferVm(vm.getId(), best.vmId, preferredVmId))) {
                best = new ReservedSlot(vm.getId(), start, start + duration);
            }
        }

        if (best == null) {
            if (saved != null) {
                bookSlot((int) saved[0], taskId, saved[1], saved[2],
                        (int) saved[3], (int) saved[4]);
            }
            return null;
        }

        bookSlot(best.vmId, taskId, best.start, best.end, cores, ramMb);
        return best;
    }

    private boolean preferVm(int candidateVmId, int currentVmId,
                             int preferredVmId) {
        if (candidateVmId == preferredVmId) return currentVmId != preferredVmId;
        if (currentVmId == preferredVmId) return false;
        return candidateVmId < currentVmId;
    }

    /** Returns a snapshot of booked [start, end] intervals for a reserved VM. */
    public List<double[]> getBookings(int vmId) {
        Map<Integer, double[]> bookings = reservedBookings.get(vmId);
        return bookings != null ? new ArrayList<>(bookings.values()) : new ArrayList<>();
    }

    public int getBookingCount(int vmId) {
        Map<Integer, double[]> bookings = reservedBookings.get(vmId);
        return bookings == null ? 0 : bookings.size();
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
        int taskCores = getTaskCores(taskId);
        int taskRamMb = getTaskRamMb(taskId);
        runningCoresByVm.merge(vmId, taskCores, Integer::sum);
        runningRamByVm.merge(vmId, taskRamMb, Integer::sum);
        runningTasksByVm.merge(vmId, 1, Integer::sum);
        CondorVM vm = getVmById(vmId);
        if (vm != null) {
            if (isReserved(vmId)) {
                totalRunningReservedCores += taskCores;
                totalRunningReservedRamMb += taskRamMb;
            } else {
                totalRunningOnDemandCores += taskCores;
                totalRunningOnDemandRamMb += taskRamMb;
            }
            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
        }
        assert aggregateCountersMatch();
    }

    public void taskFinished(int vmId, int taskId) {
        int previousCores = runningCoresByVm.getOrDefault(vmId, 0);
        int previousRam = runningRamByVm.getOrDefault(vmId, 0);
        int runningCores = Math.max(0, previousCores - getTaskCores(taskId));
        int runningRam = Math.max(0, previousRam - getTaskRamMb(taskId));
        runningCoresByVm.put(vmId, runningCores);
        runningRamByVm.put(vmId, runningRam);
        runningTasksByVm.put(vmId,
                Math.max(0, runningTasksByVm.getOrDefault(vmId, 0) - 1));
        CondorVM vm = getVmById(vmId);
        if (vm != null) {
            int removedCores = previousCores - runningCores;
            int removedRam = previousRam - runningRam;
            if (isReserved(vmId)) {
                totalRunningReservedCores = Math.max(0L,
                        totalRunningReservedCores - removedCores);
                totalRunningReservedRamMb = Math.max(0L,
                        totalRunningReservedRamMb - removedRam);
            } else {
                totalRunningOnDemandCores = Math.max(0L,
                        totalRunningOnDemandCores - removedCores);
                totalRunningOnDemandRamMb = Math.max(0L,
                        totalRunningOnDemandRamMb - removedRam);
            }
            if (runningCores == 0) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
        }
        assert aggregateCountersMatch();
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
        return toIntCapacity(onDemand
                ? totalRunningOnDemandCores : totalRunningReservedCores);
    }

    public int getTotalRunningRamMb(boolean onDemand) {
        return toIntCapacity(onDemand
                ? totalRunningOnDemandRamMb : totalRunningReservedRamMb);
    }

    public void activateOnDemandContainer(int vmId) {
        if (vmsById.containsKey(vmId) && !isReserved(vmId)) {
            CondorVM vm = getVmById(vmId);
            if (activeOnDemandIds.add(vmId) && vm != null) {
                activeOnDemandCores += vm.getNumberOfPes();
                activeOnDemandRamMb += vm.getRam();
            }
        }
        assert aggregateCountersMatch();
    }

    public int getActiveOnDemandCount() {
        return activeOnDemandIds.size();
    }

    public boolean isOnDemandContainerActive(int vmId) {
        return activeOnDemandIds.contains(vmId);
    }

    public int getActiveOnDemandCores() {
        return toIntCapacity(activeOnDemandCores);
    }

    public int getActiveOnDemandRamMb() {
        return toIntCapacity(activeOnDemandRamMb);
    }

    private boolean aggregateCountersMatch() {
        long scannedReservedCores = 0L;
        long scannedReservedRamMb = 0L;
        for (CondorVM vm : reservedVms) {
            scannedReservedCores += runningCoresByVm.getOrDefault(vm.getId(), 0);
            scannedReservedRamMb += runningRamByVm.getOrDefault(vm.getId(), 0);
        }
        long scannedOnDemandCores = 0L;
        long scannedOnDemandRamMb = 0L;
        for (CondorVM vm : onDemandVms) {
            scannedOnDemandCores += runningCoresByVm.getOrDefault(vm.getId(), 0);
            scannedOnDemandRamMb += runningRamByVm.getOrDefault(vm.getId(), 0);
        }
        long scannedActiveCores = 0L;
        long scannedActiveRamMb = 0L;
        for (Integer vmId : activeOnDemandIds) {
            CondorVM vm = getVmById(vmId);
            if (vm != null) {
                scannedActiveCores += vm.getNumberOfPes();
                scannedActiveRamMb += vm.getRam();
            }
        }
        return totalRunningReservedCores == scannedReservedCores
                && totalRunningReservedRamMb == scannedReservedRamMb
                && totalRunningOnDemandCores == scannedOnDemandCores
                && totalRunningOnDemandRamMb == scannedOnDemandRamMb
                && activeOnDemandCores == scannedActiveCores
                && activeOnDemandRamMb == scannedActiveRamMb;
    }

    private int toIntCapacity(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
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

    /** Capacity check that excludes the task's own reservation without
     * changing the booking profile. A profile segment can cross an old booking
     * boundary when adjacent segments have equal usage, so visit both bounds. */
    private boolean hasBookedCapacityIgnoringTask(int vmId, double start,
                                                   double end, int cores,
                                                   int ramMb, double[] old) {
        if (old == null || (int) old[0] != vmId) {
            return hasBookedCapacity(vmId, start, end, cores, ramMb);
        }
        if (cores > RESERVED_CORES || ramMb > RESERVED_RAM_MB) return false;
        TreeMap<Double, int[]> profile = reservedProfiles.get(vmId);
        if (profile == null) return false;
        double cursor = start;
        while (cursor < end - 1e-9) {
            Map.Entry<Double, int[]> segment = profile.floorEntry(cursor);
            if (segment == null) return false;
            boolean ownSlot = cursor >= old[1] && cursor < old[2];
            int[] used = segment.getValue();
            if (used[0] - (ownSlot ? (int) old[3] : 0) + cores > RESERVED_CORES
                    || used[1] - (ownSlot ? (int) old[4] : 0) + ramMb > RESERVED_RAM_MB) {
                return false;
            }
            Double next = profile.higherKey(segment.getKey());
            if (cursor < old[1] && (next == null || old[1] < next)) next = old[1];
            if (cursor < old[2] && (next == null || old[2] < next)) next = old[2];
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
            CBMWLogger.logf("RELEASE-SLOT",
                    "task=%d NOT FOUND in booking index (on-demand or already released)",
                    taskId);
            return;
        }
        int vmId = (int) entry[0];
        double start = entry[1], end = entry[2];
        int cores = entry.length > 3 ? (int) entry[3] : TASK_CORES;
        int ramMb = entry.length > 4 ? (int) entry[4] : TASK_RAM_MB;
        updateProfile(vmId, start, end, -cores, -ramMb);
        Map<Integer, double[]> bookings = reservedBookings.get(vmId);
        int before = bookings != null ? bookings.size() : 0;
        if (bookings != null) bookings.remove(taskId);
        CBMWLogger.logf("RELEASE-SLOT",
                "task=%d vm=%d [%.4f, %.4f] freed bookings=%d->%d",
                taskId, vmId, start, end, before,
                bookings != null ? bookings.size() : 0);
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
