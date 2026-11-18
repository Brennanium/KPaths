#!/usr/bin/env python3
"""Render a release's own guides with the current documentation site theme."""
import argparse
import shutil
from pathlib import Path

import yaml


def prepare(repo: Path, release: Path, version: str) -> Path:
    current = yaml.safe_load((repo / 'mkdocs.yml').read_text(encoding='utf-8'))
    historical = yaml.safe_load((release / 'mkdocs.yml').read_text(encoding='utf-8'))
    staging = release / 'build' / 'docs' / 'themed-source'
    shutil.copytree(release / 'docs', staging, dirs_exist_ok=True)
    overview = staging / 'index.md'
    contents = overview.read_text(encoding='utf-8')
    if not contents.startswith('---\n'):
        overview.write_text('---\nhide:\n  - navigation\n---\n\n' + contents, encoding='utf-8')
    logo = staging / 'assets' / 'images' / 'kpaths-mark.svg'
    logo.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(repo / 'docs' / 'assets' / 'images' / 'kpaths-mark.svg', logo)

    # Preserve the release's guide selection and ordering, while sharing the
    # current theme and pointing source links at the release tag.
    current['nav'] = historical.get('nav', current.get('nav'))
    current['docs_dir'] = str(staging.resolve())
    current['site_dir'] = str((release / 'build' / 'docs' / 'themed-site').resolve())
    current['edit_uri'] = f'edit/{version}/docs/'
    # Shared assets are added with site-root-relative URLs during finalization.
    current['extra_css'] = []
    current['extra_javascript'] = []
    config = release / 'build' / 'docs' / 'themed-mkdocs.yml'
    config.write_text(yaml.safe_dump(current, sort_keys=False), encoding='utf-8')
    return config


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repo', type=Path)
    parser.add_argument('release', type=Path)
    parser.add_argument('version')
    args = parser.parse_args()
    print(prepare(args.repo.resolve(), args.release.resolve(), args.version))
