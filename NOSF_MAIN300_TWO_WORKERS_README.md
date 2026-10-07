# NOSF option C, main 300, two scenarios at a time

Extract these replacement files over the CBMW project root. This bundle includes option C and the earlier Ohio, EFT, RAM, queue, progress, and main-300 changes. It does not include the generic 500/200 README or the optional PowerShell smoke test.

Run all 18 scenario configurations, with at most two separate JVMs concurrently:

    python3 scripts/run_nosf_ohio_middle300.py --all --workers 2 --workflow-dir test_workflows/workflows --output outputs/NOSF_option_C_two_workers

The runner compiles once before starting simulations. Each JVM uses up to 3 GiB of heap. Use --workers 1 if the server cannot accommodate two JVMs. Each scenario has its own middle300 manifest, logs, and results directory. Terminal progress lines identify the scenario and show its task progress.

For every arrival/deadline configuration, only original manifest entries 101 through 400 run. The 500-entry source manifest is read to select those 300 workflows; workflows 1-100 and 401-500 are never simulated. Java is passed MIDDLE_300 and max.workflows=300, and results.csv total should be 300 for each scenario.

Option C: NOSF defaults to CBMW's planning duration, 1.20 x nominal under the default alpha=0.20. If no VM can satisfy a task subdeadline, the VM with earliest predicted finish is selected, with incremental hourly rental cost as tie-breaker. Actual task samples, EFT priority, 60-second provisioning, hourly billing, RAM eligibility, and unlimited waiting queues are unchanged.

The original workflow dataset is not included in this update. Keep new results in their own output folder so they do not mix with older NOSF results.
