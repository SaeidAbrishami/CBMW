package org.workflowsim.cbmw;

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
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;

/**
 * Central CBMW broker that wires together the four modules and handles
 * CloudSim events for multiple dynamically-arriving workflows.
 *
 * Architecture: CBMWBroker IS-A WorkflowScheduler. It overrides
 * processCloudletUpdate and processCloudletReturn to inject the CBMW
 * algorithm, and adds a WORKFLOW_ARRIVE handler.
 */
public class CBMWBroker extends WorkflowScheduler {

    private final HybridVmPool vmPool;
    private final NegotiationModule negotiation;
    private final ProvisioningModule provisioner;
    private final CBMWDynamicSchedulingAlgorithm dynamicScheduler;

    /** workflowId -> WorkflowRecord for currently active (not yet complete) workflows. */
    private final Map<Integer, WorkflowRecord> activeWorkflows = new HashMap<>();
    /** All workflows that have been seen (accepted or rejected). */
    private final List<WorkflowRecord> allWorkflows = new ArrayList<>();

    private int nextWorkflowId = 0;
    private int workflowEngineId = -1;

    // Arrival schedule — populated before startSimulation(), fired in startEntity()
    private final List<WorkflowArrivalData> pendingArrivals    = new ArrayList<>();
    private final List<Double>              pendingArrivalTimes = new ArrayList<>();
    private double simEndTime = -1;

    public CBMWBroker(String name, double tightness) throws Exception {
        super(name);
        this.vmPool      = new HybridVmPool(getId());
        this.negotiation = new NegotiationModule(tightness);
        this.provisioner = new ProvisioningModule(vmPool, getId());
        this.dynamicScheduler = new CBMWDynamicSchedulingAlgorithm(
                vmPool, activeWorkflows, provisioner);
    }

    /** Called before startSimulation() to register a workflow arrival. */
    public void addArrival(double time, String daxPath, double userDeadline) {
        pendingArrivalTimes.add(time);
        pendingArrivals.add(new WorkflowArrivalData(daxPath, time, userDeadline));
    }

    /** Called before startSimulation() to set when the simulation should end. */
    public void setSimEndTime(double time) {
        this.simEndTime = time;
    }

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

    public HybridVmPool getVmPool() { return vmPool; }
    public List<WorkflowRecord> getAllWorkflows() { return allWorkflows; }

    // -----------------------------------------------------------------------
    // Event dispatch
    // -----------------------------------------------------------------------

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case WorkflowSimTags.WORKFLOW_ARRIVE:
                processWorkflowArrival(ev);
                break;
            case WorkflowSimTags.SIM_END:
                processSimEnd();
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Workflow arrival
    // -----------------------------------------------------------------------

    private void processWorkflowArrival(SimEvent ev) {
        WorkflowArrivalData data = (WorkflowArrivalData) ev.getData();
        int wfId = nextWorkflowId++;

        WorkflowEngine engine = getWorkflowEngineRef();
        if (engine != null) engine.notifyWorkflowArriving();

        // Parse the DAX file into tasks
        List<Task> tasks = parseDax(data.getDaxPath(), wfId);
        if (tasks == null || tasks.isEmpty()) {
            Log.printLine("CBMW: failed to parse " + data.getDaxPath());
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        WorkflowRecord wfr = new WorkflowRecord(wfId, data.getDaxPath(), data.getArrivalTime());
        wfr.setTaskList(tasks);
        wfr.setDeadline(data.getUserDeadline());   // deadline comes from the submitter
        allWorkflows.add(wfr);

        // Module 1: negotiate — checks cp * BETA <= (deadline - arrivalTime)
        if (!negotiation.negotiate(wfr)) {
            Log.printLine(CloudSim.clock() + ": CBMW: workflow " + wfId + " rejected");
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        // Module 2: static planning
        CBMWStaticPlanningAlgorithm planner =
                new CBMWStaticPlanningAlgorithm(wfr, vmPool, negotiation);
        planner.setTaskList(tasks);
        planner.setVmList(vmPool.getAllVms());
        try {
            planner.run();
        } catch (Exception e) {
            Log.printLine("CBMW: static planner error for workflow " + wfId);
            e.printStackTrace();
            if (engine != null) engine.notifyWorkflowDisposed();
            return;
        }

        // Wrap tasks into Jobs (1-task-per-job, no clustering)
        List<Job> jobs = wrapTasksAsJobs(tasks, wfr);

        // Register in active map and enqueue in WorkflowEngine
        activeWorkflows.put(wfId, wfr);
        sendNow(workflowEngineId, WorkflowSimTags.JOB_SUBMIT, jobs);

        Log.printLine(CloudSim.clock() + ": CBMW: workflow " + wfId
                + " accepted, deadline=" + String.format("%.1f", wfr.getDeadline())
                + ", tasks=" + tasks.size());
    }

    // -----------------------------------------------------------------------
    // Periodic dynamic scheduling (Module 3)
    // -----------------------------------------------------------------------

    @Override
    protected void processCloudletUpdate(SimEvent ev) {
        dynamicScheduler.setCloudletList(getCloudletList());
        dynamicScheduler.setVmList(getVmsCreatedList());
        dynamicScheduler.getScheduledList().clear();

        try {
            dynamicScheduler.run();
        } catch (Exception e) {
            Log.printLine("CBMW dynamic scheduler error");
            e.printStackTrace();
        }

        List<Cloudlet> scheduled = dynamicScheduler.getScheduledList();
        for (Cloudlet cl : scheduled) {
            int vmId = cl.getVmId();
            Integer dcId = getVmsToDatacentersMap().get(vmId);
            if (dcId == null) {
                // on-demand VM not yet registered — register it now
                CondorVM vm = vmPool.getVmById(vmId);
                if (vm != null) {
                    List<CondorVM> tmp = new ArrayList<>();
                    tmp.add(vm);
                    submitVmList(tmp);
                    createVmsInDatacenter(getDatacenterIdsList().get(0));
                }
                // Re-queue the cloudlet for next tick
                continue;
            }
            double delay = Parameters.getOverheadParams().getQueueDelay() != null
                    ? Parameters.getOverheadParams().getQueueDelay(cl) : 0.0;
            schedule(dcId, delay, CloudSimTags.CLOUDLET_SUBMIT, cl);
        }
        getCloudletList().removeAll(scheduled);
        getCloudletSubmittedList().addAll(scheduled);
        cloudletsSubmitted += scheduled.size();
    }

    // -----------------------------------------------------------------------
    // Job completion (Module 4 cleanup + deadline tracking)
    // -----------------------------------------------------------------------

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cl = (Cloudlet) ev.getData();
        Job job = (Job) cl;

        getCloudletReceivedList().add(cl);
        getCloudletSubmittedList().remove(cl);
        cloudletsSubmitted--;

        // Release VM
        CondorVM vm = (CondorVM) VmList.getById(getVmsCreatedList(), cl.getVmId());
        if (vm != null) vm.setState(WorkflowSimTags.VM_STATUS_IDLE);

        // Module 4: terminate on-demand VM if no more work
        if (provisioner.isOnDemandVm(cl.getVmId())) {
            provisioner.jobCompleted(cl.getCloudletId());
            // Record on-demand cost for this job
            int wfId = getWorkflowIdForJob(job);
            WorkflowRecord wfr = activeWorkflows.get(wfId);
            if (wfr != null) {
                wfr.addOnDemandCost(cl.getActualCPUTime() * HybridVmPool.ON_DEMAND_PER_SEC);
            }
        }

        // Track workflow completion
        updateWorkflowCompletion(job);

        double delay = Parameters.getOverheadParams().getPostDelay() != null
                ? Parameters.getOverheadParams().getPostDelay(job) : 0.0;
        schedule(workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, cl);

        schedule(getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    // -----------------------------------------------------------------------
    // Simulation end
    // -----------------------------------------------------------------------

    private void processSimEnd() {
        // Tell WorkflowEngine that no more workflows will arrive
        WorkflowEngine engine = getWorkflowEngineRef();
        if (engine != null) {
            engine.setSimulationComplete(true);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorkflowEngine getWorkflowEngineRef() {
        try {
            return (WorkflowEngine) CloudSim.getEntity(workflowEngineId);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Task> parseDax(String daxPath, int wfId) {
        try {
            Parameters.setDaxPath(daxPath);
            WorkflowParser p = new WorkflowParser(getId());
            p.parse();
            return p.getTaskList();
        } catch (Exception e) {
            Log.printLine("CBMW: DAX parse error: " + e.getMessage());
            return null;
        }
    }

    private List<Job> wrapTasksAsJobs(List<Task> tasks, WorkflowRecord wfr) {
        List<Job> jobs = new ArrayList<>();
        Map<Integer, Job> taskIdToJob = new HashMap<>();

        for (Task t : tasks) {
            Job job = new Job(t.getCloudletId(), t.getCloudletLength());
            job.setUserId(getId());
            job.setVmId(t.getVmId());
            List<Task> tl = new ArrayList<>();
            tl.add(t);
            job.setTaskList(tl);
            taskIdToJob.put(t.getCloudletId(), job);
            jobs.add(job);
        }

        // Wire parent-child relationships at the Job level
        for (Task t : tasks) {
            Job job = taskIdToJob.get(t.getCloudletId());
            for (Task parent : t.getParentList()) {
                Job parentJob = taskIdToJob.get(parent.getCloudletId());
                if (parentJob != null) job.addParent(parentJob);
            }
            for (Task child : t.getChildList()) {
                Job childJob = taskIdToJob.get(child.getCloudletId());
                if (childJob != null) job.addChild(childJob);
            }
        }
        return jobs;
    }

    private int getWorkflowIdForJob(Job job) {
        if (!job.getTaskList().isEmpty()) {
            return job.getTaskList().get(0).getWorkflowId();
        }
        return -1;
    }

    private void updateWorkflowCompletion(Job job) {
        int wfId = getWorkflowIdForJob(job);
        WorkflowRecord wfr = activeWorkflows.get(wfId);
        if (wfr == null) return;

        double finishTime = CloudSim.clock();
        if (finishTime > wfr.getCompletionTime() || wfr.getCompletionTime() == Double.MAX_VALUE) {
            wfr.setCompletionTime(finishTime);
        }

        // Check if all tasks of this workflow are done
        boolean allDone = true;
        for (Task t : wfr.getTaskList()) {
            boolean found = getCloudletReceivedList().stream()
                    .anyMatch(cl -> cl.getCloudletId() == t.getCloudletId());
            if (!found) { allDone = false; break; }
        }

        if (allDone) {
            wfr.setDeadlineMet(wfr.getCompletionTime() <= wfr.getDeadline());
            activeWorkflows.remove(wfId);
            Log.printLine(CloudSim.clock() + ": CBMW: workflow " + wfId
                    + (wfr.isDeadlineMet() ? " MET" : " MISSED") + " deadline "
                    + String.format("%.1f", wfr.getDeadline()));
            WorkflowEngine eng = getWorkflowEngineRef();
            if (eng != null) eng.notifyWorkflowDisposed();
        }
    }
}
