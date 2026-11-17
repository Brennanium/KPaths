#!/usr/bin/env python3
"""Run compiler or IDE compatibility checks locally or in any CI environment."""
import argparse
import subprocess
import sys
from pathlib import Path


SCRIPTS = {
    'compiler': 'verify-compiler-compatibility.py',
    'ide': 'verify-android-studio-version.py',
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        'Use "compiler --help" or "ide --help" for command-specific options.'))
    parser.add_argument('command', choices=SCRIPTS, help='Compatibility checks to run')
    values = list(sys.argv[1:] if argv is None else argv)
    if not values or values[0] not in SCRIPTS:
        parser.parse_args(values)
    command, remaining = values[0], values[1:]
    custom_compiler = command == 'compiler' and any(arg == '--driver' or arg.startswith('--driver=') for arg in remaining)
    script = Path(__file__).resolve().parent / ('run-compiler-matrix.py' if custom_compiler else SCRIPTS[command])
    result = subprocess.run([sys.executable, str(script), *remaining], check=False).returncode
    if command == 'compiler' and '--help' in remaining and not custom_compiler:
        print('\nFor a project-provided test driver, use: compiler <versions...> --driver <executable>')
        print('Use compiler --driver <executable> --help for driver options.')
    return result


if __name__ == '__main__':
    sys.exit(main())
