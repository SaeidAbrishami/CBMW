# NOSF option C update

Overlay these files onto the root of the CBMW Codex checkout. This includes the prior NOSF Ohio/300-workflow, EFT, RAM, FIFO queue, and progress changes, plus option C. The shared CBMW and CEWB/Greedy implementations are not changed by option C.

Option C: NOSF plans with CBMW's 1.20 x nominal duration by default (controlled by cbmw.runtime.planning.alpha). If no eligible VM can meet the task subdeadline, it chooses the eligible active or new VM with the earliest predicted finish, breaking ties by incremental hourly rental cost. Set -Dnosf.runtime.estimator=MU_PLUS_SIGMA for the paper-estimator sensitivity. Actual TXT task runtimes, EFT, RAM/core eligibility, 60-second boot, unlimited waiting queues, hourly billing, and deadlines remain as before.

Run one of the middle-300 cases from the project root with the original workflow dataset installed:

    python3 scripts/run_nosf_ohio_middle300.py --scenario arrival15_alpha1.2 --workflow-dir test_workflows/workflows

Use --all for all 18 combinations. This runner compiles the Java sources and prints task progress. Run output should use a new directory; existing NOSF CSVs are from the old algorithm.

Validation (with compiled build_classes):

    java -ea -cp 'build_classes:lib/*' org.workflowsim.cbmw.baselines.NOSFValidationTest

This package does not include test_workflows/workflows or previously produced results.
