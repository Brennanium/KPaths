"""Console-script entry point for the packaged CLI."""
import subprocess
import sys
from pathlib import Path


def main():
    script = Path(__file__).resolve().with_name('kotlin-plugin-matrix.py')
    return subprocess.run([sys.executable, str(script), *sys.argv[1:]], check=False).returncode
