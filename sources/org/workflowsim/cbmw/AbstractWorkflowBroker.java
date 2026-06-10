package org.workflowsim.cbmw;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.lists.VmList;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowParser;
import org.workflowsim.WorkflowScheduler;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.Parameters;

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

    protected final Map<Integer, WorkflowRecord> activeWorkflows = new HashMap<>();
    protected final List<WorkflowRecord>         allWorkflows    = new ArrayList<>();

    protected int nextWorkflowId  = 0;
    protected int nextTaskId      = 1;
    protected int workflowEngineId = -1;

    // Counts VM_CREATE_ACKs received for reserved VMs (success + failure).
    // The scheduler is blocked until this reaches NUM_RESERVED, guaranteeing
    // the pool is fully initialised before any task is dispatched.
    private int reservedVmsAcknowledged = 0;

    // On-demand VMs waiting for their OPD to expire before being registered.
    // vmId -> simTime at which the VM becomes available (now + OPD when ordered).
    private final Map<Integer, Double> pendingVmCreations = new HashMap<>();

    private final List<WorkflowArrivalData> pendingArrivals     = new ArrayList<>();
    private final List<Double>              pendingArrivalTimes = new ArrayList<>();
    private double simEndTime = -1;

    protected AbstractWorkflowBroker(String name, double tightness) throws Exception {
        super(name);
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
            case CloudSimTags.VM_DESTROY_ACK:
                break; // on-demand VM destroyed in datacenter; PE slot freed
            case WorkflowSimTags.CLOUDLET_UPDATE:
                // Block scheduling until every reserved VM has received a
                // creation response (success or failure). This prevents the
                // scheduler from setting a VM BUSY before the datacenter has
                // registered it, which would permanently strand that VM.
                if (reservedVmsAcknowledged < HybridVmPool.NUM_RESERVED) return;
                processPendingVmCreations();
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

        CondorVM vm = (CondorVM) VmList.getById(getVmsCreatedList(), cl.getVmId());
        if (vm != null) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);

        int wfId = workflowIdForJob(job);
        if (provisioner.isOnDemandVm(cl.getVmId())) {
            provisioner.jobCompleted(cl.getCloudletId());
            WorkflowRecord wfr = activeWorkflows.get(wfId);
            double cost = cl.getActualCPUTime() * HybridVmPool.ON_DEMAND_PER_SEC;
            if (wfr != null) wfr.addOnDemandCost(cost);
            CBMWLogger.log("TASK-COMPLETE",
                    String.format("task=%d wf=%d vm=%d(on-demand) actualCPU=%.4fs cost=$%.6f",
                            cl.getCloudletId(), wfId, cl.getVmId(), cl.getActualCPUTime(), cost));
        } else {
            onTaskComplete(cl);
            WorkflowRecord wfrRes = activeWorkflows.get(wfId);
            if (wfrRes != null) wfrRes.addReservedCpuTime(cl.getActualCPUTime());
            CBMWLogger.log("TASK-COMPLETE",
                    String.format("task=%d wf=%d vm=%d(reserved) actualCPU=%.4fs",
                            cl.getCloudletId(), wfId, cl.getVmId(), cl.getActualCPUTime()));
        }

        updateWorkflowCompletion(job);

        double delay = Parameters.getOverheadParams().getPostDelay() != null
                ? Parameters.getOverheadParams().getPostDelay(job) : 0.0;
        schedule(workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, cl);
        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    /** Called when a reserved-VM task completes. Default no-op. */
    protected void onTaskComplete(Cloudlet cl) {}

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
     * Registers any on-demand VMs whose OPD countdown has expired.
     * Called on every CLOUDLET_UPDATE so VMs become available at exactly
     * readyAt = orderTime + ON_DEMAND_PROVISIONING_DELAY.
     */
    private void processPendingVmCreations() {
        double now = CloudSim.clock();
        List<Integer>  toRemove  = new ArrayList<>();
        List<CondorVM> readyVms  = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : pendingVmCreations.entrySet()) {
            if (now >= e.getValue()) {
                CondorVM vm = vmPool.getVmById(e.getKey());
                if (vm != null && !getVmList().contains(vm)) readyVms.add(vm);
                toRemove.add(e.getKey());
            }
        }
        toRemove.forEach(pendingVmCreations::remove);
        if (!readyVms.isEmpty()) {
            submitVmList(readyVms);
            createVmsInDatacenter(getDatacenterIdsList().get(0));
            CBMWLogger.log("VM-PROVISION-READY",
                    String.format("registered %d on-demand VMs at t=%.1f (opd=%.0fs)",
                            readyVms.size(), now,
                            HybridVmPool.ON_DEMAND_PROVISIONING_DELAY));
        }
    }

    protected void dispatchScheduledJobs(List<Cloudlet> toSchedule) {
        List<Cloudlet> actuallySubmitted = new ArrayList<>();

        for (Cloudlet cl : toSchedule) {
            int vmId = cl.getVmId();
            Integer dcId = getVmsToDatacentersMap().get(vmId);
            if (dcId == null) {
                // VM provisioned but not yet registered with the datacenter.
                // If it hasn't been ordered yet, start the OPD countdown and
                // schedule a wake-up at the exact moment it becomes available.
                // The cloudlet stays in the ready queue and will be retried on
                // the next CLOUDLET_UPDATE (which fires at readyAt + ~0.1s).
                CondorVM provVm = vmPool.getVmById(vmId);
                if (provVm != null
                        && !pendingVmCreations.containsKey(vmId)
                        && !getVmList().contains(provVm)) {
                    double readyAt = CloudSim.clock()
                            + HybridVmPool.ON_DEMAND_PROVISIONING_DELAY;
                    pendingVmCreations.put(vmId, readyAt);
                    schedule(getId(), HybridVmPool.ON_DEMAND_PROVISIONING_DELAY,
                            WorkflowSimTags.CLOUDLET_UPDATE);
                    CBMWLogger.log("VM-PROVISION-ORDERED",
                            String.format("on-demand vm=%d orderedAt=%.1f readyAt=%.1f",
                                    vmId, CloudSim.clock(), readyAt));
                }
                continue;
            }
            double delay = Parameters.getOverheadParams().getQueueDelay() != null
                    ? Parameters.getOverheadParams().getQueueDelay(cl) : 0.0;
            schedule(dcId, delay, CloudSimTags.CLOUDLET_SUBMIT, cl);
            actuallySubmitted.add(cl);
            CBMWLogger.log("DISPATCH", String.format("wf=%d task=%d vm=%d",
                    workflowIdForJob((Job) cl), cl.getCloudletId(), cl.getVmId()));
        }

        getCloudletList().removeAll(actuallySubmitted);
        getCloudletSubmittedList().addAll(actuallySubmitted);
        cloudletsSubmitted += actuallySubmitted.size();
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
        wfr.setDeadline(data.getUserDeadline());
        allWorkflows.add(wfr);

        if (!negotiation.negotiate(wfr)) {
            Log.printLine(CloudSim.clock() + ": " + getName()
                    + ": wf=" + wfId + " rejected");
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        if (!planWorkflow(wfr, tasks)) {
            Log.printLine(CloudSim.clock() + ": " + getName()
                    + ": wf=" + wfId + " planning failed");
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

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
            applyPerturbedRuntimes(daxPath, tasks);
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

    private List<Job> wrapTasksAsJobs(List<Task> tasks, WorkflowRecord wfr) {
        List<Job> jobs = new ArrayList<>();
        Map<Integer, Job> byId = new HashMap<>();

        for (Task t : tasks) {
            Job job = new Job(t.getCloudletId(), t.getCloudletLength());
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

    private void updateWorkflowCompletion(Job job) {
        int wfId = workflowIdForJob(job);
        WorkflowRecord wfr = activeWorkflows.get(wfId);
        if (wfr == null) return;

        double now = CloudSim.clock();
        if (now > wfr.getCompletionTime() || wfr.getCompletionTime() == Double.MAX_VALUE) {
            wfr.setCompletionTime(now);
        }

        boolean allDone = wfr.getTaskList().stream().allMatch(t ->
                getCloudletReceivedList().stream()
                        .anyMatch(cl -> cl.getCloudletId() == t.getCloudletId()));

        if (allDone) {
            boolean met = wfr.getCompletionTime() <= wfr.getDeadline();
            wfr.setDeadlineMet(met);
            activeWorkflows.remove(wfId);
            CBMWLogger.log("WF-COMPLETE",
                    String.format("wf=%d completionTime=%.4f deadline=%.4f -> %s",
                            wfId, wfr.getCompletionTime(), wfr.getDeadline(),
                            met ? "MET" : "MISSED"));
            WorkflowEngine eng = getWorkflowEngineRef();
            if (eng != null) eng.notifyWorkflowDisposed();
        }
    }

    private WorkflowEngine getWorkflowEngineRef() {
        try {
            return (WorkflowEngine) CloudSim.getEntity(workflowEngineId);
        } catch (Exception e) {
            return null;
        }
    }
}
