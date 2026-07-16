"""Generate a reproducible perturbed-runtime workflow dataset.

The output is a self-contained WorkflowSim input directory containing the
manifest, every XML referenced by it, one generated TXT runtime file per XML,
and generation metadata. The source dataset is never modified.
"""

import argparse
import hashlib
import json
import math
import random
import shutil
import statistics
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_STDDEV_RATIO = 0.05
DEFAULT_QUANTILE = 0.99
DEFAULT_SEED = 20260716
METADATA_FILENAME = "runtime_generation_metadata.json"


def parse_args():
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        description=(
            "Generate normally distributed task runtimes for every workflow "
            "listed in a WorkflowSim arrival manifest."
        )
    )
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=script_dir,
        help="Directory containing the source manifest and XML files.",
    )
    parser.add_argument(
        "--manifest",
        default="poisson_distribution.json",
        help="Arrival manifest filename within --source-dir.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="New or empty destination directory. It must differ from --source-dir.",
    )
    parser.add_argument(
        "--stddev-ratio",
        type=float,
        default=DEFAULT_STDDEV_RATIO,
        help="Runtime sigma/mu ratio (default: 0.05).",
    )
    parser.add_argument(
        "--quantile",
        type=float,
        default=DEFAULT_QUANTILE,
        help="CET quantile used only for validation statistics (default: 0.99).",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=DEFAULT_SEED,
        help="Python random seed (default: 20260716).",
    )
    return parser.parse_args()


def validate_args(args):
    source_dir = args.source_dir.resolve()
    output_dir = args.output_dir.resolve()
    if source_dir == output_dir:
        raise ValueError("--output-dir must differ from --source-dir")
    if not source_dir.is_dir():
        raise FileNotFoundError(f"Source directory does not exist: {source_dir}")
    if not math.isfinite(args.stddev_ratio) or args.stddev_ratio < 0.0:
        raise ValueError("--stddev-ratio must be finite and >= 0")
    if not math.isfinite(args.quantile) or not 0.0 < args.quantile < 1.0:
        raise ValueError("--quantile must be finite and between 0 and 1")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError(f"Output directory must be empty: {output_dir}")
    return source_dir, output_dir


def load_manifest(path):
    with path.open("r", encoding="utf-8") as stream:
        manifest = json.load(stream)
    if not isinstance(manifest, dict) or not manifest:
        raise ValueError("Manifest must be a nonempty JSON object")
    for workflow_name in manifest:
        if not isinstance(workflow_name, str) or Path(workflow_name).name != workflow_name:
            raise ValueError(f"Unsafe workflow name in manifest: {workflow_name!r}")
    return manifest


def nominal_runtime_seconds(runtime_text, xml_path, job_id):
    try:
        runtime = float(runtime_text)
    except (TypeError, ValueError) as exc:
        raise ValueError(
            f"Invalid runtime for job {job_id!r} in {xml_path}: {runtime_text!r}"
        ) from exc
    if not math.isfinite(runtime) or runtime < 0.0:
        raise ValueError(
            f"Runtime must be finite and nonnegative for job {job_id!r} in {xml_path}"
        )

    # Mirror WorkflowParser: length = (long) max(runtime * 1000, 100),
    # then execution time is length / 1000 MIPS.
    cloudlet_length = int(max(runtime * 1000.0, 100.0))
    return cloudlet_length / 1000.0


def read_nominal_runtimes(xml_path):
    root = ET.parse(xml_path).getroot()
    jobs = [element for element in root.iter() if element.tag.split("}")[-1] == "job"]
    if not jobs:
        raise ValueError(f"No <job> elements found in {xml_path}")

    runtimes = []
    for index, job in enumerate(jobs):
        job_id = job.get("id", f"index-{index}")
        runtime_text = job.get("runtime")
        if runtime_text is None:
            raise ValueError(f"Missing runtime for job {job_id!r} in {xml_path}")
        runtimes.append(nominal_runtime_seconds(runtime_text, xml_path, job_id))
    return runtimes


def generate_positive_samples(rng, means, stddev_ratio):
    if stddev_ratio == 0.0:
        return list(means)
    samples = []
    for mean in means:
        sample = rng.gauss(mean, mean * stddev_ratio)
        while not math.isfinite(sample) or sample <= 0.0:
            sample = rng.gauss(mean, mean * stddev_ratio)
        samples.append(sample)
    return samples


def effective_execution_samples(samples):
    # Mirror AbstractWorkflowBroker.applyPerturbedRuntimes().
    return [int(max(sample * 1000.0, 100.0)) / 1000.0 for sample in samples]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    args = parse_args()
    source_dir, output_dir = validate_args(args)
    manifest_path = source_dir / args.manifest
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest does not exist: {manifest_path}")

    manifest = load_manifest(manifest_path)
    workflow_names = sorted(manifest)

    # Preflight every source file before creating output.
    nominal_by_workflow = {}
    for workflow_name in workflow_names:
        xml_path = source_dir / f"{workflow_name}.xml"
        if not xml_path.is_file():
            raise FileNotFoundError(f"Missing workflow XML: {xml_path}")
        nominal_by_workflow[workflow_name] = read_nominal_runtimes(xml_path)

    output_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(manifest_path, output_dir / args.manifest)

    rng = random.Random(args.seed)
    all_relative_deviations = []
    exceed_count = 0
    task_count = 0
    z_value = statistics.NormalDist().inv_cdf(args.quantile)
    cet_multiplier = 1.0 + z_value * args.stddev_ratio

    for workflow_name in workflow_names:
        source_xml = source_dir / f"{workflow_name}.xml"
        output_xml = output_dir / source_xml.name
        output_txt = output_dir / f"{workflow_name}.txt"
        means = nominal_by_workflow[workflow_name]
        samples = generate_positive_samples(rng, means, args.stddev_ratio)
        effective_samples = effective_execution_samples(samples)

        shutil.copy2(source_xml, output_xml)
        with output_txt.open("w", encoding="utf-8", newline="\n") as stream:
            for sample in samples:
                stream.write(f"{sample:.12f}\n")

        relative_deviations = [
            (actual - mean) / mean
            for actual, mean in zip(effective_samples, means)
        ]
        all_relative_deviations.extend(relative_deviations)
        exceed_count += sum(
            actual > mean * cet_multiplier
            for actual, mean in zip(effective_samples, means)
        )
        task_count += len(means)

    empirical_mean = statistics.fmean(all_relative_deviations)
    empirical_stddev = statistics.stdev(all_relative_deviations)
    exceed_rate = exceed_count / task_count
    expected_probability = 1.0 - args.quantile
    expected_exceed_count = task_count * expected_probability
    binomial_stddev = math.sqrt(
        task_count * expected_probability * (1.0 - expected_probability)
    )
    expected_95_low = max(0.0, expected_exceed_count - 1.96 * binomial_stddev)
    expected_95_high = expected_exceed_count + 1.96 * binomial_stddev

    metadata = {
        "schema_version": 1,
        "distribution": "normal",
        "stddev_ratio": args.stddev_ratio,
        "seed": args.seed,
        "workflow_count": len(workflow_names),
        "task_count": task_count,
        "manifest": args.manifest,
        "manifest_sha256": sha256(manifest_path),
        "source_directory": str(source_dir),
        "runtime_floor_seconds": 0.1,
        "validation_quantile": args.quantile,
        "validation_z": z_value,
        "validation_cet_multiplier": cet_multiplier,
        "empirical_relative_deviation_mean": empirical_mean,
        "empirical_relative_deviation_stddev": empirical_stddev,
        "cet_exceed_count": exceed_count,
        "cet_exceed_rate": exceed_rate,
        "expected_cet_exceed_count": expected_exceed_count,
        "expected_cet_exceed_95_percent_interval": [
            expected_95_low,
            expected_95_high,
        ],
    }
    metadata_path = output_dir / METADATA_FILENAME
    with metadata_path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(metadata, stream, indent=2)
        stream.write("\n")

    print(f"Generated dataset: {output_dir}")
    print(f"Workflows: {len(workflow_names)}")
    print(f"Tasks: {task_count}")
    print(f"Configured sigma/mu: {args.stddev_ratio:.6f}")
    print(f"Empirical sigma/mu: {empirical_stddev:.6f}")
    print(f"Empirical relative mean: {empirical_mean:.6f}")
    print(
        f"CET exceedance at alpha={args.quantile:.4f}: "
        f"{exceed_count}/{task_count} ({100.0 * exceed_rate:.4f}%)"
    )
    print(
        "Approximate expected 95% count interval: "
        f"[{expected_95_low:.1f}, {expected_95_high:.1f}]"
    )
    print(f"Metadata: {metadata_path}")


if __name__ == "__main__":
    main()
