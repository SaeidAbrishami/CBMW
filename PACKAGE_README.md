# CBMW source with revised NOSF

Based on SaeidAbrishami/CBMW, branch Codex, commit e6bbbb6 (CEWB revised).
The complete current Java sources and experiment scripts include the prior CEWB
and Greedy changes. NOSF changes are described in docs/nosf_ohio_middle300.md.

The large test_workflows/workflows dataset is not duplicated in this source
archive. Place this package over the matching repository checkout, which holds
the 500 original XML/TXT pairs and six 500-workflow manifests, or pass the
existing workflow directory to the runner. Only the middle 300 are loaded.

Build and run (Linux/macOS):

    python3 scripts/run_nosf_ohio_middle300.py --all \
      --workflow-dir /path/to/CBMW/test_workflows/workflows

To test one scenario first, use --scenario arrival15_alpha1.2.
The script compiles Java and writes output to outputs/nosf_ohio_2026.
Validation: java -ea -cp "build_classes:lib/*" \
  org.workflowsim.cbmw.baselines.NOSFValidationTest
