# Reference Specification for Implementing NOSF in WorkflowSim

## Purpose

This document defines a practical implementation reference for the **NOSF (onliNe multi-workflOw Scheduling Framework)** algorithm in **WorkflowSim**.

The goal is to implement NOSF as a baseline algorithm for online scheduling of multiple deadline-constrained scientific workflows in an IaaS cloud environment.

The implementation should follow the NOSF description used in the CBMW paper: NOSF schedules multiple workflows online, uses only **on-demand VMs**, applies a **greedy heuristic**, and assigns each ready task to the resource that causes the **minimum cost increase** while satisfying the task's assigned **sub-deadline**.

> Note: The exact full formulas from the original NOSF paper are not fully available in the accessible sources used here. Therefore, this file separates:
>
> 1. **confirmed NOSF requirements**, based on the CBMW paper's description of NOSF and the NOSF abstract available online;
> 2. **implementation formulas**, which are practical formulas needed to implement NOSF in WorkflowSim while preserving the described behavior.

---

## 1. Confirmed NOSF Behavior

NOSF is an online multi-workflow scheduling framework for IaaS clouds.

It assumes:

- workflows arrive dynamically and unpredictably;
- future workflow arrivals are unknown;
- workflows are represented as directed acyclic graphs;
- tasks have precedence constraints;
- each workflow has a deadline;
- task execution time is uncertain;
- execution times are modeled as random variables following a normal distribution;
- the scheduling process includes workflow preprocessing, VM allocation, and feedback;
- resources are on-demand VMs;
- the scheduler minimizes VM rental cost;
- each task is scheduled on the VM that produces the minimum cost increase while satisfying its sub-deadline.

---

## 2. WorkflowSim Requirements

Implement the algorithm in WorkflowSim as a custom scheduling algorithm.

Recommended class:

```java
public class NOSFSchedulingAlgorithm extends BaseSchedulingAlgorithm
```

Override:

```java
@Override
public void run() throws Exception
```

Inside the scheduler:

```java
List<Cloudlet> cloudletList = getCloudletList();
List<CondorVM> vmList = getVmList();
```

For each scheduled job:

```java
Job job = (Job) cloudlet;
job.setVmId(selectedVm.getId());
getScheduledList().add(job);
```

The scheduler should only map ready jobs to VMs. WorkflowSim already manages task dependency resolution, so the scheduler normally receives only jobs whose parent jobs have completed.

---

## 3. Resource Model

NOSF must use **on-demand VMs only**.

Do not implement:

- reserved instances;
- spot instances;
- hybrid reserved/on-demand scheduling;
- hybrid spot/on-demand scheduling;
- static reserved-resource planning;
- resource reliability classes.

Each VM should have:

```text
vm_id
mips
number_of_processing_elements
ram
bandwidth
storage
price_per_time_unit
start_time
available_time
planned_shutdown_time
assigned_task_list
```

### 3.1 VM Provisioning Delay

For a faithful NOSF baseline, use:

```text
provisioning_delay = 0
```

That is, when a new on-demand VM is created, it can be considered available at the current simulation time:

```text
new_vm.available_time = current_time
```

If a provisioning delay is added, the algorithm becomes an extended NOSF variant rather than the original baseline.

---

## 4. Workflow Model

Each workflow is represented as:

```text
W_i = (T_i, E_i)
```

where:

```text
T_i = set of tasks in workflow i
E_i = set of dependency edges in workflow i
```

Each workflow has:

```text
arrival_time(W_i)
deadline(W_i)
```

A dependency edge:

```text
(t_a, t_b) ∈ E_i
```

means task `t_b` can start only after task `t_a` has completed.

---

## 5. Task Model

Each task should store:

```text
task_id
workflow_id
parents
children
mean_runtime
standard_deviation
predicted_runtime
earliest_start_time
earliest_finish_time
sub_deadline
assigned_vm
actual_start_time
actual_finish_time
status
```

Task status can be:

```text
WAITING
READY
RUNNING
FINISHED
FAILED_OR_DEADLINE_RISK
```

---

## 6. Execution-Time Uncertainty Formula

The original NOSF model considers task execution time uncertain and models it with a normal distribution.

For task `t`:

```text
X_t ~ N(μ_t, σ_t²)
```

where:

```text
X_t = random execution time of task t
μ_t = mean execution time of task t
σ_t = standard deviation of execution time of task t
```

A practical WorkflowSim implementation may use:

```text
σ_t = 0.1 × μ_t
```

### 6.1 Predicted Runtime

For the simplest baseline:

```text
predicted_runtime(t) = μ_t
```

For a conservative uncertainty-aware version:

```text
predicted_runtime(t) = μ_t + z_α × σ_t
```

where `z_α` is the standard normal quantile.

Common values:

```text
α = 0.90  →  z_α ≈ 1.28
α = 0.95  →  z_α ≈ 1.645
α = 0.99  →  z_α ≈ 2.33
```

So:

```text
predicted_runtime(t) = μ_t + 1.28 × σ_t
```

can be used for approximately 90% confidence.

---

## 7. Earliest Start and Finish Time Formulas

For each task `t`, compute the estimated earliest start time.

For an entry task:

```text
EST(t) = arrival_time(W_i)
```

For a non-entry task:

```text
EST(t) = max EFT(p)
         for all p ∈ parents(t)
```

Then:

```text
EFT(t) = EST(t) + predicted_runtime(t)
```

where:

```text
EST(t) = estimated earliest start time of task t
EFT(t) = estimated earliest finish time of task t
```

If data transfer time is modeled, use:

```text
EST(t) = max [ EFT(p) + transfer_time(p, t) ]
         for all p ∈ parents(t)
```

and then:

```text
EFT(t) = EST(t) + predicted_runtime(t)
```

For a basic WorkflowSim implementation, data transfer time may be set to zero unless the simulation already models file transfer.

---

## 8. Critical Path Formula

The critical path length of a workflow is the length of the longest path from an entry task to an exit task.

For an exit task:

```text
rank_u(t) = predicted_runtime(t)
```

For a non-exit task:

```text
rank_u(t) = predicted_runtime(t)
            + max rank_u(c)
              for all c ∈ children(t)
```

Then:

```text
critical_path(W_i) = max rank_u(e)
                     for all entry tasks e ∈ W_i
```

---

## 9. Sub-Deadline Assignment Formula

NOSF requires each task to have a task-level sub-deadline.

A practical deadline distribution formula is:

```text
sub_deadline(t) =
    arrival_time(W_i)
    + (EFT(t) - arrival_time(W_i))
      / (critical_path(W_i))
      × (deadline(W_i) - arrival_time(W_i))
```

Equivalent form:

```text
sub_deadline(t) =
    arrival_time(W_i)
    + relative_progress(t) × workflow_deadline_span
```

where:

```text
relative_progress(t) =
    (EFT(t) - arrival_time(W_i)) / critical_path(W_i)
```

and:

```text
workflow_deadline_span =
    deadline(W_i) - arrival_time(W_i)
```

This distributes the workflow deadline across tasks according to their earliest finish position in the workflow.

A task assignment is feasible only if:

```text
predicted_finish_time(t, vm) ≤ sub_deadline(t)
```

---

## 10. Ready Task Queue

A task is ready when:

```text
parents(t) are all FINISHED
```

The ready queue should be sorted by ascending sub-deadline:

```text
READY_QUEUE = sort(ready_tasks, by sub_deadline ascending)
```

This gives priority to tasks with tighter timing constraints.

---

## 11. VM Availability Formula

For a task `t` and VM `v`:

```text
start_time(t, v) =
    max(current_time, available_time(v), data_ready_time(t))
```

where:

```text
available_time(v) = time when VM v finishes its current assigned work
data_ready_time(t) = time when all required input data for t is available
```

If data transfer is ignored:

```text
data_ready_time(t) = current_time
```

Then:

```text
finish_time(t, v) =
    start_time(t, v) + predicted_runtime(t, v)
```

If all VMs are homogeneous:

```text
predicted_runtime(t, v) = predicted_runtime(t)
```

If VMs are heterogeneous:

```text
predicted_runtime(t, v) =
    task_length(t) / mips(v)
```

or, if the workflow runtime is already given for a reference machine:

```text
predicted_runtime(t, v) =
    reference_runtime(t) × reference_mips / mips(v)
```

---

## 12. Feasibility Condition

A VM is feasible for task `t` only if:

```text
finish_time(t, v) ≤ sub_deadline(t)
```

If this condition is false, the VM must not be selected for that task.

---

## 13. VM Cost Formula

For a VM `v`:

```text
cost(v) = billing_time(v) × price(v)
```

For second-level billing:

```text
billing_time(v) = shutdown_time(v) - start_time(v)
```

Thus:

```text
cost(v) =
    (shutdown_time(v) - start_time(v)) × price_per_second(v)
```

For minute-level billing:

```text
billing_time(v) =
    ceil((shutdown_time(v) - start_time(v)) / 60) × 60
```

Thus:

```text
cost(v) =
    ceil((shutdown_time(v) - start_time(v)) / 60)
    × price_per_minute(v)
```

---

## 14. Cost Increase Formula

For each candidate VM `v`, compute the cost increase caused by assigning task `t` to VM `v`.

Before assignment:

```text
old_shutdown(v) = planned_shutdown_time(v)
old_cost(v) = billing_cost(start_time(v), old_shutdown(v), price(v))
```

After assignment:

```text
new_shutdown(v) =
    max(old_shutdown(v), finish_time(t, v))
```

```text
new_cost(v) =
    billing_cost(start_time(v), new_shutdown(v), price(v))
```

Then:

```text
cost_increase(t, v) =
    new_cost(v) - old_cost(v)
```

The greedy NOSF selection rule is:

```text
selected_vm =
    argmin cost_increase(t, v)
    over all feasible VMs v
```

subject to:

```text
finish_time(t, v) ≤ sub_deadline(t)
```

If assigning task `t` to an already active VM does not extend the VM billing interval:

```text
cost_increase(t, v) = 0
```

This is the preferred case because the task can be executed using already-paid VM time.

---

## 15. New VM Provisioning Rule

If no existing VM can execute the task before its sub-deadline, provision a new on-demand VM.

For each available VM type `k`:

```text
new_vm_start = current_time + provisioning_delay
```

For faithful NOSF:

```text
provisioning_delay = 0
```

Therefore:

```text
new_vm_start = current_time
```

Then:

```text
new_vm_finish =
    new_vm_start + predicted_runtime(t, vm_type_k)
```

The VM type is feasible if:

```text
new_vm_finish ≤ sub_deadline(t)
```

The selected new VM type is:

```text
selected_vm_type =
    argmin billing_cost(new_vm_start, new_vm_finish, price_k)
    over all feasible VM types k
```

If no VM type can satisfy the sub-deadline, mark the task as deadline-risk or assign it to the fastest available VM depending on the experiment policy.

---

## 16. NOSF Main Pseudocode

```text
Algorithm NOSF

Input:
    Workflows arriving online
    On-demand VM types
    Workflow deadlines
    Task runtime distributions

Output:
    Task-to-VM mapping
    Total cost
    Deadline success rate
    VM utilization

Initialize:
    active_workflows = empty
    active_vms = empty
    ready_queue = empty

while simulation is running do

    if a new workflow arrives then

        add workflow to active_workflows

        for each task t in workflow do
            compute predicted_runtime(t)
        end for

        compute EST(t) and EFT(t) for each task

        compute critical_path(workflow)

        for each task t in workflow do
            compute sub_deadline(t)
        end for

        add all entry tasks to ready_queue

    end if

    if a task finishes then

        mark task as FINISHED

        update available_time of its assigned VM

        for each child task c of finished task do
            if all parents of c are FINISHED then
                add c to ready_queue
            end if
        end for

    end if

    sort ready_queue by ascending sub_deadline

    for each task t in ready_queue do

        best_vm = null
        best_cost_increase = infinity

        for each VM v in active_vms do

            predicted_start =
                max(current_time, available_time(v), data_ready_time(t))

            predicted_finish =
                predicted_start + predicted_runtime(t, v)

            if predicted_finish <= sub_deadline(t) then

                old_cost =
                    billing_cost(start_time(v),
                                 planned_shutdown_time(v),
                                 price(v))

                new_shutdown =
                    max(planned_shutdown_time(v), predicted_finish)

                new_cost =
                    billing_cost(start_time(v),
                                 new_shutdown,
                                 price(v))

                cost_increase = new_cost - old_cost

                if cost_increase < best_cost_increase then
                    best_cost_increase = cost_increase
                    best_vm = v
                end if

            end if

        end for

        if best_vm is not null then

            assign task t to best_vm

            actual_start_time(t) =
                max(current_time, available_time(best_vm), data_ready_time(t))

            predicted_finish_time(t) =
                actual_start_time(t) + predicted_runtime(t, best_vm)

            update available_time(best_vm)
            update planned_shutdown_time(best_vm)

            remove t from ready_queue

        else

            selected_type = null
            min_new_vm_cost = infinity

            for each on-demand VM type k do

                new_vm_start = current_time + provisioning_delay

                new_vm_finish =
                    new_vm_start + predicted_runtime(t, k)

                if new_vm_finish <= sub_deadline(t) then

                    new_vm_cost =
                        billing_cost(new_vm_start,
                                     new_vm_finish,
                                     price(k))

                    if new_vm_cost < min_new_vm_cost then
                        min_new_vm_cost = new_vm_cost
                        selected_type = k
                    end if

                end if

            end for

            if selected_type is not null then

                create new on-demand VM of selected_type

                assign task t to new VM

                add new VM to active_vms

                remove t from ready_queue

            else

                mark task t as deadline-risk
                keep it waiting or assign to fastest VM
                according to the experiment policy

            end if

        end if

    end for

end while
```

---

## 17. Feedback Process

The feedback process updates the scheduling state when actual execution times differ from predicted execution times.

When a task finishes:

```text
actual_runtime(t) =
    actual_finish_time(t) - actual_start_time(t)
```

If:

```text
actual_runtime(t) ≠ predicted_runtime(t)
```

then update:

```text
available_time(assigned_vm)
ready_queue
successor readiness
future predicted start times
```

The feedback process should not reschedule already running tasks. It only affects future scheduling decisions.

---

## 18. Workflow Deadline Success

A workflow is successful if:

```text
actual_finish_time(W_i) ≤ deadline(W_i)
```

where:

```text
actual_finish_time(W_i) =
    max actual_finish_time(t)
    for all t ∈ W_i
```

Deadline success rate:

```text
success_rate =
    number_of_successful_workflows / total_number_of_workflows
```

---

## 19. Total Cost Metric

Total cost:

```text
total_cost =
    Σ cost(v)
    for all provisioned VMs v
```

where:

```text
cost(v) =
    billing_cost(start_time(v), shutdown_time(v), price(v))
```

---

## 20. VM Utilization Metric

For each VM:

```text
busy_time(v) =
    Σ actual_runtime(t)
    for all tasks t assigned to v
```

```text
active_time(v) =
    shutdown_time(v) - start_time(v)
```

```text
utilization(v) =
    busy_time(v) / active_time(v)
```

Overall utilization:

```text
overall_utilization =
    Σ busy_time(v) / Σ active_time(v)
```

---

## 21. Experimental Metrics

Report at least:

```text
total_cost
deadline_success_rate
number_of_completed_workflows
number_of_deadline_violations
average_makespan
average_VM_utilization
number_of_provisioned_on_demand_VMs
```

Optional metrics:

```text
average_cost_per_workflow
average_waiting_time
average_task_delay
VM idle time
```

---

## 22. Implementation Policy Summary

The implementation should follow these rules:

1. Use only on-demand VMs.
2. Do not use reserved resources.
3. Do not use spot resources.
4. Do not use static HEFT scheduling.
5. Use online scheduling.
6. Do not assume knowledge of future workflow arrivals.
7. Use task sub-deadlines.
8. Use greedy minimum cost-increase VM selection.
9. Reuse active VMs whenever possible.
10. Provision a new VM only when no existing VM can satisfy the task sub-deadline.
11. Use zero provisioning delay for the faithful NOSF baseline.
12. Add provisioning delay only in an extended variant.
13. Model uncertain task execution time using a normal distribution.
14. Use the feedback process to update state after task completion.
15. Report cost, success rate, and utilization.

---

## 23. Notes for Codex

When implementing in Java/WorkflowSim:

- create a new scheduling algorithm class;
- maintain a map from VM id to VM state;
- maintain a map from job id to sub-deadline;
- maintain a map from job id to predicted runtime;
- compute cost increase before assigning each job;
- select the VM with the lowest feasible cost increase;
- create or activate new VMs only if needed;
- use CloudSim clock as the current simulation time:

```java
double currentTime = CloudSim.clock();
```

- for each job:

```java
job.setVmId(selectedVm.getId());
getScheduledList().add(job);
```

- ensure the scheduler does not violate WorkflowSim dependency handling.

---

## 24. Source Notes

The CBMW paper describes NOSF as an online multi-workflow scheduling framework that uses a greedy heuristic to minimize execution cost while satisfying deadlines. It states that each task is scheduled on the resource that leads to the minimum cost increase while meeting its assigned sub-deadline, and that task execution times are modeled as random variables following a normal distribution.

The original NOSF publication is:

Jiagang Liu, Ju Ren, Wei Dai, Deyu Zhang, Peng Zhou, Yaoxue Zhang, Geyong Min, and Noushin Najjari.  
"Online Multi-Workflow Scheduling under Uncertain Task Execution Time in IaaS Clouds."  
IEEE Transactions on Cloud Computing, 9(3), 1180–1194, 2021.  
DOI: 10.1109/TCC.2019.2906300.

