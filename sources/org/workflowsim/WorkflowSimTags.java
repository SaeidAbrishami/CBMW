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

/**
 * This WorkflowSimTags include tags that are not supported in CloudSimTags
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowSimTags {

    /**
     * Starting constant value for cloud-related tags *
     */
    private static final int BASE = 1000;
    /**
     * VM Status is ready (not used)
     */
    public static final int VM_STATUS_READY = BASE + 2;
    /**
     * VM Status is busy (no jobs should run on this vm)
     */
    public static final int VM_STATUS_BUSY = BASE + 3;
    /**
     * VM Status is idle (a job can run on this vm)
     */
    public static final int VM_STATUS_IDLE = BASE + 4;
    public static final int START_SIMULATION = BASE + 0;
    public static final int JOB_SUBMIT = BASE + 1;
    public static final int CLOUDLET_UPDATE = BASE + 5;
    public static final int CLOUDLET_CHECK = BASE + 6;
    public static final int WORKFLOW_ARRIVE  = BASE + 7;
    public static final int VM_PROVISION_ACK = BASE + 8;
    public static final int VM_TERMINATE     = BASE + 9;
    public static final int SIM_END          = BASE + 10;
    /** Completion of a lightweight paper-style on-demand container task. */
    public static final int ON_DEMAND_TASK_COMPLETE = BASE + 11;
    /** Completion of a CEWB logical spot-instance attempt. */
    public static final int CEWB_SPOT_TASK_COMPLETE = BASE + 12;
    /** Interruption of a CEWB logical spot-instance attempt. */
    public static final int CEWB_SPOT_TASK_INTERRUPTED = BASE + 13;
    /** StaticGreedy's pre-planned order time for a dedicated on-demand container. */
    public static final int STATIC_GREEDY_ON_DEMAND_ORDER = BASE + 14;
    /** DynamicGreedy task reaching its OPD-adjusted latest-start threshold. */
    public static final int DYNAMIC_GREEDY_SST_REACHED = BASE + 15;
    /** Deduplicated wake-up for StaticGreedy's next planned task start. */
    public static final int STATIC_GREEDY_SCHEDULE_WAKE = BASE + 16;
    /** CEWB ready task reaching its exact on-demand safe-start threshold. */
    public static final int CEWB_SST_REACHED = BASE + 17;

    /**
     * Private Constructor
     */
    private WorkflowSimTags() {
        throw new UnsupportedOperationException("WorkflowSim Tags cannot be instantiated");
    }
}
