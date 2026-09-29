CEWB + latest local Greedy updates: combined replacement package
Snapshot: 2026-09-29
Base commit: 7e5d693 (SST); CEWB revision: 27be8fd
Greedy revision: local uncommitted edits in the current project checkout.

Contains the changed project files with their relative paths. To update the
project checkout that has these Greedy changes, extract at its root and allow
replacement. CBMWSimulation.java includes both CEWB cost export and Greedy
indexed dependency handling. Do not extract the older CEWB-only ZIP afterward.

Workflow input manifests and DAX/TXT inputs are not included. No full-scale
experiment was run as part of this integration check. The combined source
compiled and passed the Greedy recovery and four CEWB validation classes.
The original project checkout was not modified.
