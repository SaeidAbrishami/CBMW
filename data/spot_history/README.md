# Ohio m5.8xlarge Spot history, July–August 2026

These six TSVs contain `UTC_ISO_timestamp<TAB>USD_per_instance_hour`, with
one price change per line. They are exact filters of the `use2-az1`,
`use2-az2`, and `use2-az3` / `m5.8xlarge` / `Linux/UNIX` rows in
[Eric Pauley's archived AWS Spot Price History](https://zenodo.org/records/22647367).
The global Availability Zone ID is used because `us-east-2a` style names
can refer to different physical zones in different AWS accounts.

| Archive file | Published archive MD5 | Selected zone observations |
|:--|:--|:--|
| `2026-07.tsv.zst` | `29a172eb2ec02c2860ab634d42b140fc` | 137 / 136 / 140 |
| `2026-08.tsv.zst` | `e45475d16af7e68f81335162f1dd65fd` | 127 / 130 / 132 |

| Zone | July minimum | August minimum | August maximum | August time-weighted mean | July low maximum price |
|:--|--:|--:|--:|--:|--:|
| `use2-az1` | $0.3237 | $0.3047 | $0.3816 | $0.349646 | $0.626775 |
| `use2-az2` | $0.2964 | $0.3061 | $0.3667 | $0.338756 | $0.606300 |
| `use2-az3` | $0.3495 | $0.3499 | $0.4410 | $0.409921 | $0.646125 |

All prices are USD per physical VM hour. The August mean treats each price
as constant until the next observation, and the last observation until
September 1, 00:00 UTC. The low maximum price follows CEWB Equation 3:
`July minimum + 0.25 * ($1.536 - July minimum)`.

The On-Demand $1.536/hour is from AWS's `us-east-2`, Linux, Shared,
No License required, `capacitystatus=Used` EC2 regional price lists,
SKU `8X9B68EH66TVHPDD`, in both the [July
version](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/20260728175247/us-east-2/index.json)
and [August
version](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/20260831181331/us-east-2/index.json).

This history records prices, not fulfilled capacity, VM lifecycle events, or
capacity-driven interruptions. It cannot estimate provider reclamation odds.
