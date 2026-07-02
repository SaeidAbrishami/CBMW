package org.workflowsim.cbmw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowParser;
import org.workflowsim.WorkflowScheduler;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.Parameters;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Shared base for all CBMW-family brokers.
 *
 * Handles CloudSim event wiring, DAX parsing (with .txt runtime override),
 * workflow arrival (negotiation + template method planWorkflow), VM lifecycle,
 * job completion tracking, and result bookkeeping.
 *
 * Subclasses implement only the algorithm-specific parts:
 *   planWorkflow(wfr, tasks)    — assign VMs to tasks before the run
 *   processCloudletUpdate(ev)   — dispatch ready jobs during the run
 *
 * Optionally override onTaskComplete(cl) to react to reserved-VM completions
 * (e.g. CBMWBroker uses this to release its slot bookings).
 */
public abstract class AbstractWorkflowBroker extends WorkflowScheduler {

    protected final HybridVmPool     vmPool;
    protected final NegotiationModule negotiation;
    protected final ProvisioningModule provisioner;
    protected final CBMWAccounting accounting = new CBMWAccounting();
    protected final double tightness;

    protected final Map<Integer, WorkflowRecord> activeWorkflows = new HashMap<>();
    protected final List<WorkflowRecord>         allWorkflows    = new ArrayList<>();

    protected int nextWorkflowId  = 0;
    protected int nextTaskId      = 1;
    protected int workflowEngineId = -1;

    // Counts VM_CREATE_ACKs received for reserved VMs (success + failure).
    // The scheduler is blocked until this reaches NUM_RESERVED, guaranteeing
    // the pool is fully initialised before any task is dispatched.
    private int reservedVmsAcknowledged = 0;

    // Logical on-demand containers waiting for their OPD to expire.
    // vmId -> simTime at which the dedicated container becomes available.
    private final Map<Integer, Double> pendingVmCreations = new HashMap<>();
    private final Set<Integer> readyOnDemandContainers = new HashSet<>();
    private final Set<Integer> submittedVmIds = new HashSet<>();

    private final List<WorkflowArrivalData> pendingArrivals     = new ArrayList<>();
    private final List<Double>              pendingArrivalTimes = new ArrayList<>();
    private double simEndTime = -1;
    private static final double EPS = 1e-6;

    protected AbstractWorkflowBroker(String name, double tightness) throws Exception {
        super(name);
        this.tightness = tightness;
        this.vmPool      = new HybridVmPool(getId());
        this.negotiation = new NegotiationModule(tightness);
        this.provisioner = new ProvisioningModule(vmPool, getId());
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void addArrival(double time, String daxPath, double userDeadline) {
        pendingArrivalTimes.add(time);
        pendingArrivals.add(new WorkflowArrivalData(daxPath, time, userDeadline));
    }

    public void setSimEndTime(double time) { this.simEndTime = time; }
    public HybridVmPool          getVmPool()      { return vmPool; }
    public List<WorkflowRecord>  getAllWorkflows() { return allWorkflows; }
    public CBMWAccounting        getAccounting() { return accounting; }

    @Override
    public void submitVmList(List<? extends Vm> list) {
        super.submitVmList(list);
        for (Vm vm : list) {
            submittedVmIds.add(vm.getId());
        }
    }

    // -----------------------------------------------------------------------
    // CloudSim lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void startEntity() {
        super.startEntity();
        for (int i = 0; i < pendingArrivals.size(); i++) {
            schedule(getId(), pendingArrivalTimes.get(i),
                    WorkflowSimTags.WORKFLOW_ARRIVE, pendingArrivals.get(i));
        }
        if (simEndTime > 0) {
            schedule(getId(), simEndTime, WorkflowSimTags.SIM_END, null);
        }
    }

    @Override
    public void setWorkflowEngineId(int id) {
        this.workflowEngineId = id;
        super.setWorkflowEngineId(id);
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case WorkflowSimTags.WORKFLOW_ARRIVE:
                processWorkflowArrival(ev);
                break;
            case WorkflowSimTags.SIM_END:
                processSimEnd();
                break;
            case WorkflowSimTags.ON_DEMAND_TASK_COMPLETE:
                processOnDemandTaskComplete(ev);
                break;
            case CloudSimTags.VM_DESTROY_ACK:
                break; // on-demand VM destroyed in datacenter; PE slot freed
            case WorkflowSimTags.CLOUDLET_UPDATE:
                // Block scheduling until every reserved VM has received a
                // creation response (success or failure). This prevents the
                // scheduler from setting a VM BUSY before the datacenter has
                // registered it, which would permanently strand that VM.
                if (reservedVmsAcknowledged < HybridVmPool.NUM_RESERVED) return;
                processPendingVmCreations();
                if (usesPeriodicScheduling() && !isSchedulingMoment()) return;
                super.processEvent(ev);
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // VM creation ack — register new on-demand VMs and trigger dispatch
    // -----------------------------------------------------------------------

    @Override
    protected void processVmCreate(SimEvent ev) {
        int[] data     = (int[]) ev.getData();
        int   vmId     = data[1];
        int   result   = data[2];
        super.processVmCreate(ev);
        if (vmPool.isReserved(vmId)) {
            reservedVmsAcknowledged++;
            if (result == CloudSimTags.FALSE) {
                vmPool.removeReservedVm(vmId);
                CBMWLogger.log("VM-CREATE-FAIL",
                        String.format("reserved vm=%d failed to register — removed from pool"
                                + " remainingReserved=%d acknowledged=%d/%d",
                                vmId, vmPool.getReservedVms().size(),
                                reservedVmsAcknowledged, HybridVmPool.NUM_RESERVED));
            }
        } else if (result != CloudSimTags.FALSE) {
            accounting.markOnDemandLaunched(vmId, CloudSim.clock());
            accounting.snapshotUtilization(vmPool);
        }
        sendNow(getId(), WorkflowSimTags.CLOUDLET_UPDATE);
    }

    @Override
    protected void submitCloudlets() {
        // intentional no-op: all dispatch is driven by processCloudletUpdate
    }

    // -----------------------------------------------------------------------
    // Job completion
    // -----------------------------------------------------------------------

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cl = (Cloudlet) ev.getData();
        Job job = (Job) cl;

        getCloudletReceivedList().add(cl);
        getCloudletSubmittedList().remove(cl);
        cloudletsSubmitted--;

        vmPool.taskFinished(cl.getVmId(), primaryTaskId(job));

        int wfId = workflowIdForJob(job);
        boolean onDemand = provisioner.isOnDemandVm(cl.getVmId());
        accounting.markTaskFinished(cl, onDemand);
        onTaskReturned(cl, onDemand);

        if (onDemand) {
            CondorVM idleOnDemand = provisioner.jobCompleted(cl.getCloudletId());
            if (idleOnDemand != null && terminateOnDemandWhenIdle()) {
                accounting.markOnDemandDestroyed(idleOnDemand.getId(),
                        accounting.billableDestroyTime(idleOnDemand.getId(), CloudSim.clock()));
                vmPool.terminateOnDemandVm(idleOnDemand.getId());
                readyOnDemandContainers.remove(idleOnDemand.getId());
            }
            WorkflowRecord wfr = activeWorkflows.get(wfId);
            double uptime = accounting.getOnDemandUptime(cl.getVmId());
            double pricePerSecond = vmPool.getOnDemandPricePerSecond(
                    primaryTaskId(job));
            double cost = (Double.isFinite(uptime) ? uptime : cl.getActualCPUTime())
                    * pricePerSecond;
            if (wfr != null) wfr.addOnDemandCost(cost);
            CBMWLogger.logf("TASK-COMPLETE",
                    "task=%d wf=%d vm=%d(on-demand) actualCPU=%.4fs cost=$%.6f",
                    cl.getCloudletId(), wfId, cl.getVmId(), cl.getActualCPUTime(), cost);
        } else {
            onTaskComplete(cl);
            WorkflowRecord wfrRes = activeWorkflows.get(wfId);
            if (wfrRes != null) {
                wfrRes.addReservedCpuTime(cl.getActualCPUTime()
                        * wfrRes.getTaskCores(primaryTaskId(job)));
            }
            CBMWLogger.logf("TASK-COMPLETE",
                    "task=%d wf=%d vm=%d(reserved) actualCPU=%.4fs",
                    cl.getCloudletId(), wfId, cl.getVmId(), cl.getActualCPUTime());
        }
        accounting.snapshotUtilization(vmPool);

        updateWorkflowCompletion(job);

        double delay = Parameters.getOverheadParams().getPostDelay() != null
                ? Parameters.getOverheadParams().getPostDelay(job) : 0.0;
        schedule(workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, cl);
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    /** Called when a reserved-VM task completes. Default no-op. */
    protected void onTaskComplete(Cloudlet cl) {}

    /** Called for every returned task before resource-specific cleanup. */
    protected void onTaskReturned(Cloudlet cl, boolean onDemand) {}

    protected boolean terminateOnDemandWhenIdle() { return true; }

    /** Records the current ready queue before an algorithm dispatches from it. */
    protected void recordReadyQueue(List<Cloudlet> readyJobs) {
        accounting.markReadyQueue(readyJobs);
    }

    // -----------------------------------------------------------------------
    // Simulation end
    // -----------------------------------------------------------------------

    private void processSimEnd() {
        WorkflowEngine engine = getWorkflowEngineRef();
        if (engine != null) engine.setSimulationComplete(true);
    }

    // -----------------------------------------------------------------------
    // Shared dispatch helper — called by every subclass processCloudletUpdate
    // -----------------------------------------------------------------------

    /**
     * Activates logical on-demand containers whose OPD countdown has expired.
     * They intentionally bypass CloudSim's heavyweight VM registration: the
     * paper models each as a serverless, task-sized container rather than a VM.
     */
    private void processPendingVmCreations() {
        double now = CloudSim.clock();
        List<Integer>  toRemove  = new ArrayList<>();
        List<CondorVM> readyContainers = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : pendingVmCreations.entrySet()) {
            if (now >= e.getValue()) {
                CondorVM vm = vmPool.getVmById(e.getKey());
                if (vm != null && !submittedVmIds.contains(vm.getId())) {
                    readyContainers.add(vm);
                }
                toRemove.add(e.getKey());
            }
        }
        toRemove.forEach(pendingVmCreations::remove);
        if (!readyContainers.isEmpty()) {
            for (CondorVM container : readyContainers) {
                readyOnDemandContainers.add(container.getId());
                vmPool.activateOnDemandContainer(container.getId());
                accounting.markOnDemandLaunched(container.getId(), now);
            }
            accounting.snapshotUtilization(vmPool);
            CBMWLogger.logf("VM-PROVISION-READY",
                    "activated %d dedicated on-demand containers at t=%.1f (opd=%.0fs)",
                    readyContainers.size(), now,
                    HybridVmPool.ON_DEMAND_PROVISIONING_DELAY);
        }
    }

    protected void dispatchScheduledJobs(List<Cloudlet> toSchedule) {
        List<Cloudlet> actuallySubmitted = new ArrayList<>();

        for (Cloudlet cl : toSchedule) {
            int vmId = cl.getVmId();
            Integer dcId = getVmsToDatacentersMap().get(vmId);
            if (dcId == null) {
                if (!vmPool.isReserved(vmId) && readyOnDemandContainers.contains(vmId)) {
                    submitToLogicalOnDemandContainer(cl);
                    actuallySubmitted.add(cl);
                    continue;
                }
                // VM provisioned but not yet registered with the datacenter.
                // If it hasn't been ordered yet, start the OPD countdown and
                // schedule a wake-up at the exact moment it becomes available.
                // The cloudlet stays in the ready queue and will be retried on
                // the next CLOUDLET_UPDATE (which fires at readyAt + ~0.1s).
                CondorVM provVm = vmPool.getVmById(vmId);
                if (provVm != null
                        && !pendingVmCreations.containsKey(vmId)
                        && !submittedVmIds.contains(vmId)) {
                    orderLogicalOnDemandContainer(primaryTaskId((Job) cl));
                }
                continue;
            }
            int taskId = primaryTaskId((Job) cl);
            if (vmPool.isReserved(vmId)
                    && !vmPool.hasRuntimeCapacity(vmId, taskId)) {
                continue;
            }
            double delay = Parameters.getOverheadParams().getQueueDelay() != null
                    ? Parameters.getOverheadParams().getQueueDelay(cl) : 0.0;
            schedule(dcId, delay, CloudSimTags.CLOUDLET_SUBMIT, cl);
            actuallySubmitted.add(cl);
            vmPool.taskStarted(vmId, taskId);
            accounting.markTaskSubmitted(cl, provisioner.isOnDemandVm(vmId));
            accounting.snapshotUtilization(vmPool);
            CBMWLogger.logf("DISPATCH", "wf=%d task=%d vm=%d",
                    workflowIdForJob((Job) cl), cl.getCloudletId(), cl.getVmId());
        }

        getCloudletList().removeAll(actuallySubmitted);
        getCloudletSubmittedList().addAll(actuallySubmitted);
        cloudletsSubmitted += actuallySubmitted.size();
    }

    private void submitToLogicalOnDemandContainer(Cloudlet cl) {
        double now = CloudSim.clock();
        double queueDelay = Parameters.getOverheadParams().getQueueDelay() != null
                ? Parameters.getOverheadParams().getQueueDelay(cl) : 0.0;
        int taskCores = vmPool.getTaskCores(primaryTaskId((Job) cl));
        double executionTime = HybridVmPool.executionTimeSeconds(
                cl.getCloudletLength(), taskCores, HybridVmPool.RESERVED_MIPS);
        try {
            cl.setResourceParameter(getId(), vmPool.getOnDemandPricePerSecond(
                    primaryTaskId((Job) cl)));
            cl.setSubmissionTime(now);
            cl.setExecStartTime(now + queueDelay);
            cl.setCloudletStatus(Cloudlet.INEXEC);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start logical on-demand task "
                    + cl.getCloudletId(), e);
        }
        schedule(getId(), queueDelay + executionTime,
                WorkflowSimTags.ON_DEMAND_TASK_COMPLETE, cl);
        vmPool.taskStarted(cl.getVmId(), primaryTaskId((Job) cl));
        accounting.markTaskSubmitted(cl, true);
        accounting.snapshotUtilization(vmPool);
        CBMWLogger.logf("DISPATCH", "wf=%d task=%d container=%d",
                workflowIdForJob((Job) cl), cl.getCloudletId(), cl.getVmId());
    }

    private void processOnDemandTaskComplete(SimEvent ev) {
        Cloudlet cl = (Cloudlet) ev.getData();
        double actualTime = Math.max(0.0, CloudSim.clock() - cl.getExecStartTime());
        cl.setExecParam(Math.max(0.0, CloudSim.clock() - cl.getSubmissionTime()), actualTime);
        try {
            cl.setCloudletStatus(Cloudlet.SUCCESS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not complete logical on-demand task "
                    + cl.getCloudletId(), e);
        }
        processCloudletReturn(ev);
    }

    // -----------------------------------------------------------------------
    // Subclass contract
    // -----------------------------------------------------------------------

    /**
     * Assign VMs to tasks before submitting them to the engine.
     * Return false to reject the workflow (engine notified, record kept).
     */
    protected abstract boolean planWorkflow(WorkflowRecord wfr, List<Task> tasks);

    /** Dispatch ready jobs to VMs. Triggered on every CLOUDLET_UPDATE event. */
    protected abstract void processCloudletUpdate(SimEvent ev);

    // -----------------------------------------------------------------------
    // Workflow arrival — template method
    // -----------------------------------------------------------------------

    private void processWorkflowArrival(SimEvent ev) {
        WorkflowArrivalData data = (WorkflowArrivalData) ev.getData();
        int wfId = nextWorkflowId++;

        WorkflowEngine engine = getWorkflowEngineRef();
        if (engine != null) engine.notifyWorkflowArriving();

        List<Task> tasks = parseDax(data.getDaxPath(), wfId);
        if (tasks == null || tasks.isEmpty()) {
            Log.printLine(getName() + ": failed to parse " + data.getDaxPath());
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        WorkflowRecord wfr = new WorkflowRecord(wfId, data.getDaxPath(), data.getArrivalTime());
        wfr.setTaskList(tasks);
        captureTaskResourceRequirements(data.getDaxPath(), wfr, tasks);
        captureEstimatedRuntimes(wfr, tasks);
        wfr.setDeadline(data.getUserDeadline());
        allWorkflows.add(wfr);

        if (!negotiateWorkflow(wfr)) {
            wfr.setRejectionReason("NEGOTIATION_DEADLINE_INFEASIBLE");
            accounting.registerWorkflowTasks(wfr, tasks, tightness, false,
                    "REJECTED_NEGOTIATION_DEADLINE_INFEASIBLE");
            Log.printLine(CloudSim.clock() + ": " + getName()
                    + ": wf=" + wfId + " rejected");
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        if (!planWorkflow(wfr, tasks)) {
            wfr.setAccepted(false);
            wfr.setRejectionReason("PLANNING_FAILED");
            accounting.registerWorkflowTasks(wfr, tasks, tightness, false,
                    "REJECTED_PLANNING_FAILED");
            Log.printLine(CloudSim.clock() + ": " + getName()
                    + ": wf=" + wfId + " planning failed");
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }
        wfr.setRejectionReason("");
        finalizeWorkflowNegotiation(wfr);
        applyPerturbedRuntimes(data.getDaxPath(), tasks);
        accounting.registerWorkflowTasks(wfr, tasks, tightness, true, "ACCEPTED");

        List<Job> jobs = wrapTasksAsJobs(tasks, wfr);
        activeWorkflows.put(wfId, wfr);
        sendNow(workflowEngineId, WorkflowSimTags.JOB_SUBMIT, jobs);

        Log.printLine(CloudSim.clock() + ": " + getName()
                + ": wf=" + wfId
                + " accepted deadline=" + String.format("%.1f", wfr.getDeadline())
                + " tasks=" + tasks.size());
        CBMWLogger.log("WF-ACCEPTED",
                String.format("wf=%d dax=%s arrival=%.4f deadline=%.4f cp=%.4f jobs=%d",
                        wfId, data.getDaxPath(), wfr.getArrivalTime(),
                        wfr.getDeadline(), wfr.getCriticalPathLength(), jobs.size()));
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /** Extracts the workflow ID embedded in the first task of the job. */
    protected static int workflowIdForJob(Job job) {
        return job.getTaskList().isEmpty() ? -1
                : job.getTaskList().get(0).getWorkflowId();
    }

    /** Extracts the primary task ID from the job (used for VM assignment lookup). */
    protected static int primaryTaskId(Job job) {
        return job.getTaskList().isEmpty() ? job.getCloudletId()
                : job.getTaskList().get(0).getCloudletId();
    }

    private List<Task> parseDax(String daxPath, int wfId) {
        try {
            Parameters.setDaxPath(daxPath);
            WorkflowParser p = new WorkflowParser(getId());
            p.setJobIdStartsFrom(nextTaskId);
            p.parse();
            List<Task> tasks = p.getTaskList();
            nextTaskId += tasks.size();
            CBMWLogger.log("PARSE-DAX",
                    String.format("wf=%d dax=%s tasks=%d idRange=[%d,%d]",
                            wfId, daxPath, tasks.size(),
                            nextTaskId - tasks.size(), nextTaskId - 1));
            return tasks;
        } catch (Exception e) {
            Log.printLine(getName() + ": DAX parse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Replaces each task's cloudletLength with the perturbed runtime from the
     * matching .txt file. One runtime (seconds) per line, in job-declaration order.
     * If no .txt file exists the nominal runtimes from the XML are kept as-is.
     */
    private void applyPerturbedRuntimes(String daxPath, List<Task> tasks) {
        String txtPath = daxPath.replaceAll("\\.xml$", ".txt");
        File txtFile = new File(txtPath);
        if (!txtFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(txtFile))) {
            for (Task task : tasks) {
                String line = br.readLine();
                if (line == null) break;
                double rt = Double.parseDouble(line.trim());
                // Mirror WorkflowParser: cloudletLength = runtime * 1000, min 100 MI
                task.setCloudletLength((long) Math.max(rt * 1000.0, 100.0));
            }
        } catch (Exception e) {
            Log.printLine(getName() + ": could not apply perturbed runtimes for "
                    + txtPath + ": " + e.getMessage());
        }
    }

    private void captureEstimatedRuntimes(WorkflowRecord wfr, List<Task> tasks) {
        for (Task task : tasks) {
            double mean = HybridVmPool.executionTimeSeconds(
                    task.getCloudletLength(),
                    wfr.getTaskCores(task.getCloudletId()),
                    HybridVmPool.RESERVED_MIPS);
            double estimated = estimatePlanningRuntime(mean);
            wfr.setNominalExecTime(task.getCloudletId(), mean);
            wfr.setEstimatedExecTime(task.getCloudletId(), estimated);
        }
    }

    /**
     * Loads the paper's rigid per-task core and RAM requirements. Common DAX
     * attributes and profile keys are supported; absent metadata uses the
     * explicit experiment defaults.
     */
    private void captureTaskResourceRequirements(String daxPath,
                                                 WorkflowRecord wfr,
                                                 List<Task> tasks) {
        try {
            NodeList jobs = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(new File(daxPath))
                    .getElementsByTagName("job");
            for (int i = 0; i < tasks.size(); i++) {
                Element job = i < jobs.getLength() ? (Element) jobs.item(i) : null;
                ResourceValue cores = readResource(job, false,
                        "cores", "core", "cpus", "cpu", "num_procs", "numcores");
                ResourceValue ram = readResource(job, true,
                        "ram", "memory", "memorymb", "rammb");
                int taskCores = cores.present ? cores.value : HybridVmPool.TASK_CORES;
                int taskRamMb = ram.present ? ram.value : HybridVmPool.TASK_RAM_MB;
                String source = cores.present || ram.present ? "DAX" : "DEFAULT";
                Task task = tasks.get(i);
                task.setNumberOfPes(taskCores);
                wfr.setTaskResources(task.getCloudletId(), taskCores, taskRamMb, source);
                vmPool.registerTaskResources(task.getCloudletId(), taskCores, taskRamMb);
            }
        } catch (Exception e) {
            Log.printLine(getName() + ": could not read task resources from "
                    + daxPath + ": " + e.getMessage());
            for (Task task : tasks) {
                task.setNumberOfPes(HybridVmPool.TASK_CORES);
                wfr.setTaskResources(task.getCloudletId(), HybridVmPool.TASK_CORES,
                        HybridVmPool.TASK_RAM_MB, "DEFAULT");
                vmPool.registerTaskResources(task.getCloudletId(),
                        HybridVmPool.TASK_CORES, HybridVmPool.TASK_RAM_MB);
            }
        }
    }

    private ResourceValue readResource(Element job, boolean memory,
                                       String... acceptedKeys) {
        if (job == null) return ResourceValue.missing();
        NamedNodeMap attributes = job.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (matchesKey(attribute.getNodeName(), acceptedKeys)) {
                return ResourceValue.of(parseResourceValue(attribute.getNodeValue(), memory));
            }
        }
        NodeList profiles = job.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            if (matchesKey(profile.getAttribute("key"), acceptedKeys)) {
                return ResourceValue.of(parseResourceValue(
                        profile.getTextContent(), memory));
            }
        }
        return ResourceValue.missing();
    }

    private boolean matchesKey(String candidate, String... acceptedKeys) {
        String normalized = candidate == null ? ""
                : candidate.toLowerCase().replace("-", "").replace("_", "");
        for (String key : acceptedKeys) {
            if (normalized.equals(key.replace("_", ""))) return true;
        }
        return false;
    }

    private int parseResourceValue(String raw, boolean memory) {
        String value = raw.trim().toLowerCase().replace(" ", "");
        double multiplier = 1.0;
        if (memory && (value.endsWith("gib") || value.endsWith("gb"))) {
            multiplier = 1024.0;
            value = value.replaceFirst("gib$|gb$", "");
        } else if (memory && (value.endsWith("mib") || value.endsWith("mb"))) {
            value = value.replaceFirst("mib$|mb$", "");
        }
        int parsed = (int) Math.ceil(Double.parseDouble(value) * multiplier);
        if (parsed <= 0) throw new IllegalArgumentException("resource value must be > 0");
        return parsed;
    }

    private static class ResourceValue {
        final boolean present;
        final int value;

        private ResourceValue(boolean present, int value) {
            this.present = present;
            this.value = value;
        }

        static ResourceValue of(int value) { return new ResourceValue(true, value); }
        static ResourceValue missing() { return new ResourceValue(false, 0); }
    }

    /** Allows an algorithm to replace the DAX mean with its planning estimate. */
    protected double estimatePlanningRuntime(double meanExecutionTime) {
        return meanExecutionTime;
    }

    /** Dynamic event-based baselines may bypass the periodic scheduling gate. */
    protected boolean usesPeriodicScheduling() { return true; }

    /**
     * Starts the OPD countdown for a dedicated logical container. Static
     * planners may call this before the corresponding job becomes ready.
     */
    protected CondorVM orderLogicalOnDemandContainer(int taskId) {
        CondorVM vm = provisioner.getOrProvision(taskId);
        int vmId = vm.getId();
        if (readyOnDemandContainers.contains(vmId)
                || pendingVmCreations.containsKey(vmId)) {
            return vm;
        }
        double orderedAt = CloudSim.clock();
        double readyAt = projectedOnDemandReadyTime(orderedAt);
        pendingVmCreations.put(vmId, readyAt);
        accounting.markOnDemandOrdered(vmId, orderedAt, readyAt);
        accounting.markTaskProvisioningOrdered(taskId, orderedAt, readyAt);
        schedule(getId(), Math.max(0.0, readyAt - orderedAt),
                WorkflowSimTags.CLOUDLET_UPDATE);
        CBMWLogger.logf("VM-PROVISION-ORDERED",
                "on-demand vm=%d task=%d orderedAt=%.1f readyAt=%.1f",
                vmId, taskId, orderedAt, readyAt);
        return vm;
    }

    /** Paper model: a container is ready exactly OPD seconds after ordering. */
    protected double projectedOnDemandReadyTime(double orderTime) {
        return orderTime + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
    }

    /** Allows baselines without CBMW admission control to accept every arrival. */
    protected boolean negotiateWorkflow(WorkflowRecord wfr) {
        return negotiation.negotiate(wfr);
    }

    /** Runs after static planning when a broker needs the final plan to quote. */
    protected void finalizeWorkflowNegotiation(WorkflowRecord wfr) {}

    private boolean isSchedulingMoment() {
        double period = HybridVmPool.SCHEDULING_PERIOD;
        if (period <= 0.0) return true;
        double now = CloudSim.clock();
        double nextTick = Math.ceil((now - EPS) / period) * period;
        if (nextTick > now + EPS) {
            schedule(getId(), nextTick - now, WorkflowSimTags.CLOUDLET_UPDATE);
            return false;
        }
        return true;
    }

    private List<Job> wrapTasksAsJobs(List<Task> tasks, WorkflowRecord wfr) {
        List<Job> jobs = new ArrayList<>();
        Map<Integer, Job> byId = new HashMap<>();

        for (Task t : tasks) {
            Job job = new Job(t.getCloudletId(), t.getCloudletLength());
            job.setNumberOfPes(wfr.getTaskCores(t.getCloudletId()));
            job.setUserId(getId());
            job.setVmId(t.getVmId());
            List<Task> tl = new ArrayList<>();
            tl.add(t);
            job.setTaskList(tl);
            byId.put(t.getCloudletId(), job);
            jobs.add(job);
        }

        for (Task t : tasks) {
            Job job = byId.get(t.getCloudletId());
            for (Task parent : t.getParentList()) {
                Job pj = byId.get(parent.getCloudletId());
                if (pj != null) job.addParent(pj);
            }
            for (Task child : t.getChildList()) {
                Job cj = byId.get(child.getCloudletId());
                if (cj != null) job.addChild(cj);
            }
        }
        return jobs;
    }

    protected void updateWorkflowCompletion(Job job) {
        int wfId = workflowIdForJob(job);
        WorkflowRecord wfr = activeWorkflows.get(wfId);
        if (wfr == null) return;

        double now = CloudSim.clock();
        if (now > wfr.getCompletionTime() || wfr.getCompletionTime() == Double.MAX_VALUE) {
            wfr.setCompletionTime(now);
        }
        int taskId = primaryTaskId(job);
        wfr.markTaskCompleted(taskId);
        onWorkflowTaskComplete(wfr, taskId, now);

        if (wfr.isComplete()) {
            boolean met = wfr.getCompletionTime() <= wfr.getDeadline();
            wfr.setDeadlineMet(met);
            accounting.markWorkflowComplete(wfr, tightness);
            activeWorkflows.remove(wfId);
            CBMWLogger.log("WF-COMPLETE",
                    String.format("wf=%d completionTime=%.4f deadline=%.4f -> %s",
                            wfId, wfr.getCompletionTime(), wfr.getDeadline(),
                            met ? "MET" : "MISSED"));
            WorkflowEngine eng = getWorkflowEngineRef();
            if (eng != null) eng.notifyWorkflowDisposed();
        }
    }

    /** Algorithm-specific feedback after a task's actual completion is known. */
    protected void onWorkflowTaskComplete(WorkflowRecord wfr, int taskId,
                                          double finishTime) {}

    private WorkflowEngine getWorkflowEngineRef() {
        try {
            return (WorkflowEngine) CloudSim.getEntity(workflowEngineId);
        } catch (Exception e) {
            return null;
        }
    }
}
