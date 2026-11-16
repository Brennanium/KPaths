"""Choose Android Studio compatibility targets for CI and manual runs."""
import argparse
import json
import re
from pathlib import Path

SELECTOR = re.compile(r'(?:[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+|[0-9]+(?:\.[0-9]+){4}|latest-stable)')


def select(entries, event, requested):
    if requested:
        versions = requested.replace(',', ' ').split()
        if not versions or any(not SELECTOR.fullmatch(version) for version in versions):
            raise ValueError('Use complete Android Studio versions, build numbers, or latest-stable')
        if len(set(versions)) != len(versions):
            raise ValueError('IDE selectors must be distinct')
        if len(versions) > 20:
            raise ValueError('Select at most 20 IDE versions in one run')
        return versions
    if not entries:
        raise ValueError('The tested IDE matrix is empty')
    builds = [entry['build'] for entry in entries if entry['product'] == 'Android Studio']
    if len(builds) != len(entries) or len(set(builds)) != len(builds):
        raise ValueError('The Android Studio matrix must contain distinct builds')
    if any(not SELECTOR.fullmatch(build) for build in builds):
        raise ValueError('The IDE matrix contains an invalid build number')
    if event != 'pull_request':
        return builds
    latest = {}
    for build in builds:
        numbers = tuple(map(int, build.split('.')))
        if numbers[0] not in latest or numbers > latest[numbers[0]][0]:
            latest[numbers[0]] = (numbers, build)
    selected = {item[1] for item in latest.values()}
    return [build for build in builds if build in selected]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--versions', default='')
    parser.add_argument('--matrix', type=Path, default=Path('kpaths-kefs-ide-fixture/ide-compatibility.json'))
    args = parser.parse_args()
    try:
        entries = json.loads(args.matrix.read_text())['tested']
        print(json.dumps(select(entries, args.event, args.versions), separators=(',', ':')))
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
