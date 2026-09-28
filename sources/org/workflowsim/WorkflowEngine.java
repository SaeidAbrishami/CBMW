/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.reclustering.ReclusteringEngine;
import org.workflowsim.utils.Parameters;

/**
 * WorkflowEngine represents a engine acting on behalf of a user. It hides VM
 * management, as vm creation, submission of cloudlets to this VMs and
 * destruction of VMs.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public final class WorkflowEngine extends SimEntity {

    /**
     * The job list.
     */
    protected List<? extends Cloudlet> jobsList;
    /**
     * The job submitted list.
     */
    protected List<? extends Cloudlet> jobsSubmittedList;
    /**
     * The job received list.
     */
    protected List<? extends Cloudlet> jobsReceivedList;
    private final Set<Integer> jobsReceivedIds = new HashSet<>();
    private boolean indexedDependencies;
    private final Map<Integer, Integer> unresolvedParents = new HashMap<>();
    private final Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
    private final Map<Integer, Long> jobArrivalOrder = new HashMap<>();
    private long nextJobOrder;
    private final TreeSet<Integer> newlyReadyJobs = new TreeSet<>((a, b) -> {
        int order = Long.compare(jobArrivalOrder.get(a), jobArrivalOrder.get(b));
        return order != 0 ? order : Integer.compare(a, b);
    });
    /**
     * The job submitted.
     */
    protected int jobsSubmitted;
    protected List<? extends Vm> vmList;
    protected boolean simulationComplete = false;
    /** Counts workflows that have arrived but not yet fully disposed (rejected or all jobs returned). */
    private int pendingWorkflowCount = 0;
    /**
     * The associated scheduler id*
     */
    private List<Integer> schedulerId;
    private List<WorkflowScheduler> scheduler;

    /**
     * Created a new WorkflowEngine object.
     *
     * @param name name to be associated with this entity (as required by
     * Sim_entity class from simjava package)
     * @throws Exception the exception
     * @pre name != null
     * @post $none
     */
    public WorkflowEngine(String name) throws Exception {
        this(name, 1);
    }

    public WorkflowEngine(String name, int schedulers) throws Exception {
        super(name);

        setJobsList(new ArrayList<>());
        setJobsSubmittedList(new ArrayList<>());
        setJobsReceivedList(new ArrayList<>());

        jobsSubmitted = 0;

        setSchedulers(new ArrayList<>());
        setSchedulerIds(new ArrayList<>());

        for (int i = 0; i < schedulers; i++) {
            WorkflowScheduler wfs = new WorkflowScheduler(name + "_Scheduler_" + i);
            getSchedulers().add(wfs);
            getSchedulerIds().add(wfs.getId());
            wfs.setWorkflowEngineId(this.getId());
        }
    }

    /**
     * Replaces the auto-created internal scheduler with an external one (e.g. CBMWBroker).
     * Must be called before startSimulation().
     */
    public void replaceScheduler(WorkflowScheduler externalScheduler) {
        getSchedulers().clear();
        getSchedulerIds().clear();
        getSchedulers().add(externalScheduler);
        getSchedulerIds().add(externalScheduler.getId());
        externalScheduler.setWorkflowEngineId(getId());
    }

    /** Index dependencies for large, event-driven greedy workflow runs. */
    public void enableIndexedDependencies() {
        if (!getJobsList().isEmpty()) {
            throw new IllegalStateException("Enable dependency indexing before arrivals");
        }
        indexedDependencies = true;
        setJobsList(new IndexedJobList());
    }

    /**
     * This method is used to send to the broker the list with virtual machines
     * that must be created.
     *
     * @param list the list
     * @param schedulerId the scheduler id
     */
    public void submitVmList(List<? extends Vm> list, int schedulerId) {
        getScheduler(schedulerId).submitVmList(list);
    }

    public void submitVmList(List<? extends Vm> list) {
        //bug here, not sure whether we should have different workflow schedulers
        getScheduler(0).submitVmList(list);
        setVmList(list);
    }
    
    public List<? extends Vm> getAllVmList(){
        if(this.vmList != null && !this.vmList.isEmpty()){
            return this.vmList;
        }
        else{
            List list = new ArrayList();
            for(int i = 0;i < getSchedulers().size();i ++){
                list.addAll(getScheduler(i).getVmList());
            }
            return list;
        }
    }

    /**
     * This method is used to send to the broker the list of cloudlets.
     *
     * @param list the list
     */
    public void submitCloudletList(List<? extends Cloudlet> list) {
        getJobsList().addAll(list);
        if (indexedDependencies) indexJobs(list);
    }

    /**
     * Processes events available for this Broker.
     *
     * @param ev a SimEvent object
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            // Resource characteristics request
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;
            //this call is from workflow scheduler when all vms are created
            case CloudSimTags.CLOUDLET_SUBMIT:
                submitJobs();
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processJobReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                processJobSubmit(ev);
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * Process a request for the characteristics of a PowerDatacenter.
     *
     * @param ev a SimEvent object
     */
    protected void processResourceCharacteristicsRequest(SimEvent ev) {
        for (int i = 0; i < getSchedulerIds().size(); i++) {
            schedule(getSchedulerId(i), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
        }
    }

    /**
     * Binds a scheduler with a datacenter.
     *
     * @param datacenterId the data center id
     * @param schedulerId the scheduler id
     */
    public void bindSchedulerDatacenter(int datacenterId, int schedulerId) {
        getScheduler(schedulerId).bindSchedulerDatacenter(datacenterId);
    }

    /**
     * Binds a datacenter to the default scheduler (id=0)
     *
     * @param datacenterId dataceter Id
     */
    public void bindSchedulerDatacenter(int datacenterId) {
        bindSchedulerDatacenter(datacenterId, 0);
    }
   
    /**
     * Process a submit event
     *
     * @param ev a SimEvent object
     */
    protected void processJobSubmit(SimEvent ev) {
        List<? extends Cloudlet> list = (List) ev.getData();
        getJobsList().addAll(list);
        if (indexedDependencies) indexJobs(list);
        sendNow(getId(), CloudSimTags.CLOUDLET_SUBMIT, null);
    }

    /**
     * Process a job return event.
     *
     * @param ev a SimEvent object
     * @pre ev != $null
     * @post $none
     */
    protected void processJobReturn(SimEvent ev) {

        Job job = (Job) ev.getData();
        if (job.getCloudletStatus() == Cloudlet.FAILED) {
            // Reclusteringengine will add retry job to jobList
            int newId = getJobsList().size() + getJobsSubmittedList().size();
            List<Job> retryJobs = ReclusteringEngine.process(job, newId);
            getJobsList().addAll(retryJobs);
            if (indexedDependencies) indexJobs(retryJobs);
        }

        getJobsReceivedList().add(job);
        jobsReceivedIds.add(job.getCloudletId());
        if (indexedDependencies) releaseIndexedChildren(job.getCloudletId());
        jobsSubmitted--;
        if (getJobsList().isEmpty() && jobsSubmitted == 0 && simulationComplete) {
            tryTerminate();
        } else if (indexedDependencies ? !newlyReadyJobs.isEmpty()
                : !getJobsList().isEmpty() || jobsSubmitted > 0) {
            sendNow(this.getId(), CloudSimTags.CLOUDLET_SUBMIT, null);
        }
    }

    /** Called by the broker when a workflow arrives (accepted or rejected). */
    public void notifyWorkflowArriving() {
        pendingWorkflowCount++;
    }

    /** Called by the broker when a workflow is fully disposed (rejected or all jobs returned). */
    public void notifyWorkflowDisposed() {
        pendingWorkflowCount--;
        tryTerminate();
    }

    public void setSimulationComplete(boolean complete) {
        this.simulationComplete = complete;
        tryTerminate();
    }

    private void tryTerminate() {
        if (simulationComplete && getJobsList().isEmpty()
                && jobsSubmitted == 0 && pendingWorkflowCount == 0) {
            for (int i = 0; i < getSchedulerIds().size(); i++) {
                sendNow(getSchedulerId(i), CloudSimTags.END_OF_SIMULATION, null);
            }
        }
    }

    /**
     * Overrides this method when making a new and different type of Broker.
     * This method is called by {@link #body()} for incoming unknown tags.
     *
     * @param ev a SimEvent object
     */
    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }
        Log.printLine(getName() + ".processOtherEvent(): "
                + "Error - event unknown by this DatacenterBroker.");
    }

    /**
     * Checks whether a job list contains a id
     *
     * @param jobList the job list
     * @param id the job id
     * @return
     */
    private boolean hasJobBeenReceived(int id) {
        return jobsReceivedIds.contains(id);
    }

    /**
     * Submit jobs to the created VMs.
     *
     * @pre $none
     * @post $none
     */
    protected void submitJobs() {

        List<Job> list = getJobsList();
        Map<Integer, List> allocationList = new HashMap<>();
        for (int i = 0; i < getSchedulers().size(); i++) {
            List<Job> submittedList = new ArrayList<>();
            allocationList.put(getSchedulerId(i), submittedList);
        }
        if (indexedDependencies) {
            IndexedJobList pending = (IndexedJobList) jobsList;
            for (int jobId : newlyReadyJobs) {
                Job job = pending.removeById(jobId);
                if (job == null) continue;
                unresolvedParents.remove(jobId);
                jobArrivalOrder.remove(jobId);
                allocationList.get(job.getUserId()).add(job);
                jobsSubmitted++;
                getJobsSubmittedList().add(job);
            }
            newlyReadyJobs.clear();
        } else {
            for (Iterator<Job> it = list.iterator(); it.hasNext();) {
                Job job = it.next();
                //Dont use job.isFinished() it is not right
                if (!hasJobBeenReceived(job.getCloudletId())) {
                    List<Job> parentList = job.getParentList();
                    boolean flag = true;
                    for (Job parent : parentList) {
                        if (!hasJobBeenReceived(parent.getCloudletId())) {
                            flag = false;
                            break;
                        }
                    }
                    /**
                     * This job's parents have all completed successfully. Should
                     * submit.
                     */
                    if (flag) {
                        List submittedList = allocationList.get(job.getUserId());
                        submittedList.add(job);
                        jobsSubmitted++;
                        getJobsSubmittedList().add(job);
                        it.remove();
                    }
                }
            }
        }
        /**
         * If we have multiple schedulers. Divide them equally.
         */
        for (int i = 0; i < getSchedulers().size(); i++) {

            List submittedList = allocationList.get(getSchedulerId(i));
            //divid it into sublist

            int interval = Parameters.getOverheadParams().getWEDInterval();
            double delay = 0.0;
            if(Parameters.getOverheadParams().getWEDDelay()!=null){
                delay = Parameters.getOverheadParams().getWEDDelay(submittedList);
            }

            double delaybase = delay;
            int size = submittedList.size();
            if (interval > 0 && interval <= size) {
                int index = 0;
                List subList = new ArrayList();
                while (index < size) {
                    subList.add(submittedList.get(index));
                    index++;
                    if (index % interval == 0) {
                        //create a new one
                        schedule(getSchedulerId(i), delay, CloudSimTags.CLOUDLET_SUBMIT, subList);
                        delay += delaybase;
                        subList = new ArrayList();
                    }
                }
                if (!subList.isEmpty()) {
                    schedule(getSchedulerId(i), delay, CloudSimTags.CLOUDLET_SUBMIT, subList);
                }
            } else if (!submittedList.isEmpty()) {
                sendNow(this.getSchedulerId(i), CloudSimTags.CLOUDLET_SUBMIT, submittedList);
            }
        }
    }

    private void indexJobs(List<? extends Cloudlet> jobs) {
        for (Cloudlet cl : jobs) {
            Job job = (Job) cl;
            int jobId = job.getCloudletId();
            jobArrivalOrder.put(jobId, nextJobOrder++);
            int remaining = 0;
            for (Object parentObject : job.getParentList()) {
                int parentId = ((Job) parentObject).getCloudletId();
                if (jobsReceivedIds.contains(parentId)) continue;
                remaining++;
                childrenByParent.computeIfAbsent(parentId,
                        ignored -> new ArrayList<>()).add(jobId);
            }
            unresolvedParents.put(jobId, remaining);
            if (remaining == 0) newlyReadyJobs.add(jobId);
        }
    }

    private void releaseIndexedChildren(int parentId) {
        List<Integer> children = childrenByParent.remove(parentId);
        if (children == null) return;
        for (int childId : children) {
            Integer remaining = unresolvedParents.get(childId);
            if (remaining == null) continue;
            if (remaining == 1) {
                unresolvedParents.put(childId, 0);
                newlyReadyJobs.add(childId);
            } else {
                unresolvedParents.put(childId, remaining - 1);
            }
        }
    }

    /** Pending jobs with insertion-order iteration and constant-time removal. */
    private static final class IndexedJobList extends AbstractList<Cloudlet> {
        private final LinkedHashMap<Integer, Job> jobs = new LinkedHashMap<>();

        @Override
        public int size() { return jobs.size(); }

        @Override
        public Cloudlet get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            Iterator<Job> it = jobs.values().iterator();
            for (int i = 0; i < index; i++) it.next();
            return it.next();
        }

        @Override
        public void add(int index, Cloudlet cloudlet) {
            if (index != size()) throw new UnsupportedOperationException(
                    "Indexed jobs can only be appended");
            Job job = (Job) cloudlet;
            jobs.put(job.getCloudletId(), job);
        }

        Job removeById(int jobId) { return jobs.remove(jobId); }

        @Override
        public Iterator<Cloudlet> iterator() {
            Iterator<Job> it = jobs.values().iterator();
            return new Iterator<Cloudlet>() {
                public boolean hasNext() { return it.hasNext(); }
                public Cloudlet next() { return it.next(); }
                public void remove() { it.remove(); }
            };
        }
    }

    /*
     * (non-Javadoc)
     * @see cloudsim.core.SimEntity#shutdownEntity()
     */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /*
     * (non-Javadoc)
     * @see cloudsim.core.SimEntity#startEntity()
     * Here we creata a message when it is started
     */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    /**
     * Gets the job list.
     *
     * @param <T> the generic type
     * @return the job list
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsList() {
        return (List<T>) jobsList;
    }

    /**
     * Sets the job list.
     *
     * @param <T> the generic type
     * @param cloudletList the new job list
     */
    private <T extends Cloudlet> void setJobsList(List<T> jobsList) {
        this.jobsList = jobsList;
    }

    /**
     * Gets the job submitted list.
     *
     * @param <T> the generic type
     * @return the job submitted list
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsSubmittedList() {
        return (List<T>) jobsSubmittedList;
    }

    /**
     * Sets the job submitted list.
     *
     * @param <T> the generic type
     * @param jobsSubmittedList the new job submitted list
     */
    private <T extends Cloudlet> void setJobsSubmittedList(List<T> jobsSubmittedList) {
        this.jobsSubmittedList = jobsSubmittedList;
    }

    /**
     * Gets the job received list.
     *
     * @param <T> the generic type
     * @return the job received list
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsReceivedList() {
        return (List<T>) jobsReceivedList;
    }

    /**
     * Sets the job received list.
     *
     * @param <T> the generic type
     * @param cloudletReceivedList the new job received list
     */
    private <T extends Cloudlet> void setJobsReceivedList(List<T> jobsReceivedList) {
        this.jobsReceivedList = jobsReceivedList;
    }

    /**
     * Gets the vm list.
     *
     * @param <T> the generic type
     * @return the vm list
     */
    @SuppressWarnings("unchecked")
    public <T extends Vm> List<T> getVmList() {
        return (List<T>) vmList;
    }

    /**
     * Sets the vm list.
     *
     * @param <T> the generic type
     * @param vmList the new vm list
     */
    private <T extends Vm> void setVmList(List<T> vmList) {
        this.vmList = vmList;
    }

    /**
     * Gets the schedulers.
     *
     * @return the schedulers
     */
    public List<WorkflowScheduler> getSchedulers() {
        return this.scheduler;
    }

    /**
     * Sets the scheduler list.
     *
     * @param <T> the generic type
     * @param vmList the new scheduler list
     */
    private void setSchedulers(List list) {
        this.scheduler = list;
    }

    /**
     * Gets the scheduler id.
     *
     * @return the scheduler id
     */
    public List<Integer> getSchedulerIds() {
        return this.schedulerId;
    }

    /**
     * Sets the scheduler id list.
     *
     * @param <T> the generic type
     * @param vmList the new scheduler id list
     */
    private void setSchedulerIds(List list) {
        this.schedulerId = list;
    }

    /**
     * Gets the scheduler id list.
     *
     * @param index
     * @return the scheduler id list
     */
    public int getSchedulerId(int index) {
        if (this.schedulerId != null) {
            return this.schedulerId.get(index);
        }
        return 0;
    }

    /**
     * Gets the scheduler .
     *
     * @param schedulerId
     * @return the scheduler
     */
    public WorkflowScheduler getScheduler(int schedulerId) {
        if (this.scheduler != null) {
            return this.scheduler.get(schedulerId);
        }
        return null;
    }
}
