"""Locate KPaths when its built-in drivers are run from a packaged CLI."""
import os
from pathlib import Path


def project_root():
    override = os.environ.get('KPATHS_PROJECT_ROOT')
    if override:
        return Path(override).expanduser().resolve()
    for start in (Path.cwd(), Path(__file__).resolve().parent.parent):
        for candidate in (start, *start.parents):
            if (candidate / 'gradlew').is_file() and (candidate / 'kpaths-compiler-plugin').is_dir():
                return candidate
    return Path.cwd()
