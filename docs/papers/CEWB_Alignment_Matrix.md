# CEWB implementation alignment matrix

The repository does not contain the full text or pseudocode of Taghavi,
Zolfaghari, and Abrishami (2023). The local
`CEWB_WorkflowSim_Reference_Specification.txt` explicitly reconstructs the
missing details. Consequently, the `CEWB` implementation distinguishes claims
available from the paper metadata/abstract from configurable WorkflowSim
adaptations. `CEWB-Reconstructed` preserves the pre-alignment implementation.

| Concern | Authority | Project implementation | Status |
|---|---|---|---|
| Hybrid on-demand and spot resources | Paper-defined | `CEWBBroker`, `CEWBSpotMarket` | Implemented |
| On-demand is most reliable | Paper-defined | Reliability class 0 | Implemented |
| Spot classes use maximum bid/reliability | Paper-defined concept; exact values unavailable | Three configurable classes, maximum price ratio, MTBI | Reconstructed |
| Task criticality comes from slack | Paper-defined | `CEWBCriticalityPolicy` recomputes slack for every ready queue | Implemented |
| Critical tasks use more reliable classes | Paper-defined | Normalized slack maps to classes 0–3 | Implemented |
| Criticality thresholds | Not available | Configurable 0.75/0.50/0.25 defaults | Assumption |
| Rank and sub-deadline equations | Local reference adaptation | HEFT-style zero-communication ranks and proportional sub-deadlines | Implemented |
| Ready ordering | Local reference reconstruction | Slack, upward rank, workflow deadline, task ID | Implemented |
| Interruption recovery | Paper-defined concept; details unavailable | Restart and progressively escalate reliability, then on-demand | Reconstructed |
| Large VM/container multiplexing | Paper-defined | Persistent logical spot VMs enforce CPU/RAM slots and host concurrent task containers | Implemented adaptation |
| Three pricing policies | Paper-defined families; equations unavailable | Configurable constant-profit, constant-discount, prediction-based reconstruction | Reconstructed |
| Broker profit reporting | Paper-defined objective | Revenue/profit stored per workflow | Implemented internally |
| Spot capacity | Experiment assumption | Configurable, defaults to capacity-matched 960-core envelope | Assumption |
| Spot prices and interruptions | Experiment constants unavailable | Configurable synthetic price factors and exponential MTBI | Assumption |

## Configuration

| Property | Default |
|---|---:|
| `cbmw.cewb.criticality.ondemand` | `0.75` |
| `cbmw.cewb.criticality.high.spot` | `0.50` |
| `cbmw.cewb.criticality.medium.spot` | `0.25` |
| `cbmw.cewb.pricing.policy` | `CONSTANT_PROFIT` |
| `cbmw.cewb.pricing.profit.margin` | `0.10` |
| `cbmw.cewb.pricing.discount` | `0.10` |
| `cbmw.cewb.pricing.prediction.risk` | `0.05` |

The implementation must still be described as paper-informed rather than an
exact reproduction until the full CEWB paper supplies the unavailable class,
lifecycle, pricing, billing, and experimental constants.
