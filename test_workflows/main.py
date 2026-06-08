import os
import json
import numpy as np
from pathlib import Path
import re


def generate_poisson_distribution(xml_files, scale=40):
    np.random.seed(42)
    poisson_data = {}

    inter_arrival_times = np.random.exponential(scale, len(xml_files))
    samples = np.cumsum(inter_arrival_times)

    random_indices = np.random.choice(len(samples), size=len(xml_files), replace=False)

    for i, xml_file in enumerate(xml_files):
        # Extract filename without extension
        filename = Path(xml_file).stem

        # Generate Poisson distribution value (lambda parameter)
        poisson_value = float((samples[random_indices[i]]))

        poisson_data[filename] = poisson_value

    return poisson_data


def main():
    # Get current working directory
    current_path = r"workflows/1"

    # Find all XML files in current directory
    xml_files = [
        f
        for f in os.listdir(current_path)
        if (
            (f.endswith(tuple(f"_997_{rrr}.xml" for rrr in range(1, 26))))
            or (f.endswith(tuple(f"_100_{rrr}.xml" for rrr in range(1, 26))))
            or (f.endswith(tuple(f"_1000_{rrr}.xml" for rrr in range(1, 26))))
        )
        and f.count("_") == 2
        and f.find("Epigenomics") == -1
    ]

    # Sort by prefix (before first _) then by the numeric value between the two underscores
    def sort_key(name):
        m = re.match(r"^([^_]+)_(\d+)_(\d+)", name)
        if m:
            prefix = m.group(1)
            mid = int(m.group(2))
            last = int(m.group(3))
            return (last, prefix, mid)
        prefix = name.split("_", 1)[0]
        return (float("inf"), prefix, float("inf"))

    xml_files = sorted(xml_files, key=sort_key)

    if not xml_files:
        print("No XML files found in the current directory.")
        return

    print(f"Found {len(xml_files)} XML files")

    # Generate Poisson distribution with scale factor of 1000
    poisson_data = generate_poisson_distribution(xml_files)

    # Save to JSON file
    output_file = os.path.join(current_path, "poisson_distribution.json")

    with open(output_file, "w") as f:
        json.dump(poisson_data, f, indent=4)

    print(f"Poisson distribution saved to: {output_file}")
    print(f"Sample data: {dict(list(poisson_data.items())[:5])}")


if __name__ == "__main__":
    main()
