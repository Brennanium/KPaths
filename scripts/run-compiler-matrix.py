#!/usr/bin/env python3
"""Run a project-provided compiler compatibility driver for each selected Kotlin version."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

from kotlin_version_resolver import resolve


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('versions', nargs='+', help='Exact Kotlin versions or quoted dev selectors')
    parser.add_argument('--driver', required=True, type=Path,
                        help='Executable called with --kotlin-version <resolved version>')
    parser.add_argument('--report-json', type=Path, help='Write resolved versions and results as JSON')
    args = parser.parse_args(argv)
    try:
        resolved = resolve(args.versions)
        driver = args.driver.expanduser().resolve(strict=True)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    results = []
    for version in dict.fromkeys(version for _, version in resolved):
        print(f'\n=== Kotlin {version} ===', flush=True)
        try:
            code = subprocess.run([str(driver), '--kotlin-version', version], cwd=driver.parent, check=False).returncode
            results.append({'version': version, 'result': 'passed' if code == 0 else 'failed', 'exitCode': code})
        except OSError as error:
            results.append({'version': version, 'result': 'failed', 'error': str(error)})
    report = {'selectors': [{'input': selector, 'resolved': version} for selector, version in resolved],
              'driver': str(driver), 'results': results}
    for result in results:
        print(f"Kotlin {result['version']}: {result['result']}", flush=True)
    if args.report_json:
        args.report_json.parent.mkdir(parents=True, exist_ok=True)
        args.report_json.write_text(json.dumps(report, indent=2) + '\n')
    return int(any(result['result'] != 'passed' for result in results))


if __name__ == '__main__':
    sys.exit(main())
