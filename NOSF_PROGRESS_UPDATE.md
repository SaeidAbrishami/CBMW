# NOSF progress update for Codex commit c4b0a3c

This archive overlays four files of the CBMW repository. From the repository
root on Ubuntu, after pulling the Codex branch, apply it with:

    unzip -o /path/to/NOSF_progress_update.zip -d .

Then run:

    python3 scripts/run_nosf_ohio_middle300.py --scenario arrival15_alpha1.2
    python3 scripts/run_nosf_ohio_middle300.py --all

The terminal displays a live task bar every 10 seconds and the full Java
output remains in each scenario's run.log. Use --progress-interval-sec N to
change the display interval. The change also correctly increments the NOSF
started-task count. XML/TXT workflows are read from test_workflows/workflows.
