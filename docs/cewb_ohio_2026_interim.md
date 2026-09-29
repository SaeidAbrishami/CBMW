# CEWB Ohio Spot replay: interim results (September 28, 2026)

7 paired 500/200 configurations and one standalone middle-300 configuration completed. The remaining runs were stopped at the researcher's request. These are simulations on the same workflow XML/TXT data, not historical EC2 execution receipts.

## Inputs and simulation choices

- Region `us-east-2`, zone ID `use2-az1`; Linux `m5.8xlarge`, 32 vCPU and 128 GiB per physical VM.
- July 2026 Ohio Spot observations train the CEWB maximum-price classes; August 2026 observations drive replay and rental rates. Both archived July and August AWS EC2 price lists quote $1.536 per On-Demand instance-hour (SKU `8X9B68EH66TVHPDD`).
- Zone `use2-az1` July minimum is $0.3237/hour. The CEWB low/medium/high maximum prices are $0.626775 / $0.929850 / $1.232925 per hour. The August price range is $0.3047–$0.3816/hour, with a time-weighted mean of $0.349646/hour, 77.24% below On-Demand.
- 60-second VM provisioning, 0.4-second container deployment, 100-second pool-adjustment cycle, and a distinct 90-second checkpoint delay. Same August price offset is used across deadline factors at each arrival rate. Physical Spot and On-Demand VM rental, including idle periods, is assigned to workflows by their allocated core-time. Customer billing policy is omitted.

## Completed costs (USD)

| Mean arrival (s) | Deadline factor | Full 500 | Shifted edge 200 | 500 − 200 | Attributed middle 300 | Isolated middle 300 | Met deadline (500 / 200) |
|---:|---:|---:|---:|---:|---:|---:|:---|
| 15 | 1.2 | 478.0461 | 180.9454 | 297.1007 | 314.7805 | 308.1195 | 499 / 199 |
| 15 | 2 | 469.5449 | 175.7618 | 293.7831 | 308.9206 | — | 500 / 200 |
| 15 | 4 | 465.2961 | 167.1476 | 298.1485 | 306.3686 | — | 500 / 200 |
| 30 | 1.2 | 411.7767 | 160.4404 | 251.3363 | 269.1947 | — | 499 / 199 |
| 30 | 2 | 406.4253 | 154.3890 | 252.0363 | 266.6827 | — | 500 / 200 |
| 30 | 4 | 396.4196 | 154.8191 | 241.6005 | 259.8510 | — | 500 / 200 |
| 45 | 1.2 | 367.0851 | 149.2268 | 217.8583 | 233.6317 | — | 500 / 200 |

For the completed `arrival15_alpha1.2` standalone run, the 300 workflows cost $308.1195 (On-Demand $283.6505, Spot $24.4690); 299 of 300 met deadline. Its arrivals were shifted to start at zero and its price-clock offset advanced by the identical time, preserving the price period experienced by these workflows within the full run.

**The three middle-300 columns measure different things.** The edge-200 manifest shifts the final 100 workflows earlier, so full-minus-edge includes changed idle/rental and scheduling conditions. “Attributed” is the sum of middle workflows’ shares of physical VM cost in the full-500 simulation. “Isolated” rents VMs for those 300 alone.

## Interpretation and limits

- All completed runs recorded zero price-triggered Spot interruptions. August prices never exceeded even the low July-trained maximum price in any Ohio zone. The Spot archive does not contain capacity-driven interruption events, so provider reclamation probability cannot be estimated from this replay. A real Spot deployment may still be interrupted.
- The primary results use only `use2-az1`. `use2-az2` and `use2-az3` files are included for zone sensitivity; neither was simulated here.
- CBMW still has separately configured synthetic reserved and task-sized On-Demand rates. A direct numerical claim that one algorithm is cheaper requires a common cost basis.
- The set is deliberately partial: 7 of 18 paired configurations and 1 of 18 isolated-middle configurations. No uncompleted run appears in the summary CSV.

## Provenance and reproducibility

- [Archived AWS Spot Price History](https://zenodo.org/records/22647367), monthly July/August 2026 files, filtered to `m5.8xlarge` Linux/UNIX by zone ID.
- [Archived July Ohio EC2 price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/20260728175247/us-east-2/index.json) and [August Ohio EC2 price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/20260831181331/us-east-2/index.json).
- `scripts/run_cewb_ohio_2026.py` and `scripts/run_cewb_ohio_middle300.py` reproduce the paired and isolated runs. `scripts/summarize_cewb_ohio_2026.py` verifies cost conservation and labels individual workflows. The results archive contains complete scenario CSVs, per-workflow costs, the input Spot slices, and run configurations.
